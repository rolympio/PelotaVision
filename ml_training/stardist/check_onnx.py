"""Confere o ONNX no OpenCV DNN (mesma versão do app) contra o ONNX Runtime, gera o
metadado do modelo para os apps e a referência de paridade para o teste Java.

Uso: python check_onnx.py --model final.onnx --final-metrics final.json --holdout-metrics holdout.json
       --metadata pellet_stardist.json --reference reference_dense.json
     python check_onnx.py --conjunto correia --model belt.onnx --final-metrics belt_metrics.json
       --holdout-metrics belt_validation.json --metadata pellet_stardist_belt.json --reference reference_belt.json
"""
import argparse, json, hashlib, time
from pathlib import Path
import numpy as np, cv2
import common as C

MODEL_ID = 'stardist_demo_20261024_v1'


def cv_runner(path):
    net = cv2.dnn.readNetFromONNX(str(path))
    net.setPreferableBackend(cv2.dnn.DNN_BACKEND_OPENCV)
    net.setPreferableTarget(cv2.dnn.DNN_TARGET_CPU)
    def run(tile):
        net.setInput(tile[None, None].astype(np.float32))
        return net.forward()[0]
    return run


BELT_MODEL_ID = 'stardist_belt_pseudo_{date}_v1'
BELT_REFERENCE_FRAME = 'img765.jpg'   # Disco 11 (validação), quadro com correia exposta à direita


