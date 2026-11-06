"""Auditoria e ablação de conflitos usando cache bruto, sem inferência SAM."""
import argparse
import json
from collections import Counter
from pathlib import Path
import cv2
import numpy as np
from .pipeline import PRIVATE, new_run, json_write, sha256, read_image, write_image, compare_instances
from .sam2_candidates import child, load_source, overlay
from .sam2_quality import select_candidates, geometry
from .quality_review import queue_card
from .review import load_candidate


def audit(args):
    root=args.run.resolve(strict=True)
    if not root.is_relative_to(PRIVATE.resolve()):raise ValueError('Run fora da raiz privada')
    manifest=json.loads((root/'artifact_hashes.json').read_text(encoding='utf-8'))
    for name,digest in manifest.items():
        if sha256(child(root,name))!=digest:raise ValueError('Artefato alterado: '+name)
    summary=json.loads((root/'summary.json').read_text(encoding='utf-8'))
    old=Path(summary['previous_run'])
    original_summary=json.loads((old/'summary.json').read_text(encoding='utf-8'))
    mapping=json.loads(args.source_map.read_text(encoding='utf-8'))
    out=new_run('sam2-quality-report');rows=[]
    lines=['# Qualidade SAM 2.1 — relatório técnico privado','',
           'Comparação exploratória. Sem revisão humana independente; menor contagem não prova maior qualidade.',
           '',f'Run novo: `{root}`',f'Run anterior: `{old}`','',
           '| Imagem | Grade antes/depois | Centros antes/depois | Fila grade/centros | Tempo total novo s |',
           '|---|---:|---:|---:|---:|']
    for row in summary['results']:
        directory=row['directory'];image=root/directory
        _,source,digest,bgr,_=load_source(Path(original_summary['source_run'])/directory,mapping)
        if digest!=row['source_sha256_before'] or digest!=row['source_sha256_after']:raise ValueError('Original alterado')
        roi=read_image(image/'roi_uint8.png',-1)>0
        methods={name:read_image(path,-1) for name,path in {
            'watershed':Path(original_summary['source_run'])/directory/'instances_uint16.png',
            'stardist':Path(original_summary['stardist_run'])/directory/'stardist_instances_uint16.png',
            'grid':image/'grid/instances_uint16.png',
            'stardist_centers':image/'stardist_centers/instances_uint16.png'}.items()}
        ablation={};cards=[];before_counts={}
        for name in ('grid','stardist_centers'):
            folder=image/name
            model=load_candidate(folder)
            assert len(model.meta)==row['variants'][name]['accepted']
            queue=json.loads((folder/'review_queue.json').read_text(encoding='utf-8'))
            decisions=json.loads((folder/'decisions.json').read_text(encoding='utf-8'))
            metadata={d['candidate_index']:d for d in decisions}
            with np.load(folder/'raw_masks.npz',allow_pickle=False) as data:
                packed=data['packed'];h,w=map(int,data['shape_hw'])
            raw=[]
            for index,packed_mask in enumerate(packed):
                d=metadata[index];mask=np.unpackbits(packed_mask,axis=1)[:,:w].astype(bool)
                raw.append((mask,d['score_sam'] if d['score_sam'] is not None else float('nan'),
                            {'prompt_xy':d['prompt_xy'],'mask_variant':d['mask_variant']}))
            # Esta ablação é exclusivamente não calibrada, como o piloto auditado.
            record=json.loads((folder/'record.json').read_text(encoding='utf-8'))
            if record['scale'] is not None:raise ValueError('Ablação deste relatório requer piloto não calibrado')
            ablation[name]={}
            for policy in ('any_pixel','iou_only','edge'):
                labels,dec,_,_=select_candidates(raw,roi,bgr,overlap_policy=policy)
                ablation[name][policy]={'accepted':int(labels.max()),'conflicts':dict(Counter(c['state'] for d in dec for c in d['conflicts']))}
                if policy=='edge':
                    if not np.array_equal(labels,methods[name]):raise ValueError('Cache não reproduz mapa final')
                write_image(out/(directory+'-'+name+'-'+policy+'.jpg'),overlay(bgr,labels,name+' '+policy))
            if queue:
                card=queue_card(folder,0);loaded=load_candidate(card)
                assert loaded.labels.any();cards.append(str(card))
                top=raw[queue[0]['candidate_index']][0]
                write_image(out/(directory+'-'+name+'-queue-top.jpg'),overlay(bgr,top.astype(np.uint16),'SUSPEITO '+name))
            before=read_image(old/directory/name/'instances_uint16.png',-1)
            before_counts[name]=int(len(np.unique(before[before>0])))
            del raw,packed
        diagnostics={}
        for name,labels in methods.items():
            objects=[]
            for ident in np.unique(labels[labels>0]):
                m=labels==ident;g=geometry(m);yy,xx=np.where(m)
                objects.append({'id':int(ident),**g,'centroid_xy':[float(xx.mean()),float(yy.mean())],
                    'outside_roi_fraction':float((m&~roi).sum()/g['area_px'])})
            median=float(np.median([o['area_px'] for o in objects])) if objects else None
            diagnostics[name]={'instances':objects,'median_area_px':median,
                'suspected_composites_ids':[o['id'] for o in objects if o['components']>1 or o['area_px']>2.8*median or o['solidity']<.75],
                'outside_roi_ids':[o['id'] for o in objects if o['outside_roi_fraction']>.02]}
        pairs={}
        names=list(methods)
        for i,name in enumerate(names):
            for other in names[i+1:]:pairs[name+'__'+other]=compare_instances(methods[name],methods[other])
        rows.append({'directory':directory,'source_sha256':digest,'ablation_same_raw':ablation,
                     'diagnostics_not_ground_truth':diagnostics,'pairwise_agreement_not_accuracy':pairs,'queue_cards_checked':cards})
        g=row['variants']['grid'];c=row['variants']['stardist_centers']
        lines.append(f"| {directory.split('-')[1]} | {before_counts['grid']}/{g['accepted']} | {before_counts['stardist_centers']}/{c['accepted']} | {g['pending_review']}/{c['pending_review']} | {row['image_seconds']:.2f} |")
        if sha256(source)!=digest:raise ValueError('Original alterado durante auditoria')
    lines += ['', '## Interpretação e limitações', '',
        '- As oito imagens foram inspecionadas pelo agente; não há anotação humana independente.',
        '- ROI conservadora pode remover pelotas periféricas. Exemplos e propostas brutas precisam de revisão.',
        '- Os métodos não foram fundidos. Centros SAM são dependentes de StarDist por construção.',
        '- Ausência de par é discordância, não omissão comprovada. Suspeitas geométricas não são rótulos.',
        '- Cada ablação mantém cache/ROI/geometria fixos. Seu resultado reproduz o mapa final da política edge.',
        '- Pares IoU/contenção identificam duplicações ou aninhamentos; objetos compostos ainda podem sobreviver.',
        '- Nenhuma escala física aplicada. Referências brancas permanecem suspeitas até declaração/medição rastreável.',
        '- Pronto: integração experimental de ROI, persistência, fila e proveniência. Não pronto: granulometria automática ou alegação de desempenho científico.',
        '- Testes de carregamento de cartões não substituem sessão humana no editor. Alterações em cartões não são reconciliadas automaticamente.',
        '- Tempos são observações desta máquina, com atividade leve concorrente; não benchmark controlado.']
    (out/'relatorio.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    json_write(out/'audit.json',{'run':str(root),'verified_artifacts':len(manifest),'rows':rows,
        'code_sha256':sha256(Path(__file__)),'summary_sha256':sha256(root/'summary.json')})
    json_write(out/'artifact_hashes.json',{p.name:sha256(p) for p in out.iterdir() if p.is_file()})
    return out


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--run',type=Path,required=True);p.add_argument('--source-map',type=Path,required=True)
    print(audit(p.parse_args()))
