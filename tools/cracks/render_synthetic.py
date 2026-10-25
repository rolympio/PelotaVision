#!/usr/bin/env python3
"""Gera recortes SINTÉTICOS de trinca (PNG + manifesto) com o gerador procedural do PelotaVision.

O gerador (synth.py, donors.json, build_dataset.py) vive na branch `feature/trincas-sintese`
(ml_training/crack_synthesis). Extraia-o sem misturar as branches:

  git archive origin/feature/trincas-sintese ml_training | tar -x -C /tmp/trincas
  python tools/cracks/render_synthetic.py --synth-dir /tmp/trincas/ml_training/crack_synthesis --out DADOS/synthetic

Todas as linhas saem com origin=synthetic e split_role=train_only: nunca entram em validação nem teste.
Para cada amostra positiva o PAR LIMPO (mesma doadora/aquisição, sem trinca) é gravado como intact.
Positivas cuja trinca ficou invisível após a aquisição são descartadas (mesma regra do build_dataset.py).
--exclude-photo remove fotos doadoras de licença/procedência duvidosa (padrão: 1000411647, que tem
marca d'água de terceiro "Jindal Steel & Power").
"""
import argparse
import csv
import hashlib
import importlib
import json
import sys
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[2]


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--synth-dir', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--per-donor', type=int, default=6)
    p.add_argument('--extra-neg', type=int, default=3)
    p.add_argument('--seed', type=int, default=20261024)
    p.add_argument('--exclude-photo', nargs='*', default=['1000411647'])
    p.add_argument('--material-type', default='fired', help='material das fotos doadoras (inspeção visual)')
    a = p.parse_args(argv)
    sys.path.insert(0, str(a.synth_dir))
    sys.path.insert(0, str(a.synth_dir.parent))
    synth = importlib.import_module('synth')
    bd = importlib.import_module('build_dataset')
    donors_doc = json.loads((a.synth_dir / 'donors.json').read_text(encoding='utf-8'))
    a.out.mkdir(parents=True, exist_ok=True)
    rows, excluded_invisible, skipped_photos = [], 0, []
    for ph in donors_doc['photos']:
        pname = Path(ph['image']).stem
        if pname in a.exclude_photo:
            skipped_photos.append(pname)
            continue
        img_path = ROOT / ph['image']
        im = cv2.imread(str(img_path))
        src_sha = hashlib.sha256(img_path.read_bytes()).hexdigest()
        for di, d in enumerate(ph['donors']):
            rng = np.random.default_rng([a.seed, int(pname), di])

            def emit(img, circle, label, pid, kind):
                g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
                rel = Path('crops') / pname / f'{pid}.png'
                (a.out / rel).parent.mkdir(parents=True, exist_ok=True)
                cv2.imwrite(str(a.out / rel), g)
                cx, cy, cr = circle
                rows.append({'image_path': rel.as_posix(), 'label': label, 'group_id': f'synthetic:{pname}',
                             'material_type': a.material_type, 'origin': 'synthetic', 'cx': f'{cx:.6f}', 'cy': f'{cy:.6f}',
                             'r': f'{cr:.6f}', 'source_id': 'synthetic_procedural_trincas_sintese',
                             'source_image': ph['image'], 'source_sha256': src_sha, 'instance_id': f'{pid}:{kind}',
                             'label_origin': 'synthetic_render', 'split_role': 'train_only', 'source_width': '',
                             'x_offset': '', 'license_status': 'internal_unverified'})

            for k in range(a.per_donor):
                s = synth.render_sample(im, tuple(d), rng, True, distractors=bd.pick_distractors(rng, 0.5))
                if bd.visibility(s) < bd.VIS_MIN or bd.mask_in_inside(s) < 0.15:
                    excluded_invisible += 1
                    continue
                pid = f'{pname}_{di:03d}_{k:02d}'
                emit(s['image'], s['circle'], 'cracked', pid + '_trinca', s['info']['kind'])
                emit(s['clean'], s['circle'], 'intact', pid + '_limpo', 'par_limpo')
            for k in range(a.extra_neg):
                s = synth.render_sample(im, tuple(d), rng, False, distractors=bd.pick_distractors(rng, 0.8))
                emit(s['image'], s['circle'], 'intact', f'{pname}_{di:03d}_n{k:02d}', 'negativo')
    cols = list(rows[0].keys())
    with (a.out / 'synthetic.csv').open('w', newline='', encoding='utf-8') as f:
        w = csv.DictWriter(f, fieldnames=cols)
        w.writeheader()
        w.writerows(rows)
    info = {'samples': len(rows), 'cracked': sum(r['label'] == 'cracked' for r in rows),
            'intact': sum(r['label'] == 'intact' for r in rows), 'excluded_invisible_positives': excluded_invisible,
            'skipped_photos': skipped_photos, 'seed': a.seed, 'per_donor': a.per_donor, 'extra_neg': a.extra_neg,
            'opencv': cv2.__version__}
    (a.out / 'synthetic_summary.json').write_text(json.dumps(info, indent=2, ensure_ascii=False), encoding='utf-8')
    print(json.dumps(info, ensure_ascii=False))


if __name__ == '__main__':
    main()
