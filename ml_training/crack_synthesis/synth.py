"""Gerador procedural de trincas sobre recortes de pelotas REAIS sem trinca.

Nenhuma rede generativa: a trinca é um caminho desenhado por algoritmo sobre a
superfície de uma esfera (projeção ortográfica), rasterizado com superamostragem.
A máscara é exata, porque é o próprio desenho. Cada amostra é determinística
para (seed, índice).

Pipeline de uma amostra (render_sample):
 1. doadora real (foto, x, y, r) -> recorte colorido, rotação e espelhamento;
 2. escala de trabalho = min(1, raio_alvo/raio_doadora) (desenha na resolução
    mais grosseira das duas, para a trinca não ficar mais nítida que a textura);
 3. trinca(s) ou distratores (negativos difíceis) aplicados como modulação
    multiplicativa da refletância;
 4. ampliação até o raio-alvo (20-120 px);
 5. aquisição: iluminação (gradiente, ganho, gamma, contraste), desfoque, ruído,
    JPEG; a mesma aquisição é aplicada à versão sem trinca (par limpo);
 6. o círculo usado na extração recebe erro de centro/raio, como o detector.
"""
import math

import cv2
import numpy as np

SS = 4  # superamostragem do desenho


# ----------------------------------------------------------------- utilidades
def _smooth_noise(rng, n, scale):
    """Ruído 1-D suave (n amostras, correlação ~scale amostras)."""
    k = max(2, int(n / max(scale, 1)) + 2)
    pts = rng.normal(0, 1, k)
    return np.interp(np.linspace(0, k - 1, n), np.arange(k), pts)


def _field_noise(rng, h, w, sigma):
    f = rng.normal(0, 1, (h, w)).astype(np.float32)
    f = cv2.GaussianBlur(f, (0, 0), sigma)
    return f / (f.std() + 1e-6)


def _rotate_tangent(p, t, ang):
    b = np.cross(p, t)
    t = t * math.cos(ang) + b * math.sin(ang)
    t = t - p * np.dot(t, p)
    return t / (np.linalg.norm(t) + 1e-12)


# ------------------------------------------------------- geometria das trincas
def sphere_walk(rng, p0, t0, length, ds, turn_sigma, kink_p, bias):
    """Caminho na superfície da esfera unitária (hemisfério visível z>0).

    Devolve lista de pontos 3-D e se parou no limbo (trinca que chega à borda)."""
    p = p0 / np.linalg.norm(p0)
    t = t0 - p * np.dot(t0, p)
    t /= np.linalg.norm(t) + 1e-12
    pts = [p.copy()]
    s = 0.0
    limb = False
    while s < length:
        ang = bias + rng.normal(0, turn_sigma)
        if rng.random() < kink_p:
            ang += rng.choice([-1, 1]) * rng.uniform(0.2, 0.6)
        t = _rotate_tangent(p, t, ang)
        p = p + t * ds
        p /= np.linalg.norm(p)
        t = t - p * np.dot(t, p)
        t /= np.linalg.norm(t) + 1e-12
        pts.append(p.copy())
        s += ds
        if p[2] < 0.05:
            limb = True
            break
    return np.array(pts), limb


def _start_point(rng, mode):
    if mode == 'borda':
        a = rng.uniform(0, 2 * math.pi)
        rho = rng.uniform(0.93, 0.985)
    else:
        a = rng.uniform(0, 2 * math.pi)
        rho = math.sqrt(rng.uniform(0, 1)) * 0.72
    x, y = rho * math.cos(a), rho * math.sin(a)
    return np.array([x, y, math.sqrt(max(1e-4, 1 - x * x - y * y))])


