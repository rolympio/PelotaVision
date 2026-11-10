"""Avaliação do refino SAM 2 em fotos reais: StarDist (sementes) × StarDist + SAM 2.

    python tools/sam2/avaliar_sam2.py --modelo <pasta sam2> --saida <pasta> [--cenas ml_training/data] [--fotos f1.jpg ...]

Espelho em Python de core/Sam2Refiner.java (mesma escala, blocos, prompts, amostragem dos logits, componente
conexa, elipse de momentos e critérios). As sementes vêm do StarDist (ml_training/stardist/common.py) com o
ONNX embarcado no app. Com --cenas, compara os raios com os círculos PROVISÓRIOS de annotations.json
(não são ground truth: servem para ver tendência, não para validar).

Saídas: resumo.json, resumo.md e, por imagem, <nome>_comparacao.jpg (esquerda: StarDist; direita: SAM 2,
contorno da máscara em verde, mantidas do detector em laranja).
"""
from __future__ import annotations

import argparse
import json
import math
import sys
import time
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'ml_training' / 'stardist'))
import common  # noqa: E402

TARGET_RADIUS, MIN_SCALE, MAX_SCALE, MAX_SCALED_PIXELS = 40, .2, 2.5, 40_000_000
BOX, WINDOW = 1.15, 1.6
MIN_IOU, MIN_AREA_RATIO, MAX_AREA_RATIO, REVIEW_IOU = .5, .35, 2.8, .8


def starts(length, tile, overlap):
    if length <= tile:
        return [0]
    s, stride = [], tile - overlap
    x = 0
    while x < length - tile:
        s.append(x); x += stride
    if s[-1] != length - tile:
        s.append(length - tile)
    return s


def owner(st, tile, v):
    for i in range(len(st) - 1):
        if v < (st[i] + tile + st[i + 1]) / 2:
            return i
    return len(st) - 1


