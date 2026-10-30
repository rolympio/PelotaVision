#!/usr/bin/env python3
"""MobileNetV3-Small como classificador neural EXPERIMENTAL de trincas (uma pelota por recorte).

Mesmo protocolo de tools/train_cracks.py, para comparação direta com o Random Forest:
  * dev: LeaveOneGroupOut sobre os group_id com split_role=dev (cena inteira fora por vez);
  * holdout: modelo final ajustado em todo o dev (+ train_only quando a variante usa sintéticos),
    avaliado UMA vez nas linhas holdout; nenhuma escolha (limiar, épocas, variante) usa o holdout;
  * 'uncertain' nunca entra em treino nem em teste; sintéticos nunca entram em validação nem teste;
  * hiperparâmetros fixos a priori (abaixo), limiar 0,5 e política do app 0,4/0,6.

Pré-processamento (idêntico ao MobileNetCrackModel.java):
  quadrado de lado ceil(2*1,15*r) centrado na pelota (pixels ORIGINAIS da foto, nunca super-resolução),
  borda refletida (BORDER_REFLECT_101), redimensionado para 128x128 com INTER_AREA, fora do círculo
  de raio 1,08*r (em escala) preenchido com 128. Entrada da rede: float32 [N,1,128,128] em 0..255.
  A normalização ImageNet, a replicação para 3 canais e o softmax estão DENTRO do ONNX
  (saída [N,2]: p_intact, p_cracked). O escore não é probabilidade calibrada.

Pesos de partida: torchvision mobilenet_v3_small IMAGENET1K_V1 (código BSD-3; pesos treinados no
ImageNet, cujos termos de uso são para pesquisa não comercial: ver LICENCAS.md antes de distribuir).
"""
import argparse
import csv
import hashlib
import json
import math
import os
import platform
import random
import sys
import time
from pathlib import Path

import cv2
import numpy as np

SIZE = 128
HALF = 1.15
MASK = 1.08
FILL = 128.0
SEED = 20261025
EPOCHS = 10
BATCH = 32
LR = 6e-4
WD = 1e-4
SYNTH_WEIGHT = 0.5
VARIANTS = {'mnv3_real_only': False, 'mnv3_plus_synthetic': True}


def prep(gray, cx, cy, r):
    """gray uint8 (recorte ou foto inteira); (cx, cy, r) no mesmo sistema. Devolve float32 128x128 em 0..255."""
    h = HALF * r
    side = max(2, int(math.ceil(2 * h)))
    x0, y0 = int(math.floor(cx - h)), int(math.floor(cy - h))
    H, W = gray.shape[:2]
    pl, pt = max(0, -x0), max(0, -y0)
    pr, pb = max(0, x0 + side - W), max(0, y0 + side - H)
    src = gray
    if pl or pt or pr or pb:
        src = cv2.copyMakeBorder(gray, pt, pb, pl, pr, cv2.BORDER_REFLECT_101)
    c = src[y0 + pt:y0 + pt + side, x0 + pl:x0 + pl + side]
    out = cv2.resize(c, (SIZE, SIZE), interpolation=cv2.INTER_AREA).astype(np.float32)
    k = SIZE / side
    ccx, ccy, rr = (cx - x0) * k - 0.5, (cy - y0) * k - 0.5, MASK * r * k
    yy, xx = np.mgrid[0:SIZE, 0:SIZE].astype(np.float32)
    out[(xx - ccx) ** 2 + (yy - ccy) ** 2 > rr * rr] = FILL
    return out


def load_rows(manifest):
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from tools.cracks import manifest as mf
    rows = mf.read(manifest)
    return [r for r in rows if r['label'] in ('cracked', 'intact')]


def tensors(rows, cache):
    xs = []
    for r in rows:
        k = r['abs_path']
        if k not in cache:
            g = cv2.imread(k, cv2.IMREAD_GRAYSCALE)
            cache[k] = prep(g, float(r['cx']), float(r['cy']), float(r['r']))
        xs.append(cache[k])
    return np.stack(xs)[:, None]


