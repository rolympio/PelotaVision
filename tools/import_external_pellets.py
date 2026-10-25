#!/usr/bin/env python3
"""Import authorized external pellet images into a traceable, non-training dataset.

Usage: python tools/import_external_pellets.py --source roboflow_iron_pellets --input /path/to/export --output data/external
This script does NOT download or infer labels. Images without verified annotations are marked unlabelled.
"""
import argparse
import csv
import hashlib
import json
from pathlib import Path

EXTENSIONS = {".jpg", ".jpeg", ".png", ".bmp", ".tif", ".tiff", ".webp"}
SOURCES = {
    "roboflow_iron_pellets": {
        "url": "https://universe.roboflow.com/hamitouche/iron-pellets",
        "license": "Public Domain (as declared by dataset page; confirm exported version)",
        "task": "instance_segmentation",
    },
    "pellet_size_distribution": {
        "url": "https://github.com/aryadeo/Pellet_Size_Distribution",
        "license": "UNVERIFIED",
        "task": "pellet_size",
    },
    "magnetic_tile_defects": {
        "url": "https://github.com/Akki-Maharaj/Manufacturing-Defect-Detection",
        "license": "CHECK UPSTREAM DATASET SEPARATELY",
        "task": "domain_transfer_only",
    },
}
def main():
    p = argparse.ArgumentParser()
    p.add_argument("--source", choices=SOURCES, required=True)
    p.add_argument("--input", type=Path, required=True)
    p.add_argument("--output", type=Path, default=Path("data/external"))
    p.add_argument("--license-verified", action="store_true",
                   help="Operator confirms image reuse rights after examining original dataset license")
    args = p.parse_args()
    if not args.license_verified:
        p.error("License review required: pass --license-verified only after confirming rights.")
    if not args.input.is_dir():
        p.error("Input directory does not exist.")
    from PIL import Image
    destination = args.output / args.source
    destination.mkdir(parents=True, exist_ok=True)
    rows = []
    seen = set()
    for path in sorted(args.input.rglob("*")):
        if not path.is_file() or path.suffix.lower() not in EXTENSIONS:
            continue
        raw = path.read_bytes()
        sha = hashlib.sha256(raw).hexdigest()
        if sha in seen:
            continue
        try:
            with Image.open(path) as im:
                im.verify()
            with Image.open(path) as im:
                width, height = im.size
                im.convert("RGB").save(destination / (sha + ".png"), "PNG")
        except (OSError, ValueError):
            continue
        seen.add(sha)
        rows.append({
            "image_id": sha, "source_id": args.source,
            "original_path": str(path.relative_to(args.input)),
            "original_url": SOURCES[args.source]["url"],
            "license": SOURCES[args.source]["license"],
            "task": SOURCES[args.source]["task"],
            "width": width, "height": height,
            "material_type": "unknown", "crack_label": "unlabelled",
            "annotation_status": "requires_review",
            "synthetic": False,
            "split_group": str(path.parent.relative_to(args.input)),
        })
    with (destination / "manifest.jsonl").open("w", encoding="utf-8") as out:
        for row in rows:
            out.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(json.dumps({"source": args.source, "imported": len(rows), "output": str(destination)}))
if __name__ == "__main__":
    main()
