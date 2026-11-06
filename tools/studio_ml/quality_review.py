"""Editor ROI poligonal e abertura de candidatos da fila no editor existente."""
import argparse
import json
from pathlib import Path
import cv2
import numpy as np
from .pipeline import PRIVATE, new_run, json_write, write_image, sha256
from .sam2_candidates import child, load_source, checked_artifacts
from .sam2_quality import propose_roi, roi_mask
from .review import Editor, load_candidate


def queue_card(folder, rank):
    folder=Path(folder).resolve(strict=True)
    if not folder.is_relative_to(PRIVATE.resolve()):raise ValueError('Fila fora da raiz privada')
    run=folder.parent.parent
    checked_artifacts(run,folder.relative_to(run).as_posix(),
                      ('review_queue.json','raw_masks.npz','record.json','training_dev.npz'))
    queue=json.loads((folder/'review_queue.json').read_text(encoding='utf-8'))
    if not 0<=rank<len(queue):raise ValueError('Posição fora da fila')
    item=queue[rank];record=json.loads((folder/'record.json').read_text(encoding='utf-8'))
    with np.load(folder/'raw_masks.npz',allow_pickle=False) as data:
        h,w=map(int,data['shape_hw']);mask=np.unpackbits(data['packed'][item['candidate_index']],axis=1)[:,:w].astype(bool)
    with np.load(folder/'training_dev.npz',allow_pickle=False) as data:gray=data['gray']
    if mask.shape!=gray.shape:raise ValueError('Grade inválida')
    out=new_run('sam2-queue-card')
    write_image(out/'instances_uint16.png',mask.astype(np.uint16))
    np.savez_compressed(out/'training_dev.npz',gray=gray)
    json_write(out/'record.json',{**record,'queue_candidate':item,'queue_source':str(folder),
        'queue_input_hashes':{name:sha256(folder/name) for name in ('raw_masks.npz','review_queue.json','record.json')},
        'review_scope':'candidato bruto isolado; criar/separar/unir instancias nesta revisao; nao altera piloto'})
    return out


def edit_roi(folder, source_map, initial=None):
    import tkinter as tk
    from tkinter import messagebox
    folder=Path(folder).resolve(strict=True)
    if not folder.is_relative_to(PRIVATE.resolve()):raise ValueError('Fonte fora da raiz privada')
    mapping=json.loads(Path(source_map).read_text(encoding='utf-8')) if source_map else {}
    _,_,digest,bgr,_=load_source(folder,mapping);h,w=bgr.shape[:2]
    spec=json.loads(Path(initial).read_text(encoding='utf-8')) if initial else {
        'source_sha256':digest,'shape_hw':[h,w],'include_xy':propose_roi(bgr),
        'exclude_xy':[],'border_exclusion_px':3,'status':'unreviewed_photometric_proposal'}
    root=tk.Tk();root.title('ROI poligonal — sugestão não validada')
    canvas=tk.Canvas(root,width=w,height=h);canvas.pack()
    mode=tk.StringVar(value='include');draft=[]
    margin=tk.IntVar(value=spec['border_exclusion_px'])
    def render():
        image=bgr.copy()
        for pts,color in [(spec['include_xy'],(0,255,0))]+[(p,(0,0,255)) for p in spec['exclude_xy']]:
            if len(pts)>=3:cv2.polylines(image,[np.array(pts,np.int32)],True,color,2)
        for x,y in draft:cv2.circle(image,(x,y),4,(0,255,255),-1)
        _,encoded=cv2.imencode('.ppm',image)
        canvas.photo=tk.PhotoImage(data=encoded.tobytes(),format='PPM')
        canvas.delete('all');canvas.create_image(0,0,anchor='nw',image=canvas.photo)
    def click(event):
        if 0<=event.x<w and 0<=event.y<h:draft.append([event.x,event.y]);render()
    def apply_polygon():
        if len(draft)<3:return
        if mode.get()=='include':spec['include_xy']=draft.copy()
        else:spec['exclude_xy'].append(draft.copy())
        draft.clear();render()
    def undo():
        if draft:draft.pop()
        elif spec['exclude_xy']:spec['exclude_xy'].pop()
        render()
    def save():
        try:
            spec['border_exclusion_px']=margin.get();roi_mask((h,w),spec,digest)
            spec['status']='edited_human_roi_not_ground_truth'
            out=new_run('sam2-roi-edit');json_write(out/'roi.json',spec)
            messagebox.showinfo('Salvo',str(out/'roi.json'))
        except (ValueError,OSError) as exc:messagebox.showerror('ROI inválida',str(exc))
    canvas.bind('<Button-1>',click)
    tk.Label(root,text='Clique vértices; aplicar substitui inclusão ou adiciona exclusão. Verde: inclusão, vermelho: exclusões.').pack()
    for value,label in [('include','Polígono válido'),('exclude','Excluir recipiente/borda')]:tk.Radiobutton(root,text=label,variable=mode,value=value).pack()
    tk.Spinbox(root,from_=0,to=50,textvariable=margin).pack()
    for text,cmd in [('Aplicar polígono',apply_polygon),('Desfazer',undo),('Salvar nova ROI',save)]:tk.Button(root,text=text,command=cmd).pack()
    render();root.mainloop()


def main():
    p=argparse.ArgumentParser(description=__doc__);sub=p.add_subparsers(dest='command',required=True)
    q=sub.add_parser('queue');q.add_argument('folder',type=Path);q.add_argument('--rank',type=int,default=0);q.add_argument('--revisor',required=True)
    r=sub.add_parser('roi');r.add_argument('folder',type=Path);r.add_argument('--source-map',type=Path);r.add_argument('--initial',type=Path)
    args=p.parse_args()
    if args.command=='roi':edit_roi(args.folder,args.source_map,args.initial)
    else:Editor(load_candidate(queue_card(args.folder,args.rank)),args.revisor,None).root.mainloop()

if __name__=='__main__':main()