def main_belt(a):
    """Modelo de correia: paridade ORT x OpenCV, referência de paridade para o Java e metadado."""
    import datetime
    ort_run, cv_run = C.ort_runner(a.model), cv_runner(a.model)
    root = Path(__file__).resolve().parents[2]
    img = root / 'ml_training' / 'data_belt' / 'raw' / BELT_REFERENCE_FRAME
    gray = cv2.cvtColor(cv2.imread(str(img), cv2.IMREAD_COLOR), cv2.COLOR_BGR2GRAY)
    rng = np.random.default_rng(3)
    real = C.normalize(C.resize_for_net(gray, 16 / 12.5)[:C.TILE, :C.TILE], C.norm_stats(C.resize_for_net(gray, 16 / 12.5)))
    tile = np.full((C.TILE, C.TILE), .5, np.float32); tile[:real.shape[0], :real.shape[1]] = real
    diffs = [float(np.abs(ort_run(t) - cv_run(t)).max()) for t in (rng.random((C.TILE, C.TILE), np.float32), tile)]
    info = {}
    t0 = time.time(); ref, ncand, limit = C.detect(ort_run, gray, 3, 75, info, belt=True); t_ort = time.time() - t0
    t0 = time.time(); alt, _, _ = C.detect(cv_run, gray, 3, 75, belt=True); t_cv = time.time() - t0
    parity = dict(opencv=cv2.__version__, max_abs_output_diff=max(diffs), detections_ort=len(ref), detections_opencv=len(alt),
                  seconds_ort=round(t_ort, 2), seconds_opencv=round(t_cv, 2), frame=BELT_REFERENCE_FRAME)
    print('paridade', parity, flush=True)
    sha = hashlib.sha256(Path(a.model).read_bytes()).hexdigest()
    Path(a.reference).write_text(json.dumps(dict(
        model_sha256=sha, image='ml_training/data_belt/raw/' + BELT_REFERENCE_FRAME, belt=True,
        gray='cv2.cvtColor(imread COLOR, BGR2GRAY)', profile=3, sensitivity=75, candidates=ncand, candidate_limit=limit,
        final_scale=info['final_scale'], scale_trials=[dict(scale=t['scale'], kept=t['kept'], median_net_radius=t['rnet']) for t in info['trials']],
        detections=ref), indent=1), encoding='utf-8')
    tr, val = (json.loads(Path(p).read_text(encoding='utf-8')) for p in (a.final_metrics, a.holdout_metrics))
    summ = json.loads((root / 'ml_training' / 'data_belt' / 'pseudo' / 'summary.json').read_text(encoding='utf-8'))
    meta = dict(
        id=BELT_MODEL_ID.format(date=datetime.date.today().strftime('%Y%m%d')), file='pellet_stardist_belt.onnx', sha256=sha,
        demonstration=True, scene='belt', label_origin=summ['label_origin'], ground_truth=False,
        architecture='StarDist 2D (Schmidt et al. 2018) reimplementado em PyTorch: U-Net 3 níveis (16-32-64-128) + cabeça 32; '
                     f"{tr['parameters']} parâmetros",
        input=dict(name='input', shape=[1, 1, C.TILE, C.TILE], gray='BGR2GRAY',
                   normalization=f'(v-p{C.NORM_LOW * 100:g})/(p{C.NORM_HIGH * 100:g}-p{C.NORM_LOW * 100:g}) do histograma da imagem reduzida'),
        output=dict(name='output', shape=[1, 1 + C.RAYS, C.TILE, C.TILE], channel0='probabilidade', channels='distâncias em px da rede'),
        rays=C.RAYS, tile=C.TILE, overlap=C.OVERLAP, nominal_radius_px=C.NOMINAL_RADIUS, norm_low=C.NORM_LOW, norm_high=C.NORM_HIGH,
        nms_overlap=C.NMS_OVERLAP, min_radius_net_px=C.MIN_RADIUS_NET,
        profile_radius_fraction_of_width={str(k): v for k, v in C.BELT_RADII.items()},
        threshold='max(0,2; min(0,8; 0,75 - 0,005 x sensibilidade))',
        scale_selection='cena Correia: uma passada por raio do perfil num recorte central de um bloco; escolhe o raio mediano mais perto do nominal (log); passada final reescalada (fator 0,5-2)',
        training=dict(conjunto='correia', label_origin=summ['label_origin'], cameras=tr['training_cameras'],
                      crops=tr['training_crops'], negative_crops=tr['training_negative_crops'], objects=tr['training_objects'],
                      source_frames=len(tr['training_sources']), iterations=tr['iterations'], seed=tr['seed'], torch=tr['torch'],
                      split=summ['rule_split'], subsample=summ['rule_subsample'], negatives=summ['rule_negatives'],
                      labelers='LoG multiescala + raio radial; Hough GRADIENT_ALT + CLAHE; ONNX de demonstração na escala do espaçamento; consenso >= 2',
                      note='Pseudo-rótulos de consenso, SEM revisão humana. Não usar para granulometria registrada sem validação com rótulos revisados/peneiramento.'),
        validation_holdout=dict(cameras=val['validation_cameras'], frames=val['validation_frames'], against='pseudo-rótulos de consenso (NÃO é verdade-terreno)',
                                overall=val['overall'], by_camera=val['by_camera'],
                                empty_belt={k: v for k, v in val['empty_belt'].items() if k != 'per_frame'}),
        opencv_parity=parity,
        license='Código e pesos: mesmos termos do PelotaVision; método StarDist (BSD-3) reimplementado, sem código copiado.')
    Path(a.metadata).write_text(json.dumps(meta, indent=1, ensure_ascii=False), encoding='utf-8')
    if max(diffs) > 2e-3 or abs(len(ref) - len(alt)) > max(1, len(ref) // 500):
        raise SystemExit(f'Paridade OpenCV falhou: {parity}')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--conjunto', choices=('bancada', 'correia'), default='bancada')
    ap.add_argument('--data', default=str(Path(__file__).resolve().parents[1] / 'data'))
    for k in ('model', 'final-metrics', 'holdout-metrics', 'metadata', 'reference'):
        ap.add_argument('--' + k, required=True)
    a = ap.parse_args()
    if a.conjunto == 'correia':
        return main_belt(a)
    ort_run, cv_run = C.ort_runner(a.model), cv_runner(a.model)
    scenes = {s['group']: s for s in C.load_scenes(a.data)}
    rng = np.random.default_rng(3)
    # 1) saída bruta: ruído + bloco real
    real = C.normalize(C.resize_for_net(scenes['dense']['gray'], .1)[:C.TILE, :C.TILE], (40, 200, 120))
    tile = np.full((C.TILE, C.TILE), .5, np.float32); tile[:real.shape[0], :real.shape[1]] = real
    diffs = []
    for t in (rng.random((C.TILE, C.TILE), np.float32), tile):
        o, c = ort_run(t), cv_run(t)
        diffs.append(float(np.abs(o - c).max()))
    # 2) pipeline completo nos dois motores
    info = {}
    t0 = time.time(); ref, ncand, limit = C.detect(ort_run, scenes['dense']['gray'], 3, 75, info); t_ort = time.time() - t0
    t0 = time.time(); alt, _, _ = C.detect(cv_run, scenes['dense']['gray'], 3, 75); t_cv = time.time() - t0
    m_ref = C.match(ref, scenes['dense']['circles'], scenes['dense']['partial'])
    parity = dict(opencv=cv2.__version__, max_abs_output_diff=max(diffs), detections_ort=len(ref), detections_opencv=len(alt),
                  seconds_ort=round(t_ort, 2), seconds_opencv=round(t_cv, 2))
    print('paridade', parity, flush=True)
    Path(a.reference).write_text(json.dumps(dict(
        model_sha256=hashlib.sha256(Path(a.model).read_bytes()).hexdigest(), image='ml_training/data/dense.png',
        gray='cv2.cvtColor(imread COLOR, BGR2GRAY)', profile=3, sensitivity=75, candidates=ncand, candidate_limit=limit,
        final_scale=info['final_scale'], scale_trials=[dict(scale=t['scale'], kept=t['kept'], median_net_radius=t['rnet']) for t in info['trials']],
        detections=ref, match=m_ref), indent=1), encoding='utf-8')
    final, hold = (json.loads(Path(p).read_text(encoding='utf-8')) for p in (a.final_metrics, a.holdout_metrics))
    meta = dict(
        id=MODEL_ID, file='pellet_stardist.onnx', sha256=final['model_sha256'], demonstration=True,
        architecture='StarDist 2D (Schmidt et al. 2018) reimplementado em PyTorch: U-Net 3 níveis (16-32-64-128) + cabeça 32; '
                     f"{final['parameters']} parâmetros",
        input=dict(name='input', shape=[1, 1, C.TILE, C.TILE], gray='BGR2GRAY',
                   normalization=f'(v-p{C.NORM_LOW * 100:g})/(p{C.NORM_HIGH * 100:g}-p{C.NORM_LOW * 100:g}) do histograma da imagem reduzida'),
        output=dict(name='output', shape=[1, 1 + C.RAYS, C.TILE, C.TILE], channel0='probabilidade', channels='distâncias em px da rede'),
        rays=C.RAYS, tile=C.TILE, overlap=C.OVERLAP, nominal_radius_px=C.NOMINAL_RADIUS, norm_low=C.NORM_LOW, norm_high=C.NORM_HIGH,
        nms_overlap=C.NMS_OVERLAP, min_radius_net_px=C.MIN_RADIUS_NET,
        profile_radius_fraction_of_width={str(k): v for k, v in C.PROFILE_RADII.items()},
        threshold='max(0,2; min(0,8; 0,75 - 0,005 x sensibilidade))',
        scale_selection='uma passada por raio do perfil; escolhe o raio mediano mais perto do nominal (log); passada final reescalada (fator 0,5-2)',
        training=dict(groups=final['training_groups'], objects='225 círculos PROVISÓRIOS do assistente em 7 cenas; uma sintética, demais de origem não comprovada',
                      iterations=final['iterations'], seed=final['seed'], torch=final['torch'],
                      note='Modelo de DEMONSTRAÇÃO do encanamento. Não foi treinado nem validado em fotos da usina.'),
        validation_holdout=dict(trained_without=hold['excluded_groups'],
                                scenes={g: m for g, m in hold['scenes'].items() if g in hold['excluded_groups']},
                                protocol=hold['evaluation_protocol']),
        training_fit=final['scenes'], opencv_parity=parity, license='Código e pesos: mesmos termos do PelotaVision; método StarDist (BSD-3) reimplementado, sem código copiado.')
    Path(a.metadata).write_text(json.dumps(meta, indent=1, ensure_ascii=False), encoding='utf-8')
    if max(diffs) > 2e-3 or abs(len(ref) - len(alt)) > 1:
        raise SystemExit(f'Paridade OpenCV falhou: {parity}')


if __name__ == '__main__':
    main()
