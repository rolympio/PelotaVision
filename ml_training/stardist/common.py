"""StarDist para pelotas — partes sem PyTorch (dados, alvos, pós-processamento, métricas).

Reimplementação do método StarDist (Schmidt et al., MICCAI 2018; Weigert et al., WACV 2020):
cada pixel prevê a probabilidade de ser interior de objeto (distância normalizada à borda)
e as distâncias até a borda do próprio objeto em RAYS direções. Objetos são polígonos
estrelados; duplicatas saem por supressão de não máximos entre polígonos.

O pós-processamento aqui é a REFERÊNCIA do Java (core/NeuralPelletDetector.java):
mesma escala, normalização, blocos, candidatos, NMS e saída. Mudou um, mude o outro.
"""
import json, math
from pathlib import Path
import numpy as np, cv2
from scipy import ndimage

RAYS = 32
NOMINAL_RADIUS = 16.0          # raio (px) das pelotas na escala de entrada da rede
TILE = 384                     # entrada fixa da rede (1x1xTILExTILE)
OVERLAP = 64
NORM_LOW, NORM_HIGH = 0.01, 0.998
NMS_OVERLAP = 0.5              # interseção / menor área
MIN_RADIUS_NET = 3.0
MAX_CANDIDATES, MAX_OBJECTS = 20000, 600
ANGLES = 2 * np.pi * np.arange(RAYS) / RAYS
COS, SIN = np.cos(ANGLES), np.sin(ANGLES)
# Raios esperados (fração da largura) por perfil: 0 pequenas, 1 médias, 2 grandes, 3 auto.
PROFILE_RADII = {0: [.022], 1: [.035], 2: [.065], 3: [.02, .042, .09]}

def threshold(sensitivity):
    return max(.2, min(.8, .75 - .005 * sensitivity))

# ---------------------------------------------------------------- dados

def load_scenes(data_dir):
    data_dir = Path(data_dir)
    scenes = []
    for s in json.load(open(data_dir / 'annotations.json', encoding='utf-8')):
        bgr = cv2.imread(str(data_dir / f"{s['group']}.png"), cv2.IMREAD_COLOR)
        if bgr is None:
            raise FileNotFoundError(s['group'])
        circles = np.array([[a['x'], a['y'], a['radius']] for a in s['annotations']], np.float64)
        partial = np.array([bool(a.get('partial', False)) for a in s['annotations']])
        scenes.append(dict(group=s['group'], gray=cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY), circles=circles, partial=partial))
    return scenes

def render_labels(shape, circles):
    """Rótulo por pixel: disco mais próximo em distância normalizada (contato vira fronteira)."""
    h, w = shape
    lab = np.zeros((h, w), np.int32)
    best = np.full((h, w), np.inf)
    for i, (x, y, r) in enumerate(circles, 1):
        x0, x1 = max(0, int(x - r - 1)), min(w, int(x + r + 2))
        y0, y1 = max(0, int(y - r - 1)), min(h, int(y + r + 2))
        if x1 <= x0 or y1 <= y0:
            continue
        yy, xx = np.mgrid[y0:y1, x0:x1]
        d = np.hypot(xx - x, yy - y) / r
        sub, cur = lab[y0:y1, x0:x1], best[y0:y1, x0:x1]
        take = (d < 1) & (d < cur)
        sub[take] = i
        cur[take] = d[take]
    return lab

def prob_target(lab):
    """Distância euclidiana à borda do PRÓPRIO objeto, normalizada pelo máximo do objeto."""
    prob = np.zeros(lab.shape, np.float32)
    for i, sl in enumerate(ndimage.find_objects(lab), 1):
        if sl is None:
            continue
        sl = tuple(slice(max(0, s.start - 1), s.stop + 1) for s in sl)
        m = lab[sl] == i
        e = ndimage.distance_transform_edt(np.pad(m, 1))[1:-1, 1:-1]
        if e.max() > 0:
            prob[sl][m] = (e[m] / e.max()).astype(np.float32)
    return prob

def star_distances(lab, max_dist=None, step=.5):
    """Distância de cada pixel de objeto até sair do próprio rótulo, em RAYS direções.
    Fora da imagem conta como fundo. Resultado (RAYS,H,W), zero no fundo."""
    h, w = lab.shape
    ys, xs = np.nonzero(lab)
    own = lab[ys, xs]
    dist = np.zeros((RAYS, h, w), np.float32)
    if len(ys) == 0:
        return dist
    if max_dist is None:
        max_dist = 2.5 * max(h, w)
    for k in range(RAYS):
        out = np.zeros(len(ys), np.float32)
        alive = np.ones(len(ys), bool)
        t = step
        while alive.any() and t <= max_dist:
            idx = np.nonzero(alive)[0]
            px = np.rint(xs[idx] + t * COS[k]).astype(np.int64)
            py = np.rint(ys[idx] + t * SIN[k]).astype(np.int64)
            inside = (px >= 0) & (py >= 0) & (px < w) & (py < h)
            same = np.zeros(len(idx), bool)
            same[inside] = lab[py[inside], px[inside]] == own[idx[inside]]
            stop = idx[~same]
            out[stop] = t - step / 2
            alive[stop] = False
            t += step
        out[alive] = max_dist
        dist[k, ys, xs] = out
    return dist

