"""Treina o modelo CANDIDATO de trincas (sintéticos procedurais + cenas rotuladas).

Uso: python train_synth.py   (antes: python crack_synthesis/build_dataset.py)

Mesmos 72 atributos (features.py == CrackFeatures.java) e mesmo formato PVF1;
o modelo de produção (training/crack_forest.pvrf) NÃO é alterado.

Validação (nenhuma doadora em treino e teste ao mesmo tempo):
 (a) sintéticos: LeaveOneGroupOut pela FOTO-FONTE das doadoras (3 fotos);
     cada rodada treina com as outras 2 fotos (+ as 7 cenas) e testa na foto deixada de fora.
 (b) 7 cenas de ml_training/data (rótulos PROVISÓRIOS; 6 parecem imagens geradas, 1 declarada
     sintética): candidato em LeaveOneSceneOut (todos os sintéticos + 6 cenas -> 7ª),
     comparado às predições fora do treino do modelo atual (training/oof_predictions.csv).
 (c) crack_example.png: foto externa de pelotas reais, rótulos PROVISÓRIOS do assistente
     registrados antes de qualquer escore; nenhum dos dois modelos a viu.
Hiperparâmetros: escolhidos SOMENTE por (a). (b) e (c) só são relatados.
"""
import hashlib
import json
import struct
import sys
from pathlib import Path

import cv2
import numpy as np
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import roc_auc_score

root = Path(__file__).resolve().parent
sys.path.insert(0, str(root))
from features import extract, lighting  # noqa: E402

OUT = root / 'training_synth'
SEED = 20261024
GRID = [dict(max_depth=7, min_samples_leaf=2), dict(max_depth=9, min_samples_leaf=2), dict(max_depth=12, min_samples_leaf=3)]


def forest(**kw):
    p = dict(n_estimators=160, max_features='sqrt', class_weight='balanced', random_state=SEED, n_jobs=2)
    p.update(kw)
    return RandomForestClassifier(**p)


def write_pvrf(model, path):
    with open(path, 'wb') as f:
        f.write(struct.pack('>4siii', b'PVF1', 1, 72, len(model.estimators_)))
        for est in model.estimators_:
            t = est.tree_
            assert t.node_count <= 65535
            f.write(struct.pack('>i', t.node_count))
            for i in range(t.node_count):
                v = t.value[i, 0]
                f.write(struct.pack('>iiidd', int(t.feature[i]), int(t.children_left[i]), int(t.children_right[i]),
                                    float(t.threshold[i]), float(v[1] / v.sum())))


def read_pvrf(path):
    b = Path(path).read_bytes()
    magic, ver, nf, nt = struct.unpack('>4siii', b[:16])
    assert magic == b'PVF1' and ver == 1 and nf == 72
    o = 16
    trees = []
    for _ in range(nt):
        (n,) = struct.unpack('>i', b[o:o + 4]); o += 4
        a = np.frombuffer(b[o:o + n * 28], dtype=np.dtype([('f', '>i4'), ('l', '>i4'), ('r', '>i4'), ('t', '>f8'), ('p', '>f8')]))
        o += n * 28
        trees.append(a)
    assert o == len(b)
    return trees


def score_pvrf(trees, X):
    """Inferência idêntica ao CrackModel.java (atributo float32 <= limiar float64)."""
    X = np.asarray(X, np.float32)
    s = np.zeros(len(X))
    for t in trees:
        idx = np.zeros(len(X), int)
        for _ in range(64):
            f = t['f'][idx]
            leaf = f == -2
            if leaf.all():
                break
            go_left = X[np.arange(len(X)), np.where(leaf, 0, f)].astype(np.float64) <= t['t'][idx]
            idx = np.where(leaf, idx, np.where(go_left, t['l'][idx], t['r'][idx]))
        s += t['p'][idx]
    return s / len(trees)


def metrics(y, s, thr=0.5):
    y = np.asarray(y)
    s = np.asarray(s)
    pos, neg = y == 1, y == 0
    m = {'n': int(len(y)), 'pos': int(pos.sum()), 'neg': int(neg.sum())}
    m['auc'] = float(roc_auc_score(y, s)) if pos.any() and neg.any() else None
    m['sens@0.5'] = float((s[pos] >= thr).mean()) if pos.any() else None
    m['spec@0.5'] = float((s[neg] < thr).mean()) if neg.any() else None
    # política do app em sensibilidade 50: >=0,6 possível, <=0,4 sem indicação, resto inconclusiva
    m['app_possivel_pos'] = int((s[pos] >= .6).sum())
    m['app_sem_ind_pos'] = int((s[pos] <= .4).sum())
    m['app_possivel_neg'] = int((s[neg] >= .6).sum())
    m['app_inconcl'] = int(((s > .4) & (s < .6)).sum())
    if neg.sum() >= 10 and pos.any():
        t90 = np.quantile(s[neg], 0.9)
        m['sens@spec90'] = float((s[pos] > t90).mean())
    return m


