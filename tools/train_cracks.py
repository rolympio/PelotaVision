#!/usr/bin/env python3
"""PelotaVision — comparação reproduzível de classificadores de trinca (offline, experimental).

Uso:
  python tools/train_cracks.py --manifest DADOS/labels.csv --output artifacts/crack_benchmark [--allow-synthetic-training]

Protocolo (fixado antes dos resultados; hiperparâmetros NÃO são ajustados nos dados):
  * dev: LeaveOneGroupOut sobre os group_id com split_role=dev (foto/campanha inteira fora por vez);
  * holdout: modelos ajustados em todo o dev (+ train_only), avaliados uma única vez nas linhas holdout;
  * 'uncertain' nunca entra em treino nem em métrica; sintéticos só entram no treino;
  * limiar fixo 0,5 e política do app 0,4/0,6; nenhuma escolha de limiar usa o holdout.

Modelos:
  embedded_0.5.0              floresta embarcada (app/src/main/assets/crack_forest.pvrf), sem treino.
                              No dev usa os escores fora-do-treino oficiais (ml_training/training/oof_predictions.csv).
  rf_original                 72 atributos do app + 6 variantes de iluminação + parâmetros do train.py original,
                              mesmos dados do 0.5.0 (inclui a cena sintética paired40, como o original).
  rf_original_plus_synthetic  idem + recortes sintéticos procedurais (requer --allow-synthetic-training).
  rf_hoglbp                   HOG+LBP+estatística (kit) com RF do kit (400 árvores), mesmos dados do 0.5.0.
  rf_hoglbp_plus_synthetic    idem + sintéticos.
  mlp_pixels_plus_synthetic   rede neural leve (MLP 48x48 -> 64 -> 1, sklearn) + sintéticos; substitui a CNN,
                              indisponível offline neste ambiente (sem PyTorch/TensorFlow).
"""
import argparse
import csv
import hashlib
import json
import platform
import sys
import time
import tracemalloc
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from tools.cracks import feats, manifest as mf, metrics as mt, pvrf  # noqa: E402

SEED = 20261018
SCENES_SOURCE = 'pelotavision_0.5.0_scenes'
SYNTH_SOURCE_PREFIX = 'synthetic_'
EMBEDDED = ROOT / 'app/src/main/assets/crack_forest.pvrf'
EMBEDDED_OOF = ROOT / 'ml_training/training/oof_predictions.csv'

MODELS = {
    'rf_original': dict(features=['pv72'], data='original', learner='rf_app'),
    'rf_original_plus_synthetic': dict(features=['pv72'], data='plus_synthetic', learner='rf_app'),
    'rf_hoglbp': dict(features=['hoglbp'], data='original', learner='rf_kit'),
    'rf_hoglbp_plus_synthetic': dict(features=['hoglbp'], data='plus_synthetic', learner='rf_kit'),
    'mlp_pixels_plus_synthetic': dict(features=['pixels'], data='plus_synthetic', learner='mlp'),
}


def learner(kind, seed):
    if kind == 'rf_app':  # = ml_training/train.py
        from sklearn.ensemble import RandomForestClassifier
        return RandomForestClassifier(n_estimators=160, max_depth=7, min_samples_leaf=2, max_features='sqrt',
                                      class_weight='balanced', random_state=seed, n_jobs=-1)
    if kind == 'rf_kit':  # = kit train_cracks.py
        from sklearn.ensemble import RandomForestClassifier
        return RandomForestClassifier(n_estimators=400, min_samples_leaf=2, class_weight='balanced_subsample',
                                      random_state=seed, n_jobs=-1)
    if kind == 'mlp':
        from sklearn.neural_network import MLPClassifier
        from sklearn.pipeline import make_pipeline
        from sklearn.preprocessing import StandardScaler
        return make_pipeline(StandardScaler(), MLPClassifier(hidden_layer_sizes=(64,), alpha=1e-2, max_iter=400,
                                                             early_stopping=False, random_state=seed))
    raise ValueError(kind)


def sample_weights(y, groups_aug):
    """Pesos balanceados por classe (o MLP não aceita class_weight)."""
    w = np.ones(len(y))
    for c in (0, 1):
        w[y == c] = len(y) / (2 * max(1, (y == c).sum()))
    return w


class FeatureCache:
    def __init__(self):
        self.cache = {}
        self.timing = {}

    def get(self, row, names, variant=0):
        key = (row['abs_path'], tuple(names), variant)
        if key not in self.cache:
            g = cv2.imread(row['abs_path'], cv2.IMREAD_GRAYSCALE)
            if g is None:
                raise SystemExit(f'imagem ilegível: {row["abs_path"]}')
            if variant:
                sw = int(row['source_width']) if row.get('source_width') else None
                xo = int(row['x_offset']) if row.get('x_offset') else 0
                g = feats.lit_variant(g, variant, sw, xo)
            t0 = time.perf_counter()
            self.cache[key] = feats.extract_set(names, g, float(row['cx']), float(row['cy']), float(row['r']))
            if variant == 0:
                self.timing.setdefault(tuple(names), []).append(time.perf_counter() - t0)
        return self.cache[key]