# Simetrias D4 com permutação dos raios (ângulo θ_k = 2πk/RAYS, y para baixo).
def ray_permutation(flip_x, flip_y, transpose):
    j = np.arange(RAYS)
    idx = j.copy()
    # Ordem de aplicação na imagem: transpose -> flip_x -> flip_y. Para os raios, compõe-se na ordem inversa.
    if flip_y:
        idx = (-idx) % RAYS
    if flip_x:
        idx = (RAYS // 2 - idx) % RAYS
    if transpose:
        idx = (RAYS // 4 - idx) % RAYS
    return idx

def apply_d4(arr, flip_x, flip_y, transpose):
    """arr (..., H, W)."""
    if transpose:
        arr = np.swapaxes(arr, -1, -2)
    if flip_x:
        arr = arr[..., :, ::-1]
    if flip_y:
        arr = arr[..., ::-1, :]
    return np.ascontiguousarray(arr)

# ---------------------------------------------------------------- normalização / escala

def norm_stats(gray8):
    """Percentis do histograma de 8 bits: menor nível v com acumulado >= q*n."""
    hist = np.bincount(gray8.ravel(), minlength=256).astype(np.int64)
    cdf = np.cumsum(hist)
    n = int(cdf[-1])
    lo = int(np.searchsorted(cdf, n * NORM_LOW))
    hi = int(np.searchsorted(cdf, n * NORM_HIGH))
    med = int(np.searchsorted(cdf, n * .5))
    if hi - lo < 8:
        hi = lo + 8
    return lo, hi, med

def normalize(gray8, stats):
    lo, hi, _ = stats
    return ((gray8.astype(np.float32) - lo) / np.float32(hi - lo)).astype(np.float32)

def resize_for_net(gray, scale):
    h, w = gray.shape
    w2, h2 = max(1, int(round(w * scale))), max(1, int(round(h * scale)))
    interp = cv2.INTER_AREA if scale < 1 else cv2.INTER_LINEAR
    return cv2.resize(gray, (w2, h2), interpolation=interp)

def tile_starts(length):
    if length <= TILE:
        return [0]
    stride = TILE - OVERLAP
    starts = list(range(0, length - TILE, stride))
    if starts[-1] != length - TILE:
        starts.append(length - TILE)
    return starts

def own_bounds(starts, length):
    """Região de posse de cada bloco: ponto médio da sobreposição com o vizinho."""
    out = []
    for i, s in enumerate(starts):
        lo = 0 if i == 0 else (starts[i - 1] + TILE + s) / 2.
        hi = length if i == len(starts) - 1 else (s + TILE + starts[i + 1]) / 2.
        out.append((lo, hi))
    return out

# ---------------------------------------------------------------- inferência + pós-processamento

def candidates_for_scale(run, gray, scale, thr):
    """run(tile float32 [TILE,TILE]) -> (33,TILE,TILE). Devolve [(score, polígono (RAYS,2) em px originais)]."""
    small = resize_for_net(gray, scale)
    h2, w2 = small.shape
    stats = norm_stats(small)
    normed = normalize(small, stats)
    fill = np.float32((stats[2] - stats[0]) / (stats[1] - stats[0]))
    xs, ys = tile_starts(w2), tile_starts(h2)
    bx, by = own_bounds(xs, w2), own_bounds(ys, h2)
    out = []
    for ty, y0 in enumerate(ys):
        for tx, x0 in enumerate(xs):
            tile = np.full((TILE, TILE), fill, np.float32)
            th, tw = min(TILE, h2 - y0), min(TILE, w2 - x0)
            tile[:th, :tw] = normed[y0:y0 + th, x0:x0 + tw]
            pred = run(tile)
            prob, dist = pred[0], np.maximum(pred[1:], 0)
            # Máximo local 3x3 (vizinhos fora do bloco ignorados) acima do limiar.
            pad = np.pad(prob, 1, constant_values=-np.inf)
            neigh = np.max(np.stack([pad[1 + dy:1 + dy + TILE, 1 + dx:1 + dx + TILE]
                                     for dy in (-1, 0, 1) for dx in (-1, 0, 1) if dy or dx]), 0)
            peak = (prob > thr) & (prob >= neigh)
            peak[th:, :] = False
            peak[:, tw:] = False
            yy, xx = np.nonzero(peak)
            gx, gy = xx + x0, yy + y0
            keep = (gx >= bx[tx][0]) & (gx < bx[tx][1]) & (gy >= by[ty][0]) & (gy < by[ty][1])
            for py, px, ox, oy in zip(yy[keep], xx[keep], gx[keep], gy[keep]):
                d = dist[:, py, px]
                vx = ((ox + d * COS) + .5) / scale - .5
                vy = ((oy + d * SIN) + .5) / scale - .5
                out.append((float(prob[py, px]), np.stack([vx, vy], 1).astype(np.float32), scale))
    return out

def polygon_area(p):
    x, y = p[:, 0].astype(np.float64), p[:, 1].astype(np.float64)
    return abs(float(np.dot(x, np.roll(y, -1)) - np.dot(y, np.roll(x, -1)))) / 2

def nms(cands):
    """Ordena por score (estável) e suprime polígonos com interseção/menor área > NMS_OVERLAP."""
    order = sorted(range(len(cands)), key=lambda i: -cands[i][0])
    limit = len(order) > MAX_CANDIDATES
    order = order[:MAX_CANDIDATES]
    kept = []
    for i in order:
        score, poly, scale = cands[i]
        area = polygon_area(poly)
        if area < math.pi * (MIN_RADIUS_NET / scale) ** 2:
            continue
        hull = cv2.convexHull(poly.reshape(-1, 1, 2)).reshape(-1, 2).astype(np.float32)
        box = (poly[:, 0].min(), poly[:, 1].min(), poly[:, 0].max(), poly[:, 1].max())
        dup = False
        for k in kept:
            kb = k['box']
            if box[0] > kb[2] or kb[0] > box[2] or box[1] > kb[3] or kb[1] > box[3]:
                continue
            inter, _ = cv2.intersectConvexConvex(hull, k['hull'], handleNested=True)
            if inter / max(1e-9, min(area, k['area'])) > NMS_OVERLAP:
                dup = True
                break
        if dup:
            continue
        if len(kept) >= MAX_OBJECTS:
            limit = True
            break
        kept.append(dict(score=score, poly=poly, hull=hull, area=area, box=box))
    return kept, limit

def describe(obj, w, h):
    poly = obj['poly']
    m = cv2.moments(poly.reshape(-1, 1, 2))
    area = abs(m['m00'])
    cx, cy = (m['m10'] / m['m00'], m['m01'] / m['m00']) if m['m00'] != 0 else tuple(poly.mean(0))
    per = cv2.arcLength(poly.reshape(-1, 1, 2), True)
    round_ = min(1., 4 * math.pi * area / max(1e-9, per * per))
    partial = bool(poly[:, 0].min() < 0 or poly[:, 1].min() < 0 or poly[:, 0].max() > w - 1 or poly[:, 1].max() > h - 1)
    return dict(x=float(cx), y=float(cy), radius=math.sqrt(area / math.pi), area=float(area),
                roundness=float(round_), partial=partial, score=obj['score'])

def detect(run, gray, profile=3, sensitivity=75):
    h, w = gray.shape
    thr = threshold(sensitivity)
    cands = []
    for frac in PROFILE_RADII[profile]:
        cands += candidates_for_scale(run, gray, NOMINAL_RADIUS / (frac * w), thr)
    kept, limit = nms(cands)
    dets = [describe(k, w, h) for k in kept]
    dets.sort(key=lambda d: (d['y'], d['x']))
    return dets, len(cands), limit

# ---------------------------------------------------------------- métricas (desenvolvimento, não metrologia)

def match(dets, circles, partial=None):
    """Guloso por distância: par aceito se distância < 0,5·r_gt e razão de raios em [0,7; 1,43]."""
    pairs = []
    for i, d in enumerate(dets):
        for j, (x, y, r) in enumerate(circles):
            dd = math.hypot(d['x'] - x, d['y'] - y)
            ratio = d['radius'] / r
            if dd < .5 * r and .7 <= ratio <= 1 / .7:
                pairs.append((dd / r, i, j))
    pairs.sort()
    used_d, used_g, matched = set(), set(), []
    for _, i, j in pairs:
        if i in used_d or j in used_g:
            continue
        used_d.add(i); used_g.add(j); matched.append((i, j))
    tp = len(matched)
    p = tp / max(1, len(dets)); r = tp / max(1, len(circles))
    f1 = 2 * p * r / max(1e-9, p + r)
    errs = [abs(dets[i]['radius'] - circles[j][2]) / circles[j][2] for i, j in matched
            if partial is None or not partial[j]]
    return dict(detections=len(dets), annotated=len(circles), matched=tp, precision=round(p, 4), recall=round(r, 4),
                f1=round(f1, 4), radius_abs_error_pct=round(100 * float(np.mean(errs)), 2) if errs else None)
