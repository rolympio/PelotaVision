#!/usr/bin/env python3
"""Converte anotações externas para instâncias de pelota (JSONL), preservando IDs.

Formatos suportados:
  coco    COCO JSON (exportação "COCO" / "COCO Segmentation" do Roboflow): bbox e/ou polígonos.
  mask    máscaras binárias por pixel (ex.: PixelLabelData do Pellet_Size_Distribution: pelota=1 ou 255).

Saída: uma linha JSON por pelota com image, image_id, instance_id, bbox, cx, cy, r (raio equivalente
à área), area, touches_border, crack_label e crack_evidence.

Rótulo de trinca:
  * COCO com categoria de trinca (--crack-categories): pelota recebe 'cracked' se o centróide de uma
    anotação de trinca estiver dentro dela.
  * Sem evidência de trinca a pelota recebe 'uncertain'. Só vira 'intact' com --exhaustive-crack-review,
    que o revisor só deve passar quando TODAS as trincas visíveis tiverem sido anotadas.
  * Máscaras binárias não têm trinca: todas as pelotas ficam 'uncertain'.

Uso:
  python tools/cracks/convert_annotations.py coco --annotations _annotations.coco.json --images DIR --out inst.jsonl \
      --pellet-categories pellet --crack-categories crack
  python tools/cracks/convert_annotations.py mask --masks DIR/PixelLabelData --images DIR/Original_images --out inst.jsonl
"""
import argparse
import json
import math
from pathlib import Path

import cv2
import numpy as np


def _poly_area_centroid(xy):
    x, y = xy[:, 0], xy[:, 1]
    a = 0.5 * np.sum(x * np.roll(y, -1) - np.roll(x, -1) * y)
    if abs(a) < 1e-9:
        return 0.0, float(x.mean()), float(y.mean())
    cx = np.sum((x + np.roll(x, -1)) * (x * np.roll(y, -1) - np.roll(x, -1) * y)) / (6 * a)
    cy = np.sum((y + np.roll(y, -1)) * (x * np.roll(y, -1) - np.roll(x, -1) * y)) / (6 * a)
    return abs(float(a)), float(cx), float(cy)


def _inside(poly_list, bbox, px, py):
    for p in poly_list:
        if cv2.pointPolygonTest(p.astype(np.float32).reshape(-1, 1, 2), (float(px), float(py)), False) >= 0:
            return True
    if not poly_list and bbox is not None:
        x, y, w, h = bbox
        return x <= px <= x + w and y <= py <= y + h
    return False


def convert_coco(ann_path, pellet_cats, crack_cats, exhaustive, border_px=2):
    doc = json.loads(Path(ann_path).read_text(encoding='utf-8'))
    cats = {c['id']: c['name'].strip().lower() for c in doc.get('categories', [])}
    pellet_cats = {c.lower() for c in pellet_cats}
    crack_cats = {c.lower() for c in crack_cats}
    images = {im['id']: im for im in doc['images']}
    by_image = {}
    for a in doc['annotations']:
        by_image.setdefault(a['image_id'], []).append(a)
    out = []
    for iid, anns in sorted(by_image.items()):
        im = images[iid]
        cracks = []
        for a in anns:
            if cats.get(a['category_id']) in crack_cats:
                polys = [np.asarray(s, float).reshape(-1, 2) for s in (a.get('segmentation') or []) if isinstance(s, list) and len(s) >= 6]
                if polys:
                    _, cx, cy = _poly_area_centroid(polys[0])
                else:
                    x, y, w, h = a['bbox']
                    cx, cy = x + w / 2, y + h / 2
                cracks.append((a['id'], cx, cy))
        for a in anns:
            name = cats.get(a['category_id'])
            if pellet_cats and name not in pellet_cats:
                continue
            if name in crack_cats:
                continue
            polys = [np.asarray(s, float).reshape(-1, 2) for s in (a.get('segmentation') or []) if isinstance(s, list) and len(s) >= 6]
            x, y, w, h = [float(v) for v in a['bbox']]
            if polys:
                area, cx, cy = 0.0, 0.0, 0.0
                for p in polys:
                    pa, pcx, pcy = _poly_area_centroid(p)
                    cx, cy, area = cx + pcx * pa, cy + pcy * pa, area + pa
                cx, cy = (cx / area, cy / area) if area else (x + w / 2, y + h / 2)
            else:
                area, cx, cy = math.pi * w * h / 4, x + w / 2, y + h / 2
            evidence = [cid for cid, px, py in cracks if _inside(polys, (x, y, w, h), px, py)]
            label = 'cracked' if evidence else ('intact' if exhaustive and crack_cats else 'uncertain')
            out.append({
                'image': im['file_name'], 'image_id': iid, 'instance_id': a['id'], 'category': name,
                'bbox': [x, y, w, h], 'cx': cx, 'cy': cy, 'r': math.sqrt(area / math.pi), 'area': area,
                'touches_border': x <= border_px or y <= border_px or x + w >= im['width'] - border_px or y + h >= im['height'] - border_px,
                'crack_label': label, 'crack_evidence': evidence,
                'label_origin': 'source_annotation' if evidence or exhaustive else 'none',
            })
    return out