def boot_auc(y, a, b=None, n=2000, seed=SEED):
    """IC 95% por bootstrap estratificado do AUC (e da diferença b-a, se b)."""
    rng = np.random.default_rng(seed)
    y = np.asarray(y)
    P, N = np.where(y == 1)[0], np.where(y == 0)[0]
    va, vd = [], []
    for _ in range(n):
        i = np.concatenate([rng.choice(P, len(P)), rng.choice(N, len(N))])
        aa = roc_auc_score(y[i], a[i])
        va.append(aa)
        if b is not None:
            vd.append(roc_auc_score(y[i], b[i]) - aa)
    r = {'auc_ci95': [float(np.quantile(va, .025)), float(np.quantile(va, .975))]}
    if b is not None:
        r['diff_ci95'] = [float(np.quantile(vd, .025)), float(np.quantile(vd, .975))]
    return r


def load_scenes():
    ann = json.loads((root / 'data/annotations.json').read_text())
    X, A, y, g, ids = [], [], [], [], []
    for sc in ann:
        gray = cv2.imread(str(root / 'data' / (sc['group'] + '.png')), 0)
        var = [lighting(gray, j) for j in range(6)]
        for a in sc['annotations']:
            if a['partial']:
                continue
            X.append(extract(gray, a['x'], a['y'], a['radius']))
            SAMPLES.append(('ml_training/data/' + sc['group'] + '.png', a['x'], a['y'], a['radius']))
            A.append([extract(v, a['x'], a['y'], a['radius']) for v in var])
            y.append(a['label']); g.append(sc['group']); ids.append(a['id'])
    return np.array(X), np.array(A), np.array(y), np.array(g), np.array(ids)


SAMPLES = []  # (imagem relativa à raiz do repositório, x, y, r) na ordem dos vetores de referência


def load_crack_example():
    lab = json.loads((root / 'crack_validation/crack_example_labels_provisorios.json').read_text())
    an = json.loads((root.parent / 'verification/fixtures/crack_example_analysis.json').read_text())
    gray = cv2.imread(str(root.parent / 'verification/fixtures/crack_example.png'), 0)
    X, y, ids, gate = [], [], [], []
    for c in an['circles']:
        if c['partial'] or c['id'] in lab['uncertain_ids']:
            continue
        f, _, info = extract(gray, c['x'], c['y'], c['radius'], return_marks=True)
        X.append(f); y.append(int(c['id'] in lab['positive_ids'])); ids.append(c['id'])
        SAMPLES.append(('verification/fixtures/crack_example.png', c['x'], c['y'], c['radius']))
        gate.append(bool(c['review'] or c['radius'] < 20 or info['spread'] < 10 or not 15 <= info['mean'] <= 242))
    return np.array(X), np.array(y), np.array(ids), np.array(gate)


