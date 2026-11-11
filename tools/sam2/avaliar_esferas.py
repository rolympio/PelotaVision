"""Viés de tamanho com esferas padrão (ex.: 11 mm) e régua milimetrada na mesma foto.

    python tools/sam2/avaliar_esferas.py --fotos <pasta ou arquivos> --saida <pasta> [--diametro 11] [--modelo <pasta sam2>]

Escala (px/mm) SÓ da régua: a régua é achada pela cor (amarelada), alinhada pelo retângulo mínimo, e cada traço
de milímetro é localizado e numerado; x(n) cúbico dá a escala LOCAL (lente/inclinação do celular mudam a escala
ao longo da foto). Cada esfera usa a escala no ponto da régua mais próximo dela. As esferas nunca entram na
escala, então o teste não é circular. Limite: o equador da esfera fica alguns mm acima da régua (mais perto
da câmera), o que aumenta o diâmetro aparente em ~2–4% a 10–15 cm de distância.

Também calibra como o app: uma esfera da foto (medida pelo mesmo método) vale --diametro e as outras são
medidas (bloco escala_pela_esfera do resumo). Esse bloco não depende do plano da régua.

Métodos comparados por esfera (diâmetro equivalente de área, mm):
  limiar  – contorno por limiar de Otsu (referência simples de silhueta)
  stardist – rede StarDist do app (common.detect)
  sam2    – StarDist + refino SAM 2 (mesmo algoritmo do app), se --modelo for dado
Saídas: esferas.csv, resumo.json, <foto>_esferas.jpg.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import sys
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'ml_training' / 'stardist'))
sys.path.insert(0, str(Path(__file__).resolve().parent))


def ruler_mask(bgr):
    lab = cv2.cvtColor(bgr, cv2.COLOR_BGR2LAB).astype(np.int16)
    L, b = lab[..., 0], lab[..., 2] - 128
    A = lab[..., 1] - 128
    m = ((b > 5) & (A < -1) & (L > 160)).astype(np.uint8)
    m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, np.ones((25, 25), np.uint8))
    n, lab_, st, _ = cv2.connectedComponentsWithStats(m)
    if n < 2:
        return None, None
    i = 1 + int(np.argmax(st[1:, cv2.CC_STAT_AREA]))
    comp = (lab_ == i).astype(np.uint8)
    cs, _ = cv2.findContours(comp, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    return comp, cv2.minAreaRect(max(cs, key=cv2.contourArea))


def ruler_ticks(gray, rect):
    """Traços de milímetro ao longo da régua. Devolve (M, posições x no recorte, índice n em mm, coeficientes
    x(n) cúbicos). Lente de celular e inclinação mudam a escala ao longo da foto (nas fotos de teste, até ~8%),
    então a escala é LOCAL: dx/dn no ponto da régua mais próximo de cada objeto."""
    (cx, cy), (w, h), ang = rect
    if w < h:
        w, h, ang = h, w, ang + 90
    W, H = int(round(w)), int(round(h))
    M = cv2.getRotationMatrix2D((cx, cy), ang, 1.0)
    M[0, 2] += W / 2 - cx
    M[1, 2] += H / 2 - cy
    strip = cv2.warpAffine(gray, M, (W, H), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
    best = None
    for y0, y1 in ((int(.03 * H), int(.10 * H)), (int(.90 * H), int(.97 * H))):
        prof = cv2.GaussianBlur(strip[y0:y1, :].astype(np.float64).mean(0).reshape(1, -1), (0, 0), 1).ravel()
        thr = np.median(prof) - 20
        xs = np.array([x for x in range(2, W - 2) if prof[x] < prof[x - 1] and prof[x] <= prof[x + 1] and prof[x] < thr], float)
        if len(xs) < 20:
            continue
        d = np.diff(xs)
        med = np.median(d)
        if not 5 < med < 40:
            continue
        # índice em mm: acumula round(d/período local), descartando falsos mínimos muito próximos
        keep, ns = [xs[0]], [0]
        for x in xs[1:]:
            step = (x - keep[-1]) / med
            if step < .6:
                continue
            keep.append(x); ns.append(ns[-1] + int(round(step)))
        keep, ns = np.array(keep), np.array(ns, float)
        for _ in range(4):  # ajuste robusto x(n), cúbico
            c = np.polyfit(ns, keep, 3)
            res = keep - np.polyval(c, ns)
            ok = np.abs(res) < max(2.0, 3 * np.std(res))
            keep, ns = keep[ok], ns[ok]
        regular = len(keep) / max(1.0, ns.max() - ns.min() + 1)
        cand = (len(keep) * regular, -med, keep, ns, c, float(np.std(keep - np.polyval(c, ns))))
        if best is None or cand[:2] > best[:2]:
            best = cand
    if best is None:
        return None
    _, _, keep, ns, c, rms = best
    return dict(M=M, x=keep, n=ns, coef=c, rms=rms)


def local_ppm(tk, x, y):
    """px/mm da régua na posição projetada de (x, y) sobre o eixo da régua (limitada ao trecho medido)."""
    u = tk['M'][0, 0] * x + tk['M'][0, 1] * y + tk['M'][0, 2]
    c = tk['coef']
    nn = np.roots(np.array([c[0], c[1], c[2], c[3] - u]))
    nn = [r.real for r in nn if abs(r.imag) < 1e-6]
    n = min(nn, key=lambda r: abs(r - tk['n'].mean())) if nn else tk['n'].mean()
    n = float(np.clip(n, tk['n'].min(), tk['n'].max()))
    return float(abs(np.polyval(np.polyder(c), n)))


def threshold_spheres(gray, ruler, ppm, d_mm):
    blur = cv2.GaussianBlur(gray, (0, 0), 2)
    _, m = cv2.threshold(blur, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    m[ruler > 0] = 0
    m = cv2.morphologyEx(m, cv2.MORPH_OPEN, np.ones((5, 5), np.uint8))
    cs, _ = cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    exp_a = math.pi * (d_mm * ppm / 2) ** 2
    out = []
    for c in cs:
        a = cv2.contourArea(c)
        if not .3 * exp_a < a < 2.5 * exp_a:
            continue
        p = cv2.arcLength(c, True)
        if 4 * math.pi * a / p ** 2 < .8:  # régua/sombra fragmentada, não esfera
            continue
        (x, y), _ = cv2.minEnclosingCircle(c)
        out.append(dict(x=x, y=y, area=a, d_px=2 * math.sqrt(a / math.pi), circ=4 * math.pi * a / p ** 2, contour=c))
    return out


def nearest(items, x, y, r):
    best = None
    for it in items:
        dd = math.hypot(it['x'] - x, it['y'] - y)
        if dd < r and (best is None or dd < best[0]):
            best = (dd, it)
    return None if best is None else best[1]


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--fotos', nargs='+', type=Path, required=True)
    p.add_argument('--saida', type=Path, required=True)
    p.add_argument('--diametro', type=float, default=11.0)
    p.add_argument('--modelo', type=Path)
    a = p.parse_args()
    a.saida.mkdir(parents=True, exist_ok=True)
    files = []
    for f in a.fotos:
        files += sorted(x for x in f.iterdir() if x.suffix.lower() in ('.jpg', '.jpeg', '.png')) if f.is_dir() else [f]
    import common
    run = common.ort_runner(ROOT / 'app/src/main/assets/pellet_stardist.onnx')
    sam = None
    if a.modelo:
        from avaliar_sam2 import Sam2
        sam = Sam2(a.modelo)
    rows, per_photo = [], []
    for f in files:
        bgr = cv2.imread(str(f))
        gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
        ruler, rect = ruler_mask(bgr)
        if rect is None:
            per_photo.append(dict(foto=f.name, erro='régua não encontrada')); continue
        tk = ruler_ticks(gray, rect)
        if tk is None:
            per_photo.append(dict(foto=f.name, erro='traços da régua não encontrados')); continue
        if tk['rms'] > 4:  # traços mal numerados (régua cortada, desfoque, reflexo): escala não confiável
            per_photo.append(dict(foto=f.name, erro=f"escala da régua rejeitada (resíduo {tk['rms']:.1f} px)")); continue
        ppm = local_ppm(tk, *rect[0])  # escala no centro da régua (só para limites de tamanho)
        sharp = tk['rms']
        thr = threshold_spheres(gray, ruler, ppm, a.diametro)
        work = gray.copy(); work[ruler > 0] = int(np.median(gray))  # régua fora do detector
        seeds, _, _ = common.detect(run, work, profile=3, sensitivity=75)
        r_exp = a.diametro * ppm / 2
        seeds = [s for s in seeds if .5 * r_exp < s['radius'] < 1.6 * r_exp]
        refined = sam.refine(work, seeds)[0] if (sam and seeds) else None
        vis = bgr.copy()
        for i, t in enumerate(thr):
            s = nearest(seeds, t['x'], t['y'], .5 * r_exp)
            lp = local_ppm(tk, t['x'], t['y'])
            row = dict(foto=f.name, esfera=i + 1, px_por_mm=round(lp, 4), x=round(t['x']), y=round(t['y']),
                       circularidade=round(t['circ'], 3), d_limiar_mm=round(t['d_px'] / lp, 3),
                       d_stardist_mm=round(2 * s['radius'] / lp, 3) if s else None, d_sam2_mm=None,
                       d_limiar_px=round(t['d_px'], 2), d_stardist_px=round(2 * s['radius'], 2) if s else None, d_sam2_px=None, d_sam2bruto_px=None,
                       dist_centro=round(math.hypot(t['x'] - gray.shape[1] / 2, t['y'] - gray.shape[0] / 2) / math.hypot(*gray.shape) * 2, 3))
            cv2.drawContours(vis, [t['contour']], -1, (255, 200, 0), 2)
            if s:
                cv2.circle(vis, (int(s['x']), int(s['y'])), int(s['radius']), (0, 165, 255), 2)
            if refined is not None and s:
                rr = refined[seeds.index(s)]
                if rr.get('sam2'):
                    row['d_sam2_mm'] = round(2 * rr['radius'] / lp, 3)
                    row['d_sam2_px'] = round(2 * rr['radius'], 2)
                    row['d_sam2bruto_px'] = round(2 * rr['radius_raw'], 2)
                    pts = np.round(rr['mask']).astype(int)
                    mm = np.zeros(gray.shape, np.uint8); ok = (pts[:, 0] >= 0) & (pts[:, 1] >= 0) & (pts[:, 0] < gray.shape[1]) & (pts[:, 1] < gray.shape[0])
                    mm[pts[ok, 1], pts[ok, 0]] = 255
                    cs, _ = cv2.findContours(cv2.morphologyEx(mm, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8)), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
                    cv2.drawContours(vis, cs, -1, (0, 255, 0), 2)
            rows.append(row)
        box = cv2.boxPoints(rect).astype(int)
        cv2.polylines(vis, [box], True, (255, 0, 255), 3)
        cv2.putText(vis, f'{ppm:.2f} px/mm (centro da regua)  ciano=limiar laranja=StarDist verde=SAM2', (20, 50), cv2.FONT_HERSHEY_SIMPLEX, 1.1, (0, 0, 255), 3)
        cv2.imwrite(str(a.saida / f'{f.stem}_esferas.jpg'), vis, [cv2.IMWRITE_JPEG_QUALITY, 85])
        per_photo.append(dict(foto=f.name, px_por_mm_centro_regua=round(ppm, 4), px_por_mm_min=round(float(min(abs(np.polyval(np.polyder(tk['coef']), tk['n'])))), 4), px_por_mm_max=round(float(max(abs(np.polyval(np.polyder(tk['coef']), tk['n'])))), 4), residuo_tracos_px=round(sharp, 2), mm_regua_medidos=int(tk['n'].max() - tk['n'].min()), esferas=len(thr)))
        print(f.name, f'{ppm:.3f} px/mm', len(thr), 'esferas', len(seeds), 'StarDist', flush=True)
    with open(a.saida / 'esferas.csv', 'w', newline='', encoding='utf-8') as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0].keys())); w.writeheader(); w.writerows(rows)
    summary = {}
    # Calibração como no app: uma esfera da própria foto (mesmo método) vale --diametro; as outras são medidas.
    # 'centro' = referência mais perto do centro da foto; 'todas' = cada esfera como referência, uma por vez.
    esf = {}
    for k in ('limiar', 'stardist', 'sam2bruto', 'sam2'):
        cen, todas = [], []
        for foto in sorted({r['foto'] for r in rows}):
            rs = [r for r in rows if r['foto'] == foto and r[f'd_{k}_px']]
            if len(rs) < 3:
                continue
            ref = min(rs, key=lambda r: r['dist_centro'])
            cen += [a.diametro * r[f'd_{k}_px'] / ref[f'd_{k}_px'] for r in rs if r is not ref]
            for ref in rs:
                todas += [a.diametro * r[f'd_{k}_px'] / ref[f'd_{k}_px'] for r in rs if r is not ref]
        for nome, v in (('referencia_no_centro', cen), ('cada_esfera_como_referencia', todas)):
            v = np.array(v, float)
            if len(v):
                esf.setdefault(k, {})[nome] = dict(n=int(len(v)), media_mm=round(float(v.mean()), 3), desvio_mm=round(float(v.std(ddof=1)), 3),
                    erro_abs_medio_mm=round(float(np.abs(v - a.diametro).mean()), 3), p10_p90_mm=[round(float(x), 3) for x in np.percentile(v, [10, 90])])
    summary['escala_pela_esfera'] = esf
    for k in ('d_limiar_mm', 'd_stardist_mm', 'd_sam2_mm'):
        v = np.array([r[k] for r in rows if r[k] is not None], float)
        if len(v):
            summary[k] = dict(n=int(len(v)), media=round(float(v.mean()), 3), desvio=round(float(v.std(ddof=1)), 3) if len(v) > 1 else None,
                              vies_pct=round(100 * (v.mean() / a.diametro - 1), 2))
    summary['escala_pela_regua_nota'] = 'd_*_mm usam a régua; se ela não estiver no plano do equador das esferas, o valor absoluto sai deslocado (perspectiva). Compare pelos blocos escala_pela_esfera.'
    (a.saida / 'resumo.json').write_text(json.dumps(dict(diametro_nominal_mm=a.diametro, metodos=summary, fotos=per_photo), indent=2, ensure_ascii=False), encoding='utf-8')
    print(json.dumps(summary, indent=2, ensure_ascii=False))


if __name__ == '__main__':
    main()
