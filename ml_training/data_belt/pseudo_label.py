"""Pseudo-rótulos de consenso para as fotos de correia (ferramenta de rotulagem, roda localmente).

Uso (na raiz do repositório):
  python ml_training/data_belt/pseudo_label.py [--out ml_training/data_belt/pseudo]

Rotuladores INDEPENDENTES (não precisam de paridade com o app):
  log   — LoG multiescala (scipy) na faixa 0,6–1,5·r0, r0 = espaçamento/2; raio pelo perfil radial
          (queda de 80 % do brilho central até o vale da fresta).
  hough — cv2.HOUGH_GRADIENT_ALT sobre CLAHE, imagem reescalada para r0 = 14 px.
  net   — ONNX de demonstração (pellet_stardist.onnx) via common.candidates_for_scale/nms numa única escala
          (r0 -> 16 px na rede), limiar 0,375 (sensibilidade 75).
Consenso: pelota vira rótulo só se ≥ 2 rotuladores concordam (centro < 0,5·r e razão de raios 0,7–1,43);
centro e raio = média dos que concordam. Material = fechamento morfológico da união das detecções de TODOS
os rotuladores em região densa, dilatado ~1·r0 (detecções isoladas bloqueiam só 1,3·r). Máscara de peso: 1 no rótulo de consenso, 1 fora do material (correia exposta),
0 no resto (material sem consenso, faixa de texto do topo e pelotas cortadas pela borda do recorte).
Quadros de correia vazia (conferidos visualmente) viram recortes 100 % fundo com peso 1.

Divisão por câmera ANTES de tudo (câmera conferida visualmente na faixa impressa; ver README):
  treino = Discos 01–10 e série 2017-06-19; validação = Disco 11, Disco 12, 4P10F.
Subamostragem: por câmera, em ordem de captura (nome do arquivo ≈ 1 quadro/min), 1 a cada 3 quadros no treino
(todos na validação, para ter mais n) e descarta o quadro cuja correlação (cinza 320x180, suavizado) com um já escolhido da mesma câmera é ≥ 0,9.
Negativos: quadros vazios do Disco 03; os capturados até 15:30 vão para o treino (1 a cada 3); os de 15:40 em
diante ficam só para medir falsos positivos (nunca entram no treino). São quase iguais entre si: a medida é otimista.
"""
import argparse, json, math, sys, time, hashlib
from pathlib import Path
import numpy as np, cv2
from scipy import ndimage
from scipy.spatial import cKDTree

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'ml_training' / 'stardist'))
import common as C  # noqa: E402

BELT = ROOT / 'ml_training' / 'data_belt'
SEED = 20261024
CROP = 400
TEXT_BAND = 20            # faixa impressa (câmera/hora) no topo: ignorada
VALIDATION = {'Disco 11', 'Disco 12', '4P10F'}
LABEL_ORIGIN = 'pseudo_consensus_v1'
AGREE_DIST, AGREE_RATIO = .5, (.7, 1 / .7)


# ---------------------------------------------------------------- câmera conferida e divisão

def camera_checked(e):
    """Câmera lida visualmente na faixa impressa (o OCR do manifesto erra/omite ~120 quadros)."""
    f = e['file']
    if f.startswith('20170619'):
        return 'Serie 2017-06-19'      # sem faixa impressa; pelotas grandes, outra data
    if f.startswith('20171227'):
        return '4P10F'
    n = int(f[3:6])
    if e['width'] == 800:              # 800x450: Disco 02 (img375-450) e Disco 01 (img455-531)
        return 'Disco 02' if n < 455 else 'Disco 01'
    fixes = {110: 'Disco 09', 29: 'Disco 08', 39: 'Disco 08', 48: 'Disco 08', 988: 'Disco 08', 581: 'Disco 12'}
    if n in fixes:
        return fixes[n]
    if n in (14, 15, 16) or 54 <= n <= 77 or n in (85, 86, 87):
        if n not in (65, 66, 67):
            return 'Disco 07'
    if n in (106, 107, 113, 114):
        return 'Disco 09'
    if n in (124, 126, 128, 131, 137):
        return 'Disco 06'
    if n in (714, 715, 753, 756, 760, 761, 778, 782):
        return 'Disco 11'
    return e['camera']