def _heading(rng, p, tendency):
    radial = np.array([p[0], p[1], 0.0])
    if np.linalg.norm(radial) < 1e-3:
        radial = np.array([1.0, 0, 0])
    radial = radial - p * np.dot(radial, p)
    radial /= np.linalg.norm(radial)
    tang = np.cross(p, radial)
    if tendency == 'radial':
        base = radial * rng.choice([-1, 1])
        jitter = rng.normal(0, 0.35)
    elif tendency == 'tangencial':
        base = tang * rng.choice([-1, 1])
        jitter = rng.normal(0, 0.35)
    else:
        base = radial
        jitter = rng.uniform(0, 2 * math.pi)
    return _rotate_tangent(p, base, jitter)


def crack_geometry(rng, kind):
    """Lista de polilinhas 3-D com largura relativa e flag de ponta livre.

    kind: 'fina', 'media', 'aberta', 'rede'."""
    polys = []
    ds = 0.03
    turn = rng.uniform(0.02, 0.10)
    kink = rng.uniform(0.0, 0.05)
    tendency = rng.choice(['radial', 'tangencial', 'livre'], p=[0.4, 0.3, 0.3])
    if kind == 'rede':
        center = _start_point(rng, 'interior')
        base = rng.uniform(0, 2 * math.pi)
        arms = rng.integers(3, 5)
        h0 = _heading(rng, center, 'livre')
        for i in range(arms):
            ang = base + i * 2 * math.pi / arms + rng.normal(0, 0.3)
            h = _rotate_tangent(center, h0, ang)
            L = rng.uniform(0.35, 1.0)
            pts, limb = sphere_walk(rng, center, h, L, ds, turn, kink, rng.normal(0, 0.03))
            polys.append({'pts': pts, 'w': rng.uniform(0.6, 1.0), 'tip_start': False, 'tip_end': not limb})
        # pequenas trincas secundárias conectadas
        for _ in range(rng.integers(0, 3)):
            src = polys[rng.integers(len(polys))]['pts']
            if len(src) < 4:
                continue
            p = src[rng.integers(1, len(src) - 1)]
            h = _heading(rng, p, 'livre')
            pts, limb = sphere_walk(rng, p, h, rng.uniform(0.15, 0.45), ds, turn, kink, 0)
            polys.append({'pts': pts, 'w': rng.uniform(0.4, 0.7), 'tip_start': False, 'tip_end': not limb})
        return polys
    mode = 'borda' if rng.random() < 0.3 else 'interior'
    p0 = _start_point(rng, mode)
    h = _heading(rng, p0, 'radial' if mode == 'borda' else tendency)
    if mode == 'borda':
        # aponta para dentro
        inward = -np.array([p0[0], p0[1], 0.0])
        if np.dot(h, inward) < 0:
            h = -h
        L = rng.uniform(0.5, 2.0)
        pts, limb = sphere_walk(rng, p0, h, L, ds, turn, kink, rng.normal(0, 0.02))
        polys.append({'pts': pts, 'w': 1.0, 'tip_start': False, 'tip_end': not limb})
    else:
        L = rng.uniform(0.7, 2.4)
        frac = rng.uniform(0.3, 0.7)
        bias = rng.normal(0, 0.025)
        a, la = sphere_walk(rng, p0, h, L * frac, ds, turn, kink, bias)
        b, lb = sphere_walk(rng, p0, -h, L * (1 - frac), ds, turn, kink, -bias)
        pts = np.concatenate([b[::-1], a[1:]])
        polys.append({'pts': pts, 'w': 1.0, 'tip_start': not lb, 'tip_end': not la})
    nb = rng.choice([0, 1, 2, 3], p=[0.35, 0.35, 0.2, 0.1])
    main = polys[0]['pts']
    for _ in range(nb):
        if len(main) < 5:
            break
        i = rng.integers(2, len(main) - 2)
        p = main[i]
        t = main[i + 1] - main[i - 1]
        t /= np.linalg.norm(t) + 1e-12
        hb = _rotate_tangent(p, t, rng.choice([-1, 1]) * rng.uniform(0.45, 1.3))
        pts, limb = sphere_walk(rng, p, hb, rng.uniform(0.12, 0.55) * max(0.5, len(main) * ds), ds, turn * 1.2, kink, 0)
        polys.append({'pts': pts, 'w': rng.uniform(0.35, 0.75), 'tip_start': False, 'tip_end': not limb})
    return polys