def augment(x, rng):
    """Aumentos fisicamente plausíveis: rotação 90°, espelho, ganho/offset/gama leves, desfoque leve."""
    out = np.empty_like(x)
    for i in range(len(x)):
        a = np.rot90(x[i, 0], rng.integers(4))
        if rng.random() < 0.5:
            a = a[:, ::-1]
        a = a.astype(np.float32)
        mask = a != FILL
        v = a / 255.0
        v = np.clip(v, 0, 1) ** rng.uniform(0.85, 1.18)
        v = v * rng.uniform(0.85, 1.15) + rng.uniform(-0.06, 0.06)
        v = np.clip(v * 255.0, 0, 255)
        if rng.random() < 0.25:
            v = cv2.GaussianBlur(v, (3, 3), rng.uniform(0.4, 0.9))
        v[~mask] = FILL
        out[i, 0] = v
    return out


def build_net(torch, pretrained=True):
    import torchvision
    nn = torch.nn
    w = torchvision.models.MobileNet_V3_Small_Weights.IMAGENET1K_V1 if pretrained else None
    base = torchvision.models.mobilenet_v3_small(weights=w)
    base.classifier[3] = nn.Linear(base.classifier[3].in_features, 2)

    class Wrapped(nn.Module):
        def __init__(self):
            super().__init__()
            self.net = base
            self.register_buffer('mean', torch.tensor([0.485, 0.456, 0.406]).view(1, 3, 1, 1) * 255)
            self.register_buffer('std', torch.tensor([0.229, 0.224, 0.225]).view(1, 3, 1, 1) * 255)

        def logits(self, x):
            return self.net((x.repeat(1, 3, 1, 1) - self.mean) / self.std)

        def forward(self, x):
            return torch.softmax(self.logits(x), dim=1)
    return Wrapped()


INIT_ONNX = None  # 0.16.0: ponto de partida = ONNX embarcado (BN dobrada), em vez dos pesos ImageNet


def load_from_onnx(torch, model, path):
    """Copia os pesos de um ONNX exportado por este script (Conv+BN dobradas) para a rede torchvision.

    Cada conv seguida de BN recebe o peso dobrado; a BN vira identidade + viés (média 0, variância 1,
    escala sqrt(1+eps)) e fica CONGELADA no treino (estatísticas fixas), então a rede começa exatamente igual
    ao ONNX. Confere a igualdade numa entrada aleatória (erro máximo < 1e-4) antes de devolver."""
    import onnx
    from onnx import numpy_helper
    nn = torch.nn
    g = onnx.load(str(path)).graph
    init = {i.name: numpy_helper.to_array(i) for i in g.initializer}
    convs = [n for n in g.node if n.op_type == 'Conv']
    gemms = [n for n in g.node if n.op_type == 'Gemm']
    pairs = []
    for mod in model.net.modules():
        if isinstance(mod, nn.Sequential) and len(mod) >= 2 and isinstance(mod[0], nn.Conv2d) and isinstance(mod[1], nn.BatchNorm2d):
            pairs.append((mod[0], mod[1]))
    conv_mods = [m for m in model.net.modules() if isinstance(m, nn.Conv2d)]
    bn_of = {id(c): b for c, b in pairs}
    assert len(conv_mods) == len(convs), (len(conv_mods), len(convs))
    with torch.no_grad():
        for mod, node in zip(conv_mods, convs):
            W = init[node.input[1]]
            B = init[node.input[2]] if len(node.input) > 2 else np.zeros(W.shape[0], np.float32)
            assert tuple(mod.weight.shape) == W.shape, (mod, W.shape)
            mod.weight.copy_(torch.from_numpy(W))
            bn = bn_of.get(id(mod))
            if bn is not None:
                bn.running_mean.zero_(); bn.running_var.fill_(1.0)
                bn.weight.fill_(math.sqrt(1.0 + bn.eps)); bn.bias.copy_(torch.from_numpy(B))
            else:
                mod.bias.copy_(torch.from_numpy(B))
        lins = [m for m in model.net.modules() if isinstance(m, nn.Linear)]
        assert len(lins) == len(gemms)
        for mod, node in zip(lins, gemms):
            W, B = init[node.input[1]], init[node.input[2]]
            trans = next((a.i for a in node.attribute if a.name == 'transB'), 0)
            W = W if trans else W.T
            assert tuple(mod.weight.shape) == W.shape
            mod.weight.copy_(torch.from_numpy(np.ascontiguousarray(W))); mod.bias.copy_(torch.from_numpy(B))
    model.eval()
    import onnxruntime as ort
    x = np.random.default_rng(0).uniform(0, 255, (2, 1, SIZE, SIZE)).astype(np.float32)
    sess = ort.InferenceSession(str(path), providers=['CPUExecutionProvider'])
    ref = np.concatenate([sess.run(None, {'input': x[i:i + 1]})[0] for i in range(2)])
    with torch.no_grad():
        got = model(torch.from_numpy(x)).numpy()
    err = float(np.abs(ref - got).max())
    assert err < 1e-4, f'pesos do ONNX não reproduzem a saída (erro {err})'
    return err


