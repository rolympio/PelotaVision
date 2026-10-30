#!/usr/bin/env python3
"""Pseudo-rótulos de bancada REAL para o StarDist a partir das análises exportadas pelo PelotaVision.

Fontes (fotos do próprio usuário, Vale/Pelotização):
  * 7 análises de webcam 1920×1080 sobre panela (27/10/2026), em ml_training/data_webcam_20261027/images,
    com as detecções do app (LoG + elipse) lidas do JSON exportado (passe --exports);
  * fotos de bancada de 26/10/2026 com UMA pelota inteira (ml_training/data_bench_20261026), fundo claro.

Não há verdade-terreno humana de contorno. As regras abaixo escolhem o que a rede aprende e o que ela ignora
(peso 0), no mesmo formato do conjunto de correia (annotations.json + máscara de peso):

  Panela
  - positivo confiável: elipse ajustada, sem alerta de revisão, não parcial -> círculo de raio equivalente;
  - positivo duvidoso (sem elipse, com alerta, parcial): entra como objeto (não vira fundo), mas o disco
    dilatado 1,15× recebe peso 0;
  - fora da ROI usada no app: anel entre 1,0× e 1,22× do raio da ROI com peso 0 (pode haver pelotas que o app
    não analisou); além disso, peso 1 (mesa, cabos, borda da panela: fundo real);
  - dentro da ROI e fora de qualquer pelota detectada: peso 1 só na FRESTA (pixel escuro, abaixo do percentil 35
    das pelotas confiáveis, a até 0,35 r de uma pelota detectada); o resto não coberto pode ser pelota perdida -> 0;
  - faixa de 2,2 r junto à borda da ROI: peso 0 (o app exclui pelotas que cruzam a ROI, então não estão rotuladas).
  Bancada (uma pelota): imagem inteira peso 1; o círculo da importação é o positivo.

Divisão: análises 13:27 e 16:09 ficam para VALIDAÇÃO (análise inteira fora); as demais e a bancada treinam.

Uso: python ml_training/data_pan_20261027/make_pseudo.py --exports <pasta com as análises do zip>
"""
import argparse
import json
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent / 'pseudo'
VALIDATION = ('2026-10-27_13-27-28_Teste2', '2026-10-27_16-09-29_Teste2')


def roi_circle(d):
    r = d['region_of_interest']
    if not r or r.get('shape') != 'CIRCLE':
        return None
    l, t, rr, b = r['left_px'], r['top_px'], r['right_px'], r['bottom_px']
    return (l + rr) / 2, (t + b) / 2, min(rr - l, b - t) / 2