def convert_masks(mask_dir, image_dir, min_area=30, border_px=2):
    out = []
    image_dir = Path(image_dir)
    for mp in sorted(Path(mask_dir).iterdir()):
        if mp.suffix.lower() not in {'.png', '.jpg', '.jpeg', '.bmp', '.tif', '.tiff'}:
            continue
        m = cv2.imread(str(mp), cv2.IMREAD_GRAYSCALE)
        if m is None:
            continue
        cands = [p for p in image_dir.glob(mp.stem + '.*')]
        image_name = cands[0].name if cands else mp.name
        n, lab, stats, cent = cv2.connectedComponentsWithStats((m > 0).astype(np.uint8), 8)
        h, w = m.shape
        for c in range(1, n):
            x, y, bw, bh, area = [int(v) for v in stats[c]]
            if area < min_area:
                continue
            out.append({
                'image': image_name, 'image_id': mp.stem, 'instance_id': c, 'category': 'pellet',
                'bbox': [x, y, bw, bh], 'cx': float(cent[c][0]), 'cy': float(cent[c][1]), 'r': math.sqrt(area / math.pi),
                'area': float(area), 'solidity': float(area / max(1, bw * bh)),
                'touches_border': x <= border_px or y <= border_px or x + bw >= w - border_px or y + bh >= h - border_px,
                'crack_label': 'uncertain', 'crack_evidence': [], 'label_origin': 'none',
            })
    return out


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest='fmt', required=True)
    c = sub.add_parser('coco')
    c.add_argument('--annotations', required=True, type=Path)
    c.add_argument('--pellet-categories', nargs='*', default=[])
    c.add_argument('--crack-categories', nargs='*', default=[])
    c.add_argument('--exhaustive-crack-review', action='store_true')
    m = sub.add_parser('mask')
    m.add_argument('--masks', required=True, type=Path)
    m.add_argument('--images', required=True, type=Path)
    m.add_argument('--min-area', type=int, default=30)
    for sp in (c, m):
        sp.add_argument('--out', required=True, type=Path)
    a = p.parse_args(argv)
    if a.fmt == 'coco':
        rows = convert_coco(a.annotations, a.pellet_categories, a.crack_categories, a.exhaustive_crack_review)
    else:
        rows = convert_masks(a.masks, a.images, a.min_area)
    a.out.parent.mkdir(parents=True, exist_ok=True)
    with a.out.open('w', encoding='utf-8') as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + '\n')
    counts = {}
    for r in rows:
        counts[r['crack_label']] = counts.get(r['crack_label'], 0) + 1
    print(json.dumps({'instances': len(rows), 'labels': counts, 'out': str(a.out)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