def main():
    OUT.mkdir(exist_ok=True)
    d = np.load(OUT / 'synth_features.npz')
    Xs, ys, ps = d['X'], d['y'], d['photo']
    kinds, dists, radius = d['kind'], d['distractors'], d['radius']
    Xc, Ac, yc, gc, idc = load_scenes()
    Xe, ye, ide, gate_e = load_crack_example()
    cur = read_pvrf(root / 'training/crack_forest.pvrf')
    photos = sorted(set(ps))
    scene_aug_X = Ac.reshape(-1, 72)
    scene_aug_y = np.repeat(yc, 6)
    report = {'model_candidate': 'random_forest_synth_20261024_cand1', 'seed': SEED,
              'dataset': json.loads((OUT / 'synth_features.json').read_text()),
              'scenes': {'n': int(len(yc)), 'pos': int(yc.sum()), 'label_origin': 'assistant_visual_provisional'}}

    # (a) validação sintética por foto-fonte, para cada configuração da grade
    grid_res = []
    best = None
    for cfg in GRID:
        oof = np.zeros(len(ys))
        for ph in photos:
            te = ps == ph
            m = forest(**cfg).fit(np.vstack([Xs[~te], scene_aug_X]), np.concatenate([ys[~te], scene_aug_y]))
            oof[te] = m.predict_proba(Xs[te])[:, 1]
        auc = roc_auc_score(ys, oof)
        grid_res.append({'cfg': cfg, 'synth_oof_auc': auc})
        print('grade', cfg, 'AUC sintético fora da foto', round(auc, 4), flush=True)
        if best is None or auc > best[1] + 1e-4:
            best = (cfg, auc, oof)
    cfg, _, oof = best
    report['grid'] = grid_res
    report['chosen'] = cfg
    cur_s = score_pvrf(cur, Xs)
    per_photo = {}
    for ph in photos:
        te = ps == ph
        per_photo[ph] = {'candidato': metrics(ys[te], oof[te]), 'atual': metrics(ys[te], cur_s[te])}
    by_kind = {}
    for k in sorted(set(kinds)):
        sel = (kinds == k) | (ys == 0) if k not in ('par_limpo', 'negativo') else None
        if sel is None:
            sel = kinds == k
            by_kind[k] = {'candidato_fp@0.5': float((oof[sel] >= .5).mean()), 'atual_fp@0.5': float((cur_s[sel] >= .5).mean()), 'n': int(sel.sum())}
        else:
            sel = kinds == k
            by_kind[k] = {'candidato_sens@0.5': float((oof[sel] >= .5).mean()), 'atual_sens@0.5': float((cur_s[sel] >= .5).mean()), 'n': int(sel.sum())}
    by_dist = {}
    for dk in ['poros', 'risco', 'sombra', 'granular', 'umidade', 'relevo']:
        sel = (ys == 0) & np.array([dk in s.split('+') for s in dists])
        by_dist[dk] = {'n': int(sel.sum()), 'candidato_fp@0.5': float((oof[sel] >= .5).mean()), 'atual_fp@0.5': float((cur_s[sel] >= .5).mean())}
    bins = [(20, 30), (30, 50), (50, 80), (80, 130)]
    by_r = {}
    for lo, hi in bins:
        sel = (radius >= lo) & (radius < hi)
        by_r[f'{lo}-{hi}'] = {'candidato': metrics(ys[sel], oof[sel]), 'atual': metrics(ys[sel], cur_s[sel])}
    vis = d['visibility']
    by_v = {}
    for lo, hi in [(0.6, 1.0), (1.0, 2.0), (2.0, 99)]:
        sel = (ys == 0) | ((vis >= lo) & (vis < hi))
        by_v[f'{lo}-{hi}'] = {'pos': int(((vis >= lo) & (vis < hi) & (ys == 1)).sum()),
                              'candidato_auc': float(roc_auc_score(ys[sel], oof[sel])), 'atual_auc': float(roc_auc_score(ys[sel], cur_s[sel]))}
    report['a_sinteticos_fora_da_foto'] = {
        'nota': 'Candidato: fora do treino por foto-fonte. Atual: nunca viu sintéticos.',
        'candidato': metrics(ys, oof), 'atual': metrics(ys, cur_s), 'por_foto': per_photo,
        'por_visibilidade_positivas_vs_todos_negativos': by_v, 'por_tipo': by_kind, 'negativos_por_distrator': by_dist, 'por_raio_px': by_r}

    # (b) 7 cenas, deixa uma de fora
    oof_c = np.zeros(len(yc))
    for sc in sorted(set(gc)):
        te = gc == sc
        m = forest(**cfg).fit(np.vstack([Xs, Ac[~te].reshape(-1, 72)]), np.concatenate([ys, np.repeat(yc[~te], 6)]))
        oof_c[te] = m.predict_proba(Xc[te])[:, 1]
    cur_oof = {}
    for line in (root / 'training/oof_predictions.csv').read_text().splitlines()[1:]:
        sc, pid, lab, s, _ = line.split(';')
        cur_oof[(sc, int(pid))] = float(s)
    cur_c = np.array([cur_oof[(g, int(i))] for g, i in zip(gc, idc)])
    synth_only = forest(**cfg).fit(Xs, ys)
    so_c = synth_only.predict_proba(Xc)[:, 1]
    per_scene = {sc: {'candidato': metrics(yc[gc == sc], oof_c[gc == sc]), 'atual_oof': metrics(yc[gc == sc], cur_c[gc == sc])} for sc in sorted(set(gc))}
    report['b_cenas_rotulos_provisorios'] = {
        'nota': 'Rótulos PROVISÓRIOS do assistente; 6 cenas parecem imagens geradas e paired40 é declarada sintética. Atual = predições fora do treino da validação original (deixa uma cena de fora); candidato = deixa uma cena de fora com todos os sintéticos no treino.',
        'candidato': metrics(yc, oof_c), 'atual_oof': metrics(yc, cur_c), 'somente_sinteticos': metrics(yc, so_c),
        'bootstrap': boot_auc(yc, cur_c, oof_c), 'por_cena': per_scene}

    # modelo final candidato
    final = forest(**cfg).fit(np.vstack([Xs, scene_aug_X]), np.concatenate([ys, scene_aug_y]))
    path = OUT / 'crack_forest_candidate.pvrf'
    write_pvrf(final, path)
    trees = read_pvrf(path)
    sk = final.predict_proba(Xe)[:, 1]
    pv = score_pvrf(trees, Xe)
    report['pvrf_vs_sklearn_max_abs'] = float(np.abs(sk - pv).max())

    # (c) foto externa crack_example.png
    cur_e = score_pvrf(cur, Xe)
    so_e = synth_only.predict_proba(Xe)[:, 1]
    ok = ~gate_e
    report['c_crack_example_rotulos_provisorios'] = {
        'nota': 'Foto externa (pelotas queimadas empilhadas, procedência não Vale). Rótulos PROVISÓRIOS do assistente, fixados antes dos escores; incertas excluídas. Nenhum modelo foi treinado nela.',
        'candidato': metrics(ye, pv), 'atual': metrics(ye, cur_e), 'somente_sinteticos': metrics(ye, so_e),
        'bootstrap': boot_auc(ye, cur_e, pv),
        'apos_gates_do_app': {'n_excluidas_por_gate': int(gate_e.sum()), 'candidato': metrics(ye[ok], pv[ok]), 'atual': metrics(ye[ok], cur_e[ok])},
        'escores': [{'id': int(i), 'label': int(l), 'atual': round(float(a), 4), 'candidato': round(float(b), 4)} for i, l, a, b in zip(ide, ye, cur_e, pv)]}

    sha = hashlib.sha256(path.read_bytes()).hexdigest()
    report['sha256'] = sha
    report['final_fit'] = 'Todos os sintéticos + as 7 cenas (com 6 variantes de iluminação) depois da validação.'
    (OUT / 'validation.json').write_text(json.dumps(report, indent=1, ensure_ascii=False))
    meta = {'model': report['model_candidate'], 'status': 'CANDIDATO - não substitui o modelo de produção sem a validação da bandeja (crack_validation/)',
            'features': 72, 'format': 'PVF1', 'trees': len(final.estimators_), 'params': cfg, 'sha256': sha,
            'training': {'synthetic_samples': int(len(ys)), 'synthetic_positive': int(ys.sum()), 'real_donor_pellets': report['dataset']['donors'],
                         'donor_photos': report['dataset']['photos'], 'scenes_pellets': int(len(yc)), 'scenes_variants': 6},
            'evaluation': 'ver validation.json: (a) sintéticos fora da foto-fonte, (b) 7 cenas com rótulos provisórios, (c) crack_example.png com rótulos provisórios'}
    (OUT / 'metadata.json').write_text(json.dumps(meta, indent=1, ensure_ascii=False))
    # vetores de referência para o teste de paridade Java (CI Windows/Android)
    ref_X = np.vstack([Xc, Xe])
    ref_s = score_pvrf(trees, ref_X)
    with (OUT / 'reference_vectors_candidate.csv').open('w') as f:
        for x, s in zip(ref_X, ref_s):
            f.write(','.join(repr(float(v)) for v in x.tolist()) + ',' + repr(float(s)) + '\n')
    assert len(SAMPLES) == len(ref_X)
    cur_ref = score_pvrf(cur, ref_X)
    with (OUT / 'reference_samples_candidate.csv').open('w') as f:
        f.write('# imagem;x;y;raio;escore_atual(crack_forest.pvrf) -- mesma ordem de reference_vectors_candidate.csv\n')
        for (im, x, y, r), sc in zip(SAMPLES, cur_ref):
            f.write(f'{im};{float(x)!r};{float(y)!r};{float(r)!r};{float(sc)!r}\n')
    with (OUT / 'oof_predictions.csv').open('w') as f:
        f.write('conjunto;grupo;id;rotulo;escore_candidato;escore_atual\n')
        for g, i, l, a, b in zip(gc, idc, yc, oof_c, cur_c):
            f.write(f'cenas;{g};{i};{l};{a:.6f};{b:.6f}\n')
        for i, l, a, b in zip(ide, ye, pv, cur_e):
            f.write(f'crack_example;crack_example;{i};{l};{a:.6f};{b:.6f}\n')
    for k in ['a_sinteticos_fora_da_foto', 'b_cenas_rotulos_provisorios', 'c_crack_example_rotulos_provisorios']:
        r = report[k]
        print(k)
        for name in [n for n in ('candidato', 'atual', 'atual_oof', 'somente_sinteticos') if n in r]:
            print('  ', name, {kk: (round(v, 3) if isinstance(v, float) else v) for kk, v in r[name].items()})
        if 'bootstrap' in r:
            print('   bootstrap', r['bootstrap'])
    print('pvrf x sklearn', report['pvrf_vs_sklearn_max_abs'], 'sha', sha)


if __name__ == '__main__':
    main()
