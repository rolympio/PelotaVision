"""Folhas de contato do gerador para inspeção visual.

Uso: python contact_sheet.py --out PASTA [--seed 20261024]

Gera (em escala comum de 160 px por célula, para comparar formas):
  positivas.jpg   pares sintético | máscara (vermelho) por tipo de trinca e raio-alvo
  negativos.jpg   negativos difíceis (um tipo de distrator por linha)
  real_vs_sint.jpg  trincas visíveis em fotos reais (crack_example.png) ao lado de sintéticas no mesmo raio
Também grava cada célula sem ampliação ao lado (pixels reais do raio-alvo) para ver
como a trinca fica em 20-30 px.
"""
import argparse
import json
from pathlib import Path

import cv2
import numpy as np

from synth import DISTRACTORS, KINDS, render_sample

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
CELL = 160


def load_donors():
    d = json.loads((HERE / 'donors.json').read_text())
    photos, donors = {}, []
    for p in d['photos']:
        photos[p['image']] = cv2.imread(str(ROOT / p['image']))
        donors += [(p['image'], tuple(c)) for c in p['donors']]
    return photos, donors


def cell(img, label, circle=None, native=True):
    h = img.shape[0]
    big = cv2.resize(img, (CELL, CELL), interpolation=cv2.INTER_NEAREST if h < CELL / 2 else cv2.INTER_CUBIC)
    if native and h <= CELL // 2:
        # mostra também o tamanho real no canto
        big[CELL - h - 2:CELL - 2, CELL - h - 2:CELL - 2] = img[:min(h, CELL), :min(h, CELL)]
    cv2.putText(big, label, (3, 12), 0, .38, (0, 255, 255), 1, cv2.LINE_AA)
    return big


def overlay(img, mask):
    o = img.astype(np.float32)
    m = mask[..., None]
    o = o * (1 - 0.7 * m) + np.array([0, 0, 255], np.float32) * 0.7 * m
    return np.clip(o, 0, 255).astype(np.uint8)


def grid(cells, cols):
    rows = (len(cells) + cols - 1) // cols
    g = np.full((rows * CELL, cols * CELL, 3), 255, np.uint8)
    for i, c in enumerate(cells):
        g[(i // cols) * CELL:(i // cols + 1) * CELL, (i % cols) * CELL:(i % cols + 1) * CELL] = c
    return g


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', required=True)
    ap.add_argument('--seed', type=int, default=20261024)
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    photos, donors = load_donors()
    rng = np.random.default_rng(a.seed)
    cells = []
    radii = [22, 32, 48, 72, 110]
    for kind in KINDS:
        for R in radii:
            src, d = donors[rng.integers(len(donors))]
            s = render_sample(photos[src], d, rng, True, target_radius=R, kind=kind)
            cells.append(cell(s['image'], f'{kind} r{R}'))
            cells.append(cell(overlay(s['image'], s['mask']), 'mascara'))
    cv2.imwrite(str(out / 'positivas.jpg'), grid(cells, 10), [cv2.IMWRITE_JPEG_QUALITY, 90])
    cells = []
    for dk in DISTRACTORS:
        for R in [24, 40, 60, 90, 110]:
            src, d = donors[rng.integers(len(donors))]
            s = render_sample(photos[src], d, rng, False, target_radius=R, distractors=[dk])
            cells.append(cell(s['image'], f'{dk} r{R}'))
    for R in [24, 40, 60, 90, 110]:
        src, d = donors[rng.integers(len(donors))]
        s = render_sample(photos[src], d, rng, False, target_radius=R, distractors=[])
        cells.append(cell(s['image'], f'limpa r{R}'))
    cv2.imwrite(str(out / 'negativos.jpg'), grid(cells, 5), [cv2.IMWRITE_JPEG_QUALITY, 90])
    # real x sintético
    ce = cv2.imread(str(ROOT / 'verification/fixtures/crack_example.png'))
    an = json.loads((ROOT / 'verification/fixtures/crack_example_analysis.json').read_text())
    lab = json.loads((ROOT / 'ml_training/crack_validation/crack_example_labels_provisorios.json').read_text())
    by = {c['id']: c for c in an['circles']}
    cells = []
    for pid in lab['positive_ids']:
        c = by[pid]
        r = c['radius']
        s = 1.0 * r
        M = np.array([[2 * s / (2 * r), 0, c['x'] - s], [0, 2 * s / (2 * r), c['y'] - s]])
        W = int(round(2 * r))
        real = cv2.warpAffine(ce, M, (W, W), flags=cv2.INTER_LINEAR | cv2.WARP_INVERSE_MAP, borderMode=cv2.BORDER_REFLECT_101)
        cells.append(cell(real, f'REAL #{pid} r{int(r)}'))
        for _ in range(2):
            src, d = donors[rng.integers(len(donors))]
            sy = render_sample(photos[src], d, rng, True, target_radius=r)
            cells.append(cell(sy['image'], f"sint {sy['info']['kind']} r{int(r)}"))
    cv2.imwrite(str(out / 'real_vs_sint.jpg'), grid(cells, 6), [cv2.IMWRITE_JPEG_QUALITY, 90])
    print('folhas em', out)


if __name__ == '__main__':
    main()
