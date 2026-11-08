"""Novo piloto, candidatos brutos preservados, quatro métodos sem fusão."""
from __future__ import annotations
import argparse
import importlib.metadata
import json
import time
from collections import Counter
from pathlib import Path
import cv2
import numpy as np
import psutil
from .pipeline import PRIVATE, new_run, sha256, json_write, write_image, read_image, compare_instances
from .sam2_candidates import (CHECKPOINT_SHA256, CONFIG, child, checked_artifacts,
                              load_source, grid_points, centers, overlay)
from .sam2_quality import roi_mask, calibrated_scale, select_candidates


def run(args):
    old=Path(args.previous).resolve(strict=True)
    if not old.is_relative_to(PRIVATE.resolve()):raise ValueError('Run fora da raiz privada')
    previous=json.loads((old/'summary.json').read_text(encoding='utf-8'))
    watershed=Path(previous['source_run']).resolve(strict=True)
    star=Path(previous['stardist_run']).resolve(strict=True)
    if not all(p.is_relative_to(PRIVATE.resolve()) for p in (watershed,star)):raise ValueError('Baseline fora da raiz privada')
    rois=json.loads(args.rois.read_text(encoding='utf-8'))
    mapping=json.loads(args.source_map.read_text(encoding='utf-8'))
    calibration=json.loads(args.calibration.read_text(encoding='utf-8')) if args.calibration else {}
    if sha256(args.checkpoint)!=CHECKPOINT_SHA256:raise ValueError('Checkpoint divergente')
    import torch
    from sam2.build_sam import build_sam2
    from sam2.sam2_image_predictor import SAM2ImagePredictor
    torch.set_num_threads(4)
    out=new_run('sam2-quality')
    json_write(out/'roi-specs.json',rois)
    json_write(out/'environment.json',{d.metadata['Name']:d.version for d in importlib.metadata.distributions()})
    json_write(out/'code_hashes.json',{p.name:sha256(p) for p in Path(__file__).parent.glob('*.py')})
    if calibration:json_write(out/'calibration.json',calibration)
    start=time.perf_counter()
    predictor=SAM2ImagePredictor(build_sam2(CONFIG,str(args.checkpoint),device='cpu'))
    model_seconds=time.perf_counter()-start
    results=[];process=psutil.Process()
    with torch.inference_mode():
        for oldrow in previous['results']:
            timage=time.perf_counter();directory=oldrow['directory'];folder=child(watershed,directory)
            checked_artifacts(watershed,directory,('record.json','roi_uint8.png','instances_uint16.png'))
            checked_artifacts(star,directory,('stardist_instances_uint16.png',))
            record,source,digest,bgr,_=load_source(folder,mapping)
            spec=rois[directory];roi=roi_mask(bgr.shape[:2],spec,digest)
            scale=calibrated_scale(calibration.get(directory),digest,roi.shape)
            reference=np.zeros(roi.shape,bool)
            if scale is not None:
                for ref in calibration[directory]['references']:
                    reference |= roi_mask(roi.shape,{**spec,'include_xy':ref['exclude_polygon_xy'],
                        'exclude_xy':[],'border_exclusion_px':0},digest)
            a=read_image(folder/'instances_uint16.png',-1)
            b=read_image(child(star,directory)/'stardist_instances_uint16.png',-1)
            if a.shape!=roi.shape or b.shape!=roi.shape:raise ValueError('Grades incompatíveis')
            target=out/directory;target.mkdir()
            write_image(target/'roi_uint8.png',roi.astype(np.uint8)*255)
            roi_view=bgr.copy();roi_view[~roi]=(roi_view[~roi]//3)
            write_image(target/'roi_review.jpg',roi_view)
            t=time.perf_counter();predictor.set_image(cv2.cvtColor(bgr,cv2.COLOR_BGR2RGB));encoder=time.perf_counter()-t
            variants={};panels=[]
            for name,points in [('grid',grid_points(roi,64)),('stardist_centers',centers(b,roi))]:
                t=time.perf_counter();raw=[];peak=process.memory_info().rss
                for x,y in points:
                    masks,scores,_=predictor.predict(point_coords=np.array([[x,y]],np.float32),point_labels=np.array([1]),multimask_output=True)
                    for k,(mask,score) in enumerate(zip(masks,scores)):
                        raw.append((mask.astype(bool),float(score),{'prompt_xy':[x,y],'mask_variant':k}))
                    peak=max(peak,process.memory_info().rss)
                prediction_seconds=time.perf_counter()-t
                labels,decisions,queue,limits=select_candidates(raw,roi,bgr,scale,reference)
                f=target/name;f.mkdir()
                write_image(f/'instances_uint16.png',labels)
                np.savez_compressed(f/'training_dev.npz',gray=cv2.cvtColor(bgr,cv2.COLOR_BGR2GRAY))
                packed=np.stack([np.packbits(m,axis=1) for m,_,_ in raw]) if raw else np.empty((0,roi.shape[0],(roi.shape[1]+7)//8),np.uint8)
                np.savez_compressed(f/'raw_masks.npz',packed=packed,shape_hw=np.array(roi.shape))
                # Scores, prompts e decisões mantêm índices das máscaras brutas.
                json_write(f/'decisions.json',decisions);json_write(f/'review_queue.json',queue)
                json_write(f/'record.json',{k:record[k] for k in ('source_shape_hw','mask_shape_hw','transform')} | {
                    'source':str(source),'source_sha256':digest,'source_sha256_after':sha256(source),
                    'candidate_model':'sam2.1_hiera_tiny_quality','candidate_method':name,
                    'roi_spec':spec,'scale':scale,'granulometry_status':'not_computed_pending_review',
                    'roi_sha256':sha256(target/'roi_uint8.png'),'previous_run':str(old)})
                checked_artifacts(old,f'{directory}/{name}',('instances_uint16.png',))
                before=read_image(old/directory/name/'instances_uint16.png',-1)
                before_view=overlay(bgr,before,name+' ANTES');after_view=overlay(bgr,labels,name+' DEPOIS')
                write_image(f/'before_after.jpg',np.concatenate([before_view,after_view],axis=1))
                write_image(f/'overlay_review.jpg',after_view)
                panels.extend([before_view,after_view])
                variants[name]={'prompts':len(points),'raw_candidates':len(raw),'accepted':int(labels.max()),
                    'pending_review':len(queue),'reasons':dict(Counter(reason for d in queue for reason in d['reasons'])),
                    'overlap_states':dict(Counter(c['state'] for d in decisions for c in d['conflicts'])),
                    'limits':limits,'prediction_seconds':prediction_seconds,'total_seconds':time.perf_counter()-t,
                    'rss_sampled_peak_bytes':peak,'process_peak_wset_bytes':getattr(process.memory_info(),'peak_wset',None),
                    'agreement_before':compare_instances(before,labels),
                    'agreement_watershed':compare_instances(a,labels),'agreement_stardist':compare_instances(b,labels)}
                del raw,packed
            write_image(target/'comparison_before_after.jpg',np.concatenate(panels,axis=0))
            write_image(target/'baselines_unchanged.jpg',np.concatenate([overlay(bgr,a,'watershed'),overlay(bgr,b,'StarDist')],axis=1))
            assert sha256(source)==digest
            row={'directory':directory,'source_sha256_before':digest,'source_sha256_after':sha256(source),
                 'encoder_seconds':encoder,'image_seconds':time.perf_counter()-timage,'variants':variants}
            json_write(target/'result.json',row);results.append(row)
            print(directory,{name:v['accepted'] for name,v in variants.items()},flush=True)
    json_write(out/'summary.json',{'schema':'pelotavision.sam2_quality.v1','previous_run':str(old),
        'checkpoint_sha256':CHECKPOINT_SHA256,'config':CONFIG,'model_load_seconds':model_seconds,
        'roi_input_sha256':sha256(args.rois),'source_map_sha256':sha256(args.source_map),
        'calibration_input_sha256':sha256(args.calibration) if args.calibration else None,
        'parameters':{'grid_spacing_px':64,'threads':4,'score_min':.7,'max_outside':.02,
            'duplicate_iou':.65,'containment':.9,'small_overlap_fraction':.08,'edge_max_distance_px':3},
        'results':results,'warning':'Comparacao exploratoria, sem validacao independente; fila preserva todos os candidatos nao aceitos'})
    json_write(out/'artifact_hashes.json',{str(p.relative_to(out)):sha256(p) for p in out.rglob('*') if p.is_file()})
    return out


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('previous','rois','source-map','checkpoint'):p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--calibration',type=Path)
    print(run(p.parse_args()))

if __name__=='__main__':main()
