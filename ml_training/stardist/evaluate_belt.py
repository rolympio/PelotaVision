"""Avaliação do modelo de CORREIA nos quadros de validação (Disco 11, Disco 12, 4P10F) e na correia vazia.

Uso: python evaluate_belt.py --belt output/belt.onnx --out output/belt_validation.json
       [--demo ../../app/src/main/assets/pellet_stardist.onnx] [--pseudo ../data_belt/pseudo]

Compara contra os PSEUDO-RÓTULOS DE CONSENSO (não é verdade-terreno):
  belt_app   modelo de correia, pipeline do app (common.detect, cena Correia, perfil Auto, sensibilidade 75)
  demo_app   modelo de demonstração (bancada), mesmo pipeline do app
  demo_scale modelo de demonstração na escala do espaçamento (é o rotulador "net": participa do consenso)
  log_scale  LoG Python do rotulador (participa do consenso)
  hough_scale Hough do rotulador (participa do consenso)
Os três últimos são lidos de val_frames.json (tempo não medido aqui) e são VIESADOS a favor (votaram no consenso); servem só de referência.
Casamento: guloso, centro < 0,5·r e razão de raios 0,7–1,43 (common.match).
  recall    = consenso casado / consenso (inclui parciais na borda do quadro)
  precisao  = casadas / (casadas + não casadas com centro em peso 1; peso 0 = material sem consenso é ignorado)
  precisao_bruta = casadas / todas as detecções
Correia vazia: quadros do Disco 03 de 15:40 em diante (nunca usados no treino); detecções por quadro.
"""
import argparse, json, math, sys, time
from pathlib import Path
import numpy as np, cv2
import common as C

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / 'ml_training' / 'data_belt'))


def score(dets, cons, wmask):
    """Precisão = casadas / (casadas + não casadas com centro em peso 1). Não casadas sobre material sem
    consenso (peso 0) não contam como erro: ali o consenso é incompleto por construção."""
    circles = np.array([[c['x'], c['y'], c['radius']] for c in cons]).reshape(-1, 3)
    m = C.match(dets, circles)
    pairs = []
    for i, d in enumerate(dets):
        for j, (x, y, r) in enumerate(circles):
            if abs(d['x'] - x) > r or abs(d['y'] - y) > r:
                continue
            dd = math.hypot(d['x'] - x, d['y'] - y)
            if dd < .5 * r and .7 <= d['radius'] / r <= 1 / .7:
                pairs.append((dd / r, i, j))
    pairs.sort()
    ud, ug = set(), set()
    for _, i, j in pairs:
        if i not in ud and j not in ug:
            ud.add(i); ug.add(j)
    assert len(ud) == m['matched']
    h, w = wmask.shape
    fp = 0
    for i, d in enumerate(dets):
        x, y = int(round(d['x'])), int(round(d['y']))
        if i not in ud and 0 <= x < w and 0 <= y < h and wmask[y, x] > 0:
            fp += 1
    matched = m['matched']
    p = matched / max(1, matched + fp)
    r = matched / max(1, len(circles))
    return dict(detections=len(dets), consensus=len(circles), matched=matched, scored_detections=matched + fp,
                precision=round(p, 4), recall=round(r, 4), f1=round(2 * p * r / max(1e-9, p + r), 4),
                precision_raw=m['precision'], radius_abs_error_pct=m['radius_abs_error_pct'])


def aggregate(rows):
    tot = {k: sum(r[k] for r in rows) for k in ('detections', 'consensus', 'matched', 'scored_detections')}
    p = tot['matched'] / max(1, tot['scored_detections'])  # micro-média
    rc = tot['matched'] / max(1, tot['consensus'])
    return dict(frames=len(rows), **tot, precision=round(p, 4), recall=round(rc, 4), f1=round(2 * p * rc / max(1e-9, p + rc), 4),
                precision_raw=round(tot['matched'] / max(1, tot['detections']), 4),
                seconds_per_frame=round(float(np.mean([r['seconds'] for r in rows])), 2) if rows[0]['seconds'] is not None else None)


