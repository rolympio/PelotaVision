#!/usr/bin/env python3
"""Generate traceable augmentations of REAL labeled pellet crops (not invented ground truth).

CSV columns: image,label,scene,split ; label 0/1; split train/test.
Input images must contain one centered pellet. Held-out test scenes are never augmented.
Requires: pip install opencv-python numpy
"""
import argparse, csv, hashlib, json
from pathlib import Path
import cv2
import numpy as np

def augment(im, rng):
    h,w=im.shape[:2]
    angle=float(rng.uniform(-180,180))
    scale=float(rng.uniform(.93,1.07))
    mat=cv2.getRotationMatrix2D((w/2,h/2),angle,scale)
    out=cv2.warpAffine(im,mat,(w,h),flags=cv2.INTER_LINEAR,borderMode=cv2.BORDER_REFLECT_101)
    if rng.random()<.5: out=cv2.flip(out,1)
    gain=float(rng.uniform(.85,1.15)); offset=float(rng.uniform(-12,12))
    out=np.clip(out.astype(np.float32)*gain+offset,0,255).astype(np.uint8)
    if rng.random()<.35:
        out=cv2.GaussianBlur(out,(3,3),float(rng.uniform(.3,.8)))
    noise=rng.normal(0,float(rng.uniform(0,2)),out.shape)
    return np.clip(out.astype(np.float32)+noise,0,255).astype(np.uint8)

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--manifest",required=True)
    p.add_argument("--out",required=True)
    p.add_argument("--per-source",type=int,default=20)
    p.add_argument("--seed",type=int,default=20261024)
    a=p.parse_args()
    if not 1<=a.per_source<=100: p.error("--per-source must be 1..100")
    root=Path(a.manifest).resolve().parent; out=Path(a.out);out.mkdir(parents=True,exist_ok=True)
    rng=np.random.default_rng(a.seed)
    with open(a.manifest,newline="",encoding="utf-8") as f: rows=list(csv.DictReader(f))
    if not rows: raise ValueError("Empty manifest")
    train_scenes={r["scene"] for r in rows if r["split"]=="train"}
    test_scenes={r["scene"] for r in rows if r["split"]=="test"}
    if train_scenes & test_scenes: raise ValueError("Scene leakage between train/test")
    if not test_scenes: raise ValueError("At least one independent test scene required")
    outputs=[]
    for i,r in enumerate(rows):
        if r["label"] not in ("0","1") or r["split"] not in ("train","test") or not r["scene"]:
            raise ValueError(f"Invalid row {i}")
        src=(root/r["image"]).resolve()
        if not src.is_relative_to(root): raise ValueError("Image path escapes manifest directory")
        im=cv2.imread(str(src),cv2.IMREAD_COLOR)
        if im is None: raise FileNotFoundError(src)
        digest=hashlib.sha256(src.read_bytes()).hexdigest()
        if r["split"]=="test": continue
        for j in range(a.per_source):
            dest=out/f"pellet_{i:05d}_aug_{j:03d}.png"
            if not cv2.imwrite(str(dest),augment(im,rng)): raise IOError(dest)
            outputs.append({"image":str(dest),"label":int(r["label"]),"scene":r["scene"],
                            "source":str(src),"source_sha256":digest,"synthetic":True,
                            "seed":a.seed,"variant":j})
    with open(out/"provenance.jsonl","w",encoding="utf-8") as f:
        for row in outputs: f.write(json.dumps(row,ensure_ascii=False)+"\n")
    print(f"Generated {len(outputs)} augmented crops; {len(test_scenes)} untouched test scenes")
    print("WARNING: augmented labels inherit source labels; do not claim independent validation.")

if __name__=="__main__": main()
