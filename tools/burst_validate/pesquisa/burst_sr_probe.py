#!/usr/bin/env python3
"""
Sonda: separa as duas causas que limitam o burst-SR no PelotaVision
  (1) amplitude do tremor (cobertura de fases sub-pixel)
  (2) precisao do registro (offsets estimados do ruido vs offsets ideais)
  (3) ruido por frame (a camera pode controlar exposicao?)
Para cada combinacao: PSNR da media alinhada, do drizzle e do drizzle+IBP,
e o erro de diametro das pelotas. Roda com offsets IDEALMENTE conhecidos
("oracle") e com offsets ESTIMADOS, para mostrar o teto e o chao.
"""
import json, time
import numpy as np
import cv2
import importlib.util
from skimage.registration import phase_cross_correlation

spec = importlib.util.spec_from_file_location("m", "/home/ubuntu/pelotavision-sr/burst_sr_demo.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

SCENE = '/home/ubuntu/pelotavision-sr/repo/ml_training/data_belt/raw/20170619_09-52-13.jpg'
N = 24
IBP_ITERS = 8


def build(hr, sigma, noise, rng):
    m.NOISE_LR = noise
    offs, frames = [], []
    for _ in range(N):
        o = rng.normal(0, sigma, 2) if sigma > 0 else np.zeros(2)
        offs.append((float(o[0]), float(o[1])))
        frames.append(m.render_frame(hr, o[0], o[1], rng))
    return np.array(offs), frames


def estimate(frames):
    est = [np.zeros(2)]
    for i in range(1, len(frames)):
        sh, _, _ = phase_cross_correlation(frames[0], frames[i], upsample_factor=100)
        est.append(-np.asarray(sh, np.float64))
    return np.array(est)


def pipeline(hr, gt_d, frames, offs):
    one = cv2.resize(frames[0], (m.HR_SIDE, m.HR_SIDE), interpolation=cv2.INTER_CUBIC)
    aligned = np.mean([cv2.warpAffine(np.ascontiguousarray(f),
                                      np.float32([[1, 0, -e[1]], [0, 1, -e[0]]]),
                                      (f.shape[1], f.shape[0]), flags=cv2.INTER_CUBIC,
                                      borderMode=cv2.BORDER_REFLECT)
                       for f, e in zip(frames, offs)], axis=0)
    avg = cv2.resize(aligned, (m.HR_SIDE, m.HR_SIDE), interpolation=cv2.INTER_CUBIC)
    dr = m.drizzle(frames, offs)
    lam = m.normal_norm(frames, offs, hr.shape, iters=6)
    step = 1.2 * len(frames) / max(lam, 1e-9)
    x = dr.copy()
    for _ in range(IBP_ITERS):
        acc = np.zeros_like(x)
        for lr, (dy, dx) in zip(frames, offs):
            acc += m.camera_t(lr - m.camera(x, dy, dx), dy, dx)
        x = np.clip(x + step * acc / len(frames), 0, 1)
    return dict(
        psnr_1frame=round(m.psnr(one, hr), 2),
        psnr_media=round(m.psnr(avg, hr), 2),
        psnr_drizzle=round(m.psnr(dr, hr), 2),
        psnr_ibp=round(m.psnr(x, hr), 2),
        diam_1frame=round(m.diam_err(m.diameters(one), gt_d) or -1, 2),
        diam_media=round(m.diam_err(m.diameters(avg), gt_d) or -1, 2),
        diam_ibp=round(m.diam_err(m.diameters(x), gt_d) or -1, 2),
    )


def main():
    t0 = time.time()
    hr = m.load_scene(SCENE)
    gt_d = m.diameters(hr)
    out = {}
    for noise in (0.035, 0.010):
        for sigma in (0.0, 0.5, 1.5, 2.5):
            rng = np.random.default_rng(4242)
            offs, frames = build(hr, sigma, noise, rng)
            est = estimate(frames)
            err = float(np.sqrt(np.mean((est - offs) ** 2)))
            key = f"ruido={noise:.3f} tremor={sigma}px"
            out[key] = dict(
                rmse_registro_px=round(err, 3),
                oracle=pipeline(hr, gt_d, frames, offs),
                estimado=pipeline(hr, gt_d, frames, est),
            )
            print(f"{key}: registro={err:.3f}px | "
                  f"oracle media={out[key]['oracle']['psnr_media']} drizzle={out[key]['oracle']['psnr_drizzle']} "
                  f"ibp={out[key]['oracle']['psnr_ibp']} | "
                  f"estimado media={out[key]['estimado']['psnr_media']} "
                  f"drizzle={out[key]['estimado']['psnr_drizzle']} ibp={out[key]['estimado']['psnr_ibp']}",
                  flush=True)

    # tabela de diametros resumida
    print("\nERRO DE DIAMETRO (px, menor melhor)")
    for k, v in out.items():
        print(f"{k:>28}: 1frame={v['oracle']['diam_1frame']:5.2f}  media={v['oracle']['diam_media']:5.2f}  "
              f"IBP(oracle)={v['oracle']['diam_ibp']:5.2f}  IBP(estimado)={v['estimado']['diam_ibp']:5.2f}")

    path = '/home/ubuntu/pelotavision-sr/burst_sr_sonda.json'
    json.dump({'cena': SCENE.split('/')[-1], 'N': N, 'IBP_ITERS': IBP_ITERS, 'resultados': out},
              open(path, 'w'), indent=2, ensure_ascii=False)
    print('json:', path, '| tempo', round(time.time() - t0, 1), 's')


if __name__ == '__main__':
    main()