def is_empty(e):
    """Correia vazia: só o Disco 03 (conferido). Os 2 quadros de 2017-06-19 com periodicity 0 estão CHEIOS."""
    return e['camera_checked'] == 'Disco 03'


def thumb(f):
    g = cv2.imread(str(BELT / 'raw' / f), cv2.IMREAD_GRAYSCALE).astype(np.float32)
    g = cv2.resize(g, (320, 180), interpolation=cv2.INTER_AREA)[20:170, 20:300]
    g = cv2.GaussianBlur(g, (0, 0), 1)
    return (g - g.mean()) / (g.std() + 1e-6)


def select_frames(manifest):
    by = {}
    for e in sorted(manifest, key=lambda e: e['file']):
        by.setdefault(e['camera_checked'], []).append(e)
    chosen, neg_train, neg_eval = [], [], []
    for cam, es in sorted(by.items()):
        if cam == 'Disco 03':
            early = [e for e in es if e['timestamp'] and e['timestamp'][11:16] <= '15:30']
            late = [e for e in es if e['timestamp'] and e['timestamp'][11:16] >= '15:40']
            neg_train += early[::3]
            neg_eval += late[::3]
            continue
        sel, th = [], []
        for e in (es if cam in VALIDATION else es[::3]):
            t = thumb(e['file'])
            if any(float((t * s).mean()) >= .9 for s in th):
                continue
            sel.append(e); th.append(t)
        chosen += sel
    return chosen, neg_train, neg_eval


def frame_radius(e, manifest):
    """r0 = espaçamento/2; se o espaçamento do quadro foge > 25 % da mediana da câmera, usa a mediana."""
    sp = [m['spacing_px'] for m in manifest if m['camera_checked'] == e['camera_checked'] and m['periodicity'] >= .05]
    med = float(np.median(sp)) if sp else e['spacing_px']
    s = e['spacing_px'] if abs(e['spacing_px'] / med - 1) <= .25 else med
    return s / 2.


# ---------------------------------------------------------------- rotuladores

def radial_radius(gray, c, r0, frac=.8):
    """Raio pelo perfil radial (32 direções): distância em que o brilho cai frac·(centro − vale)."""
    if len(c) == 0:
        return np.zeros(0)
    g = cv2.GaussianBlur(gray, (0, 0), max(.7, r0 / 12)).astype(np.float32)
    ts = np.linspace(0, 1.4 * r0, 43)
    n = len(c)
    px = c[:, 0, None, None] + C.COS[None, :, None] * ts
    py = c[:, 1, None, None] + C.SIN[None, :, None] * ts
    v = ndimage.map_coordinates(g, [py.ravel(), px.ravel()], order=1, mode='nearest').reshape(n, C.RAYS, -1)
    vv = np.where((ts >= .4 * r0)[None, None], v, np.inf)
    kv = np.argmin(vv, axis=2)
    vmin = np.take_along_axis(v, kv[..., None], 2)[..., 0]
    vc = np.median(v[:, :, :3], axis=(1, 2))[:, None]
    lvl = vc - frac * (vc - vmin)
    below = (v <= lvl[..., None]) & (np.arange(len(ts))[None, None] <= kv[..., None]) & (ts[None, None] > 0)
    k = np.where(below.any(2), below.argmax(2), kv)
    return np.median(ts[k], axis=1)