def freeze_bn(torch, model):
    for m in model.modules():
        if isinstance(m, torch.nn.BatchNorm2d):
            m.eval()


def fit(torch, X, y, w, seed, log, tag):
    torch.manual_seed(seed)
    rng = np.random.default_rng(seed)
    model = build_net(torch, pretrained=INIT_ONNX is None)
    if INIT_ONNX is not None:
        load_from_onnx(torch, model, INIT_ONNX)
    pos = max(1.0, float((w * (y == 1)).sum()))
    neg = max(1.0, float((w * (y == 0)).sum()))
    cw = torch.tensor([(pos + neg) / (2 * neg), (pos + neg) / (2 * pos)], dtype=torch.float32)
    opt = torch.optim.AdamW(model.parameters(), lr=LR, weight_decay=WD)
    steps = EPOCHS * math.ceil(len(X) / BATCH)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=LR, total_steps=steps, pct_start=0.15)
    lossf = torch.nn.CrossEntropyLoss(weight=cw, reduction='none')
    t0 = time.time()
    for ep in range(EPOCHS):
        model.train()
        if INIT_ONNX is not None:
            freeze_bn(torch, model)
        idx = rng.permutation(len(X))
        tot = 0.0
        for s in range(0, len(idx), BATCH):
            b = idx[s:s + BATCH]
            if len(b) < 2:
                continue
            xb = torch.from_numpy(augment(X[b], rng))
            yb = torch.from_numpy(y[b]).long()
            wb = torch.from_numpy(w[b]).float()
            loss = (lossf(model.logits(xb), yb) * wb).sum() / wb.sum()
            opt.zero_grad()
            loss.backward()
            opt.step()
            sched.step()
            tot += float(loss) * len(b)
        log(f'{tag} epoca {ep + 1}/{EPOCHS} perda {tot / len(X):.4f} ({time.time() - t0:.0f} s)')
    model.eval()
    return model


def predict(torch, model, X):
    with torch.no_grad():
        out = []
        for s in range(0, len(X), 64):
            out.append(model(torch.from_numpy(X[s:s + 64])).numpy()[:, 1])
    return np.concatenate(out) if out else np.zeros(0)


def auc(y, s):
    y, s = np.asarray(y), np.asarray(s)
    p, n = s[y == 1], s[y == 0]
    if len(p) == 0 or len(n) == 0:
        return float('nan')
    return float(((p[:, None] > n[None]).sum() + 0.5 * (p[:, None] == n[None]).sum()) / (len(p) * len(n)))


def binary(y, s, thr=0.5):
    y = np.asarray(y)
    yp = (np.asarray(s) >= thr).astype(int)
    tp, fp = int(((yp == 1) & (y == 1)).sum()), int(((yp == 1) & (y == 0)).sum())
    fn, tn = int(((yp == 0) & (y == 1)).sum()), int(((yp == 0) & (y == 0)).sum())
    pr = tp / (tp + fp) if tp + fp else float('nan')
    rc = tp / (tp + fn) if tp + fn else float('nan')
    f1 = 2 * pr * rc / (pr + rc) if tp and (pr + rc) else (0.0 if tp + fn else float('nan'))
    return {'confusion_[[TN,FP],[FN,TP]]': [[tn, fp], [fn, tp]], 'precision': pr, 'recall': rc, 'f1': f1,
            'false_positives': fp, 'false_negatives': fn}


