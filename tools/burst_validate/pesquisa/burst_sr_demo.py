#!/usr/bin/env python3
"""
Experimento numerico: N fotos com tremor sub-pixel viram "resolucao muito superior"?

Modelo (caso PelotaVision: cena parada, camera montada com vibracao):
  GT = recorte 512x512 cinza de foto REAL de pelotas em correia
       = o que uma camera com pixels 2x menores veria
  LR = camera 2x pior (256x256): media de duas amostras bilineares adjacentes do
       GT transladado por (dy,dx) sub-pixel + ruido gaussiano + ADC de 8 bits.
  O pipeline NAO conhece os offsets: correlacao de fase (parte inteira) +
  busca coarse-to-fine com reinicio (parte sub-pixel).

Camera (A) e adjunta (A^T) implementadas como gather/scatter com os mesmos pesos:
<A x, r> == <x, A^T r> por construcao, e o auto-teste imprime o erro.

Comparado contra o GT (PSNR dB, acutancia, erro de diametro das pelotas):
  A) 1 frame LR -> bicubica 2x             (papel do FSRCNN x2 atual, sem burst)
  B) N frames alinhados -> media na LR -> 2x (so ganha SNR, nao resolucao)
  C) burst SR drizzle na grade 2x          (aproveita as fases sub-pixel distintas)
  D) C + back-projection iterativa (IBP)
  E) media sem alinhar (tremor ignorado)
Cenarios: sigma = 0, 0,5 e 1,5 px LR. Varredura de N = 1, 3, 6, 12, 24.
"""
import json, sys, time
import numpy as np
import cv2
from scipy.ndimage import gaussian_filter, label

S = 2
LR_SIDE, HR_SIDE = 256, 512
NOISE_LR = 0.035
MAX_N = 24
IBP_ITERS = 30
RNG = np.random.default_rng(11)
OFFS = ((0, 0), (1, 0), (0, 1), (1, 1))


# ------------------------------------------------------------------ cena real
def load_scene(path):
    img = cv2.imread(path, cv2.IMREAD_GRAYSCALE)
    if img is None:
        raise SystemExit(f"falha ao ler {path}")
    h, w = img.shape
    if h < HR_SIDE or w < HR_SIDE:
        img = cv2.resize(img, (max(w, HR_SIDE), max(h, HR_SIDE)))
        h, w = img.shape
    best, bx, by = -1.0, 0, 0
    for y in range(0, h - HR_SIDE + 1, 32):
        for x in range(0, w - HR_SIDE + 1, 32):
            v = float(img[y:y + HR_SIDE, x:x + HR_SIDE].std())
            if v > best:
                best, bx, by = v, x, y
    return np.clip(img[by:by + HR_SIDE, bx:bx + HR_SIDE].astype(np.float64) / 255.0, 0, 1)


# ------------------------------------------------------------------- camera
def _weights(rows, cols):
    i0 = np.floor(rows).astype(np.int64)
    j0 = np.floor(cols).astype(np.int64)
    fr = rows - i0
    fc = cols - j0
    return (i0, j0, ((1 - fr) * (1 - fc), fr * (1 - fc), (1 - fr) * fc, fr * fc))


