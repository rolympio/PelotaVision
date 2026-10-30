"""Avaliação do StarDist de bancada nas análises de panela (webcam) deixadas fora do treino.

Referência = pseudo-rótulos de make_pseudo.py, NÃO verdade-terreno:
  * TP: detecção casada (centro < 0,5 r, razão de raios 0,7–1,43) com um positivo CONFIÁVEL;
  * casada com positivo duvidoso, ou com centro em pixel de peso 0 e sem casamento: ignorada (não dá para julgar);
  * FP: sem casamento e com centro em pixel de peso 1 (fundo real ou fresta);
  * recall: confiáveis casados / confiáveis (fora da faixa junto à borda da ROI).
Pipeline igual ao app: recorte da ROI com o exterior preenchido pela mediana (AnalysisWindow.cropGray),
common.detect com perfil e sensibilidade dados, coordenadas de volta ao quadro.

Uso: python evaluate_pan.py --model A.onnx [--model B.onnx] --out pan_validation.json [--split validacao]
"""
import argparse, json, math, time
from pathlib import Path
import numpy as np, cv2
import common as C


def crop_roi(gray, roi):
    cx, cy, r = roi
    h, w = gray.shape
    l, t = int(math.floor(cx - r)), int(math.floor(cy - r))
    rr, b = int(math.ceil(cx + r)), int(math.ceil(cy + r))
    l, t, rr, b = max(0, l), max(0, t), min(w, rr), min(h, b)
    c = gray[t:b, l:rr].copy()
    yy, xx = np.mgrid[t:b, l:rr]
    inside = (xx + .5 - cx) ** 2 + (yy + .5 - cy) ** 2 <= r * r
    c[~inside] = int(np.median(c[inside]))
    return c, l, t


def greedy(dets, gts):
    pairs = []
    for i, d in enumerate(dets):
        for j, (x, y, r) in enumerate(gts):
            dd = math.hypot(d['x'] - x, d['y'] - y)
            if dd < .5 * r and .7 <= d['radius'] / r <= 1 / .7:
                pairs.append((dd / r, i, j))
    pairs.sort()
    ud, ug, m = set(), set(), {}
    for _, i, j in pairs:
        if i in ud or j in ug:
            continue
        ud.add(i); ug.add(j); m[i] = j
    return m


def evaluate(model, scenes, data_dir, profile, sensitivity):
    run = C.ort_runner(model)
    out = {}
    for s in scenes:
        gray = cv2.imread(str(data_dir / s['image']), cv2.IMREAD_GRAYSCALE)
        weight = cv2.imread(str(data_dir / s['weight_mask']), cv2.IMREAD_GRAYSCALE) > 127
        t0 = time.time()
        crop, l, t = crop_roi(gray, s['roi_circle'])
        dets, ncand, limit = C.detect(run, crop, profile=profile, sensitivity=sensitivity)
        for d in dets:
            d['x'] += l; d['y'] += t
        cx, cy, R = s['roi_circle']
        anns = s['annotations']
        rmed = float(np.median([a['radius'] for a in anns]))
        conf = [(a['x'], a['y'], a['radius']) for a in anns if a['confident'] and math.hypot(a['x'] - cx, a['y'] - cy) <= R - 2.2 * rmed]
        allg = [(a['x'], a['y'], a['radius']) for a in anns]
        isconf = [a['confident'] for a in anns]
        m = greedy(dets, allg)
        tp = sum(1 for i, j in m.items() if isconf[j])
        conf_set = {(round(x, 2), round(y, 2)) for x, y, _ in conf}
        tp_inner = sum(1 for i, j in m.items() if isconf[j] and (round(allg[j][0], 2), round(allg[j][1], 2)) in conf_set)
        fp = 0
        for i, d in enumerate(dets):
            if i in m:
                continue
            xi, yi = int(round(d['x'])), int(round(d['y']))
            if 0 <= yi < weight.shape[0] and 0 <= xi < weight.shape[1] and weight[yi, xi]:
                fp += 1
        judged = tp + fp
        rec = tp_inner / max(1, len(conf))
        prec = tp / max(1, judged)
        errs = [abs(dets[i]['radius'] - allg[j][2]) / allg[j][2] for i, j in m.items() if isconf[j]]
        out[s['group']] = dict(detections=len(dets), confident_inner=len(conf), matched_confident=tp, false_positives_judged=fp,
                               precision_judged=round(prec, 4), recall_confident=round(rec, 4),
                               f1=round(2 * prec * rec / max(1e-9, prec + rec), 4),
                               radius_abs_error_pct=round(100 * float(np.mean(errs)), 2) if errs else None,
                               candidate_limit=limit, seconds=round(time.time() - t0, 2))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--model', action='append', required=True)
    ap.add_argument('--data', default=str(Path(__file__).resolve().parents[1] / 'data_pan_20261027/pseudo'))
    ap.add_argument('--split', default='validacao')
    ap.add_argument('--profiles', default='1,3')
    ap.add_argument('--sensitivity', type=int, default=75)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    data_dir = Path(a.data)
    scenes = [s for s in json.load(open(data_dir / 'annotations.json', encoding='utf-8'))
              if s['split'] == a.split and s['source'].startswith('webcam')]
    res = dict(protocol=__doc__.strip().splitlines()[0], reference='pseudo-rótulos do app (LoG + elipse), não verdade-terreno',
               split=a.split, scenes=[s['group'] for s in scenes], sensitivity=a.sensitivity, models={})
    for mpath in a.model:
        per = {}
        for p in (int(x) for x in a.profiles.split(',')):
            r = evaluate(mpath, scenes, data_dir, p, a.sensitivity)
            tot = {k: sum(v[k] for v in r.values()) for k in ('detections', 'confident_inner', 'matched_confident', 'false_positives_judged')}
            per[f'perfil_{p}'] = dict(scenes=r, total=tot)
            print(Path(mpath).name, f'perfil {p}', json.dumps(r, ensure_ascii=False), flush=True)
        res['models'][str(mpath)] = per
    Path(a.out).write_text(json.dumps(res, indent=1, ensure_ascii=False), encoding='utf-8')


if __name__ == '__main__':
    main()
