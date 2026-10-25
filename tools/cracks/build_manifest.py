#!/usr/bin/env python3
"""Recorta uma pelota por imagem e gera o manifesto supervisionado (labels.csv).

Entradas (todas opcionais, combináveis):
  --scenes ml_training/data/annotations.json   cenas internas do RF 0.5.0 (rótulos provisórios)
  --scene-meta tools/cracks/labels/scene_metadata.json   origem/material/licença por cena
  --holdout SPEC.json   foto(s) de teste final com rótulos registrados antes de qualquer escore
  --instances INST.jsonl --instances-meta META.json   pelotas convertidas de fontes externas
  --synthetic-manifest CSV   recortes sintéticos de render_synthetic.py (sempre train_only)

Regras aplicadas:
  * pelota parcial (borda) -> 'uncertain';
  * pelota sem anotação de trinca -> 'uncertain' (nunca 'intact' implícito); só a especificação
    de holdout com "negative_rule": "exhaustive_review" transforma as não listadas em 'intact';
  * recorte com margem e BORDER_REFLECT_101 da foto inteira, para paridade exata com o app.
"""
import argparse
import csv
import hashlib
import json
import math
import os
import sys
from pathlib import Path

import cv2

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from tools.cracks import manifest as mf  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def crop_pellet(gray, x, y, r, margin=1.25):
    """Recorte quadrado centrado na pelota, preenchido por reflexão da foto inteira.

    Devolve (recorte, cx, cy, x0, y0): (cx, cy) é o centro no recorte e (x0, y0) o canto na foto."""
    m = int(math.ceil(margin * r)) + 3
    x0, y0 = int(math.floor(x)) - m, int(math.floor(y)) - m
    size = 2 * m + 1
    h, w = gray.shape[:2]
    pad_l, pad_t = max(0, -x0), max(0, -y0)
    pad_r, pad_b = max(0, x0 + size - w), max(0, y0 + size - h)
    src = gray
    if pad_l or pad_t or pad_r or pad_b:
        src = cv2.copyMakeBorder(gray, pad_t, pad_b, pad_l, pad_r, cv2.BORDER_REFLECT_101)
    c = src[y0 + pad_t:y0 + pad_t + size, x0 + pad_l:x0 + pad_l + size].copy()
    return c, x - x0, y - y0, x0, y0


def _row(out_dir, crop_rel, gray_crop, cx, cy, r, **kw):
    path = out_dir / crop_rel
    path.parent.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(path), gray_crop)
    row = {'image_path': crop_rel.as_posix(), 'cx': f'{cx:.6f}', 'cy': f'{cy:.6f}', 'r': f'{r:.6f}'}
    row.update(kw)
    return row


def from_scenes(ann_path, meta, out_dir):
    ann = json.loads(Path(ann_path).read_text(encoding='utf-8'))
    rows = []
    for scene in ann:
        g = scene['group']
        info = meta[g]
        img_path = Path(ann_path).parent / f'{g}.png'
        gray = cv2.imread(str(img_path), cv2.IMREAD_GRAYSCALE)
        src_sha = sha256(img_path)
        for a in scene['annotations']:
            label = 'uncertain' if a['partial'] else ('cracked' if a['label'] == 1 else 'intact')
            c, cx, cy, x0, _ = crop_pellet(gray, a['x'], a['y'], a['radius'])
            rows.append(_row(out_dir, Path('crops') / 'scenes' / g / f'{g}_{a["id"]:03d}.png', c, cx, cy, a['radius'],
                             label=label, group_id=f'scene:{g}', material_type=info['material_type'], origin=info['origin'],
                             source_id='pelotavision_0.5.0_scenes', source_image=f'ml_training/data/{g}.png',
                             source_sha256=src_sha, instance_id=str(a['id']),
                             label_origin=a.get('label_origin', 'unknown') + ('+partial' if a['partial'] else ''),
                             split_role=info['split_role'], source_width=str(gray.shape[1]), x_offset=str(x0),
                             license_status=info['license_status']))
    return rows


