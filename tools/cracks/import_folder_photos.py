#!/usr/bin/env python3
"""Importa fotos de bancada rotuladas por PASTA (Trincadas / Sem trinca) para o formato --instances.

Uso:
  python tools/cracks/import_folder_photos.py --src Fotos --out ml_training/data_bench_20261026 [--long-side 1600]

O rótulo da pasta vale para a FOTO, não para cada pelota. Regras (fixadas antes de qualquer escore):
  * pelota cortada pela borda da foto              -> uncertain (touches_border);
  * foto com UMA pelota inteira                     -> rótulo da pasta (cracked / intact);
  * foto com várias pelotas inteiras                -> uncertain nas duas pastas: em "Trincadas" não se
    sabe qual está trincada, e a separação de pelotas encostadas por distância/watershed erra centro e
    raio (conferido nos overlays de 27/10/2026), o que poria erro de geometria no recorte.
Grupos: rajadas da mesma pelota (mesma pasta, até --burst-gap s entre fotos) formam um group_id; a
validação deixa a rajada inteira de fora. Sessões (intervalo > --session-gap s) vão em 'session'.

Detecção (só para esta montagem: pelotas escuras isoladas sobre mesa clara):
  fundo = máximo local em escala reduzida (vinheta e reflexo); razão foto/fundo; Otsu; abertura e
  preenchimento de furos (brilhos); pelotas encostadas separadas por distância + watershed;
  raio = raio equivalente à área. Saída revisável: overlays/*.jpg com o número de cada pelota.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageOps

CLASSES = {'Trincadas': 'cracked', 'Sem trinca': 'intact'}
STAMP = re.compile(r'(\d{8})_(\d{6})')


def load_upright(path, long_side):
    im = ImageOps.exif_transpose(Image.open(path)).convert('RGB')
    k = min(1.0, long_side / max(im.size))
    if k < 1:
        im = im.resize((round(im.width * k), round(im.height * k)), Image.LANCZOS)
    return im


def detect(gray):
    """Pelotas escuras sobre fundo claro. Devolve lista de dicts (cx, cy, r, area, touches_border)."""
    h, w = gray.shape
    small_k = 400 / max(h, w)
    small = cv2.resize(gray, (round(w * small_k), round(h * small_k)), interpolation=cv2.INTER_AREA)
    ksz = int(round(min(small.shape) * .35)) | 1
    bg = cv2.dilate(small, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (ksz, ksz)))
    bg = cv2.GaussianBlur(bg, (0, 0), ksz / 4)
    bg = cv2.resize(bg, (w, h), interpolation=cv2.INTER_LINEAR).astype(np.float32)
    ratio = np.clip(gray.astype(np.float32) / np.maximum(bg, 1), 0, 1.2)
    norm = np.clip(ratio * 200, 0, 255).astype(np.uint8)
    _, mask = cv2.threshold(norm, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    k = max(3, int(round(min(h, w) * .006)) | 1)
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k)))
    # Preenche furos (brilhos) pelo contorno externo de cada mancha; não depende de um canto livre.
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    mask = np.zeros_like(mask)
    cv2.drawContours(mask, contours, -1, 255, cv2.FILLED)
    n, lab, stats, _ = cv2.connectedComponentsWithStats(mask, 8)
    areas = stats[1:, cv2.CC_STAT_AREA]
    if len(areas) == 0:
        return []
    big = np.max(areas)
    out = []
    for i in range(1, n):
        if stats[i, cv2.CC_STAT_AREA] < .03 * big:
            continue
        comp = (lab == i).astype(np.uint8)
        for p in split(comp):
            a = int(p.sum())
            if a < .03 * big:
                continue
            ys, xs = np.nonzero(p)
            cx, cy = float(xs.mean()), float(ys.mean())
            r = float(np.sqrt(a / np.pi))
            x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
            border = x0 <= 1 or y0 <= 1 or x1 >= w - 2 or y1 >= h - 2
            out.append({'cx': cx, 'cy': cy, 'r': r, 'area': a, 'touches_border': bool(border)})
    out.sort(key=lambda d: (round(d['cy'] / max(1, d['r'])), d['cx']))
    return out


def rel(path):
    """Caminho relativo à pasta atual (raiz do repositório no CI), para o manifesto ser portátil."""
    try:
        return Path(path).resolve().relative_to(Path.cwd().resolve()).as_posix()
    except ValueError:
        return str(path)


def split(comp):
    """Separa pelotas encostadas: picos locais da distância à borda (supressão por distância) + watershed."""
    dist = cv2.distanceTransform(comp, cv2.DIST_L2, 5)
    dmax = float(dist.max())
    if dmax < 3:
        return [comp]
    ksz = max(3, int(round(dmax * .5)) | 1)
    local = (dist >= cv2.dilate(dist, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (ksz, ksz)))) & (dist >= .45 * dmax)
    ys, xs = np.nonzero(local)
    cand = sorted(zip(dist[ys, xs], xs, ys), reverse=True)
    peaks = []
    for d, x, y in cand:
        # Dois picos são a mesma pelota se o mais fraco cai dentro do disco do mais forte (com folga).
        if all(np.hypot(x - px, y - py) > .85 * max(d, pd) for pd, px, py in peaks):
            peaks.append((d, x, y))
    if len(peaks) <= 1:
        return [comp]
    markers = np.zeros(comp.shape, np.int32)
    for j, (d, x, y) in enumerate(peaks):
        cv2.circle(markers, (int(x), int(y)), max(2, int(d * .35)), j + 2, -1)
    markers[comp == 0] = 1
    relief = cv2.normalize(-dist, None, 0, 255, cv2.NORM_MINMAX).astype(np.uint8)
    markers = cv2.watershed(cv2.cvtColor(relief, cv2.COLOR_GRAY2BGR), markers)
    return [((markers == j + 2) & (comp == 1)).astype(np.uint8) for j in range(len(peaks))]


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--src', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--long-side', type=int, default=1600)
    p.add_argument('--burst-gap', type=int, default=12)
    p.add_argument('--session-gap', type=int, default=900)
    p.add_argument('--source-id', default='bench_photos_20261026')
    p.add_argument('--small-factor', type=float, default=0.3,
                   help='2ª escala (mesmo grupo): fotos reduzidas para r ~ 30-48 px, como a webcam 1920x1080 sobre a panela')
    p.add_argument('--exclusions', type=Path, help='JSON {"exclude": {foto: motivo}} conferido nos overlays')
    a = p.parse_args(argv)
    img_dir, ov_dir = a.out / 'images', a.out / 'overlays'
    img_dir.mkdir(parents=True, exist_ok=True)
    ov_dir.mkdir(parents=True, exist_ok=True)

    photos = []
    for folder, label in CLASSES.items():
        for f in sorted((a.src / folder).glob('*.jpg')):
            m = STAMP.search(f.name)
            t = int(m.group(2)[:2]) * 3600 + int(m.group(2)[2:4]) * 60 + int(m.group(2)[4:6]) if m else None
            photos.append({'path': f, 'folder': folder, 'label': label, 't': t})
    # Rajadas por pasta; sessões globais.
    photos.sort(key=lambda r: (r['t'] is None, r['t'] or 0, r['path'].name))
    session, last = 0, None
    for ph in photos:
        if last is not None and ph['t'] is not None and ph['t'] - last > a.session_gap:
            session += 1
        ph['session'] = f's{session + 1}'
        last = ph['t'] if ph['t'] is not None else last
    for folder in CLASSES:
        seq = [ph for ph in photos if ph['folder'] == folder]
        burst, prev = 0, None
        for ph in seq:
            if prev is None or ph['t'] is None or prev['t'] is None or ph['t'] - prev['t'] > a.burst_gap:
                burst += 1
            ph['burst'] = f'{"trinc" if folder == "Trincadas" else "sem"}{burst:02d}'
            prev = ph

    excluded = json.loads(a.exclusions.read_text(encoding='utf-8'))['exclude'] if a.exclusions else {}
    inst_lines, photo_rows = [], []
    for ph in photos:
        im = load_upright(ph['path'], a.long_side)
        name = ph['path'].stem + '.jpg'
        im.save(img_dir / name, quality=92)
        rgb = np.asarray(im)
        gray = cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY)
        dets = detect(gray)
        whole = [d for d in dets if not d['touches_border']]
        if ph['path'].name in excluded:
            rule, lab = 'excluded_after_overlay_review', 'uncertain'
        elif len(whole) == 1:
            rule, lab = 'folder_single_pellet', ph['label']
        elif ph['label'] == 'cracked':
            rule, lab = 'folder_trincadas_multi_pellet_unknown_which', 'uncertain'
        else:
            rule, lab = 'folder_sem_trinca_multi_pellet_split_unreliable', 'uncertain'
        ov = cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)
        for i, d in enumerate(dets):
            d_label = 'uncertain' if d['touches_border'] else lab
            inst_lines.append(json.dumps({
                'image': name, 'instance_id': f'{ph["path"].stem}_{i:02d}', 'cx': round(d['cx'], 3), 'cy': round(d['cy'], 3),
                'r': round(d['r'], 3), 'touches_border': d['touches_border'], 'crack_label': d_label,
                'label_origin': 'user_folder:' + rule, 'group_id': f'{a.source_id}:{ph["burst"]}', 'session': ph['session'],
                'photo_label': ph['label']}, ensure_ascii=False))
            col = (60, 200, 60) if d_label == 'intact' else (40, 40, 230) if d_label == 'cracked' else (0, 200, 230)
            cv2.circle(ov, (round(d['cx']), round(d['cy'])), round(d['r']), col, max(2, round(d['r'] * .04)))
            cv2.putText(ov, str(i), (round(d['cx'] - d['r'] * .2), round(d['cy'] + d['r'] * .2)), cv2.FONT_HERSHEY_SIMPLEX,
                        max(.6, d['r'] / 60), (255, 255, 255), max(2, round(d['r'] / 40)))
        cv2.putText(ov, f'{ph["folder"]} | {ph["burst"]} | {ph["session"]} | {rule}', (12, 40), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0, 0, 0), 3)
        cv2.imwrite(str(ov_dir / name), cv2.resize(ov, (ov.shape[1] // 2, ov.shape[0] // 2), interpolation=cv2.INTER_AREA),
                    [cv2.IMWRITE_JPEG_QUALITY, 80])
        photo_rows.append({'image': name, 'original_file': ph['path'].name, 'original_sha256': hashlib.sha256(ph['path'].read_bytes()).hexdigest(),
                           'folder': ph['folder'], 'photo_label': ph['label'], 'burst': ph['burst'], 'session': ph['session'],
                           'pellets_detected': len(dets), 'pellets_whole': len(whole), 'rule': rule,
                           'exclusion_reason': excluded.get(ph['path'].name, '')})

    (a.out / 'instances.jsonl').write_text('\n'.join(inst_lines) + '\n', encoding='utf-8')
    # 2ª escala: mesmas pelotas e mesmos grupos, fotos reduzidas (a nitidez da trinca muda com a escala).
    small_dir = a.out / 'images_small'
    small_dir.mkdir(exist_ok=True)
    small_lines = []
    for ph in photos:
        name = ph['path'].stem + '.jpg'
        im = Image.open(img_dir / name)
        im.resize((round(im.width * a.small_factor), round(im.height * a.small_factor)), Image.LANCZOS).save(small_dir / name, quality=92)
    for line in inst_lines:
        it = json.loads(line)
        k = a.small_factor
        it.update(cx=round(it['cx'] * k, 3), cy=round(it['cy'] * k, 3), r=round(it['r'] * k, 3), instance_id=it['instance_id'] + '_x03')
        small_lines.append(json.dumps(it, ensure_ascii=False))
    (a.out / 'instances_small.jsonl').write_text('\n'.join(small_lines) + '\n', encoding='utf-8')
    meta = {
        'source_id': a.source_id, 'images_dir': rel(img_dir), 'license_status': 'verified',
        'license_note': 'fotos do próprio usuário (Vale, Pelotização), enviadas em 27/10/2026 para treino interno',
        'material_type': 'unknown', 'scene': 'pelota_isolada_bancada_fundo_branco', 'origin': 'real', 'split_role': 'dev', 'group_by': 'instance_group',
        'label_note': 'rótulo da pasta vale para a foto; ver regras em tools/cracks/import_folder_photos.py',
        'long_side_px': a.long_side}
    (a.out / 'meta.json').write_text(json.dumps(meta, indent=2, ensure_ascii=False), encoding='utf-8')
    small_meta = dict(meta, source_id=a.source_id + '_x03', images_dir=rel(small_dir), long_side_px=round(a.long_side * a.small_factor),
                      scale_note=f'mesmas fotos reduzidas por {a.small_factor}; group_id igual ao da escala original')
    (a.out / 'meta_small.json').write_text(json.dumps(small_meta, indent=2, ensure_ascii=False), encoding='utf-8')
    import csv
    with (a.out / 'photos.csv').open('w', newline='', encoding='utf-8') as f:
        wr = csv.DictWriter(f, fieldnames=list(photo_rows[0]))
        wr.writeheader()
        wr.writerows(photo_rows)
    summary = {}
    for line in inst_lines:
        r = json.loads(line)
        summary[r['crack_label']] = summary.get(r['crack_label'], 0) + 1
    print(json.dumps({'photos': len(photo_rows), 'instances': len(inst_lines), 'by_label': summary,
                      'groups': len({json.loads(l)['group_id'] for l in inst_lines})}, ensure_ascii=False))


if __name__ == '__main__':
    main()