def as_dets(circles):
    return [dict(x=float(x), y=float(y), radius=float(r)) for x, y, r in circles]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--belt', required=True)
    ap.add_argument('--demo', default=str(ROOT / 'app' / 'src' / 'main' / 'assets' / 'pellet_stardist.onnx'))
    ap.add_argument('--pseudo', default=str(ROOT / 'ml_training' / 'data_belt' / 'pseudo'))
    ap.add_argument('--out', required=True)
    ap.add_argument('--skip-reference', action='store_true', help='não roda demo_scale/log_scale (mais rápido)')
    a = ap.parse_args()
    import pseudo_label as P
    pseudo = Path(a.pseudo)
    belt, demo = C.ort_runner(a.belt), C.ort_runner(a.demo)
    frames = json.load(open(pseudo / 'val_frames.json', encoding='utf-8'))
    summary = json.load(open(pseudo / 'summary.json', encoding='utf-8'))
    per_frame, by = [], {}
    for f in frames:
        gray = cv2.cvtColor(cv2.imread(str(ROOT / 'ml_training' / 'data_belt' / 'raw' / f['file'])), cv2.COLOR_BGR2GRAY)
        wm = cv2.imread(str(pseudo / f['weight_mask']), cv2.IMREAD_GRAYSCALE)
        row = dict(file=f['file'], camera=f['camera'])
        runs = [('belt_app', lambda: C.detect(belt, gray, 3, 75, belt=True)[0]),
                ('demo_app', lambda: C.detect(demo, gray, 3, 75, belt=True)[0])]
        if not a.skip_reference:  # detecções gravadas na rotulagem (as mesmas que votaram no consenso)
            runs += [('demo_scale', lambda: as_dets(f['labelers']['net'])), ('log_scale', lambda: as_dets(f['labelers']['log'])),
                     ('hough_scale', lambda: as_dets(f['labelers']['hough']))]
        for name, fn in runs:
            t0 = time.time(); dets = fn(); dt = time.time() - t0 if name.endswith('_app') else None
            dets = [d for d in dets if d['y'] >= P.TEXT_BAND]  # faixa impressa fora da avaliação
            s = score(dets, f['consensus'], wm); s['seconds'] = None if dt is None else round(dt, 2)
            if name.endswith('_app'):
                s['median_radius_px'] = round(float(np.median([d['radius'] for d in dets])), 2) if dets else None
            row[name] = s
            by.setdefault(name, []).append(s)
            by.setdefault(f"{name}|{f['camera']}", []).append(s)
        row['consensus_median_radius_px'] = round(float(np.median([c['radius'] for c in f['consensus']])), 2)
        print(f['file'], f['camera'], {k: (v['f1'], v['detections'], v['seconds']) for k, v in row.items() if isinstance(v, dict)}, flush=True)
        per_frame.append(row)
    empty = []
    for fn in summary['frames_negative_eval']:
        gray = cv2.cvtColor(cv2.imread(str(ROOT / 'ml_training' / 'data_belt' / 'raw' / fn)), cv2.COLOR_BGR2GRAY)
        row = dict(file=fn)
        for name, run in (('belt_app', belt), ('demo_app', demo)):
            t0 = time.time()
            dets = [d for d in C.detect(run, gray, 3, 75, belt=True)[0] if d['y'] >= P.TEXT_BAND]
            row[name] = len(dets); row[name + '_seconds'] = round(time.time() - t0, 2)
        print('vazio', row, flush=True)
        empty.append(row)
    res = dict(
        protocol=__doc__.strip(), label_origin=summary['label_origin'], ground_truth=False,
        validation_cameras=sorted({f['camera'] for f in frames}), validation_frames=len(frames),
        overall={k: aggregate(v) for k, v in by.items() if '|' not in k},
        by_camera={k: aggregate(v) for k, v in by.items() if '|' in k},
        empty_belt=dict(frames=len(empty), mean_detections={k: round(float(np.mean([r[k] for r in empty])), 1) for k in ('belt_app', 'demo_app')},
                        max_detections={k: int(max(r[k] for r in empty)) for k in ('belt_app', 'demo_app')}, per_frame=empty),
        per_frame=per_frame)
    Path(a.out).write_text(json.dumps(res, indent=1, ensure_ascii=False), encoding='utf-8')
    for k, v in res['overall'].items():
        print('VALIDACAO', k, v, flush=True)
    print('VAZIA', res['empty_belt']['mean_detections'], flush=True)


if __name__ == '__main__':
    main()
