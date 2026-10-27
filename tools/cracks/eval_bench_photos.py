#!/usr/bin/env python3
"""Avaliação das fotos de bancada de 26/10/2026 (rótulo por pasta, pelota isolada) — protocolo fixado antes dos escores.

Uso:
  python tools/cracks/eval_bench_photos.py --manifest DADOS/labels.csv --photos ml_training/data_bench_20261026/photos.csv \
      --out verification/cracks-bench-20261026 [--export-pvrf app/src/main/assets/crack_forest.pvrf]

Protocolo:
  1. Teste EXTERNO dos modelos embarcados (nunca viram estas fotos): RF (crack_forest.pvrf) e MobileNetV3 (ONNX).
  2. RF retreinado (mesmo learner e hiperparâmetros do app, 72 atributos, 6 variantes de iluminação):
     a) cenas antigas + bancada, deixando UMA RAJADA de fora por vez (a pelota física inteira, nas duas escalas);
     b) só bancada, mesma partição (mostra se as cenas antigas ajudam ou atrapalham);
     c) cenas antigas + bancada, treina numa SESSÃO e testa na outra (iluminação/horário diferentes).
  3. Não regressão: modelo final (cenas + toda a bancada) no holdout antigo (crack_example), contra o embarcado.
  Métricas por escala (original r~100-160 px; reduzida r~30-48 px). AUC com IC 95% por bootstrap de RAJADAS.
  Limiar 0,5 e política do app 0,4/0,6. Nenhum hiperparâmetro é escolhido com estes dados.
"""
import argparse
import csv
import hashlib
import json
import sys
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.cracks import feats, manifest as mf, metrics as mt, pvrf  # noqa: E402

SEED = 20261027
SCENES = 'pelotavision_0.5.0_scenes'
BENCH = 'bench_photos_20261026'
EMBEDDED_RF = ROOT / 'app/src/main/assets/crack_forest.pvrf'
EMBEDDED_MNV3 = ROOT / 'app/src/main/assets/crack_mobilenetv3.onnx'


def rf_app(seed):
    from sklearn.ensemble import RandomForestClassifier  # = ml_training/train.py (app)
    return RandomForestClassifier(n_estimators=160, max_depth=7, min_samples_leaf=2, max_features='sqrt',
                                  class_weight='balanced', random_state=seed, n_jobs=-1)


class Feats:
    def __init__(self):
        self.c = {}

    def get(self, r, variant=0):
        k = (r['abs_path'], variant)
        if k not in self.c:
            g = cv2.imread(r['abs_path'], cv2.IMREAD_GRAYSCALE)
            if variant:
                g = feats.lit_variant(g, variant, int(r['source_width']) if r.get('source_width') else None,
                                      int(r['x_offset']) if r.get('x_offset') else 0)
            self.c[k] = feats.pv72(g, float(r['cx']), float(r['cy']), float(r['r']))
        return self.c[k]


def y_of(rows):
    return np.array([1 if r['label'] == 'cracked' else 0 for r in rows])


def fit(rows, F, seed=SEED):
    X, y = [], []
    for r in rows:
        for v in range(feats.N_LIGHTING_VARIANTS):
            X.append(F.get(r, v))
            y.append(1 if r['label'] == 'cracked' else 0)
    m = rf_app(seed)
    m.fit(np.asarray(X, np.float32), np.asarray(y))
    return m


def score(m, rows, F):
    return m.predict_proba(np.asarray([F.get(r) for r in rows], np.float32))[:, 1]


def mnv3_scores(rows):
    import onnxruntime as ort
    from tools.cracks.train_mobilenet import prep
    s = ort.InferenceSession(str(EMBEDDED_MNV3), providers=['CPUExecutionProvider'])
    out = []
    for r in rows:
        g = cv2.imread(r['abs_path'], cv2.IMREAD_GRAYSCALE)
        x = prep(g, float(r['cx']), float(r['cy']), float(r['r']))[None, None]
        out.append(float(s.run(None, {'input': x})[0][0, 1]))
    return np.array(out)