def app_policy(y, s):
    y, s = np.asarray(y), np.asarray(s)
    return {'possible_cracked': int(((s >= 0.6) & (y == 1)).sum()), 'possible_intact': int(((s >= 0.6) & (y == 0)).sum()),
            'inconclusive': int(((s > 0.4) & (s < 0.6)).sum()),
            'no_indication_cracked': int(((s <= 0.4) & (y == 1)).sum())}


def bootstrap(y, s, other=None, n=2000, seed=SEED):
    rng = np.random.default_rng(seed)
    y, s = np.asarray(y), np.asarray(s)
    vals, diffs = [], []
    for _ in range(n):
        i = rng.integers(0, len(y), len(y))
        a = auc(y[i], s[i])
        if math.isnan(a):
            continue
        vals.append(a)
        if other is not None:
            diffs.append(a - auc(y[i], np.asarray(other)[i]))
    q = lambda v: [float(np.percentile(v, 2.5)), float(np.percentile(v, 97.5))] if v else None
    return {'auc_ci95': q(vals), 'delta_auc_vs_rf_ci95': q(diffs) if other is not None else None,
            'resampling': 'pelotas (não fotos): IC otimista com 1 foto'}


def by_size(rows, y, s):
    r = np.array([float(x['r']) for x in rows])
    if len(r) < 6:
        return {}
    t = np.percentile(r, [33.3, 66.7])
    out = {}
    for name, m in (('pequenas', r <= t[0]), ('medias', (r > t[0]) & (r <= t[1])), ('grandes', r > t[1])):
        yy, ss = np.asarray(y)[m], np.asarray(s)[m]
        out[name] = {'raio_px': [float(r[m].min()), float(r[m].max())], 'n': int(m.sum()), 'trincadas': int(yy.sum()),
                     'auc': auc(yy, ss), **binary(yy, ss)}
    return out


def export_onnx(torch, model, path):
    dummy = torch.zeros(1, 1, SIZE, SIZE)
    torch.onnx.export(model, dummy, str(path), input_names=['input'], output_names=['prob'], opset_version=13,
                      dynamic_axes=None, do_constant_folding=True)


