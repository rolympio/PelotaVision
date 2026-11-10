#!/usr/bin/env python3
"""Blind held-out comparison of per-pellet binary crack predictions.

CSV truth: pellet_id,scene_id,label,split[,source_id]
CSV predictions: pellet_id,model,score[,predicted_label]
label 1=visible crack, 0=no visible crack. Inconclusive labels must be excluded
from the adjudicated truth, but reported separately upstream.
"""
import argparse, csv, json, math
from collections import defaultdict
from pathlib import Path

def rows(path):
    with open(path, newline="", encoding="utf-8-sig") as f:
        return list(csv.DictReader(f))

def metric(tp, fp, fn, tn):
    div=lambda a,b: a/b if b else None
    p=div(tp,tp+fp); r=div(tp,tp+fn)
    return {"TP":tp,"FP":fp,"FN":fn,"TN":tn,"precision":p,
            "recall":r,"f1":2*p*r/(p+r) if p is not None and r is not None and p+r else None,
            "specificity":div(tn,tn+fp),"accuracy":div(tp+tn,tp+tn+fp+fn)}

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--truth",required=True)
    ap.add_argument("--predictions",required=True)
    ap.add_argument("--output",required=True)
    ap.add_argument("--threshold",type=float,default=0.5)
    args=ap.parse_args()
    if not 0<=args.threshold<=1: ap.error("threshold must be in [0,1]")
    truth=rows(args.truth); pred=rows(args.predictions)
    if not truth: ap.error("empty truth")
    ids=set(); groups=defaultdict(set); labels={}
    for r in truth:
        k=r["pellet_id"]
        if k in ids: ap.error("duplicate pellet_id in truth: "+k)
        ids.add(k)
        if r["split"].strip().lower()!="test": ap.error("truth must contain TEST ONLY: "+k)
        if r["label"] not in ("0","1"): ap.error("label must be 0/1: "+k)
        if not r["scene_id"].strip(): ap.error("missing scene_id")
        labels[k]=int(r["label"])
        groups[r["scene_id"]].add(k)
    by_model=defaultdict(dict)
    for r in pred:
        k=r["pellet_id"]; model=r["model"]
        if k not in ids: ap.error("prediction for unknown pellet: "+k)
        if k in by_model[model]: ap.error("duplicate prediction "+model+"/"+k)
        score=float(r["score"])
        if not math.isfinite(score) or not 0<=score<=1: ap.error("invalid score")
        by_model[model][k]=int(score>=args.threshold)
    if not {"RandomForest","MobileNetV3"}.issubset(by_model):
        ap.error("expected model names RandomForest and MobileNetV3")
    report={"threshold":args.threshold,"n_pellets":len(ids),"n_scenes":len(groups),"models":{}}
    for model, guesses in sorted(by_model.items()):
        if set(guesses)!=ids: ap.error("missing predictions for "+model+": "+str(len(ids-set(guesses))))
        def score(keys):
            tp=sum(labels[k]==1 and guesses[k]==1 for k in keys)
            fp=sum(labels[k]==0 and guesses[k]==1 for k in keys)
            fn=sum(labels[k]==1 and guesses[k]==0 for k in keys)
            tn=sum(labels[k]==0 and guesses[k]==0 for k in keys)
            return metric(tp,fp,fn,tn)
        report["models"][model]={"overall":score(ids),"by_scene":{g:score(keys) for g,keys in sorted(groups.items())}}
    out=Path(args.output);out.parent.mkdir(parents=True,exist_ok=True)
    out.write_text(json.dumps(report,indent=2,ensure_ascii=False)+"\n",encoding="utf-8")
    print(json.dumps(report,indent=2,ensure_ascii=False))
if __name__=="__main__": main()