def label_log(gray, r0, rel=.12):
    f = gray.astype(np.float32) / 255.
    rs = np.geomspace(.6 * r0, 1.5 * r0, 8)
    sig = rs / math.sqrt(2)
    stack = np.stack([-s * s * ndimage.gaussian_laplace(f, s) for s in sig])
    mx = ndimage.maximum_filter(stack, size=(3, 3, 3), mode='nearest')
    thr = rel * stack.max()
    k, y, x = np.nonzero((stack == mx) & (stack > thr))
    if len(x) == 0:
        return np.zeros((0, 3))
    resp = stack[k, y, x]
    order = np.argsort(-resp)
    c = np.stack([x, y, rs[k]], 1).astype(np.float64)[order]
    keep, tree_pts = [], []
    for i, (cx, cy, r) in enumerate(c):  # supressão: centros a menos de 0,7·r de um mais forte saem
        if tree_pts:
            d = np.hypot(np.array(tree_pts)[:, 0] - cx, np.array(tree_pts)[:, 1] - cy)
            if (d < .7 * np.minimum(r, np.array(tree_pts)[:, 2])).any():
                continue
        keep.append(i); tree_pts.append((cx, cy, r))
    c = c[keep]
    c[:, 2] = radial_radius(gray, c, r0)
    return c


def label_hough(gray, r0):
    k = 14. / r0
    g = cv2.resize(gray, None, fx=k, fy=k, interpolation=cv2.INTER_LINEAR if k > 1 else cv2.INTER_AREA)
    g = cv2.createCLAHE(2.0, (8, 8)).apply(g)
    g = cv2.GaussianBlur(g, (0, 0), 1.0)
    c = cv2.HoughCircles(g, cv2.HOUGH_GRADIENT_ALT, dp=1.0, minDist=.6 * 14, param1=60, param2=.4,
                         minRadius=int(.4 * 14), maxRadius=int(1.3 * 14) + 1)
    if c is None:
        return np.zeros((0, 3))
    c = c[0].astype(np.float64)
    c[:, :2] = (c[:, :2] + .5) / k - .5
    c[:, 2] /= k
    return c


def label_net(run, gray, r0, thr=.375):
    sc = C.NOMINAL_RADIUS / r0
    kept, _ = C.nms(C.candidates_for_scale(run, gray, sc, thr), C.BELT_MAX_OBJECTS, 4 * C.MAX_CANDIDATES)
    return np.array([[k['cx'], k['cy'], math.sqrt(k['area'] / math.pi)] for k in kept]).reshape(-1, 3)


# ---------------------------------------------------------------- consenso e máscaras

def pair_matches(a, b):
    """Casamento guloso 1:1 com o critério de concordância (centro < 0,5·r_a, razão 0,7–1,43)."""
    if len(a) == 0 or len(b) == 0:
        return []
    tb = cKDTree(b[:, :2])
    pairs = []
    for i, (x, y, r) in enumerate(a):
        for j in tb.query_ball_point([x, y], AGREE_DIST * r):
            ratio = b[j, 2] / r
            if AGREE_RATIO[0] <= ratio <= AGREE_RATIO[1]:
                pairs.append((math.hypot(b[j, 0] - x, b[j, 1] - y) / r, i, j))
    pairs.sort()
    ua, ub, out = set(), set(), []
    for _, i, j in pairs:
        if i in ua or j in ub:
            continue
        ua.add(i); ub.add(j); out.append((i, j))
    return out


def consensus(dets):
    """dets: {nome: (n,3)}. Devolve (círculos de consenso, votos por rótulo)."""
    names = list(dets)
    off, total = {}, 0
    for n in names:
        off[n] = total; total += len(dets[n])
    parent = list(range(total))
    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]; i = parent[i]
        return i
    for ia in range(len(names)):
        for ib in range(ia + 1, len(names)):
            a, b = names[ia], names[ib]
            for i, j in pair_matches(dets[a], dets[b]):
                parent[find(off[a] + i)] = find(off[b] + j)
    groups = {}
    for n in names:
        for i in range(len(dets[n])):
            groups.setdefault(find(off[n] + i), {}).setdefault(n, dets[n][i])
    out, votes = [], []
    for g in groups.values():
        if len(g) >= 2:
            out.append(np.mean(np.stack(list(g.values())), 0)); votes.append(sorted(g))
    return (np.array(out).reshape(-1, 3), votes)