def scale_of(r):
    return 'reduzida_x0.3' if r['source_id'].endswith('_x03') else 'original'


def burst_bootstrap_auc(rows, s, n=2000, seed=SEED):
    groups = sorted({r['group_id'] for r in rows})
    idx = {g: [i for i, r in enumerate(rows) if r['group_id'] == g] for g in groups}
    y = y_of(rows)
    rng = np.random.default_rng(seed)
    vals = []
    for _ in range(n):
        pick = rng.choice(groups, size=len(groups), replace=True)
        ii = [i for g in pick for i in idx[g]]
        yy, ss = y[ii], s[ii]
        if 0 < yy.sum() < len(yy):
            vals.append(mt._auc(yy, ss))
    return [float(np.percentile(vals, 2.5)), float(np.percentile(vals, 97.5))] if vals else None


def report(rows, s):
    out = {}
    for sc in ('original', 'reduzida_x0.3', 'ambas'):
        ii = [i for i, r in enumerate(rows) if sc == 'ambas' or scale_of(r) == sc]
        if not ii:
            continue
        rr, ss = [rows[i] for i in ii], np.asarray(s)[ii]
        rep = mt.binary_report(y_of(rr), ss)
        rep['auc_ic95_bootstrap_rajadas'] = burst_bootstrap_auc(rr, ss)
        rep['rajadas'] = len({r['group_id'] for r in rr})
        out[sc] = rep
    return out


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--manifest', type=Path, required=True)
    p.add_argument('--photos', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--export-pvrf', type=Path)
    a = p.parse_args(argv)
    rows = mf.read(a.manifest)
    sup = [r for r in rows if r['label'] in ('cracked', 'intact')]
    bench = [r for r in sup if r['source_id'].startswith(BENCH)]
    old = [r for r in sup if r['source_id'] == SCENES]  # dev + cena sintética paired40, como o treino do app
    hold = [r for r in sup if r['split_role'] == 'holdout']
    burst_session = {f'{BENCH}:{p_["burst"]}': p_['session'] for p_ in csv.DictReader(a.photos.open(encoding='utf-8'))}
    bursts = sorted({r['group_id'] for r in bench})
    sessions = sorted({burst_session[g] for g in bursts})
    F = Feats()
    preds, results = [], {}

    # 1. externos
    trees = pvrf.read_pvrf(EMBEDDED_RF)
    s_rf = pvrf.score_pvrf(trees, np.asarray([F.get(r) for r in bench], np.float32))
    results['embedded_rf_externo'] = report(bench, s_rf)
    preds += [('embedded_rf', 'externo', r, v) for r, v in zip(bench, s_rf)]
    try:
        s_nn = mnv3_scores(bench)
        results['embedded_mnv3_externo'] = report(bench, s_nn)
        preds += [('embedded_mnv3', 'externo', r, v) for r, v in zip(bench, s_nn)]
    except Exception as e:  # onnxruntime ausente: registra e segue
        results['embedded_mnv3_externo'] = {'erro': f'{type(e).__name__}: {e}'}

    # 2a/2b. deixa uma rajada de fora
    for name, base in (('rf_cenas_mais_bancada_lobo', old), ('rf_so_bancada_lobo', [])):
        s = np.zeros(len(bench))
        for g in bursts:
            te = [i for i, r in enumerate(bench) if r['group_id'] == g]
            tr = base + [r for r in bench if r['group_id'] != g]
            s[te] = score(fit(tr, F), [bench[i] for i in te], F)
        results[name] = report(bench, s)
        preds += [(name, 'lobo', r, v) for r, v in zip(bench, s)]
    # 2c. entre sessões
    s = np.full(len(bench), np.nan)
    for ses in sessions:
        te = [i for i, r in enumerate(bench) if burst_session[r['group_id']] == ses]
        tr = old + [r for r in bench if burst_session[r['group_id']] != ses]
        s[te] = score(fit(tr, F), [bench[i] for i in te], F)
    results['rf_cenas_mais_bancada_entre_sessoes'] = {'sessoes': sessions, **report(bench, s)}
    preds += [('rf_cenas_mais_bancada_entre_sessoes', 'sessao', r, v) for r, v in zip(bench, s)]

    # 3. não regressão no holdout antigo
    final = fit(old + bench, F)
    s_old_emb = pvrf.score_pvrf(trees, np.asarray([F.get(r) for r in hold], np.float32))
    s_old_new = score(final, hold, F)
    results['holdout_antigo'] = {'embedded_rf': mt.binary_report(y_of(hold), s_old_emb), 'rf_cenas_mais_bancada': mt.binary_report(y_of(hold), s_old_new),
                                 'n': len(hold), 'grupos': sorted({r['group_id'] for r in hold})}
    preds += [('embedded_rf', 'holdout_antigo', r, v) for r, v in zip(hold, s_old_emb)]
    preds += [('rf_cenas_mais_bancada', 'holdout_antigo', r, v) for r, v in zip(hold, s_old_new)]

    a.out.mkdir(parents=True, exist_ok=True)
    pv = a.out / 'rf_cenas_mais_bancada.pvrf'
    pvrf.write_pvrf(final, pv)
    Xall = np.asarray([F.get(r) for r in bench + hold], np.float32)
    diff = float(np.abs(pvrf.score_pvrf(pvrf.read_pvrf(pv), Xall) - final.predict_proba(Xall)[:, 1]).max())
    results['export'] = {'pvrf': pv.name, 'sha256': hashlib.sha256(pv.read_bytes()).hexdigest(), 'bytes': pv.stat().st_size,
                         'pvrf_vs_sklearn_max_abs_diff': diff}
    if a.export_pvrf:
        a.export_pvrf.write_bytes(pv.read_bytes())
        results['export']['instalado_em'] = str(a.export_pvrf)
    counts = {'bench_pelotas_rotuladas_por_escala': len(bench) // 2, 'bench_trincadas': int(y_of(bench).sum()) // 2,
              'bench_rajadas': len(bursts), 'bench_rajadas_trincadas': len({r['group_id'] for r in bench if r['label'] == 'cracked'}),
              'cenas_antigas_rotuladas': len(old), 'holdout_antigo': len(hold)}
    (a.out / 'metrics.json').write_text(json.dumps({'protocolo': __doc__.split('Protocolo:')[1].strip(), 'contagens': counts,
                                                    'manifest_sha256': hashlib.sha256(a.manifest.read_bytes()).hexdigest(),
                                                    'resultados': results}, indent=2, ensure_ascii=False), encoding='utf-8')
    with (a.out / 'predictions.csv').open('w', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        w.writerow(['model', 'split', 'group_id', 'instance_id', 'scale', 'truth', 'p_cracked'])
        for m, sp, r, v in preds:
            w.writerow([m, sp, r['group_id'], r['instance_id'], scale_of(r), r['label'], f'{float(v):.6f}'])
    brief = {}
    for k, v in results.items():
        if k in ('export', 'holdout_antigo') or 'erro' in v:
            continue
        brief[k] = {sc: {'auc': round(v[sc]['roc_auc'], 3) if v[sc]['roc_auc'] is not None else None, 'ic95': v[sc]['auc_ic95_bootstrap_rajadas'],
                         'recall': v[sc]['recall_cracked'], 'espec': v[sc]['specificity'], 'cm': v[sc]['confusion_matrix']['rows_truth_cols_pred']}
                     for sc in ('original', 'reduzida_x0.3') if sc in v}
    h = results['holdout_antigo']
    brief['holdout_antigo'] = {m: {'auc': h[m]['roc_auc'], 'recall': h[m]['recall_cracked'], 'cm': h[m]['confusion_matrix']['rows_truth_cols_pred']}
                               for m in ('embedded_rf', 'rf_cenas_mais_bancada')}
    print(json.dumps({'contagens': counts, **brief}, indent=1, ensure_ascii=False))


if __name__ == '__main__':
    main()
