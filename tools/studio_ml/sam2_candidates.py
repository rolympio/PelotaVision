"""SAM 2.1 CPU: propostas exploratorias, nunca ground truth.

python -m tools.studio_ml.sam2_candidates --run PATH --checkpoint PATH --stardist-run PATH
Sem download ou instalacao automatica. Somente saida privada via new_run.
"""
from __future__ import annotations
import argparse
import importlib.metadata
import json
import time
from pathlib import Path
import cv2
import numpy as np
from .pipeline import PRIVATE, new_run, sha256, read_image, write_image, json_write, boundary, compare_instances, environment

CHECKPOINT_URL = 'https://dl.fbaipublicfiles.com/segment_anything_2/092824/sam2.1_hiera_tiny.pt'
LICENSE_URL = 'https://github.com/facebookresearch/sam2/blob/main/LICENSE'
CONFIG = 'configs/sam2.1/sam2.1_hiera_t.yaml'
CHECKPOINT_SHA256 = '7402e0d864fa82708a20fbd15bc84245c2f26dff0eb43a4b5b93452deb34be69'

def child(root, directory):
    relative = Path(directory)
    if relative.is_absolute() or '..' in relative.parts:
        raise ValueError('Caminho relativo invalido')
    path = (root / directory).resolve(strict=True)
    if not path.is_relative_to(root) or path == root:
        raise ValueError('Caminho fora do run')
    return path

def checked_artifacts(root, directory, names):
    """Confere somente entradas utilizadas contra o manifesto historico."""
    manifest=json.loads((root/'artifact_hashes.json').read_text(encoding='utf-8'))
    manifest={key.replace('\\','/'):value for key,value in manifest.items()}
    hashes={}
    for name in names:
        relative=f'{directory}/{name}'
        digest=sha256(child(root,relative))
        if manifest.get(relative)!=digest:
            raise ValueError(f'Artefato historico alterado: {relative}')
        hashes[relative]=digest
    return hashes

def area_limits(args):
    if args.px_per_mm is None:
        low, high = args.min_area_px, args.max_area_px
    else:
        if not args.calibration_record or not Path(args.calibration_record).is_file():
            raise ValueError('Escala fisica exige registro rastreavel --calibration-record')
        if not np.isfinite(args.px_per_mm) or args.px_per_mm <= 0:
            raise ValueError('Escala invalida')
        low = np.pi*(args.min_diameter_mm*args.px_per_mm/2)**2
        high = np.pi*(args.max_diameter_mm*args.px_per_mm/2)**2
    if not np.isfinite([low, high]).all() or low < 1 or high < low:
        raise ValueError('Limites de area invalidos')
    return low, high

def overlay(bgr, labels, title):
    result = bgr.copy()
    color = np.stack([(labels.astype(np.uint32)*v)%255 for v in (73,139,211)], axis=-1).astype(np.uint8)
    present = labels > 0
    result[present] = cv2.addWeighted(bgr,.65,color,.35,0)[present]
    result[boundary(labels)>0] = (0,255,255)
    cv2.putText(result,title,(10,25),cv2.FONT_HERSHEY_SIMPLEX,.6,(0,0,255),2)
    return result