def discs(shape, circles, scale=1.):
    m = np.zeros(shape, np.uint8)
    for x, y, r in circles:
        cv2.circle(m, (int(round(x)), int(round(y))), max(1, int(round(r * scale))), 1, -1)
    return m


def material_mask(shape, dets, r0):
    """Região de pelotas: fechamento + dilatação (~1·r0) das detecções em região densa (>= 3 vizinhas de
    qualquer rotulador a < 3·r0); detecções isoladas só bloqueiam um disco de 1,3·r (não viram fundo)."""
    allc = np.concatenate([d for d in dets.values() if len(d)] or [np.zeros((0, 3))])
    m = np.zeros(shape, np.uint8)
    if len(allc) == 0:
        return m
    nb = np.array([len(v) - 1 for v in cKDTree(allc[:, :2]).query_ball_point(allc[:, :2], 3 * r0)])
    dense = allc[nb >= 3]
    m = discs(shape, dense)
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * int(round(r0)) + 1,) * 2)
    m = cv2.dilate(cv2.morphologyEx(m, cv2.MORPH_CLOSE, k), k)
    return np.maximum(m, discs(shape, allc, 1.3))


def weight_mask(shape, labels, partial, material):
    """1 = rótulo de consenso (não parcial) ou fora do material; 0 = material sem consenso, parciais, faixa do topo."""
    w = (material == 0).astype(np.uint8)
    lab = C.render_labels(shape, labels) if len(labels) else np.zeros(shape, np.int32)
    full = np.isin(lab, np.nonzero(~partial)[0] + 1) if len(labels) else np.zeros(shape, bool)
    part = np.isin(lab, np.nonzero(partial)[0] + 1) if len(labels) else np.zeros(shape, bool)
    w[full] = 1
    w[part] = 0
    return w


# ---------------------------------------------------------------- recortes

def crop_positions(w, h, rng):
    side = min(CROP, w, h - TEXT_BAND)
    n = 3 if w >= 1000 else 2
    slots = np.linspace(0, w - side, n + 1)
    out = []
    for i in range(n):
        x0 = int(rng.integers(int(slots[i]), int(slots[i + 1]) + 1))
        y0 = int(rng.integers(TEXT_BAND, h - side + 1))
        out.append((x0, y0, side))
    return out


def label_frame(run, gray, r0):
    t = {}
    dets = {}
    for name, fn in (('log', lambda: label_log(gray, r0)), ('hough', lambda: label_hough(gray, r0)),
                     ('net', lambda: label_net(run, gray, r0))):
        t0 = time.time(); dets[name] = fn(); t[name] = round(time.time() - t0, 2)
    cons, votes = consensus(dets)
    keep = cons[:, 1] >= TEXT_BAND if len(cons) else np.zeros(0, bool)
    cons = cons[keep]; votes = [v for v, k in zip(votes, keep) if k]
    material = material_mask(gray.shape, dets, r0)
    material[:TEXT_BAND] = 1  # faixa do topo nunca é fundo
    return dets, cons, votes, material, t


