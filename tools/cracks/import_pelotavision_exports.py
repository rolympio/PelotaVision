#!/usr/bin/env python3
"""Importa rótulos de trinca revisados no próprio PelotaVision (pastas exportadas pelo app).

Cada pasta de análise tem <base>.csv (uma pelota por linha), <base>.json e <base>_original.png.
Só entram como rótulo as pelotas com crack_manually_reviewed = true:

  POSSIBLE      -> cracked
  NO_INDICATION -> intact
  INCONCLUSIVE  -> uncertain (nunca treina nem testa)

Pelotas NÃO revisadas ficam de fora: o status delas é a opinião do classificador da época, não um rótulo.
O círculo usado é o mesmo do recorte de trinca do app (crack_crop_x/y/radius_px do JSON), em pixels
da foto ORIGINAL; a foto original é copiada sem recompressão.

group_id = <source_id>:<pasta da análise>. Entre análises as pelotas foram remexidas (centros não coincidem),
mas o lote físico é o mesmo: a validação "análise inteira fora" ainda pode ver a mesma pelota em outra
posição. Isso é registrado em meta.json e no relatório.

Uso:
  python tools/cracks/import_pelotavision_exports.py --src <pasta com as análises> \
      --out ml_training/data_webcam_20261027 --source-id webcam_pan_20261027
"""
import argparse
import csv
import hashlib
import json
import shutil
from pathlib import Path

STATUS = {'POSSIBLE': 'cracked', 'NO_INDICATION': 'intact', 'INCONCLUSIVE': 'uncertain'}


def sha256(p):
    return hashlib.sha256(Path(p).read_bytes()).hexdigest()


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--src', type=Path, required=True)
    ap.add_argument('--out', type=Path, required=True)
    ap.add_argument('--source-id', required=True)
    ap.add_argument('--license-note', default='fotos do próprio usuário (Vale, Pelotização), enviadas em 28/10/2026 para treino interno')
    a = ap.parse_args(argv)
    img_dir = a.out / 'images'
    img_dir.mkdir(parents=True, exist_ok=True)
    lines, photos = [], []
    for js in sorted(a.src.glob('*/PelotaVision_*.json')):
        if not js.stem[-3:].isdigit():
            continue
        folder = js.parent.name
        base = js.stem
        orig = js.with_name(base + '_original.png')
        csv_path = js.with_suffix('.csv')
        d = json.loads(js.read_text(encoding='utf-8'))
        dets = {int(x['id']): x for x in d['detections']}
        with csv_path.open(encoding='utf-8-sig') as f:
            rows = list(csv.DictReader(f, delimiter=';'))
        reviewed = [r for r in rows if r['crack_manually_reviewed'] == 'true']
        target = img_dir / f'{base}_original.png'
        shutil.copyfile(orig, target)
        counts = {'cracked': 0, 'intact': 0, 'uncertain': 0}
        for r in reviewed:
            det = dets[int(r['id'])]
            label = STATUS[r['crack_status']]
            counts[label] += 1
            lines.append({'image': target.name, 'instance_id': f'{base}_{int(r["id"]):03d}',
                          'cx': round(float(det['crack_crop_x_px']), 3), 'cy': round(float(det['crack_crop_y_px']), 3),
                          'r': round(float(det['crack_crop_radius_px']), 3), 'touches_border': r['partial_edge'] == 'true',
                          'crack_label': label, 'label_origin': 'user_review_in_app:' + r['crack_status'],
                          'group_id': f'{a.source_id}:{folder}', 'app_score_at_review': float(r['crack_score']),
                          'app_method_at_review': r['crack_method'], 'detection_id': int(r['id'])})
        src = d.get('source', {})
        photos.append({'folder': folder, 'image': target.name, 'sha256': sha256(target), 'width': src.get('original_width'),
                       'height': src.get('original_height'), 'app': d.get('app'), 'pellets_detected': len(rows),
                       'reviewed': len(reviewed), **counts, 'roi': d.get('region_of_interest')})
    (a.out / 'instances.jsonl').write_text(''.join(json.dumps(x, ensure_ascii=False) + '\n' for x in lines), encoding='utf-8')
    meta = {'source_id': a.source_id, 'images_dir': (a.out / 'images').as_posix(), 'license_status': 'verified',
            'license_note': a.license_note, 'material_type': 'unknown', 'scene': 'monte_de_pelotas_em_panela_webcam_1920x1080',
            'origin': 'real', 'split_role': 'dev', 'group_by': 'instance_group',
            'label_note': 'só pelotas revisadas à mão no app (crack_manually_reviewed); não revisadas ficam de fora',
            'leakage_note': 'pelotas remexidas entre análises, mas do mesmo lote físico: análise fora não garante pelota inédita',
            'photos': photos}
    (a.out / 'meta.json').write_text(json.dumps(meta, indent=1, ensure_ascii=False), encoding='utf-8')
    tot = {k: sum(p[k] for p in photos) for k in ('cracked', 'intact', 'uncertain')}
    print(f'{len(photos)} análises, {len(lines)} pelotas revisadas: {tot}')


if __name__ == '__main__':
    main()