def training_matrix(rows, names, cache, augment):
    X, y = [], []
    for r in rows:
        variants = range(feats.N_LIGHTING_VARIANTS) if augment and r['source_id'] == SCENES_SOURCE else [0]
        for v in variants:
            X.append(cache.get(r, names, v))
            y.append(1 if r['label'] == 'cracked' else 0)
    return np.asarray(X, np.float32), np.asarray(y)


def pool_for(spec, train_rows):
    if spec['data'] == 'original':
        return [r for r in train_rows if r['source_id'] == SCENES_SOURCE]
    return [r for r in train_rows if r['source_id'] == SCENES_SOURCE or r['source_id'].startswith(SYNTH_SOURCE_PREFIX)]


def fit(spec, rows, cache, seed):
    X, y = training_matrix(rows, spec['features'], cache, augment=True)
    m = learner(spec['learner'], seed)
    if spec['learner'] == 'mlp':
        m.fit(X, y, mlpclassifier__sample_weight=sample_weights(y, None)) if _mlp_supports_weight() else m.fit(X, y)
    else:
        m.fit(X, y)
    return m, len(y)


def _mlp_supports_weight():
    import inspect
    from sklearn.neural_network import MLPClassifier
    return 'sample_weight' in inspect.signature(MLPClassifier.fit).parameters


def proba(model, X):
    return model.predict_proba(np.asarray(X, np.float32))[:, 1]


def embedded_oof():
    out = {}
    with EMBEDDED_OOF.open(encoding='utf-8') as f:
        for r in csv.DictReader(f, delimiter=';'):
            out[(f'scene:{r["scene"]}', r['pellet_id'])] = float(r['out_of_fold_score'])
    return out


