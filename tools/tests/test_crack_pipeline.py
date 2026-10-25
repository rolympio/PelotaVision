"""Testes da pipeline de trincas: python -m pytest tools/tests -q"""
import csv
import json
import subprocess
import sys
from pathlib import Path

import cv2
import numpy as np
import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.cracks import convert_annotations as ca  # noqa: E402
from tools.cracks import feats, manifest as mf, metrics as mt, pvrf  # noqa: E402
from tools.cracks.build_manifest import crop_pellet  # noqa: E402

EMBEDDED = ROOT / 'app/src/main/assets/crack_forest.pvrf'


def _row(**kw):
    r = dict(image_path='a.png', label='intact', group_id='g1', material_type='unknown', origin='real', split_role='dev')
    r.update(kw)
    return r


# ---------------------------------------------------------------- manifesto
def test_manifest_accepts_valid_rows():
    mf.validate([_row(), _row(label='cracked', group_id='g2'), _row(origin='synthetic', split_role='train_only', group_id='s')])


@pytest.mark.parametrize('bad', [
    _row(label='ok'),
    _row(material_type='pink'),
    _row(origin='generated'),
    _row(group_id=' '),
    _row(origin='synthetic', split_role='holdout'),
    _row(origin='synthetic', split_role='dev'),
])
def test_manifest_rejects_invalid(bad):
    with pytest.raises(mf.ManifestError):
        mf.validate([bad])


def test_manifest_rejects_group_leakage_between_dev_and_holdout():
    with pytest.raises(mf.ManifestError):
        mf.validate([_row(group_id='photo1'), _row(group_id='photo1', split_role='holdout')])


# ------------------------------------------------------- conversão de anotações
def _coco(tmp_path):
    doc = {
        'images': [{'id': 1, 'file_name': 'a.jpg', 'width': 200, 'height': 100}],
        'categories': [{'id': 1, 'name': 'pellet'}, {'id': 2, 'name': 'crack'}],
        'annotations': [
            {'id': 10, 'image_id': 1, 'category_id': 1, 'bbox': [10, 10, 40, 40], 'segmentation': [[10, 10, 50, 10, 50, 50, 10, 50]]},
            {'id': 11, 'image_id': 1, 'category_id': 1, 'bbox': [100, 10, 40, 40], 'segmentation': [[100, 10, 140, 10, 140, 50, 100, 50]]},
            {'id': 12, 'image_id': 1, 'category_id': 2, 'bbox': [25, 25, 6, 6], 'segmentation': [[25, 25, 31, 25, 31, 31, 25, 31]]},
        ]}
    p = tmp_path / 'coco.json'
    p.write_text(json.dumps(doc))
    return p


def test_coco_unannotated_pellet_is_uncertain_not_intact(tmp_path):
    out = {r['instance_id']: r for r in ca.convert_coco(_coco(tmp_path), ['pellet'], ['crack'], exhaustive=False)}
    assert out[10]['crack_label'] == 'cracked' and out[10]['crack_evidence'] == [12]
    assert out[11]['crack_label'] == 'uncertain'
    assert abs(out[10]['cx'] - 30) < 1e-6 and abs(out[10]['r'] - np.sqrt(1600 / np.pi)) < 1e-6


def test_coco_exhaustive_review_allows_intact(tmp_path):
    out = {r['instance_id']: r for r in ca.convert_coco(_coco(tmp_path), ['pellet'], ['crack'], exhaustive=True)}
    assert out[11]['crack_label'] == 'intact'


def test_coco_without_crack_category_never_intact(tmp_path):
    out = ca.convert_coco(_coco(tmp_path), ['pellet'], [], exhaustive=True)
    assert {r['crack_label'] for r in out} == {'uncertain'}