def from_holdout(spec_path, out_dir):
    spec = json.loads(Path(spec_path).read_text(encoding='utf-8'))
    img_path = ROOT / spec['image']
    gray = cv2.imread(str(img_path), cv2.IMREAD_GRAYSCALE)
    circles = json.loads((ROOT / spec['circles']).read_text(encoding='utf-8'))['circles']
    pos, unc = set(spec['positive_ids']), set(spec['uncertain_ids'])
    if spec['negative_rule'] != 'exhaustive_review':
        raise SystemExit('holdout sem revisão exaustiva: pelotas não listadas não podem virar intact')
    rows = []
    stem = Path(spec['image']).stem
    for c in circles:
        if c.get('partial'):
            label = 'uncertain'
        elif c['id'] in pos:
            label = 'cracked'
        elif c['id'] in unc:
            label = 'uncertain'
        else:
            label = 'intact'
        crop, cx, cy, x0, _ = crop_pellet(gray, c['x'], c['y'], c['radius'])
        rows.append(_row(out_dir, Path('crops') / 'holdout' / stem / f'{stem}_{c["id"]:03d}.png', crop, cx, cy, c['radius'],
                         label=label, group_id=f'photo:{stem}', material_type=spec['material_type'], origin=spec['origin'],
                         source_id=spec['source_id'], source_image=spec['image'], source_sha256=sha256(img_path),
                         instance_id=str(c['id']), label_origin=spec['label_origin'] + ('+partial' if c.get('partial') else ''),
                         split_role='holdout', source_width=str(gray.shape[1]), x_offset=str(x0),
                         license_status=spec['license_status']))
    return rows


def from_instances(inst_path, meta_path, out_dir):
    meta = json.loads(Path(meta_path).read_text(encoding='utf-8'))
    if meta['license_status'] != 'verified':
        raise SystemExit(f'{meta["source_id"]}: licença não verificada; importação recusada')
    images = Path(meta['images_dir'])
    rows, cache = [], {}
    for line in Path(inst_path).read_text(encoding='utf-8').splitlines():
        it = json.loads(line)
        if it['image'] not in cache:
            p = images / it['image']
            cache[it['image']] = (cv2.imread(str(p), cv2.IMREAD_GRAYSCALE), sha256(p))
        gray, src_sha = cache[it['image']]
        label = 'uncertain' if it.get('touches_border') else it['crack_label']
        crop, cx, cy, x0, _ = crop_pellet(gray, it['cx'], it['cy'], it['r'])
        stem = Path(it['image']).stem
        group = meta.get('group_by', 'image')
        group_id = f'{meta["source_id"]}:{meta.get("campaign", stem) if group == "campaign" else stem}'
        rows.append(_row(out_dir, Path('crops') / meta['source_id'] / stem / f'{stem}_{it["instance_id"]}.png', crop, cx, cy, it['r'],
                         label=label, group_id=group_id, material_type=meta['material_type'], origin=meta['origin'],
                         source_id=meta['source_id'], source_image=it['image'], source_sha256=src_sha,
                         instance_id=str(it['instance_id']), label_origin=it.get('label_origin', 'none'),
                         split_role=meta.get('split_role', 'dev'), source_width=str(gray.shape[1]), x_offset=str(x0),
                         license_status=meta['license_status']))
    return rows


def from_synthetic(csv_path, out_dir):
    csv_path = Path(csv_path)
    rows = []
    with csv_path.open(newline='', encoding='utf-8') as f:
        for r in csv.DictReader(f):
            if r['origin'] != 'synthetic' or r['split_role'] != 'train_only':
                raise SystemExit('manifesto sintético deve ter origin=synthetic e split_role=train_only')
            src = (csv_path.parent / r['image_path']).resolve()
            r['image_path'] = Path(os.path.relpath(src, Path(out_dir).resolve())).as_posix()
            rows.append(r)
    return rows


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--scenes', type=Path)
    p.add_argument('--scene-meta', type=Path, default=ROOT / 'tools/cracks/labels/scene_metadata.json')
    p.add_argument('--holdout', type=Path, action='append', default=[])
    p.add_argument('--instances', type=Path, action='append', default=[])
    p.add_argument('--instances-meta', type=Path, action='append', default=[])
    p.add_argument('--synthetic-manifest', type=Path, action='append', default=[])
    p.add_argument('--out', type=Path, required=True)
    a = p.parse_args(argv)
    rows = []
    if a.scenes:
        meta = json.loads(a.scene_meta.read_text(encoding='utf-8'))['scenes']
        rows += from_scenes(a.scenes, meta, a.out)
    for h in a.holdout:
        rows += from_holdout(h, a.out)
    if len(a.instances) != len(a.instances_meta):
        p.error('cada --instances precisa de um --instances-meta')
    for i, m in zip(a.instances, a.instances_meta):
        rows += from_instances(i, m, a.out)
    for s in a.synthetic_manifest:
        rows += from_synthetic(s, a.out)
    mf.validate(rows)
    mf.write(rows, a.out / 'labels.csv')
    summary = {}
    for r in rows:
        k = (r['origin'], r['split_role'], r['material_type'], r['label'])
        summary['/'.join(k)] = summary.get('/'.join(k), 0) + 1
    (a.out / 'summary.json').write_text(json.dumps({'rows': len(rows), 'by_origin_role_material_label': dict(sorted(summary.items()))},
                                                   indent=2, ensure_ascii=False), encoding='utf-8')
    print(json.dumps({'rows': len(rows), 'manifest': str(a.out / 'labels.csv')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