def model_bytes(model, path):
    import joblib
    joblib.dump(model, path, compress=3)
    return path.stat().st_size


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--manifest', required=True, type=Path)
    p.add_argument('--output', default=Path('artifacts/crack_benchmark'), type=Path)
    p.add_argument('--seed', type=int, default=SEED)
    p.add_argument('--models', nargs='*', default=list(MODELS))
    p.add_argument('--allow-synthetic-training', action='store_true')
    a = p.parse_args(argv)
    rows = mf.read(a.manifest)
    sup = [r for r in rows if r['label'] in ('cracked', 'intact')]
    dev = [r for r in sup if r['split_role'] == 'dev']
    hold = [r for r in sup if r['split_role'] == 'holdout']
    train_only = [r for r in sup if r['split_role'] == 'train_only']
    if any(r['origin'] == 'synthetic' for r in dev + hold):
        raise SystemExit('sintético em avaliação: proibido')
    models = [m for m in a.models if m in MODELS]
    if not a.allow_synthetic_training:
        models = [m for m in models if MODELS[m]['data'] != 'plus_synthetic']
    groups = sorted({r['group_id'] for r in dev})
    if len(groups) < 3 or len({r['label'] for r in dev}) < 2:
        raise SystemExit('dev precisa de >=3 grupos independentes e das duas classes')
    a.output.mkdir(parents=True, exist_ok=True)
    cache = FeatureCache()
    preds = []  # (model, split, row, score)
    trees = pvrf.read_pvrf(EMBEDDED)
    oof_emb = embedded_oof()
    for r in dev:
        if (r['group_id'], r['instance_id']) in oof_emb:
            preds.append(('embedded_0.5.0', 'dev', r, oof_emb[(r['group_id'], r['instance_id'])]))
    for r in hold:
        preds.append(('embedded_0.5.0', 'holdout', r, float(pvrf.score_pvrf(trees, [cache.get(r, ['pv72'])])[0])))
    train_counts, perf = {}, {}
    for name in models:
        spec = MODELS[name]
        t0 = time.perf_counter()
        for g in groups:
            tr = pool_for(spec, [r for r in dev + train_only if r['group_id'] != g])
            te = [r for r in dev if r['group_id'] == g]
            m, _ = fit(spec, tr, cache, a.seed)
            s = proba(m, [cache.get(r, spec['features']) for r in te])
            preds += [(name, 'dev', r, float(v)) for r, v in zip(te, s)]
        cv_time = time.perf_counter() - t0
        final, n_train = fit(spec, pool_for(spec, dev + train_only), cache, a.seed)
        train_counts[name] = n_train
        Xh = [cache.get(r, spec['features']) for r in hold]
        if Xh:
            preds += [(name, 'holdout', r, float(v)) for r, v in zip(hold, proba(final, Xh))]
        # desempenho computacional (Python, CPU deste ambiente; não é o celular)
        sample = [r for r in hold + dev][:50]
        tracemalloc.start()
        t1 = time.perf_counter()
        for r in sample:
            g = cv2.imread(r['abs_path'], cv2.IMREAD_GRAYSCALE)
            proba(final, [feats.extract_set(spec['features'], g, float(r['cx']), float(r['cy']), float(r['r']))])
        per_pellet = (time.perf_counter() - t1) / len(sample)
        peak = tracemalloc.get_traced_memory()[1]
        tracemalloc.stop()
        mdir = a.output / 'models'
        mdir.mkdir(exist_ok=True)
        size = model_bytes(final, mdir / f'{name}.joblib')
        entry = {'cv_wall_s': round(cv_time, 2), 'features_plus_inference_ms_per_pellet_python': round(per_pellet * 1000, 3),
                 'python_peak_alloc_kib_during_inference': round(peak / 1024, 1), 'joblib_bytes': size,
                 'n_features': int(len(Xh[0]) if Xh else 0), 'train_rows_including_augmentation': n_train}
        if spec['features'] == ['pv72'] and spec['learner'] == 'rf_app':
            pv = mdir / f'{name}.pvrf'
            pvrf.write_pvrf(final, pv)
            Xp = np.asarray([cache.get(r, ['pv72']) for r in dev + hold], np.float32)
            entry['pvrf_bytes'] = pv.stat().st_size
            entry['pvrf_sha256'] = hashlib.sha256(pv.read_bytes()).hexdigest()
            entry['pvrf_vs_sklearn_max_abs_diff'] = float(np.abs(pvrf.score_pvrf(pvrf.read_pvrf(pv), Xp) - proba(final, Xp)).max())
            entry['java_runtime_compatible'] = True
        else:
            entry['java_runtime_compatible'] = False
        perf[name] = entry
        print(f'{name}: ok ({cv_time:.1f}s)', flush=True)
    perf['embedded_0.5.0'] = {'pvrf_bytes': EMBEDDED.stat().st_size, 'pvrf_sha256': hashlib.sha256(EMBEDDED.read_bytes()).hexdigest(),
                              'java_runtime_compatible': True,
                              'note': 'dev = escores fora-do-treino oficiais da 0.5.0 (sklearn 1.8.0); holdout = asset embarcado'}
    feat_ms = {'+'.join(k): round(1000 * float(np.median(v)), 3) for k, v in cache.timing.items()}
    # métricas
    results = {}
    for name in ['embedded_0.5.0'] + models:
        results[name] = {}
        for split in ('dev', 'holdout'):
            sel = [(r, s) for m, sp, r, s in preds if m == name and sp == split]
            if not sel:
                continue
            y = [1 if r['label'] == 'cracked' else 0 for r, _ in sel]
            sc = [s for _, s in sel]
            res = {'all': mt.binary_report(y, sc)}
            for mat in ('green', 'fired', 'unknown'):
                idx = [i for i, (r, _) in enumerate(sel) if r['material_type'] == mat]
                res[mat] = mt.binary_report([y[i] for i in idx], [sc[i] for i in idx]) if idx else {'n': 0, 'note': 'sem dados'}
            if split == 'dev':
                res['per_group'] = {}
                for g in sorted({r['group_id'] for r, _ in sel}):
                    idx = [i for i, (r, _) in enumerate(sel) if r['group_id'] == g]
                    cm = mt.binary_report([y[i] for i in idx], [sc[i] for i in idx])
                    res['per_group'][g] = {'n': cm['n'], 'positives': cm['positives'], 'false_negatives': cm['false_negatives'],
                                           'false_positives': cm['false_positives']}
            results[name][split] = res
    counts = {}
    for r in rows:
        k = f"{r['split_role']}/{r['origin']}/{r['material_type']}/{r['label']}"
        counts[k] = counts.get(k, 0) + 1
    report = {
        'experimental_only': True,
        'protocol': {'dev': f'LeaveOneGroupOut em {len(groups)} grupos: {groups}', 'holdout_groups': sorted({r["group_id"] for r in hold}),
                     'threshold': 0.5, 'app_policy': [0.4, 0.6], 'hyperparameters': 'fixos a priori; sem ajuste nos dados',
                     'synthetic': 'somente treino (train_only)'},
        'manifest_sha256': hashlib.sha256(a.manifest.read_bytes()).hexdigest(), 'manifest_counts': dict(sorted(counts.items())),
        'results': results, 'performance': perf, 'feature_extraction_median_ms_per_pellet_python': feat_ms,
        'environment': {'python': sys.version.split()[0], 'platform': platform.platform(), 'opencv': cv2.__version__,
                        'numpy': np.__version__, 'sklearn': __import__('sklearn').__version__, 'seed': a.seed},
    }
    (a.output / 'metrics.json').write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
    with (a.output / 'predictions.csv').open('w', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        w.writerow(['model', 'split', 'group_id', 'instance_id', 'image_path', 'material_type', 'origin', 'truth', 'p_cracked'])
        for m, sp, r, s in preds:
            w.writerow([m, sp, r['group_id'], r['instance_id'], r['image_path'], r['material_type'], r['origin'], r['label'], f'{s:.6f}'])
    print(json.dumps({n: {sp: {k: v['all'][k] for k in ('recall_cracked', 'precision_cracked', 'f1_cracked', 'false_negatives', 'roc_auc')}
                          for sp, v in d.items()} for n, d in results.items()}, indent=1, ensure_ascii=False))


if __name__ == '__main__':
    main()