# ------------------------------------------------------------------ rasterização
def _draw_polys(shape_ss, polys, center, radius, w_px, depth, rng, core=False, rough=0.5):
    """Desenha em canvas superamostrado; devolve (cobertura*profundidade) na escala de trabalho."""
    H, W = shape_ss
    canvas = np.zeros((H, W), np.float32)
    for poly in polys:
        pts = poly['pts']
        n = len(pts)
        if n < 2:
            continue
        s = np.arange(n) / max(n - 1, 1)
        wn = 1 + 0.35 * _smooth_noise(rng, n, 6)
        dn = np.clip(1 + 0.25 * _smooth_noise(rng, n, 8), 0.4, 1.5)
        taper = np.ones(n)
        tl = max(3, int(n * rng.uniform(0.12, 0.3)))
        ramp = (np.arange(tl) + 1) / tl
        if poly['tip_start']:
            taper[:tl] = np.minimum(taper[:tl], ramp ** 0.7)
        if poly['tip_end']:
            taper[-tl:] = np.minimum(taper[-tl:], ramp[::-1] ** 0.7)
        z = np.clip(pts[:, 2], 0, 1)
        fore = 0.3 + 0.7 * np.sqrt(z)  # escorço perto do limbo
        width = np.clip(w_px * poly['w'] * wn * taper * fore, 0, None) * (0.45 if core else 1.0)
        dval = np.clip(depth * dn * (0.55 + 0.45 * np.sqrt(z)), 0, 0.97)
        xy = np.stack([center[0] + radius * pts[:, 0], center[1] + radius * pts[:, 1]], 1)
        # aspereza: deslocamento lateral de alta frequência (zigue-zague fino das trincas reais)
        tan = np.gradient(xy, axis=0)
        nrm = np.stack([-tan[:, 1], tan[:, 0]], 1) / (np.linalg.norm(tan, axis=1, keepdims=True) + 1e-9)
        amp = rough * (0.25 + 0.5 * width / max(w_px, 1e-3))
        xy = (xy + nrm * (amp * _smooth_noise(rng, n, 1.5))[:, None]) * SS
        for i in range(n - 1):
            wss = width[i] * SS
            if wss <= 0.05:
                continue
            th = max(1, int(round(wss)))
            val = float(dval[i] * min(1.0, wss / th))
            p1 = tuple(np.round(xy[i] * 16).astype(int))
            p2 = tuple(np.round(xy[i + 1] * 16).astype(int))
            cv2.line(canvas, p1, p2, val, th, cv2.LINE_AA, shift=4)
    return cv2.resize(canvas, (W // SS, H // SS), interpolation=cv2.INTER_AREA)


def _chips(rng, shape, polys, center, radius, w_px, n):
    """Lascas: pequenas concavidades irregulares coladas ao caminho."""
    H, W = shape
    m = np.zeros((H * SS, W * SS), np.uint8)
    allpts = np.concatenate([p['pts'] for p in polys])
    for _ in range(n):
        p = allpts[rng.integers(len(allpts))]
        c = np.array([center[0] + radius * p[0], center[1] + radius * p[1]])
        size = max(0.8, w_px * rng.uniform(1.0, 2.8))
        k = rng.integers(5, 9)
        ang = np.sort(rng.uniform(0, 2 * math.pi, k))
        rr = size * rng.uniform(0.5, 1.3, k)
        off = rng.normal(0, size * 0.6, 2)
        poly = np.stack([c[0] + off[0] + rr * np.cos(ang), c[1] + off[1] + rr * np.sin(ang)], 1) * SS
        cv2.fillPoly(m, [np.round(poly).astype(np.int32)], 255, cv2.LINE_AA)
    return cv2.resize(m.astype(np.float32) / 255, (W, H), interpolation=cv2.INTER_AREA)


def _relief(img, cover, light, strength_dark, strength_lit, sigma):
    """Paredes da trinca: uma iluminada, outra em sombra, conforme a direção da luz."""
    if strength_dark <= 0 and strength_lit <= 0:
        return img
    cb = cv2.GaussianBlur(cover, (0, 0), max(0.5, sigma))
    gx = cv2.Sobel(cb, cv2.CV_32F, 1, 0, ksize=3) / 8
    gy = cv2.Sobel(cb, cv2.CV_32F, 0, 1, ksize=3) / 8
    d = gx * light[0] + gy * light[1]
    d /= (np.abs(d).max() + 1e-6)
    mod = 1 - strength_dark * np.clip(d, 0, None) + strength_lit * np.clip(-d, 0, None) * (1 - cover)
    return img * mod[..., None]


# ------------------------------------------------------------- negativos difíceis
def distractor(rng, img, center, radius, kind, scale_px):
    """Aplica um distrator (não-trinca) na escala de trabalho. scale_px = px de trabalho por px-alvo."""
    H, W = img.shape[:2]
    yy, xx = np.mgrid[:H, :W].astype(np.float32)
    if kind == 'poros':
        m = np.zeros((H * SS, W * SS), np.float32)
        for _ in range(rng.integers(3, 26)):
            a = rng.uniform(0, 2 * math.pi)
            rho = math.sqrt(rng.uniform(0, 1)) * 0.85 * radius
            r = rng.uniform(0.4, 2.2) * scale_px
            ax = (r * rng.uniform(0.7, 1.3) * SS, r * rng.uniform(0.7, 1.3) * SS)
            c = ((center[0] + rho * math.cos(a)) * SS, (center[1] + rho * math.sin(a)) * SS)
            cv2.ellipse(m, (int(c[0]), int(c[1])), (max(1, int(ax[0])), max(1, int(ax[1]))), rng.uniform(0, 180), 0, 360,
                        float(rng.uniform(0.3, 0.75)), -1, cv2.LINE_AA)
        m = cv2.resize(m, (W, H), interpolation=cv2.INTER_AREA)
        return img * (1 - m)[..., None]
    if kind == 'risco':
        m = np.zeros((H * SS, W * SS), np.float32)
        bright = rng.random() < 0.6
        for _ in range(rng.integers(1, 4)):
            a = rng.uniform(0, 2 * math.pi)
            rho = math.sqrt(rng.uniform(0, 1)) * 0.7 * radius
            c = np.array([center[0] + rho * math.cos(a), center[1] + rho * math.sin(a)])
            L = rng.uniform(0.08, 0.3) * radius
            th = rng.uniform(0, math.pi)
            d = np.array([math.cos(th), math.sin(th)]) * L / 2
            w = max(1, int(round(rng.uniform(0.5, 1.2) * scale_px * SS)))
            v = rng.uniform(0.15, 0.35) if bright else rng.uniform(0.08, 0.2)
            cv2.line(m, tuple(((c - d) * SS * 16).astype(int)), tuple(((c + d) * SS * 16).astype(int)), float(v), w, cv2.LINE_AA, 4)
        m = cv2.resize(m, (W, H), interpolation=cv2.INTER_AREA)
        return img * (1 + m)[..., None] if bright else img * (1 - m)[..., None]
    if kind == 'sombra':
        a = rng.uniform(0, 2 * math.pi)
        off = rng.uniform(0.15, 0.4) * radius
        c = (center[0] - off * math.cos(a), center[1] - off * math.sin(a))
        dist = np.hypot(xx - c[0], yy - c[1])
        soft = rng.uniform(0.05, 0.22) * radius
        m = np.clip((dist - radius * rng.uniform(0.95, 1.05)) / soft + 0.5, 0, 1)
        return img * (1 - rng.uniform(0.3, 0.75) * m)[..., None]
    if kind == 'granular':
        f = _field_noise(rng, H, W, rng.uniform(0.5, 1.8) * scale_px)
        return img * (1 + rng.uniform(0.05, 0.18) * f)[..., None]
    if kind == 'umidade':
        f = _field_noise(rng, H, W, rng.uniform(0.15, 0.35) * radius)
        t = rng.uniform(0.3, 1.2)
        m = np.clip((f - t) / rng.uniform(0.2, 0.6), 0, 1)
        return img * (1 - rng.uniform(0.1, 0.35) * m)[..., None]
    if kind == 'relevo':
        m = np.zeros((H, W), np.float32)
        for _ in range(rng.integers(2, 8)):
            a = rng.uniform(0, 2 * math.pi)
            rho = math.sqrt(rng.uniform(0, 1)) * 0.8 * radius
            r = rng.uniform(1.0, 3.5) * scale_px
            cv2.circle(m, (int(center[0] + rho * math.cos(a)), int(center[1] + rho * math.sin(a))), max(1, int(r)), 1.0, -1, cv2.LINE_AA)
        light = rng.normal(0, 1, 2)
        light /= np.linalg.norm(light)
        out = _relief(img, m, light, rng.uniform(0.1, 0.3), rng.uniform(0.1, 0.3), 1.2 * scale_px)
        return out
    raise ValueError(kind)


DISTRACTORS = ['poros', 'risco', 'sombra', 'granular', 'umidade', 'relevo']


# ---------------------------------------------------------------- aquisição
def acquisition_params(rng):
    return {
        'grad': rng.uniform(0, 0.5), 'grad_dir': rng.uniform(0, 2 * math.pi),
        'gain': rng.uniform(0.6, 1.15), 'gamma': math.exp(rng.uniform(math.log(0.65), math.log(1.6))),
        'contrast': rng.uniform(0.7, 1.2),
        'blur': 0.0 if rng.random() < 0.35 else rng.uniform(0.3, 1.4),
        'motion': rng.uniform(2, 4) if rng.random() < 0.1 else 0.0, 'motion_dir': rng.uniform(0, 180),
        'noise': rng.uniform(0, 0.025), 'shot': rng.uniform(0, 0.02),
        'jpeg': int(rng.uniform(35, 95)) if rng.random() < 0.7 else 0,
        'noise_seed': int(rng.integers(1 << 31)),
    }


def acquire(img, P, radius):
    """img float BGR [0,1] na escala-alvo; mesma P -> mesma degradação (pares limpos)."""
    H, W = img.shape[:2]
    yy, xx = np.mgrid[:H, :W].astype(np.float32)
    d = ((xx - W / 2) * math.cos(P['grad_dir']) + (yy - H / 2) * math.sin(P['grad_dir'])) / max(radius, 1)
    out = img * (P['gain'] * (1 + P['grad'] * np.clip(d, -1.5, 1.5) / 1.5))[..., None]
    out = np.clip(out, 0, 1) ** P['gamma']
    m = out.mean()
    out = (out - m) * P['contrast'] + m
    if P['blur'] > 0:
        out = cv2.GaussianBlur(out, (0, 0), P['blur'])
    if P['motion'] > 0:
        L = int(round(P['motion'])) * 2 + 1
        k = np.zeros((L, L), np.float32)
        k[L // 2, :] = 1
        k = cv2.warpAffine(k, cv2.getRotationMatrix2D((L / 2 - .5, L / 2 - .5), P['motion_dir'], 1), (L, L))
        out = cv2.filter2D(out, -1, k / k.sum())
    nr = np.random.default_rng(P['noise_seed'])
    out = out + nr.normal(0, 1, out.shape).astype(np.float32) * (P['noise'] + P['shot'] * np.sqrt(np.clip(out, 0, 1)))
    u8 = np.clip(np.rint(out * 255), 0, 255).astype(np.uint8)
    if P['jpeg']:
        ok, buf = cv2.imencode('.jpg', u8, [cv2.IMWRITE_JPEG_QUALITY, P['jpeg']])
        u8 = cv2.imdecode(buf, cv2.IMREAD_COLOR)
    return u8


# ---------------------------------------------------------------- amostra
KINDS = ['fina', 'media', 'aberta', 'rede']
KIND_P = [0.3, 0.3, 0.2, 0.2]


def crack_params(rng, kind, R):
    """Largura em px-alvo = fração do raio (a mesma trinca em mm fica mais fina em px numa
    foto de menor resolução), com piso de 0,45 px para continuar minimamente visível."""
    if kind == 'fina':
        p = dict(wf=rng.uniform(0.005, 0.014), depth=rng.uniform(0.3, 0.6), core=0, chips=0, relief=rng.uniform(0, 0.15))
    elif kind == 'media':
        p = dict(wf=rng.uniform(0.012, 0.028), depth=rng.uniform(0.4, 0.8), core=rng.uniform(0, 0.3),
                 chips=rng.integers(0, 4) if rng.random() < 0.35 else 0, relief=rng.uniform(0.05, 0.3))
    elif kind == 'aberta':
        p = dict(wf=rng.uniform(0.026, 0.05), depth=rng.uniform(0.45, 0.8), core=rng.uniform(0.1, 0.45),
                 chips=rng.integers(1, 5) if rng.random() < 0.5 else 0, relief=rng.uniform(0.15, 0.45))
    else:
        p = dict(wf=rng.uniform(0.008, 0.022), depth=rng.uniform(0.35, 0.75), core=rng.uniform(0, 0.25),
                 chips=rng.integers(0, 3) if rng.random() < 0.25 else 0, relief=rng.uniform(0.05, 0.25))
    p['w'] = max(0.45, p['wf'] * R)
    return p


def render_sample(photo_bgr, donor, rng, positive, target_radius=None, kind=None, distractors=None):
    """Gera uma amostra. Devolve dict com 'image' (BGR uint8 alvo), 'clean' (mesma aquisição sem
    trinca, só se positive), 'mask' (float [0,1] na escala-alvo), círculo de extração e parâmetros."""
    x, y, rd = donor
    Rt = target_radius if target_radius is not None else float(np.exp(rng.uniform(math.log(20), math.log(min(120, 2.5 * rd)))))
    s_w = min(1.0, Rt / rd)
    half_d = int(math.ceil(1.45 * rd))
    ang = rng.uniform(0, 360)
    M = cv2.getRotationMatrix2D((half_d, half_d), ang, 1.0)
    M[:, 2] += [x - half_d - (M[0, 0] * 0 + 0), y - half_d]
    # recorte rotacionado: mapeia pixel do recorte -> foto
    T = np.array([[1, 0, x - half_d], [0, 1, y - half_d]], np.float64)
    R = cv2.getRotationMatrix2D((half_d, half_d), ang, 1.0)
    Rinv = cv2.invertAffineTransform(R)
    A = T @ np.vstack([Rinv, [0, 0, 1]])
    crop = cv2.warpAffine(photo_bgr, A, (2 * half_d, 2 * half_d), flags=cv2.INTER_LINEAR | cv2.WARP_INVERSE_MAP,
                          borderMode=cv2.BORDER_REFLECT_101)
    if rng.random() < 0.5:
        crop = crop[:, ::-1]
    Ww = max(8, int(round(2 * half_d * s_w)))
    work = cv2.resize(crop, (Ww, Ww), interpolation=cv2.INTER_AREA if s_w < 1 else cv2.INTER_LINEAR).astype(np.float32) / 255
    rw = rd * Ww / (2 * half_d)
    cw = (Ww / 2, Ww / 2)
    px_per_target = rw / Rt  # px de trabalho por px-alvo (<=1)
    light = rng.normal(0, 1, 2)
    light /= np.linalg.norm(light)
    info = {'donor_radius': rd, 'target_radius': Rt, 'rotation': ang, 'positive': bool(positive)}
    base = work.copy()
    dlist = distractors if distractors is not None else []
    for dk in dlist:
        base = distractor(rng, base, cw, rw, dk, px_per_target)
    info['distractors'] = list(dlist)
    cover = np.zeros(work.shape[:2], np.float32)
    img = base.copy()
    if positive:
        kind = kind or rng.choice(KINDS, p=KIND_P)
        cp = crack_params(rng, kind, Rt)
        polys = crack_geometry(rng, kind)
        if kind == 'rede' or rng.random() < 0.12:
            # segunda trinca independente
            if kind != 'rede' and rng.random() < 0.5:
                polys = polys + crack_geometry(rng, 'fina')
        w_work = cp['w'] * px_per_target
        shape_ss = (Ww * SS, Ww * SS)
        rough = rng.uniform(0.15, 0.7) * px_per_target * (1 + 0.3 * cp['w'])
        cov = _draw_polys(shape_ss, polys, cw, rw, w_work, cp['depth'], rng, rough=rough)
        # PSF da foto doadora: a trinca não pode ser mais nítida que a textura em volta
        psf = rng.uniform(0.35, 0.9)
        cov = cv2.GaussianBlur(cov, (0, 0), psf)
        cover = np.clip(cov / max(cp['depth'], 1e-3), 0, 1)
        img = img * (1 - cov)[..., None]
        if cp['core'] > 0:
            core = cv2.GaussianBlur(_draw_polys(shape_ss, polys, cw, rw, w_work, cp['core'], rng, core=True, rough=rough), (0, 0), psf)
            img = img * (1 - core)[..., None]
        img = _relief(img, cover, light, cp['relief'], cp['relief'] * 0.8, 0.6 * max(w_work, 0.5))
        if cp['chips']:
            ch = _chips(rng, (Ww, Ww), polys, cw, rw, max(w_work, 0.6 * px_per_target), int(cp['chips']))
            img = img * (1 - rng.uniform(0.25, 0.6) * ch)[..., None]
            img = _relief(img, ch, light, 0.15, 0.25, 0.8)
            cover = np.maximum(cover, ch)
        limb = any(not p['tip_end'] or not p['tip_start'] for p in polys)
        info.update({'kind': kind, 'width_target_px': cp['w'], 'depth': cp['depth'], 'branches': len(polys) - 1,
                     'chips': int(cp['chips']), 'reaches_edge': bool(limb), 'psf_sigma_work_px': psf})
    # ampliação até o raio-alvo
    scale_up = Rt / rw
    Wt = max(8, int(round(Ww * scale_up)))
    up = cv2.INTER_CUBIC if scale_up > 1.01 else cv2.INTER_LINEAR

    def to_target(a):
        return cv2.resize(a, (Wt, Wt), interpolation=up)
    P = acquisition_params(rng)
    out = acquire(np.clip(to_target(img), 0, 1.5), P, Rt)
    res = {'image': out, 'acq': P, 'info': info}
    if positive:
        res['clean'] = acquire(np.clip(to_target(base), 0, 1.5), P, Rt)
        res['mask'] = np.clip(cv2.resize(cover, (Wt, Wt), interpolation=cv2.INTER_LINEAR), 0, 1)
    c = Wt / 2 - 0.5
    jit = rng.normal(0, 0.04, 2) * Rt
    res['circle'] = (c + float(np.clip(jit[0], -0.08 * Rt, 0.08 * Rt)), c + float(np.clip(jit[1], -0.08 * Rt, 0.08 * Rt)),
                     Rt * float(rng.uniform(0.9, 1.08)))
    res['center'] = (c, c)
    return res
