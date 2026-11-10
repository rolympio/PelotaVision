#!/usr/bin/env python3
"""Compare matched per-pellet image diameters to physical references.

CSV columns: pellet_id,scene_id,reference_mm,measured_mm,calibration_id,
occluded,perspective_corrected,segmentation_reviewed
Each run should isolate a single processing configuration; never pool repeated
measurements of the same pellet as independent objects.
"""
import argparse,csv,json,math,statistics
from collections import defaultdict
from pathlib import Path
def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--input",required=True);ap.add_argument("--output",required=True)
    a=ap.parse_args()
    with open(a.input,newline="",encoding="utf-8-sig") as f: rows=list(csv.DictReader(f))
    if not rows: ap.error("empty reference file")
    seen=set(); groups=defaultdict(list)
    for r in rows:
        k=r["pellet_id"]
        if k in seen: ap.error("duplicate pellet_id: "+k)
        seen.add(k)
        ref=float(r["reference_mm"]); meas=float(r["measured_mm"])
        if not all(map(math.isfinite,(ref,meas))) or ref<=0 or meas<=0: ap.error("invalid diameter "+k)
        e=meas-ref
        for key,value in [("overall","all"),("scene",r["scene_id"]),
                          ("calibration",r["calibration_id"]),
                          ("occluded",r["occluded"].lower()),
                          ("perspective_corrected",r["perspective_corrected"].lower()),
                          ("segmentation_reviewed",r["segmentation_reviewed"].lower())]:
            groups[key+":"+value].append((e,abs(e),abs(e)/ref*100))
    report={}
    for g,values in sorted(groups.items()):
        n=len(values)
        report[g]={"n":n,"bias_mm":statistics.mean(v[0] for v in values),
            "mae_mm":statistics.mean(v[1] for v in values),
            "rmse_mm":math.sqrt(statistics.mean(v[0]**2 for v in values)),
            "mape_percent":statistics.mean(v[2] for v in values),
            "p95_absolute_error_mm":sorted(v[1] for v in values)[math.ceil(.95*n)-1]}
    path=Path(a.output);path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(report,indent=2,ensure_ascii=False)+"\n",encoding="utf-8")
    print(json.dumps(report,indent=2,ensure_ascii=False))
if __name__=="__main__":main()