class Sam2:
    def __init__(self, folder: Path, threads=2):
        self.meta = json.loads((folder / 'sam2.json').read_text(encoding='utf-8'))
        so = ort.SessionOptions(); so.intra_op_num_threads = threads
        so.enable_cpu_mem_arena = False; so.enable_mem_pattern = False
        self.enc = ort.InferenceSession(str(folder / 'sam2_encoder.onnx'), so, providers=['CPUExecutionProvider'])
        self.dec = ort.InferenceSession(str(folder / 'sam2_decoder.onnx'), so, providers=['CPUExecutionProvider'])
        self.batch, self.size, self.ms = self.meta['batch'], self.meta['input_size'], self.meta['mask_size']
        mean, std = np.array(self.meta['mean'], np.float32), np.array(self.meta['std'], np.float32)
        v = np.arange(256, dtype=np.float32)[:, None] / 255.0
        self.lut = ((v - mean) / std).astype(np.float32)  # 256x3

    def refine(self, gray, dets):
        t0 = time.time()
        h, w = gray.shape
        radii = [d['radius'] for d in dets]
        s = max(MIN_SCALE, min(MAX_SCALE, TARGET_RADIUS / max(1e-6, common.median(radii))))
        if w * h * s * s > MAX_SCALED_PIXELS:
            s = math.sqrt(MAX_SCALED_PIXELS / (w * h))
        sw, sh = max(1, round(w * s)), max(1, round(h * s))
        sx, sy = sw / w, sh / h
        sm = math.sqrt(sx * sy)
        r95 = sorted(radii)[min(len(radii) - 1, int(math.floor(.95 * len(radii))))] * sm
        overlap = int(max(128, min(512, math.ceil(2 * WINDOW * r95 + 16))))
        scaled = cv2.resize(gray, (sw, sh), interpolation=cv2.INTER_AREA if s < 1 else cv2.INTER_LINEAR)
        pad = int(scaled.mean()) if scaled.size else 128
        xs, ys = starts(sw, self.size, overlap), starts(sh, self.size, overlap)
        owned = {}
        for i, d in enumerate(dets):
            cx, cy = (d['x'] + .5) * sx - .5, (d['y'] + .5) * sy - .5
            owned.setdefault((owner(ys, self.size, cy), owner(xs, self.size, cx)), []).append(i)
        out = [dict(d, sam2=False, iou=None, mask=None) for d in dets]
        tiles = refined = kept = 0
        for (ty, tx), ids in sorted(owned.items()):
            x0, y0 = xs[tx], ys[ty]
            tile = np.full((self.size, self.size), pad, np.uint8)
            crop = scaled[y0:y0 + self.size, x0:x0 + self.size]
            tile[:crop.shape[0], :crop.shape[1]] = crop
            img = self.lut[tile].transpose(2, 0, 1)[None].copy()
            emb, f0, f1 = self.enc.run(None, {'image': img}); tiles += 1
            for b0 in range(0, len(ids), self.batch):
                n = min(self.batch, len(ids) - b0)
                coords = np.zeros((self.batch, 3, 2), np.float32)
                for k in range(self.batch):
                    d = dets[ids[b0 + min(k, n - 1)]]
                    cx, cy = (d['x'] + .5) * sx - .5 - x0, (d['y'] + .5) * sy - .5 - y0
                    r = max(d['radius'], d.get('semi_major', 0)) * sm * BOX
                    coords[k] = [[cx - r, cy - r], [cx + r, cy + r], [cx, cy]]
                labels = np.tile(np.array([[2, 3, 1]], np.float32), (self.batch, 1))
                logits, iou = self.dec.run(None, {'image_embed': emb, 'high_res_feats_0': f0, 'high_res_feats_1': f1,
                                                  'point_coords': coords, 'point_labels': labels})
                for k in range(n):
                    i = ids[b0 + k]
                    m = self.measure(dets[i], logits[k, 0], float(iou[k, 0]), x0, y0, sw, sh, sx, sy)
                    if m is None:
                        kept += 1
                    else:
                        out[i] = m; refined += 1
        report = dict(tiles=tiles, prompts=len(dets), refined=refined, kept=kept, scale=round(s, 3),
                      seconds=round(time.time() - t0, 2))
        return out, report

    def measure(self, seed, logits, iou, x0, y0, sw, sh, sx, sy):
        if not iou >= MIN_IOU:
            return None
        sm, ratio = math.sqrt(sx * sy), self.size / self.ms
        cx, cy = (seed['x'] + .5) * sx - .5, (seed['y'] + .5) * sy - .5
        rw = max(seed['radius'], seed.get('semi_major', 0)) * sm * WINDOW
        ax, ay, bx, by = math.floor(cx - rw), math.floor(cy - rw), math.ceil(cx + rw), math.ceil(cy + rw)
        gw, gh = bx - ax + 1, by - ay + 1
        px, py = np.meshgrid(np.arange(ax, bx + 1), np.arange(ay, by + 1))
        u, v = px - x0, py - y0
        valid = (px >= 0) & (py >= 0) & (px < sw) & (py < sh) & (u >= 0) & (v >= 0) & (u < self.size) & (v < self.size)
        gx = np.clip((u + .5) / ratio - .5, 0, self.ms - 1); gy = np.clip((v + .5) / ratio - .5, 0, self.ms - 1)
        ix, iy = np.floor(gx).astype(int), np.floor(gy).astype(int)
        jx, jy = np.minimum(self.ms - 1, ix + 1), np.minimum(self.ms - 1, iy + 1)
        fx, fy = gx - ix, gy - iy
        val = ((logits[iy, ix] * (1 - fx) + logits[iy, jx] * fx) * (1 - fy) + (logits[jy, ix] * (1 - fx) + logits[jy, jx] * fx) * fy)
        on = (val > 0) & valid
        icx, icy = int(round(cx)) - ax, int(round(cy)) - ay
        if 0 <= icx < gw and 0 <= icy < gh and on[icy, icx]:
            sy_, sx_ = icy, icx
        else:
            yy, xx = np.nonzero(on)
            if len(xx) == 0:
                return None
            dist = np.hypot(xx - icx, yy - icy); k = int(np.argmin(dist))
            if dist[k] > seed['radius'] * sm * .5:
                return None
            sy_, sx_ = yy[k], xx[k]
        n_lab, lab = cv2.connectedComponents(on.astype(np.uint8), connectivity=4)
        comp = lab == lab[sy_, sx_]
        n = int(comp.sum())
        if n < 12 or comp[0, :].any() or comp[-1, :].any() or comp[:, 0].any() or comp[:, -1].any():
            return None
        ratio_area = n / (math.pi * seed['radius'] ** 2 * sx * sy)
        if not MIN_AREA_RATIO <= ratio_area <= MAX_AREA_RATIO:
            return None
        yy, xx = np.nonzero(comp)
        X, Y = xx + ax, yy + ay
        mx, my = X.mean(), Y.mean()
        cxx, cyy, cxy = X.var() + 1 / 12, Y.var() + 1 / 12, ((X - mx) * (Y - my)).mean()
        tr, det = cxx + cyy, cxx * cyy - cxy * cxy
        disc = math.sqrt(max(0, tr * tr / 4 - det))
        a, b = 2 * math.sqrt(tr / 2 + disc), 2 * math.sqrt(max(1e-9, tr / 2 - disc))
        k = math.sqrt(n / (math.pi * a * b)); a *= k; b *= k
        partial = seed.get('partial', False) or X.min() <= 0 or Y.min() <= 0 or X.max() >= sw - 1 or Y.max() >= sh - 1
        mask_pts = np.stack([(X + .5) / sx - .5, (Y + .5) / sy - .5], 1)
        return dict(seed, x=(mx + .5) / sx - .5, y=(my + .5) / sy - .5, radius=math.sqrt(a * b) / sm, area=n / (sx * sy),
                    semi_major=a / sm, semi_minor=b / sm, partial=bool(partial), review=iou < REVIEW_IOU,
                    sam2=True, iou=iou, mask=mask_pts)