def main(argv=None):
    global EPOCHS, INIT_ONNX, LR
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--manifest', type=Path, required=True)
    p.add_argument('--rf-predictions', type=Path, default=Path('verification/cracks-benchmark-20261025/predictions.csv'))
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--skip-dev', action='store_true', help='só o ajuste final (teste rápido)')
    p.add_argument('--epochs', type=int, default=EPOCHS)
    p.add_argument('--variants', default=','.join(VARIANTS), help='subconjunto de ' + ','.join(VARIANTS))
    p.add_argument('--lobo-prefix', default='', help='deixa de fora UM grupo dev com este prefixo por vez (os demais dev sempre treinam)')
    p.add_argument('--lobo-limit', type=int, default=0, help='teste rápido: só os N primeiros grupos do LOBO')
    p.add_argument('--model-id', default='mobilenetv3_small_cracks_20261025_v1')
    p.add_argument('--init-onnx', type=Path, default=None, help='parte do ONNX embarcado (BN congelada) em vez do ImageNet')
    p.add_argument('--lr', type=float, default=LR)
    p.add_argument('--holdout-example', type=Path, default=Path('tools/cracks/labels/holdout_crack_example.json'))
    a = p.parse_args(argv)
    EPOCHS = a.epochs
    INIT_ONNX = a.init_onnx
    LR = a.lr
    variants = {k: VARIANTS[k] for k in a.variants.split(',') if k in VARIANTS}
    assert variants, 'nenhuma variante válida'
    import torch
    torch.set_num_threads(max(1, os.cpu_count() or 1))
    random.seed(SEED)
    np.random.seed(SEED)
    a.out.mkdir(parents=True, exist_ok=True)
    logf = (a.out / 'training_log.txt').open('w', encoding='utf-8')

    def log(msg):
        print(msg, flush=True)
        logf.write(msg + '\n')
        logf.flush()

    rows = load_rows(a.manifest)
    dev = [r for r in rows if r['split_role'] == 'dev']
    hold = [r for r in rows if r['split_role'] == 'holdout']
    extra = [r for r in rows if r['split_role'] == 'train_only']
    assert not any(r['origin'] == 'synthetic' for r in dev + hold), 'sintético fora de train_only'
    assert not ({r['group_id'] for r in dev} & {r['group_id'] for r in hold}), 'vazamento dev/holdout'
    groups = sorted({r['group_id'] for r in dev})
    log(f'dev {len(dev)} ({sum(r["label"] == "cracked" for r in dev)} trincadas) em {groups}; '
        f'holdout {len(hold)} ({sum(r["label"] == "cracked" for r in hold)} trincadas); train_only {len(extra)}')
    cache = {}
    lab = lambda rs: np.array([1 if r['label'] == 'cracked' else 0 for r in rs])
    wts = lambda rs: np.array([SYNTH_WEIGHT if r['origin'] == 'synthetic' else 1.0 for r in rs], dtype=np.float32)

    rf = {}
    if a.rf_predictions.exists():
        with a.rf_predictions.open(encoding='utf-8') as f:
            for r in csv.DictReader(f):
                rf[(r['model'], r['split'], r['group_id'], r['instance_id'])] = float(r['p_cracked'])

    results, preds = {}, []
    for name, synth in variants.items():
        res = {'uses_synthetic_training': synth}
        if a.lobo_prefix:
            # 2027-10-27: rajadas das fotos de bancada; a pelota física inteira (todas as escalas) sai junta.
            prefixes = tuple(x for x in a.lobo_prefix.split(',') if x)
            lg = sorted({r['group_id'] for r in dev if r['group_id'].startswith(prefixes)})
            if a.lobo_limit:
                lg = lg[:a.lobo_limit]
            idx = [i for i, r in enumerate(dev) if r['group_id'] in set(lg)]
            oof = {}
            for gi, g in enumerate(lg):
                tr = [r for r in dev if r['group_id'] != g] + (extra if synth else [])
                te = [i for i in idx if dev[i]['group_id'] == g]
                m = fit(torch, tensors(tr, cache), lab(tr), wts(tr), SEED + 500 + gi, log, f'{name} lobo-{g}')
                for i, v in zip(te, predict(torch, m, tensors([dev[i] for i in te], cache))):
                    oof[i] = float(v)
            bl = {}
            srcs = sorted({dev[i]['source_id'] for i in idx})
            for sc, sel in [(x, [i for i in idx if dev[i]['source_id'] == x]) for x in srcs] + [('ambas', idx)]:
                yy = lab([dev[i] for i in sel])
                ss = np.array([oof[i] for i in sel])
                bl[sc] = {'n': len(sel), 'cracked': int(yy.sum()), 'groups': len({dev[i]['group_id'] for i in sel}),
                          'auc': auc(yy, ss), **binary(yy, ss), 'app_policy_0.4_0.6': app_policy(yy, ss)}
            res['bench_lobo'] = bl
            preds += [(name, 'bench_lobo', dev[i], v) for i, v in oof.items()]
            log(f'{name}: LOBO ' + ' · '.join(f'{k} AUC {v["auc"]:.3f} n={v["n"]}' for k, v in bl.items()) + f' {bl["ambas"]["confusion_[[TN,FP],[FN,TP]]"]}')
        if not a.skip_dev:
            oof = np.zeros(len(dev))
            for gi, g in enumerate(groups):
                tr = [r for r in dev if r['group_id'] != g] + (extra if synth else [])
                te = [i for i, r in enumerate(dev) if r['group_id'] == g]
                m = fit(torch, tensors(tr, cache), lab(tr), wts(tr), SEED + gi, log, f'{name} dev-{g}')
                oof[te] = predict(torch, m, tensors([dev[i] for i in te], cache))
            yd = lab(dev)
            res['dev'] = {'auc': auc(yd, oof), **binary(yd, oof), 'app_policy_0.4_0.6': app_policy(yd, oof)}
            preds += [(name, 'dev', r, float(v)) for r, v in zip(dev, oof)]
        tr = dev + (extra if synth else [])
        t0 = time.time()
        m = fit(torch, tensors(tr, cache), lab(tr), wts(tr), SEED + 100, log, f'{name} final')
        res['train_seconds_final'] = round(time.time() - t0, 1)
        yh = lab(hold)
        sh = predict(torch, m, tensors(hold, cache))
        rf_h = np.array([rf.get(('embedded_0.5.0', 'holdout', r['group_id'], r['instance_id']), np.nan) for r in hold])
        res['holdout'] = {'n': len(hold), 'cracked': int(yh.sum()), 'auc': auc(yh, sh), **binary(yh, sh),
                          'app_policy_0.4_0.6': app_policy(yh, sh), 'by_size_terciles': by_size(hold, yh, sh),
                          **bootstrap(yh, sh, rf_h if not np.isnan(rf_h).any() else None)}
        preds += [(name, 'holdout', r, float(v)) for r, v in zip(hold, sh)]
        onnx = a.out / f'{name}.onnx'
        export_onnx(torch, m, onnx)
        res['onnx_bytes'] = onnx.stat().st_size
        res['onnx_sha256'] = hashlib.sha256(onnx.read_bytes()).hexdigest()
        res['parameters'] = int(sum(p.numel() for p in m.parameters()))
        results[name] = res
        log(f'{name}: holdout AUC {res["holdout"]["auc"]:.3f} {res["holdout"]["confusion_[[TN,FP],[FN,TP]]"]}')

    # Referência do RF no mesmo holdout (asset embarcado), para leitura lado a lado.
    yh = lab(hold)
    rf_h = np.array([rf.get(('embedded_0.5.0', 'holdout', r['group_id'], r['instance_id']), np.nan) for r in hold])
    if not np.isnan(rf_h).any():
        results['embedded_rf_0.5.0_reference'] = {'holdout': {'auc': auc(yh, rf_h), **binary(yh, rf_h),
                                                              'app_policy_0.4_0.6': app_policy(yh, rf_h),
                                                              'by_size_terciles': by_size(hold, yh, rf_h), **bootstrap(yh, rf_h)}}

    with (a.out / 'predictions.csv').open('w', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        w.writerow(['model', 'split', 'group_id', 'instance_id', 'image_path', 'r', 'truth', 'p_cracked'])
        for name, sp, r, v in preds:
            w.writerow([name, sp, r['group_id'], r['instance_id'], r['image_path'], r['r'], r['label'], f'{v:.6f}'])

    # Paridade e latência: onnxruntime e OpenCV DNN (mesmo motor do app), e referência para o teste Java.
    ref = []
    timing = {}
    for name in variants:
        onnx = a.out / f'{name}.onnx'
        Xh = tensors(hold, cache)
        entry = {}
        try:
            import onnxruntime as ort
            s = ort.InferenceSession(str(onnx), providers=['CPUExecutionProvider'])
            t0 = time.perf_counter()
            o = np.concatenate([s.run(None, {'input': Xh[i:i + 1]})[0] for i in range(len(Xh))])[:, 1]
            entry['onnxruntime_ms_per_pellet'] = (time.perf_counter() - t0) * 1000 / len(Xh)
            entry['onnxruntime_max_abs_diff_vs_torch'] = float(np.abs(o - np.array(
                [v for n, sp, r, v in preds if n == name and sp == 'holdout'])).max())
        except Exception as e:  # noqa: BLE001
            entry['onnxruntime_error'] = repr(e)
        try:
            net = cv2.dnn.readNetFromONNX(str(onnx))
            t0 = time.perf_counter()
            o = []
            for i in range(len(Xh)):
                net.setInput(Xh[i:i + 1])
                o.append(float(net.forward()[0, 1]))
            entry['opencv_dnn_ms_per_pellet'] = (time.perf_counter() - t0) * 1000 / len(Xh)
            entry['opencv_dnn_max_abs_diff_vs_torch'] = float(np.abs(np.array(o) - np.array(
                [v for n, sp, r, v in preds if n == name and sp == 'holdout'])).max())
            entry['opencv_version'] = cv2.__version__
        except Exception as e:  # noqa: BLE001
            entry['opencv_dnn_error'] = repr(e)
        timing[name] = entry

    # Referência para o teste de paridade Java (foto inteira + círculo da anotação).
    spec = json.loads(a.holdout_example.read_text(encoding='utf-8'))
    photo = cv2.imread(spec['image'], cv2.IMREAD_GRAYSCALE)
    circles = json.loads(Path(spec['circles']).read_text(encoding='utf-8'))
    hold_ids = [r['instance_id'] for r in hold][:12]
    final = [v for v in variants if v.endswith('synthetic')][0] if any(v.endswith('synthetic') for v in variants) else list(variants)[0]
    net = cv2.dnn.readNetFromONNX(str(a.out / f'{final}.onnx'))
    items = circles.get('circles') or circles.get('detections') or circles
    by_id = {}
    for i, c in enumerate(items):
        cid = str(c.get('id', i + 1))
        by_id[cid] = c
    for iid in hold_ids:
        c = by_id.get(str(iid))
        if c is None:
            continue
        x = prep(photo, float(c['x']), float(c['y']), float(c.get('r', c.get('radius'))))
        net.setInput(x[None, None])
        ref.append({'id': iid, 'x': float(c['x']), 'y': float(c['y']), 'r': float(c.get('r', c.get('radius'))),
                    'input_sum': float(x.astype(np.float64).sum()), 'p_cracked': float(net.forward()[0, 1])})

    import torch as _t
    import torchvision as _tv
    summary = {
        'protocol': {'dev': f'LeaveOneGroupOut em {len(groups)} grupos: {groups}', 'holdout': sorted({r['group_id'] for r in hold}),
                     'epochs': EPOCHS, 'batch': BATCH, 'lr': LR, 'weight_decay': WD, 'synthetic_weight': SYNTH_WEIGHT,
                     'input': f'{SIZE}x{SIZE} cinza 0..255, lado {2 * HALF}r, máscara {MASK}r preenchida com {FILL}',
                     'pretrained': (f'ONNX {INIT_ONNX} (BN dobrada e congelada)' if INIT_ONNX else 'torchvision mobilenet_v3_small IMAGENET1K_V1'), 'seed': SEED,
                     'threshold': 0.5, 'app_policy': [0.4, 0.6], 'holdout_used_for_selection': False},
        'results': results, 'inference_cpu_runner': timing, 'java_parity_reference': ref,
        'environment': {'python': sys.version.split()[0], 'platform': platform.platform(), 'torch': _t.__version__,
                        'torchvision': _tv.__version__, 'opencv': cv2.__version__, 'numpy': np.__version__,
                        'cpus': os.cpu_count()},
    }
    (a.out / 'metrics.json').write_text(json.dumps(summary, indent=1, ensure_ascii=False, allow_nan=True), encoding='utf-8')
    fr = results[final]
    nreal = sum(1 for r in dev if r['label'] == 'cracked')
    meta = {'id': a.model_id, 'file': 'crack_mobilenetv3.onnx', 'sha256': fr['onnx_sha256'],
            'variant': final, 'input_size': SIZE, 'crop_half_radius': HALF, 'mask_radius': MASK, 'fill': FILL,
            'output': 'prob[0,1] = p_cracked (softmax, NÃO calibrado)',
            'status': 'experimental_not_validated',
            'note': (f'Treinado com {nreal} recortes reais de trinca (inclui fotos de bancada de 26/10/2026 em duas escalas) + sintéticos; '
                     'holdout de 1 foto (7 trincas) e validação por rajada nas fotos de bancada. Escore não calibrado.'
                     if a.lobo_prefix else 'Treinado com 29 trincas reais provisórias + sintéticos; testado em 1 foto (7 trincas). '
                     'Não substitui o Random Forest; só para comparação.'),
            'bench_lobo': fr.get('bench_lobo'),
            'holdout_auc': fr['holdout']['auc'], 'holdout_auc_ci95': fr['holdout']['auc_ci95'],
            'parity_reference': ref}
    (a.out / 'crack_mobilenetv3.json').write_text(json.dumps(meta, indent=1, ensure_ascii=False, allow_nan=True), encoding='utf-8')
    log('ok')


if __name__ == '__main__':
    main()
