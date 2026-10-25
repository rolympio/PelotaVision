#!/usr/bin/env python3
"""IC 95% de AUC e de ΔAUC (modelo − referência) por bootstrap estratificado de pelotas.

Uso: python tools/cracks/bootstrap_auc.py --predictions artifacts/crack_benchmark/predictions.csv --split holdout

Limitação: reamostra pelotas, não fotos. Com uma única foto de holdout, pelotas da mesma
foto são correlacionadas e o IC fica otimista; não substitui o critério da bandeja
(>=15 trincadas e >=30 íntegras em fotos independentes).
"""
import argparse
import csv
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from tools.cracks.metrics import _auc  # noqa: E402


def main(argv=None):
    p = argparse.ArgumentParser()
    p.add_argument('--predictions', type=Path, required=True)
    p.add_argument('--split', default='holdout')
    p.add_argument('--reference', default='embedded_0.5.0')
    p.add_argument('--n', type=int, default=5000)
    p.add_argument('--seed', type=int, default=0)
    p.add_argument('--out', type=Path)
    a = p.parse_args(argv)
    P = {}
    with a.predictions.open(encoding='utf-8') as f:
        for r in csv.DictReader(f):
            if r['split'] == a.split:
                P.setdefault(r['model'], {})[(r['group_id'], r['instance_id'])] = (r['truth'] == 'cracked', float(r['p_cracked']))
    keys = sorted(P[a.reference])
    y = np.array([P[a.reference][k][0] for k in keys], int)
    base = np.array([P[a.reference][k][1] for k in keys])
    pos, neg = np.where(y == 1)[0], np.where(y == 0)[0]
    rng = np.random.default_rng(a.seed)
    boots = [np.concatenate([rng.choice(pos, len(pos)), rng.choice(neg, len(neg))]) for _ in range(a.n)]
    out = {'split': a.split, 'reference': a.reference, 'n': int(len(y)), 'positives': int(y.sum()), 'resamples': a.n,
           'unit': 'pelota (não foto)', 'models': {}}
    for m, d in P.items():
        s = np.array([d[k][1] for k in keys])
        au = np.array([_auc(y[i], s[i]) for i in boots])
        dd = au - np.array([_auc(y[i], base[i]) for i in boots])
        out['models'][m] = {'auc': _auc(y, s), 'auc_ci95': [float(np.percentile(au, 2.5)), float(np.percentile(au, 97.5))],
                            'delta_auc_vs_reference_ci95': [float(np.percentile(dd, 2.5)), float(np.percentile(dd, 97.5))]}
    txt = json.dumps(out, indent=2, ensure_ascii=False)
    if a.out:
        a.out.write_text(txt, encoding='utf-8')
    print(txt)


if __name__ == '__main__':
    main()