def write_crop(out, name, gray, x0, y0, side, labels, material, empty):
    sub = gray[y0:y0 + side, x0:x0 + side]
    cv2.imwrite(str(out / 'crops' / f'{name}.jpg'), sub, [cv2.IMWRITE_JPEG_QUALITY, 92])
    anns, inside = [], []
    if not empty and len(labels):
        cx, cy, r = labels[:, 0] - x0, labels[:, 1] - y0, labels[:, 2]
        sel = (cx + r > 0) & (cy + r > 0) & (cx - r < side) & (cy - r < side)
        for x, y, rr in zip(cx[sel], cy[sel], r[sel]):
            partial = bool(x - rr < 0 or y - rr < 0 or x + rr > side - 1 or y + rr > side - 1)
            anns.append(dict(x=round(float(x), 2), y=round(float(y), 2), radius=round(float(rr), 2), partial=partial))
            inside.append((x, y, rr, partial))
    if empty:
        w = np.ones((side, side), np.uint8)
    else:
        arr = np.array([[a, b, c] for a, b, c, _ in inside]).reshape(-1, 3)
        part = np.array([p for *_, p in inside], bool)
        w = weight_mask((side, side), arr, part, material[y0:y0 + side, x0:x0 + side])
    cv2.imwrite(str(out / 'masks' / f'{name}.png'), w * 255, [cv2.IMWRITE_PNG_BILEVEL, 1])
    return anns, w


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default=str(BELT / 'pseudo'))
    ap.add_argument('--model', default=str(ROOT / 'app' / 'src' / 'main' / 'assets' / 'pellet_stardist.onnx'))
    ap.add_argument('--limit', type=int, default=0, help='processa só os N primeiros quadros (depuração)')
    a = ap.parse_args()
    out = Path(a.out)
    for d in ('crops', 'masks', 'val_frames'):
        (out / d).mkdir(parents=True, exist_ok=True)
    manifest = json.load(open(BELT / 'manifest.json', encoding='utf-8'))
    for e in manifest:
        e['camera_checked'] = camera_checked(e)
    frames, neg_train, neg_eval = select_frames(manifest)
    if a.limit:
        frames = frames[:a.limit]
    run = C.ort_runner(a.model)
    rng = np.random.default_rng(SEED)
    scenes, val_frames, stats = [], [], dict(labelers_seconds={'log': 0, 'hough': 0, 'net': 0})
    for idx, e in enumerate(frames):
        split = 'validacao' if e['camera_checked'] in VALIDATION else 'treino'
        gray = cv2.cvtColor(cv2.imread(str(BELT / 'raw' / e['file'])), cv2.COLOR_BGR2GRAY)
        r0 = frame_radius(e, manifest)
        dets, cons, votes, material, t = label_frame(run, gray, r0)
        for k, v in t.items():
            stats['labelers_seconds'][k] += v
        print(f"[{idx + 1}/{len(frames)}] {e['file']} {e['camera_checked']} {split} r0={r0:.1f} "
              f"log={len(dets['log'])} hough={len(dets['hough'])} net={len(dets['net'])} consenso={len(cons)} {t}", flush=True)
        stem = Path(e['file']).stem
        if split == 'validacao':  # quadro inteiro para a avaliação (rótulos + máscara de peso do quadro)
            h, w = gray.shape
            part = (cons[:, 0] - cons[:, 2] < 0) | (cons[:, 1] - cons[:, 2] < TEXT_BAND) | \
                   (cons[:, 0] + cons[:, 2] > w - 1) | (cons[:, 1] + cons[:, 2] > h - 1) if len(cons) else np.zeros(0, bool)
            wm = weight_mask(gray.shape, cons, part, material)
            wm[:TEXT_BAND] = 0
            cv2.imwrite(str(out / 'val_frames' / f'{stem}.png'), wm * 255, [cv2.IMWRITE_PNG_BILEVEL, 1])
            val_frames.append(dict(file=e['file'], camera=e['camera_checked'], radius_px=r0, weight_mask=f'val_frames/{stem}.png',
                                   consensus=[dict(x=round(float(x), 2), y=round(float(y), 2), radius=round(float(r), 2), partial=bool(p), votes=v)
                                              for (x, y, r), p, v in zip(cons, part, votes)],
                                   labelers={k: np.round(v, 2).tolist() for k, v in dets.items()}))
        for ci, (x0, y0, side) in enumerate(crop_positions(gray.shape[1], gray.shape[0], rng)):
            name = f'{stem}_c{ci}'
            anns, wm = write_crop(out, name, gray, x0, y0, side, cons, material, False)
            scenes.append(dict(group=name, image=f'crops/{name}.jpg', weight_mask=f'masks/{name}.png', split=split,
                               camera=e['camera_checked'], source=e['file'], crop=[x0, y0, side, side], label_origin=LABEL_ORIGIN,
                               radius_px=r0, negative=False, weight_fraction=round(float(wm.mean()), 3), annotations=anns))
    for e in neg_train:
        gray = cv2.cvtColor(cv2.imread(str(BELT / 'raw' / e['file'])), cv2.COLOR_BGR2GRAY)
        h, w = gray.shape
        stem = Path(e['file']).stem
        for ci in range(6):
            x0 = int(rng.integers(0, w - CROP + 1)); y0 = int(rng.integers(TEXT_BAND, h - CROP + 1))
            name = f'{stem}_n{ci}'
            write_crop(out, name, gray, x0, y0, CROP, None, None, True)
            scenes.append(dict(group=name, image=f'crops/{name}.jpg', weight_mask=f'masks/{name}.png', split='treino',
                               camera=e['camera_checked'], source=e['file'], crop=[x0, y0, CROP, CROP], label_origin=LABEL_ORIGIN,
                               radius_px=None, negative=True, weight_fraction=1.0, annotations=[]))
    tr = [s for s in scenes if s['split'] == 'treino']
    summary = dict(
        label_origin=LABEL_ORIGIN, seed=SEED, crop_px=CROP, model_for_net_labeler=Path(a.model).name,
        model_for_net_labeler_sha256=hashlib.sha256(Path(a.model).read_bytes()).hexdigest(),
        rule_split='treino = Discos 01-10 e série 2017-06-19; validação = Disco 11, Disco 12, 4P10F (câmera conferida visualmente)',
        rule_subsample='por câmera, em ordem de captura: 1 a cada 3 quadros no treino (todos na validação); descarta correlação >= 0,9 com quadro já escolhido',
        rule_negatives='Disco 03 (correia vazia): até 15:30 -> treino (1 a cada 3, 6 recortes cada); de 15:40 em diante -> só avaliação',
        frames_positive=dict(treino=sorted({s['source'] for s in tr if not s['negative']}),
                             validacao=sorted({s['source'] for s in scenes if s['split'] == 'validacao'})),
        frames_negative_train=[e['file'] for e in neg_train], frames_negative_eval=[e['file'] for e in neg_eval],
        crops=dict(treino_positivos=sum(1 for s in tr if not s['negative']), treino_negativos=sum(1 for s in tr if s['negative']),
                   validacao=sum(1 for s in scenes if s['split'] == 'validacao')),
        labels=dict(treino=sum(len(s['annotations']) for s in tr),
                    treino_inteiras=sum(1 for s in tr for x in s['annotations'] if not x['partial']),
                    validacao_recortes=sum(len(s['annotations']) for s in scenes if s['split'] == 'validacao'),
                    validacao_quadros_inteiros=sum(len(v['consensus']) for v in val_frames)),
        mean_weight_fraction_positive_train=round(float(np.mean([s['weight_fraction'] for s in tr if not s['negative']])), 3),
        **stats)
    json.dump(scenes, open(out / 'annotations.json', 'w', encoding='utf-8'), indent=0, ensure_ascii=False)
    json.dump(val_frames, open(out / 'val_frames.json', 'w', encoding='utf-8'), ensure_ascii=False)
    json.dump(summary, open(out / 'summary.json', 'w', encoding='utf-8'), indent=1, ensure_ascii=False)
    # câmera conferida também no manifesto (campo novo; os demais não mudam)
    raw = json.load(open(BELT / 'manifest.json', encoding='utf-8'))
    for e in raw:
        e['camera_checked'] = camera_checked(e)
    json.dump(raw, open(BELT / 'manifest.json', 'w', encoding='utf-8'), indent=1, ensure_ascii=False)
    print(json.dumps({k: v for k, v in summary.items() if not k.startswith('frames')}, ensure_ascii=False, indent=1))


if __name__ == '__main__':
    main()
