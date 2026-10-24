"""Confere o ONNX no OpenCV DNN (mesma versão do app) contra o ONNX Runtime, gera o
metadado do modelo para os apps e a referência de paridade para o teste Java.

Uso: python check_onnx.py --model final.onnx --final-metrics final.json --holdout-metrics holdout.json
       --metadata pellet_stardist.json --reference reference_dense.json
"""
import argparse, json, hashlib, time
from pathlib import Path
import numpy as np, cv2
import common as C
from train import ort_runner

MODEL_ID = 'stardist_demo_20261024_v1'


def cv_runner(path):
    net = cv2.dnn.readNetFromONNX(str(path))
    net.setPreferableBackend(cv2.dnn.DNN_BACKEND_OPENCV)
    net.setPreferableTarget(cv2.dnn.DNN_TARGET_CPU)
    def run(tile):
        net.setInput(tile[None, None].astype(np.float32))
        return net.forward()[0]
    return run


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--data', default=str(Path(__file__).resolve().parents[1] / 'data'))
    for k in ('model', 'final-metrics', 'holdout-metrics', 'metadata', 'reference'):
        ap.add_argument('--' + k, required=True)
    a = ap.parse_args()
    ort_run, cv_run = ort_runner(a.model), cv_runner(a.model)
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
    t0 = time.time(); ref, ncand, limit = C.detect(ort_run, scenes['dense']['gray'], 3, 75); t_ort = time.time() - t0
    t0 = time.time(); alt, _, _ = C.detect(cv_run, scenes['dense']['gray'], 3, 75); t_cv = time.time() - t0
    m_ref = C.match(ref, scenes['dense']['circles'], scenes['dense']['partial'])
    parity = dict(opencv=cv2.__version__, max_abs_output_diff=max(diffs), detections_ort=len(ref), detections_opencv=len(alt),
                  seconds_ort=round(t_ort, 2), seconds_opencv=round(t_cv, 2))
    print('paridade', parity, flush=True)
    Path(a.reference).write_text(json.dumps(dict(
        model_sha256=hashlib.sha256(Path(a.model).read_bytes()).hexdigest(), image='ml_training/data/dense.png',
        gray='cv2.cvtColor(imread COLOR, BGR2GRAY)', profile=3, sensitivity=75, candidates=ncand, candidate_limit=limit,
        detections=ref, match=m_ref), indent=1), encoding='utf-8')
    final, hold = (json.loads(Path(p).read_text(encoding='utf-8')) for p in (a.final_metrics, a.holdout_metrics))
    meta = dict(
        id=MODEL_ID, file='pellet_stardist.onnx', sha256=final['model_sha256'], demonstration=True,
        architecture='StarDist 2D (Schmidt et al. 2018) reimplementado em PyTorch: U-Net 3 níveis (16-32-64-128) + cabeça 64; '
                     f"{final['parameters']} parâmetros",
        input=dict(name='input', shape=[1, 1, C.TILE, C.TILE], gray='BGR2GRAY',
                   normalization=f'(v-p{C.NORM_LOW * 100:g})/(p{C.NORM_HIGH * 100:g}-p{C.NORM_LOW * 100:g}) do histograma da imagem reduzida'),
        output=dict(name='output', shape=[1, 1 + C.RAYS, C.TILE, C.TILE], channel0='probabilidade', channels='distâncias em px da rede'),
        rays=C.RAYS, tile=C.TILE, overlap=C.OVERLAP, nominal_radius_px=C.NOMINAL_RADIUS, norm_low=C.NORM_LOW, norm_high=C.NORM_HIGH,
        nms_overlap=C.NMS_OVERLAP, min_radius_net_px=C.MIN_RADIUS_NET,
        profile_radius_fraction_of_width={str(k): v for k, v in C.PROFILE_RADII.items()},
        threshold='max(0,2; min(0,8; 0,75 - 0,005 x sensibilidade))',
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
