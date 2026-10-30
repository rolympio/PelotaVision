#!/usr/bin/env python3
"""Avaliação EXTERNA de um ONNX de trincas (mesmo motor do app: OpenCV DNN) num conjunto de pelotas rotuladas.

Lê as linhas de um manifesto (labels.csv) cujo group_id começa com --prefix e que são cracked/intact,
aplica o pré-processamento do app (train_mobilenet.prep) na foto ORIGINAL e reporta AUC, matriz de confusão
no limiar 0,5 e a política do app (0,4/0,6), por grupo e no total. Nenhum treino; serve para medir o modelo
embarcado em dados que ele nunca viu.

Uso:
  python tools/cracks/eval_external_onnx.py --manifest data/pv_cracks/labels.csv --prefix webcam_pan_20261027: \
      --onnx app/src/main/assets/crack_mobilenetv3.onnx --out verification/x/external.json
"""
import argparse
import json
import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from tools.cracks import manifest as mf  # noqa: E402
from tools.cracks.train_mobilenet import prep, auc, binary, app_policy, bootstrap  # noqa: E402


def score(net, rows, cache):
    out = []
    for r in rows:
        k = r['abs_path']
        if k not in cache:
            cache[k] = cv2.imread(k, cv2.IMREAD_GRAYSCALE)
        x = prep(cache[k], float(r['cx']), float(r['cy']), float(r['r']))
        net.setInput(x[None, None])
        out.append(float(net.forward()[0, 1]))
    return np.array(out)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--manifest', type=Path, required=True)
    ap.add_argument('--prefix', required=True)
    ap.add_argument('--onnx', type=Path, action='append', required=True)
    ap.add_argument('--out', type=Path, required=True)
    a = ap.parse_args(argv)
    rows = [r for r in mf.read(a.manifest) if r['group_id'].startswith(a.prefix) and r['label'] in ('cracked', 'intact')]
    y = np.array([1 if r['label'] == 'cracked' else 0 for r in rows])
    groups = sorted({r['group_id'] for r in rows})
    res = {'prefix': a.prefix, 'n': len(rows), 'cracked': int(y.sum()), 'groups': groups, 'opencv': cv2.__version__, 'models': {}}
    preds = []
    for path in a.onnx:
        net = cv2.dnn.readNetFromONNX(str(path))
        s = score(net, rows, {})
        meta = path.with_suffix('.json')
        mid = json.loads(meta.read_text(encoding='utf-8')).get('id', path.name) if meta.exists() else path.name
        m = {'onnx': str(path), 'auc': auc(y, s), **binary(y, s), 'app_policy_0.4_0.6': app_policy(y, s), **bootstrap(y, s),
             'by_group': {}}
        for g in groups:
            sel = np.array([r['group_id'] == g for r in rows])
            m['by_group'][g] = {'n': int(sel.sum()), 'cracked': int(y[sel].sum()), 'auc': auc(y[sel], s[sel]), **binary(y[sel], s[sel])}
        res['models'][mid] = m
        preds += [(mid, r, v) for r, v in zip(rows, s)]
        print(f'{mid}: AUC {m["auc"]:.3f} {m["confusion_[[TN,FP],[FN,TP]]"]} política {m["app_policy_0.4_0.6"]}')
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(json.dumps(res, indent=1, ensure_ascii=False, allow_nan=True), encoding='utf-8')
    with a.out.with_suffix('.csv').open('w', encoding='utf-8') as f:
        f.write('model,group_id,instance_id,label,p_cracked\n')
        for mid, r, v in preds:
            f.write(f'{mid},{r["group_id"]},{r["instance_id"]},{r["label"]},{v:.6f}\n')


if __name__ == '__main__':
    main()