def pan_scene(js, img_dir):
    d = json.loads(js.read_text(encoding='utf-8'))
    base = js.stem
    gray = cv2.imread(str(img_dir / f'{base}_original.png'), cv2.IMREAD_GRAYSCALE)
    h, w = gray.shape
    roi = roi_circle(d)
    anns, sure, unsure = [], [], []
    for det in d['detections']:
        x, y, r = float(det['x_px']), float(det['y_px']), float(det['radius_px'])
        ok = bool(det.get('ellipse_fitted')) and not det.get('needs_review') and not det.get('partial_edge')
        anns.append({'x': round(x, 2), 'y': round(y, 2), 'radius': round(r, 2), 'partial': bool(det.get('partial_edge')),
                     'confident': ok})
        (sure if ok else unsure).append((x, y, r))
    yy, xx = np.mgrid[:h, :w].astype(np.float32)
    weight = np.ones((h, w), np.uint8)
    covered = np.zeros((h, w), bool)
    sure_px = np.zeros((h, w), bool)
    for x, y, r in sure + unsure:
        covered |= (xx - x) ** 2 + (yy - y) ** 2 <= (1.05 * r) ** 2
    for x, y, r in sure:
        sure_px |= (xx - x) ** 2 + (yy - y) ** 2 <= (0.8 * r) ** 2
    if roi is not None:
        cx, cy, R = roi
        dist = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
        inside = dist <= R
        weight[(dist > R) & (dist <= 1.22 * R)] = 0
        dark = np.percentile(gray[sure_px], 35) if sure_px.any() else 0
        free = inside & ~covered
        rmed = float(np.median([r for _, _, r in sure + unsure])) if sure + unsure else 20.
        # fresta = escura E colada numa pelota detectada (até 0,35 r da borda dilatada); o resto não coberto é
        # duvidoso (pelota perdida ou cortada pela ROI) -> peso 0.
        near = cv2.dilate(covered.astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * int(.35 * rmed) + 1,) * 2)) > 0
        gap = free & near & (gray < dark)
        weight[free & ~gap] = 0
        # faixa junto à borda da ROI: o app exclui pelotas que cruzam a ROI, então elas não estão nos rótulos.
        weight[inside & (dist > R - 2.2 * rmed) & ~sure_px] = 0
    for x, y, r in unsure:
        weight[(xx - x) ** 2 + (yy - y) ** 2 <= (1.15 * r) ** 2] = 0
    med = float(np.median([r for _, _, r in sure])) if sure else float(np.median([a['radius'] for a in anns]))
    return gray, weight, anns, med, roi


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--exports', type=Path, required=True, help='pasta com as subpastas de análise (JSON do app)')
    a = ap.parse_args(argv)
    (OUT / 'weights').mkdir(parents=True, exist_ok=True)
    (OUT / 'overlays').mkdir(parents=True, exist_ok=True)
    img_dir = ROOT / 'ml_training/data_webcam_20261027/images'
    out = []
    for js in sorted(a.exports.glob('*/PelotaVision_*.json')):
        if not js.stem[-3:].isdigit():
            continue
        folder = js.parent.name
        gray, weight, anns, med, roi = pan_scene(js, img_dir)
        name = js.stem
        cv2.imwrite(str(OUT / 'weights' / f'{name}.png'), weight * 255)
        ov = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)
        ov[weight == 0] = (ov[weight == 0] * .45 + np.array([0, 0, 110]) * .55).astype(np.uint8)
        for an in anns:
            cv2.circle(ov, (int(an['x']), int(an['y'])), int(an['radius']), (80, 230, 120) if an['confident'] else (0, 200, 255), 2)
        cv2.imwrite(str(OUT / 'overlays' / f'{name}.jpg'), cv2.resize(ov, None, fx=.5, fy=.5), [cv2.IMWRITE_JPEG_QUALITY, 80])
        out.append({'group': f'pan:{folder}', 'source': 'webcam_pan_20261027', 'camera': 'webcam_1920x1080',
                    'split': 'validacao' if folder in VALIDATION else 'treino', 'negative': False,
                    'image': f'../../data_webcam_20261027/images/{name}_original.png', 'weight_mask': f'weights/{name}.png', 'radius_px': round(med, 2),
                    'roi_circle': [round(v, 2) for v in roi] if roi else None,
                    'confident': sum(an['confident'] for an in anns), 'uncertain': sum(not an['confident'] for an in anns),
                    'weight_zero_fraction': round(float((weight == 0).mean()), 4), 'annotations': anns})
    # Bancada: fotos com uma única pelota detectada e inteira (o círculo da importação de 26/10).
    bench = ROOT / 'ml_training/data_bench_20261026'
    photos = {}
    for line in (bench / 'instances.jsonl').read_text(encoding='utf-8').splitlines():
        it = json.loads(line)
        photos.setdefault(it['image'], []).append(it)
    import csv
    with (bench / 'photos.csv').open(encoding='utf-8-sig') as f:
        meta = {r['image']: r for r in csv.DictReader(f)}
    for image, its in sorted(photos.items()):
        m = meta.get(image, {})
        if len(its) != 1 or str(m.get('pellets_detected')) != '1' or its[0].get('touches_border'):
            continue
        gray = cv2.imread(str(bench / 'images' / image), cv2.IMREAD_GRAYSCALE)
        if gray is None:
            continue
        stem = Path(image).stem
        cv2.imwrite(str(OUT / 'weights' / f'bench_{stem}.png'), np.full(gray.shape, 255, np.uint8))
        it = its[0]
        out.append({'group': f'bench:{stem}', 'source': 'bench_photos_20261026', 'camera': 'celular_bancada',
                    'split': 'treino', 'negative': False, 'image': f'../../data_bench_20261026/images/{image}',
                    'weight_mask': f'weights/bench_{stem}.png', 'radius_px': round(float(it['r']), 2), 'roi_circle': None,
                    'confident': 1, 'uncertain': 0, 'weight_zero_fraction': 0.0,
                    'annotations': [{'x': it['cx'], 'y': it['cy'], 'radius': it['r'], 'partial': False, 'confident': True}]})
    (OUT / 'annotations.json').write_text(json.dumps(out, indent=1, ensure_ascii=False), encoding='utf-8')
    pans = [s for s in out if s['source'].startswith('webcam')]
    print(f'{len(pans)} análises de panela ({sum(s["split"] == "validacao" for s in pans)} validação), '
          f'{len(out) - len(pans)} fotos de bancada com 1 pelota; '
          f'positivos confiáveis {sum(s["confident"] for s in pans)}, duvidosos {sum(s["uncertain"] for s in pans)}')


if __name__ == '__main__':
    main()