def camera(x, dy, dx):
    """A: um pixel LR = media de duas amostras bilineares adjacentes (integracao 2x2)."""
    H, W = x.shape
    ii, jj = np.mgrid[0:H // S, 0:W // S]
    out = np.zeros((H // S, W // S), np.float64)
    for off in (0.0, 1.0):
        rows = 2 * ii + off - dy * S
        cols = 2 * jj + off - dx * S
        i0, j0, ws = _weights(rows, cols)
        vals = np.zeros(rows.shape, np.float64)
        for (di, dj), wt in zip(OFFS, ws):
            ii2, jj2 = i0 + di, j0 + dj
            ok = (ii2 >= 0) & (ii2 < H) & (jj2 >= 0) & (jj2 < W)
            vals[ok] += wt[ok] * x[ii2[ok], jj2[ok]]
        out += 0.5 * vals
    return out


def camera_t(r, dy, dx):
    """A^T: scatter dos mesmos pesos (adjunto exato)."""
    H, W = r.shape
    out = np.zeros((H * S, W * S), np.float64)
    ii, jj = np.mgrid[0:H, 0:W]
    for off in (0.0, 1.0):
        rows = 2 * ii + off - dy * S
        cols = 2 * jj + off - dx * S
        i0, j0, ws = _weights(rows, cols)
        for (di, dj), wt in zip(OFFS, ws):
            ii2, jj2 = i0 + di, j0 + dj
            ok = (ii2 >= 0) & (ii2 < H * S) & (jj2 >= 0) & (jj2 < W * S)
            np.add.at(out, (ii2[ok], jj2[ok]), (0.5 * wt * r)[ok])
    return out


def render_frame(hr, dy, dx, rng):
    lr = camera(hr, dy, dx) + rng.normal(0, NOISE_LR, (hr.shape[0] // S, hr.shape[1] // S))
    return np.clip(np.round(lr * 255.0) / 255.0, 0, 1)


# ------------------------------------------------- estimativa dos deslocamentos
def phase_int(ref, img):
    """Parte inteira (px LR). O pico sai invertido; o sinal foi conferido numericamente."""
    win = cv2.createHanningWindow((ref.shape[1], ref.shape[0]), cv2.CV_32F)
    C = np.fft.fft2(ref * win) * np.conj(np.fft.fft2(img * win))
    cc = np.real(np.fft.ifft2(C / (np.abs(C) + 1e-9)))
    iy, ix = np.unravel_index(np.argmax(cc), cc.shape)
    iy = iy if iy <= cc.shape[0] // 2 else iy - cc.shape[0]
    ix = ix if ix <= cc.shape[1] // 2 else ix - cc.shape[1]
    return -np.array([iy, ix], np.float64)


def _cost(ref, img, r, crop=64):
    rc = np.ascontiguousarray(ref[crop:-crop, crop:-crop])
    ic = img[crop:-crop, crop:-crop]
    h, w = rc.shape
    M = np.float32([[1, 0, r[1]], [0, 1, r[0]]])
    t = cv2.warpAffine(rc, M, (w, h), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REFLECT)
    return float(np.mean((t - ic) ** 2))


def refine(ref, img, guess):
    """Parte sub-pixel: busca coarse-to-fine num recorte interno (borda enviesaria)."""
    rc = np.ascontiguousarray(ref[64:-64, 64:-64])
    ic = img[64:-64, 64:-64]
    h, w = rc.shape
    r = np.asarray(guess, np.float64)
    for lo, hi, steps in ((-1.3, 1.3, 15), (-0.25, 0.25, 21), (-0.05, 0.05, 21)):
        cand = np.linspace(lo, hi, steps)
        best, bp = np.inf, (0.0, 0.0)
        for dy in cand:
            for dx in cand:
                M = np.float32([[1, 0, r[1] + dx], [0, 1, r[0] + dy]])
                t = cv2.warpAffine(rc, M, (w, h), flags=cv2.INTER_CUBIC,
                                   borderMode=cv2.BORDER_REFLECT)
                e = float(np.mean((t - ic) ** 2))
                if e < best:
                    best, bp = e, (float(dy), float(dx))
        r = r + np.array(bp, np.float64)
    return r


def estimate_offsets(frames):
    """Deslocamento de cada frame em px da grade LR, relativo ao frame 0.

    Usa correlacao de fase com refinamento por DFT em grade sobreamostrada
    (Guizar-Sicairos et al. 2008, implementacao de referencia do scikit-image).
    A convencao de sinal foi conferida numericamente: o retorno vem invertido.
    """
    from skimage.registration import phase_cross_correlation
    est = [np.zeros(2)]
    for i in range(1, len(frames)):
        sh, _, _ = phase_cross_correlation(frames[0], frames[i], upsample_factor=100)
        est.append(-np.asarray(sh, np.float64))
    return np.array(est, np.float64)


# ------------------------------------------------------------------ fusao SR
def w1(e):
    """Overlap da caixa do pixel LR (largura S em HR) com as celulas HR [n, n+1)."""
    c = 1.0 + e
    return [max(0.0, min(c + 1.0, n + 1.0) - max(c - 1.0, n)) / 2.0 for n in range(3)]


def drizzle(frames, offs):
    H, W = frames[0].shape
    num = np.zeros((H * S, W * S), np.float64)
    den = np.zeros_like(num)
    ri_all, ci_all = S * np.arange(H), S * np.arange(W)
    for lr, (dy, dx) in zip(frames, offs):
        ey, ex = -dy * S, -dx * S
        ny0, nx0 = int(np.floor(ey)), int(np.floor(ex))
        wy, wx = w1(ey - ny0), w1(ex - nx0)
        for a in range(3):
            if wy[a] <= 0:
                continue
            ri = ri_all + ny0 + a
            mv = (ri >= 0) & (ri < H * S)
            if not mv.any():
                continue
            for b in range(3):
                if wx[b] <= 0:
                    continue
                ci = ci_all + nx0 + b
                cv = (ci >= 0) & (ci < W * S)
                if not cv.any():
                    continue
                wgt = wy[a] * wx[b]
                idx = np.ix_(ri[mv], ci[cv])
                num[idx] += lr[np.ix_(mv, cv)] * wgt
                den[idx] += wgt
    return num / np.maximum(den, 1e-6)


def normal_norm(frames, offs, shape, iters=10):
    """Norma de sum A^T A por iteracao de potencia -> define o passo do IBP."""
    v = np.random.default_rng(2).random(shape)
    lam = 1.0
    for _ in range(iters):
        acc = np.zeros(shape)
        for _, (dy, dx) in zip(frames, offs):
            acc += camera_t(camera(v, dy, dx), dy, dx)
        lam = float(np.linalg.norm(acc))
        v = acc / lam
    return lam


def ibp(frames, offs, x0):
    lam = normal_norm(frames, offs, x0.shape)
    step = 1.2 * len(frames) / max(lam, 1e-9)
    x = x0.copy()
    for _ in range(IBP_ITERS):
        acc = np.zeros_like(x)
        for lr, (dy, dx) in zip(frames, offs):
            acc += camera_t(lr - camera(x, dy, dx), dy, dx)
        x = np.clip(x + step * acc / len(frames), 0, 1)
    return x, step, lam


# ------------------------------------------------------------------- metricas
def psnr(a, b):
    m = float(np.mean((a - b) ** 2))
    return 99.0 if m < 1e-12 else 10.0 * np.log10(1.0 / m)


def acutance(a):
    gy, gx = np.gradient(a)
    return float(np.sqrt(gx ** 2 + gy ** 2).mean())


def diameters(hr):
    sm = gaussian_filter(hr, 1.2)
    u8 = (np.clip(sm, 0, 1) * 255).astype(np.uint8)
    th, _ = cv2.threshold(u8, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    lab, n = label(u8 >= th)
    d = []
    for i in range(1, n + 1):
        a = int((lab == i).sum())
        if 150 < a < 9000:
            d.append(2.0 * np.sqrt(a / np.pi))
    return np.sort(np.array(d, np.float64))


def diam_err(p, gt):
    if p is None or len(p) < 3 or len(gt) < 3:
        return None
    q = np.linspace(0.05, 0.95, 19)
    a = np.interp(q, np.linspace(0, 1, len(gt)), gt)
    b = np.interp(q, np.linspace(0, 1, len(p)), p)
    return float(np.mean(np.abs(b - a)))


# ------------------------------------------------------------------------ run
def run_batch(hr, gt_d, sigma, seed):
    rng = np.random.default_rng(seed)
    offs_true, frames = [], []
    for _ in range(MAX_N):
        o = rng.normal(0, sigma, 2) if sigma > 0 else np.zeros(2)
        offs_true.append((float(o[0]), float(o[1])))
        frames.append(render_frame(hr, o[0], o[1], rng))
    offs_true = np.array(offs_true)
    est = estimate_offsets(frames)

    one = cv2.resize(frames[0], (HR_SIDE, HR_SIDE), interpolation=cv2.INTER_CUBIC)
    aligned = np.mean([cv2.warpAffine(np.ascontiguousarray(f),
                                      np.float32([[1, 0, -e[1]], [0, 1, -e[0]]]),
                                      (f.shape[1], f.shape[0]), flags=cv2.INTER_CUBIC,
                                      borderMode=cv2.BORDER_REFLECT)
                       for f, e in zip(frames, est)], axis=0)
    avg_up = cv2.resize(aligned, (HR_SIDE, HR_SIDE), interpolation=cv2.INTER_CUBIC)
    naive = cv2.resize(np.mean(frames, axis=0), (HR_SIDE, HR_SIDE), interpolation=cv2.INTER_CUBIC)
    dr = drizzle(frames, est)
    ib, step, lam = ibp(frames, est, dr)

    row = dict(
        sigma_px_LR=sigma, N=MAX_N,
        rmse_offset_estimado_px_LR=round(float(np.sqrt(np.mean((est - offs_true) ** 2))), 3),
        psnr_A_1frame_bicubica=round(psnr(one, hr), 2),
        psnr_B_media_alinhada=round(psnr(avg_up, hr), 2),
        psnr_E_media_sem_alinhar=round(psnr(naive, hr), 2),
        psnr_C_burst_drizzle=round(psnr(dr, hr), 2),
        psnr_D_drizzle_ibp=round(psnr(ib, hr), 2),
        acutancia_gt=round(acutance(hr), 4),
        acutancia_A=round(acutance(one), 4),
        acutancia_C=round(acutance(dr), 4),
        acutancia_D=round(acutance(ib), 4),
        erro_diametro_A_px=round(diam_err(diameters(one), gt_d) or -1, 2),
        erro_diametro_B_px=round(diam_err(diameters(avg_up), gt_d) or -1, 2),
        erro_diametro_C_px=round(diam_err(diameters(dr), gt_d) or -1, 2),
        erro_diametro_D_px=round(diam_err(diameters(ib), gt_d) or -1, 2),
        passo_ibp=round(step, 4), n_pelotas_gt=int(len(gt_d)),
    )
    imgs = dict(hr=hr, one=one, avg=avg_up, dr=dr, ib=ib)
    return row, imgs, frames, est


def main(scene):
    t0 = time.time()
    hr = load_scene(scene)
    gt_d = diameters(hr)

    x = np.clip(RNG.normal(size=(16, 16)), 0, 1)
    r = RNG.normal(size=(8, 8))
    adj = abs(float((camera(x, .37, -.21) * r).sum()) - float((x * camera_t(r, .37, -.21)).sum()))
    print(f"auto-teste do adjunto |<Ax,r>-<x,ATr>| = {adj:.3e}", flush=True)

    scenarios = {'trepé perfeito (jitter ~0)': 0.0,
                 'vibração fraca (0,5 px LR)': 0.5,
                 'vibração de montagem (1,5 px LR)': 1.5}
    res, tiles = {}, {}
    for name, sigma in scenarios.items():
        row, im, _, _ = run_batch(hr, gt_d, sigma, 100 + int(sigma * 100))
        res[name] = row
        tiles[name] = im
        print(f"{name} -> {row}", flush=True)

    _, _, frames_s, est_s = run_batch(hr, gt_d, 1.5, 999)
    sweep = {}
    for n in (1, 3, 6, 12, 24):
        d = drizzle(frames_s[:n], est_s[:n])
        a = cv2.resize(np.mean([cv2.warpAffine(np.ascontiguousarray(f),
                                               np.float32([[1, 0, -e[1]], [0, 1, -e[0]]]),
                                               (f.shape[1], f.shape[0]), flags=cv2.INTER_CUBIC,
                                               borderMode=cv2.BORDER_REFLECT)
                                for f, e in zip(frames_s[:n], est_s[:n])], axis=0),
                       (HR_SIDE, HR_SIDE), interpolation=cv2.INTER_CUBIC)
        sweep[n] = dict(psnr_C=round(psnr(d, hr), 2), psnr_B=round(psnr(a, hr), 2),
                        acut_C=round(acutance(d), 4),
                        erro_diam_C_px=round(diam_err(diameters(d), gt_d) or -1, 2))
        print(f"N={n}: {sweep[n]}", flush=True)

    out = {'cena': scene.split('/')[-1], 'adjunto_abs_erot': adj, 'resultados': res,
           'varredura_N': sweep,
           'config': dict(S=S, LR_SIDE=LR_SIDE, NOISE_LR=NOISE_LR, MAX_N=MAX_N,
                          IBP_ITERS=IBP_ITERS)}
    path = '/home/ubuntu/pelotavision-sr/burst_sr_results.json'
    json.dump(out, open(path, 'w'), indent=2, ensure_ascii=False)

    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    names = list(res)
    fig, ax = plt.subplots(len(names), 5, figsize=(15, 3.1 * len(names)))
    heads = ['GT (pixels 2× menores)', '1 frame + bicúbica', f'média alinhada N={MAX_N}',
             f'burst drizzle 2× (N={MAX_N})', 'drizzle + IBP']
    for r_, name in enumerate(names):
        im = tiles[name]
        for c, (t, img) in enumerate(zip(heads, [im['hr'], im['one'], im['avg'], im['dr'], im['ib']])):
            ax[r_, c].imshow(img, cmap='gray', vmin=0, vmax=1)
            ax[r_, c].set_title(f'{t}\n{psnr(img, im["hr"]):.1f} dB', fontsize=8.5)
            ax[r_, c].axis('off')
        ax[r_, 0].set_ylabel(name, fontsize=8, rotation=0, ha='right', va='center')
    fig.suptitle('Empilhar fotos com tremor sub-pixel — pelotas reais de correia, sensor simulado '
                 '2× pior, ruído 35/255 por frame, N=24', fontsize=11)
    fig.tight_layout(rect=[0, 0, 1, 0.96])
    png = '/home/ubuntu/pelotavision-sr/burst_sr_comparacao.png'
    fig.savefig(png, dpi=115)

    fig2, ax2 = plt.subplots(figsize=(8.8, 4.9))
    ns = [int(k) for k in sweep]
    ax2.plot(ns, [sweep[k]['psnr_B'] for k in ns], 'o-', label='média alinhada (só ruído, ~√N)')
    ax2.plot(ns, [sweep[k]['psnr_C'] for k in ns], 's-', label='burst drizzle 2× (tenta resolução)')
    ax2.axhline(res['vibração de montagem (1,5 px LR)']['psnr_A_1frame_bicubica'], ls='--',
                color='gray', label='1 frame + bicúbica (sem burst)')
    ax2.axhline(res['trepé perfeito (jitter ~0)']['psnr_C_burst_drizzle'], ls=':', color='red',
                label='drizzle com jitter ~0 (nada a alinhar)')
    ax2.set_xlabel('frames na rajada (N)')
    ax2.set_ylabel('PSNR vs cena real (dB)')
    ax2.set_title('O ruído cai com √N; a resolução só aparece com fases sub-pixel distintas')
    ax2.grid(alpha=.3)
    ax2.legend(fontsize=8)
    fig2.tight_layout()
    png2 = '/home/ubuntu/pelotavision-sr/burst_sr_ganho.png'
    fig2.savefig(png2, dpi=115)
    print('json:', path); print('png :', png); print('png2:', png2)
    print('tempo total', round(time.time() - t0, 1), 's')


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else
         '/home/ubuntu/pelotavision-sr/repo/ml_training/data_belt/raw/20170619_09-52-13.jpg')
