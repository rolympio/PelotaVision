"""Constrói o conjunto sintético de atributos (72, mesmo features.py do app).

Uso: python build_dataset.py [--per-donor 10] [--extra-neg 5] [--seed 20261024] [--out ../training_synth/synth_features.npz]

Para cada pelota doadora real (donors.json):
  * per-donor renderizações POSITIVAS (trinca procedural, com distratores em ~50%),
    cada uma acompanhada do seu PAR LIMPO (mesma doadora, rotação, escala, distratores
    e aquisição, sem a trinca) rotulado 0 — negativo difícil pareado;
  * extra-neg NEGATIVOS adicionais com 0-2 distratores (poros, riscos, sombra de vizinha,
    granulação, umidade, relevo).
Positivas cuja trinca ficou invisível depois da aquisição (contraste dentro da máscara
abaixo de 0,6 desvio-padrão da textura) são EXCLUÍDAS, não rotuladas 0 nem 1: um
rótulo "trinca" sem evidência visual ensinaria ruído.
Grupos: foto-fonte (3) e doadora (foto#índice) para validação sem vazamento.
"""
import argparse
import json
import sys
import time
from pathlib import Path

import cv2
import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from features import extract  # noqa: E402
from synth import DISTRACTORS, render_sample  # noqa: E402

ROOT = HERE.parents[1]
VIS_MIN = 0.6


def visibility(s):
    g = cv2.cvtColor(s['image'], cv2.COLOR_BGR2GRAY).astype(np.float32)
    c = cv2.cvtColor(s['clean'], cv2.COLOR_BGR2GRAY).astype(np.float32)
    m = s['mask'] > 0.3
    if m.sum() < 3:
        return 0.0
    H = g.shape[0]
    yy, xx = np.mgrid[:H, :H]
    cx, cy = s['center']
    inside = np.hypot(xx - cx, yy - cy) < 0.78 * s['info']['target_radius']
    tex = cv2.GaussianBlur(c, (0, 0), 1.0)
    sd = float((c - tex)[inside].std()) + 2.0
    return float(np.abs(g - c)[m].mean() / sd)


def mask_in_inside(s):
    """Fração da máscara dentro do disco de 78% do raio (região que os atributos enxergam)."""
    H = s['mask'].shape[0]
    yy, xx = np.mgrid[:H, :H]
    cx, cy = s['center']
    inside = np.hypot(xx - cx, yy - cy) < 0.78 * s['info']['target_radius']
    tot = s['mask'].sum()
    return float(s['mask'][inside].sum() / tot) if tot > 0 else 0.0


def pick_distractors(rng, p_any):
    if rng.random() >= p_any:
        return []
    k = 1 if rng.random() < 0.7 else 2
    return list(rng.choice(DISTRACTORS, size=k, replace=False))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--per-donor', type=int, default=10)
    ap.add_argument('--extra-neg', type=int, default=5)
    ap.add_argument('--seed', type=int, default=20261024)
    ap.add_argument('--out', default=str(HERE.parent / 'training_synth' / 'synth_features.npz'))
    ap.add_argument('--dump', help='pasta opcional para gravar PNG de cada amostra + máscara')
    a = ap.parse_args()
    donors_doc = json.loads((HERE / 'donors.json').read_text())
    X, y, photo, donor, kind, radius, dist, vis, inside_frac, pair = [], [], [], [], [], [], [], [], [], []
    excluded = 0
    t0 = time.time()
    for p in donors_doc['photos']:
        im = cv2.imread(str(ROOT / p['image']))
        pname = Path(p['image']).stem
        for di, d in enumerate(p['donors']):
            # semente por doadora: acrescentar/remover doadoras não muda as outras
            rng = np.random.default_rng([a.seed, int(pname), di])
            for k in range(a.per_donor):
                s = render_sample(im, tuple(d), rng, True, distractors=pick_distractors(rng, 0.5))
                v = visibility(s)
                fr = mask_in_inside(s)
                pid = f'{pname}#{di}#{k}'
                if v < VIS_MIN or fr < 0.15:
                    excluded += 1
                    continue
                cx, cy, cr = s['circle']
                for img, lab in ((s['image'], 1), (s['clean'], 0)):
                    g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
                    X.append(extract(g, cx, cy, cr))
                    y.append(lab); photo.append(pname); donor.append(f'{pname}#{di}')
                    kind.append(s['info']['kind'] if lab else 'par_limpo'); radius.append(cr)
                    dist.append('+'.join(s['info']['distractors'])); vis.append(v if lab else 0.0)
                    inside_frac.append(fr if lab else 0.0); pair.append(pid)
                if a.dump:
                    Path(a.dump).mkdir(parents=True, exist_ok=True)
                    cv2.imwrite(f'{a.dump}/{pid.replace("#", "_")}_trinca.png', s['image'])
                    cv2.imwrite(f'{a.dump}/{pid.replace("#", "_")}_mascara.png', np.rint(s['mask'] * 255).astype(np.uint8))
            for k in range(a.extra_neg):
                s = render_sample(im, tuple(d), rng, False, distractors=pick_distractors(rng, 0.8))
                cx, cy, cr = s['circle']
                X.append(extract(cv2.cvtColor(s['image'], cv2.COLOR_BGR2GRAY), cx, cy, cr))
                y.append(0); photo.append(pname); donor.append(f'{pname}#{di}'); kind.append('negativo')
                radius.append(cr); dist.append('+'.join(s['info']['distractors'])); vis.append(0.0)
                inside_frac.append(0.0); pair.append(f'{pname}#{di}#n{k}')
        print(pname, len(y), 'amostras até agora', f'{time.time() - t0:.0f}s', flush=True)
    out = Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(out, X=np.array(X, np.float32), y=np.array(y), photo=np.array(photo), donor=np.array(donor),
                        kind=np.array(kind), radius=np.array(radius, np.float32), distractors=np.array(dist),
                        visibility=np.array(vis, np.float32), inside_frac=np.array(inside_frac, np.float32), pair=np.array(pair))
    summary = {'seed': a.seed, 'per_donor': a.per_donor, 'extra_neg': a.extra_neg, 'samples': len(y),
               'positive': int(np.sum(y)), 'negative': int(len(y) - np.sum(y)),
               'excluded_invisible_positives': excluded, 'visibility_min': VIS_MIN,
               'donors': sum(len(p['donors']) for p in donors_doc['photos']),
               'photos': [Path(p['image']).name for p in donors_doc['photos']], 'opencv': cv2.__version__}
    out.with_suffix('.json').write_text(json.dumps(summary, indent=1, ensure_ascii=False))
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == '__main__':
    main()