def draw(gray, dets, title):
    img = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)
    for d in dets:
        if d.get('mask') is not None:
            m = np.zeros(gray.shape, np.uint8)
            pts = np.round(d['mask']).astype(int)
            ok = (pts[:, 0] >= 0) & (pts[:, 1] >= 0) & (pts[:, 0] < gray.shape[1]) & (pts[:, 1] < gray.shape[0])
            m[pts[ok, 1], pts[ok, 0]] = 255
            m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))
            cs, _ = cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
            cv2.drawContours(img, cs, -1, (0, 255, 0), 2)
        else:
            color = (0, 140, 255) if 'sam2' in d else (255, 200, 0)
            cv2.circle(img, (int(round(d['x'])), int(round(d['y']))), int(round(d['radius'])), color, 2)
    cv2.putText(img, title, (12, 40), cv2.FONT_HERSHEY_SIMPLEX, 1.2, (0, 0, 255), 3)
    return img


def stats(dets):
    r = [2 * d['radius'] for d in dets if not d.get('partial')]
    if not r:
        return {}
    r = np.sort(np.array(r))
    return dict(n=len(r), d_mean_px=round(float(r.mean()), 2), d10_px=round(float(np.percentile(r, 10)), 2),
                d50_px=round(float(np.percentile(r, 50)), 2), d90_px=round(float(np.percentile(r, 90)), 2))


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--modelo', type=Path, required=True)
    p.add_argument('--saida', type=Path, required=True)
    p.add_argument('--cenas', type=Path)
    p.add_argument('--fotos', type=Path, nargs='*', default=[])
    p.add_argument('--lado', type=int, default=3000, help='lado maior máximo (como o app no Windows)')
    a = p.parse_args()
    a.saida.mkdir(parents=True, exist_ok=True)
    run = common.ort_runner(ROOT / 'app/src/main/assets/pellet_stardist.onnx')
    sam = Sam2(a.modelo)
    items = []
    if a.cenas:
        for s in common.load_scenes(a.cenas):
            items.append((s['group'], s['gray'], s['circles'], s['partial']))
    for f in a.fotos:
        g = cv2.imread(str(f), cv2.IMREAD_GRAYSCALE)
        items.append((f.stem, g, None, None))
    results = []
    for name, gray, circles, partial in items:
        h, w = gray.shape
        f = min(1.0, a.lado / max(h, w))
        if f < 1:
            gray = cv2.resize(gray, (round(w * f), round(h * f)), interpolation=cv2.INTER_AREA)
            if circles is not None:
                circles = circles * f
        seeds, _, _ = common.detect(run, gray, profile=3, sensitivity=75)
        if not seeds:
            results.append(dict(imagem=name, erro='StarDist não encontrou pelotas')); continue
        refined, rep = sam.refine(gray, seeds)
        row = dict(imagem=name, largura=gray.shape[1], altura=gray.shape[0], sam2=rep,
                   stardist=stats(seeds), stardist_sam2=stats(refined))
        if circles is not None:
            row['vs_circulos_provisorios'] = dict(stardist=common.match(seeds, circles, partial),
                                                  stardist_sam2=common.match(refined, circles, partial))
        results.append(row)
        both = np.hstack([draw(gray, seeds, 'StarDist'), draw(gray, refined, 'StarDist + SAM 2')])
        k = min(1.0, 2400 / both.shape[1])
        cv2.imwrite(str(a.saida / f'{name}_comparacao.jpg'), cv2.resize(both, None, fx=k, fy=k, interpolation=cv2.INTER_AREA),
                    [cv2.IMWRITE_JPEG_QUALITY, 88])
        print(json.dumps({kk: vv for kk, vv in row.items()}, ensure_ascii=False))
    (a.saida / 'resumo.json').write_text(json.dumps(dict(modelo=sam.meta['id'], checkpoint=sam.meta.get('checkpoint_sha256'),
                                                          resultados=results), indent=2, ensure_ascii=False), encoding='utf-8')
    print('OK', a.saida)


if __name__ == '__main__':
    main()