def test_binary_masks_become_uncertain_instances(tmp_path):
    (tmp_path / 'm').mkdir()
    (tmp_path / 'i').mkdir()
    m = np.zeros((60, 100), np.uint8)
    cv2.circle(m, (20, 30), 10, 1, -1)
    cv2.circle(m, (70, 30), 12, 255, -1)
    cv2.circle(m, (99, 59), 6, 1, -1)  # toca a borda
    cv2.imwrite(str(tmp_path / 'm' / 'p1.png'), m)
    cv2.imwrite(str(tmp_path / 'i' / 'p1.jpeg'), m)
    out = ca.convert_masks(tmp_path / 'm', tmp_path / 'i')
    assert len(out) == 3 and {r['crack_label'] for r in out} == {'uncertain'}
    assert out[0]['image'] == 'p1.jpeg' and sum(r['touches_border'] for r in out) == 1


# --------------------------------------------------------- recorte e atributos
def _scene():
    rng = np.random.default_rng(3)
    g = np.clip(rng.normal(120, 25, (180, 260)), 0, 255).astype(np.uint8)
    for x, y, r in [(40, 40, 30), (200, 150, 28), (130, 90, 35)]:
        cv2.circle(g, (x, y), r, 170, -1)
        cv2.line(g, (x - r // 2, y), (x + r // 2, y + 5), 60, 1)
    return g


@pytest.mark.parametrize('x,y,r', [(40.3, 40.7, 30.0), (200.5, 150.2, 28.4), (130.0, 90.0, 35.0), (25.0, 20.0, 30.0)])
def test_crop_features_identical_to_full_image(x, y, r):
    g = _scene()
    c, cx, cy, x0, _ = crop_pellet(g, x, y, r)
    assert np.array_equal(feats.pv72(c, cx, cy, r), feats.pv72(g, x, y, r))
    for v in range(feats.N_LIGHTING_VARIANTS):
        a = feats.pv72(feats.lit_variant(c, v, g.shape[1], x0), cx, cy, r)
        b = feats.pv72(feats.lighting(g, v), x, y, r)
        assert np.array_equal(a, b), v


def test_feature_dimensions():
    g = _scene()
    assert feats.pv72(g, 130, 90, 35).shape == (72,)
    assert feats.hoglbp(g, 130, 90, 35).shape == (1776,)
    assert feats.pixels(g, 130, 90, 35).shape == (48 * 48,)


# ------------------------------------------------------------------ PVF1
def test_pvrf_roundtrip_matches_sklearn(tmp_path):
    from sklearn.ensemble import RandomForestClassifier
    rng = np.random.default_rng(0)
    X = rng.normal(size=(300, 72)).astype(np.float32)
    y = (X[:, 3] + X[:, 10] > 0).astype(int)
    m = RandomForestClassifier(n_estimators=20, max_depth=6, random_state=0).fit(X, y)
    p = tmp_path / 'm.pvrf'
    pvrf.write_pvrf(m, p)
    s = pvrf.score_pvrf(pvrf.read_pvrf(p), X)
    assert np.abs(s - m.predict_proba(X)[:, 1]).max() < 1e-12


def test_pvrf_rejects_corruption(tmp_path):
    b = bytearray(EMBEDDED.read_bytes())
    (tmp_path / 'trail.pvrf').write_bytes(bytes(b) + b'\0')
    with pytest.raises(ValueError):
        pvrf.read_pvrf(tmp_path / 'trail.pvrf')
    b[8:12] = (71).to_bytes(4, 'big')
    (tmp_path / 'nf.pvrf').write_bytes(bytes(b))
    with pytest.raises(ValueError):
        pvrf.read_pvrf(tmp_path / 'nf.pvrf')


def test_embedded_model_unchanged_and_reproduces_reference_scores():
    meta = json.loads((ROOT / 'app/src/main/assets/metadata.json').read_text())
    import hashlib
    assert hashlib.sha256(EMBEDDED.read_bytes()).hexdigest() == meta['sha256']
    ref = np.load(ROOT / 'ml_training/training/reference_vectors.npz')
    assert np.abs(pvrf.score_pvrf(pvrf.read_pvrf(EMBEDDED), ref['features']) - ref['score_final']).max() < 1e-12


def test_reference_features_reproduced_with_installed_opencv():
    ann = json.loads((ROOT / 'ml_training/data/annotations.json').read_text())
    ref = np.load(ROOT / 'ml_training/training/reference_vectors.npz')['features']
    scene = ann[0]
    g = cv2.imread(str(ROOT / 'ml_training/data' / (scene['group'] + '.png')), cv2.IMREAD_GRAYSCALE)
    got = [feats.pv72(g, a['x'], a['y'], a['radius']) for a in scene['annotations'] if not a['partial']]
    assert np.abs(np.array(got) - ref[:len(got)]).max() == 0


# --------------------------------------------------------------- métricas
def test_metrics_counts_false_negatives():
    r = mt.binary_report([1, 1, 1, 0, 0], [0.9, 0.3, 0.55, 0.1, 0.7])
    assert r['false_negatives'] == 1 and r['false_positives'] == 1
    assert r['confusion_matrix']['rows_truth_cols_pred'] == [[1, 1], [1, 2]]
    assert abs(r['recall_cracked'] - 2 / 3) < 1e-12
    assert r['app_policy_0.4_0.6']['inconclusive_cracked'] == 1
    assert mt._auc(np.array([1, 0]), np.array([0.5, 0.5])) == 0.5


# ------------------------------------------------------- ponta a ponta (pequeno)
def test_train_cracks_end_to_end_keeps_synthetic_out_of_evaluation(tmp_path):
    rng = np.random.default_rng(1)
    rows = []

    def add(name, label, group, origin, role, source):
        g = np.clip(rng.normal(130, 20, (90, 90)), 0, 255).astype(np.uint8)
        cv2.circle(g, (45, 45), 30, 160, -1)
        if label == 'cracked':
            cv2.line(g, (30, 40), (62, 52), 40, 2)
        cv2.imwrite(str(tmp_path / name), g)
        rows.append(dict(image_path=name, label=label, group_id=group, material_type='fired', origin=origin, cx='45', cy='45',
                         r='30', source_id=source, source_image='x', source_sha256='', instance_id=name, label_origin='test',
                         split_role=role, source_width='', x_offset='', license_status='verified'))

    k = 0
    for gi in range(4):
        for lab in ['cracked'] * 3 + ['intact'] * 5:
            add(f'd{k}.png', lab, f'scene:{gi}', 'unverified', 'dev', 'pelotavision_0.5.0_scenes')
            k += 1
    for lab in ['cracked'] * 3 + ['intact'] * 3:
        add(f'h{k}.png', lab, 'photo:h', 'real', 'holdout', 'fixture')
        k += 1
    for lab in ['cracked'] * 4 + ['intact'] * 4:
        add(f's{k}.png', lab, 'synthetic:x', 'synthetic', 'train_only', 'synthetic_test')
        k += 1
    mf.write(rows, tmp_path / 'labels.csv')
    out = tmp_path / 'out'
    subprocess.run([sys.executable, str(ROOT / 'tools/train_cracks.py'), '--manifest', str(tmp_path / 'labels.csv'), '--output', str(out),
                    '--models', 'rf_original', 'rf_original_plus_synthetic', '--allow-synthetic-training'], check=True,
                   capture_output=True, cwd=ROOT)
    with (out / 'predictions.csv').open() as f:
        pred = list(csv.DictReader(f))
    assert pred and not any(p['origin'] == 'synthetic' for p in pred)
    assert {p['split'] for p in pred if p['group_id'] == 'photo:h'} == {'holdout'}
    report = json.loads((out / 'metrics.json').read_text())
    assert report['performance']['rf_original']['pvrf_vs_sklearn_max_abs_diff'] < 1e-12
    assert (out / 'models' / 'rf_original_plus_synthetic.pvrf').is_file()


def test_importer_refuses_blocked_sources(tmp_path):
    r = subprocess.run([sys.executable, str(ROOT / 'tools/import_external_pellets.py'), '--source', 'pellet_size_distribution',
                        '--input', str(tmp_path), '--output', str(tmp_path / 'o'), '--license-verified'], capture_output=True, text=True)
    assert r.returncode != 0 and 'blocked' in r.stderr
