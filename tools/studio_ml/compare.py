"""Compara dois mapas de instâncias na mesma grade, sem pressupor ground truth."""
import argparse
from pathlib import Path

import cv2

from .pipeline import compare_instances, json_write, new_run, read_image, sha256


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("a", type=Path)
    p.add_argument("b", type=Path)
    p.add_argument("--iou", type=float, default=.5)
    p.add_argument("--tolerance", type=int, default=2)
    args = p.parse_args()
    a, b = [read_image(x, cv2.IMREAD_UNCHANGED) for x in (args.a, args.b)]
    result = compare_instances(a, b, args.iou, args.tolerance)
    result["inputs"] = [{"path": str(x.resolve()), "sha256": sha256(x)} for x in (args.a, args.b)]
    result["warning"] = "Sem ground truth. Identidades arbitrarias; matching 1:1. Dependencia entre rotuladores deve ser auditada."
    out = new_run("comparison")
    json_write(out / "comparison.json", result)
    print(out)


if __name__ == "__main__":
    main()