def grid_points(roi, spacing=32):
    if spacing < 1 or roi.ndim != 2: raise ValueError('Grade invalida')
    h,w=roi.shape
    return [(x,y) for y in range(spacing//2,h,spacing) for x in range(spacing//2,w,spacing) if roi[y,x]]

def centers(labels, roi):
    if labels.shape != roi.shape: raise ValueError('Grades incompatíveis')
    pts=[]
    for ident in np.unique(labels):
        if not ident: continue
        yy,xx=np.where((labels==ident)&(roi>0))
        if len(xx):
            k=np.argmin((xx-xx.mean())**2+(yy-yy.mean())**2)
            pts.append((int(xx[k]),int(yy[k])))
    return pts

def filter_candidates(candidates, roi, min_area, max_area, max_outside=.02):
    """Ordena por score, aceita somente regioes inteiras sem conflito.
    Rejeita sobreposicao em vez de cortar artificialmente a instancia.
    """
    if roi.ndim != 2 or not np.isfinite([min_area,max_area]).all() or not 0 <= max_outside <= 1 or min_area < 1 or max_area < min_area: raise ValueError('Limites invalidos')
    labels=np.zeros(roi.shape,np.uint16); info=[]; rejected={}
    def order(pair):
        index, (_, score, _) = pair
        return (-float(score) if np.isfinite(score) else float('inf'), index)
    for index,(mask,score,meta) in sorted(enumerate(candidates),key=order):
        mask=np.asarray(mask,dtype=bool)
        if mask.shape!=roi.shape: raise ValueError('Grade candidata diferente')
        area=int(mask.sum()); reason=None
        if not np.isfinite(score): reason='score_invalido'
        elif area<min_area or area>max_area: reason='area'
        elif area and int((mask & (roi==0)).sum())/area>max_outside: reason='fora_roi'
        elif np.any(mask & (labels>0)): reason='sobreposicao'
        if reason:
            rejected[reason]=rejected.get(reason,0)+1;continue
        if len(info)>=65535: raise ValueError('Limite uint16')
        ident=len(info)+1
        labels[mask]=ident
        info.append({'id':ident,'score_sam':float(score),'area_px':area,'candidate_index':int(index),**meta})
    return labels,info,rejected

def load_source(folder, source_map=None):
    record=json.loads((folder/'record.json').read_text(encoding='utf-8'))
    source=Path((source_map or {}).get(record['source_sha256'],record['source'])).resolve(strict=True)
    before=sha256(source)
    if before!=record['source_sha256'] or (record.get('source_sha256_after') and before!=record['source_sha256_after']):
        raise RuntimeError('Hash da imagem original nao confere')
    original=read_image(source)
    h,w=map(int,record['mask_shape_hw'])
    if list(original.shape[:2])!=record['source_shape_hw']:raise ValueError('Dimensoes originais divergentes')
    image=cv2.resize(original,(w,h),interpolation=cv2.INTER_AREA) if original.shape[:2]!=(h,w) else original
    roi=read_image(folder/'roi_uint8.png',cv2.IMREAD_UNCHANGED)>0
    baseline=read_image(folder/'instances_uint16.png',cv2.IMREAD_UNCHANGED)
    if roi.shape!=(h,w) or baseline.shape!=(h,w) or baseline.dtype!=np.uint16:raise ValueError('Grade watershed inconsistente')
    if sha256(source)!=before:raise RuntimeError('Original mudou durante leitura')
    return record,source,before,image,roi

def run(args):
    min_area, max_area = area_limits(args)
    if args.limit < 1 or args.spacing < 1 or args.threads < 1 or not 0 <= args.max_outside <= 1:
        raise ValueError('Parametros invalidos')
    root=Path(PRIVATE).resolve()
    watershed=Path(args.run).resolve(strict=True)
    if not watershed.is_relative_to(root):raise ValueError('Watershed fora da raiz privada')
    summary=json.loads((watershed/'summary.json').read_text(encoding='utf-8'))
    source_map=json.loads(args.source_map.read_text(encoding='utf-8')) if args.source_map else {}
    rows=summary['rows'][:args.limit]
    if not rows:raise ValueError('Run sem imagens')
    checkpoint=Path(args.checkpoint).resolve(strict=True)
    if checkpoint.name!='sam2.1_hiera_tiny.pt':raise ValueError('Checkpoint nao e SAM 2.1 tiny')
    checkpoint_hash=sha256(checkpoint)
    if checkpoint_hash != CHECKPOINT_SHA256 or checkpoint.stat().st_size != 156008466:
        raise ValueError('Integridade do checkpoint oficial divergente')
    try:
        from sam2.build_sam import build_sam2
        from sam2.sam2_image_predictor import SAM2ImagePredictor
    except ImportError as exc:raise RuntimeError('Instale SAM2 somente apos aprovacao') from exc
    import torch
    import psutil
    process = psutil.Process()
    torch.set_num_threads(args.threads)
    torch.set_grad_enabled(False)
    model_start = time.monotonic()
    model=build_sam2(CONFIG,str(checkpoint),device='cpu')
    predictor=SAM2ImagePredictor(model)
    model_seconds = time.monotonic()-model_start
    star_root=Path(args.stardist_run).resolve(strict=True) if args.stardist_run else None
    if star_root and not star_root.is_relative_to(root):raise ValueError('StarDist fora da raiz privada')
    out=new_run('sam2-candidates')
    json_write(out/'environment.json', {**environment(), 'versions': {d.metadata['Name']:d.version for d in importlib.metadata.distributions()}})
    json_write(out/'code_hashes.json',{p.name:sha256(p) for p in Path(__file__).parent.glob('*.py')})
    star_rows = {}
    if star_root:
        star_summary = json.loads((star_root/'summary.json').read_text(encoding='utf-8'))
        star_rows = {r['directory']:r for r in star_summary['results']}
    results=[]
    for row in rows:
        image_start = time.monotonic()
        folder=child(watershed,row['directory'])
        input_hashes={'watershed':checked_artifacts(watershed,row['directory'],
            ('record.json','roi_uint8.png','instances_uint16.png'))}
        record,source,before,bgr,roi=load_source(folder,source_map)
        shape=roi.shape
        baseline=read_image(folder/'instances_uint16.png',cv2.IMREAD_UNCHANGED)
        rgb=cv2.cvtColor(bgr,cv2.COLOR_BGR2RGB)
        encode_start=time.monotonic()
        predictor.set_image(rgb)
        encode_seconds=time.monotonic()-encode_start
        variants={'grid':grid_points(roi,args.spacing)}
        references={'watershed':baseline}
        if star_root:
            input_hashes['stardist']=checked_artifacts(star_root,row['directory'],('stardist_instances_uint16.png',))
            if star_rows[row['directory']]['source_sha256'] != before:
                raise ValueError('StarDist corresponde a outro original')
            labels=read_image(child(star_root,row['directory'])/'stardist_instances_uint16.png',cv2.IMREAD_UNCHANGED)
            if labels.dtype != np.uint16 or labels.shape != shape:
                raise ValueError('Grade StarDist inconsistente')
            variants['stardist_centers']=centers(labels,roi)
            references['stardist']=labels
        image_results={}
        panels=[overlay(bgr,lab,name) for name,lab in references.items()]
        for name,points in variants.items():
            t0=time.monotonic();candidates=[]
            rss_samples=[process.memory_info().rss]
            for x,y in points:
                masks,scores,_=predictor.predict(point_coords=np.array([[x,y]],np.float32),point_labels=np.array([1]),multimask_output=True)
                for k,(mask,score) in enumerate(zip(masks,scores)):
                    candidates.append((mask,float(score),{'prompt_xy':[x,y],'mask_variant':k}))
                rss_samples.append(process.memory_info().rss)
            labels,accepted,rejected=filter_candidates(candidates,roi,min_area,max_area,args.max_outside)
            target=out/row['directory']/name
            target.mkdir(parents=True,exist_ok=False)
            write_image(target/'instances_uint16.png',labels)
            json_write(target/'instances.json',accepted)
            # Contrato do editor: imagem cinza e proveniencia; nenhum alvo de treino.
            candidate_record={key:record[key] for key in ('source','source_sha256','source_sha256_after',
                'source_shape_hw','mask_shape_hw','transform','roi_ellipse_normalized')}
            json_write(target/'record.json',{**candidate_record,'candidate_method':name,
                'source':str(source),'historical_source':record['source'],
                'candidate_model':'sam2.1_hiera_tiny','instances':accepted,
                'status':'candidatas_sem_ground_truth','zero_instancia':'desconhecido_nao_fundo',
                'watershed_record_sha256':sha256(folder/'record.json')})
            np.savez_compressed(target/'training_dev.npz',gray=cv2.cvtColor(bgr,cv2.COLOR_BGR2GRAY))
            panel=overlay(bgr,labels,'SAM '+name)
            write_image(target/'overlay_review.jpg',panel)
            panels.append(panel)
            image_results[name]={'prompts':len(points),'candidates':len(candidates),'accepted':len(accepted),'rejected':rejected,'seconds':round(time.monotonic()-t0,4),
                'rss_sampled_peak_bytes':max(rss_samples),'rss_after_bytes':process.memory_info().rss,
                'process_peak_wset_bytes':getattr(process.memory_info(),'peak_wset',None),
                'comparison':{key:compare_instances(lab,labels) for key,lab in references.items()}}
            del candidates
        write_image(out/row['directory']/'comparison.jpg',np.concatenate(panels,axis=1))
        if sha256(source)!=before:raise RuntimeError('Original modificado durante processamento')
        results.append({'directory':row['directory'],'source_sha256_before':before,'source_sha256_after':sha256(source),'variants':image_results,
            'encoder_seconds':encode_seconds,'image_seconds':time.monotonic()-image_start,
            'input_hashes':input_hashes,
            'baseline_counts':{key:int(len(np.unique(lab[lab>0]))) for key,lab in references.items()},
            'stardist_historical_seconds':star_rows.get(row['directory'],{}).get('seconds'),
            'watershed_historical_seconds':None})
        json_write(out/row['directory']/'result.json',results[-1])
        print(json.dumps(results[-1],ensure_ascii=False),flush=True)
    version=importlib.metadata.version('SAM-2')
    json_write(out/'summary.json',{'schema':'pelotavision.sam2_candidates.v1','status':'candidatas_sem_ground_truth',
        'model':{'name':'sam2.1_hiera_tiny','package_version':version,'code_license':'Apache-2.0','license_url':LICENSE_URL,
                  'checkpoint_license':'Apache-2.0','checkpoint_url':CHECKPOINT_URL,
                 'checkpoint_sha256':checkpoint_hash,'checkpoint_bytes':checkpoint.stat().st_size,'config':CONFIG},
        'parameters':{'device':'cpu','spacing_px':args.spacing,'px_per_mm':args.px_per_mm,'min_diameter_mm':args.min_diameter_mm,
                       'max_diameter_mm':args.max_diameter_mm,'max_outside':args.max_outside,
                       'area_limits_px':[min_area,max_area],'threads':args.threads,
                       'calibration_sha256':sha256(args.calibration_record) if args.px_per_mm is not None else None},
         'model_load_seconds':model_seconds,'memory_method':'RSS amostrado apos cada prompt; peak_wset cumulativo do processo Windows, nao pico isolado por imagem',
         'source_run':str(watershed),
         'source_map_sha256':sha256(args.source_map) if args.source_map else None,
        'stardist_run':str(star_root) if star_root else None,'results':results,
        'warning':'Candidatas nao sao verdade, acuracia, referencia nem diagnostico de trinca.'})
    json_write(out/'artifact_hashes.json',{str(p.relative_to(out)):sha256(p) for p in out.rglob('*') if p.is_file()})
    return out

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--run',type=Path,required=True);p.add_argument('--checkpoint',type=Path,required=True)
    p.add_argument('--stardist-run',type=Path);p.add_argument('--limit',type=int,default=8)
    p.add_argument('--source-map',type=Path,help='JSON privado SHA-256 -> caminho atual; verifica hash antes de usar')
    p.add_argument('--spacing',type=int,default=32);p.add_argument('--px-per-mm',type=float)
    p.add_argument('--calibration-record',type=Path)
    p.add_argument('--min-area-px',type=float,default=60);p.add_argument('--max-area-px',type=float,default=1000000)
    p.add_argument('--threads',type=int,default=4)
    p.add_argument('--min-diameter-mm',type=float,default=5);p.add_argument('--max-diameter-mm',type=float,default=25)
    p.add_argument('--max-outside',type=float,default=.02)
    args=p.parse_args()
    if args.limit<1 or args.spacing<1 or args.min_diameter_mm<=0 or args.max_diameter_mm<args.min_diameter_mm:p.error('Parametros invalidos')
    print(run(args))
if __name__=='__main__':main()
