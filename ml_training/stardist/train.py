"""Treino reproduzível do detector StarDist de pelotas (PyTorch, CPU ou GPU) e exportação ONNX.

Uso:
  python train.py --out modelo.onnx --metrics metricas.json [--exclude cluster,paired40] [--iterations 1500]
  python train.py --conjunto correia --out belt.onnx --metrics belt_metrics.json [--iterations 6000]

--conjunto bancada (padrão) é exatamente o treino anterior. --conjunto correia lê os pseudo-rótulos de
ml_training/data_belt/pseudo (annotations.json + máscaras de peso) e usa só os recortes com split "treino";
peso 0 na máscara ignora o pixel na BCE e no termo de distância.

O ONNX tem entrada fixa 1x1x384x384 (cinza normalizado por percentis, ver common.py)
e saída 1x33x384x384: canal 0 = probabilidade de interior, canais 1..32 = distâncias
(px na escala da rede) até a borda em 32 direções.
"""
import argparse, json, math, os, time, hashlib
from pathlib import Path
import numpy as np, cv2, torch, torch.nn as nn, torch.nn.functional as F
import common as C

SEED = 20261024
CROP = 128
PAD = 32
RNET = np.geomspace(10.4, 24.0, 7)  # raios na escala da rede vistos no treino


def block(i, o):
    return nn.Sequential(nn.Conv2d(i, o, 3, padding=1, bias=False), nn.BatchNorm2d(o), nn.ReLU(inplace=True),
                         nn.Conv2d(o, o, 3, padding=1, bias=False), nn.BatchNorm2d(o), nn.ReLU(inplace=True))


class StarDistNet(nn.Module):
    """U-Net de 3 níveis + cabeça StarDist (prob + RAYS distâncias)."""
    def __init__(self, c=16, rays=C.RAYS):
        super().__init__()
        self.e1, self.e2, self.e3, self.b = block(1, c), block(c, 2 * c), block(2 * c, 4 * c), block(4 * c, 8 * c)
        self.u3, self.d3 = nn.ConvTranspose2d(8 * c, 4 * c, 2, 2), block(8 * c, 4 * c)
        self.u2, self.d2 = nn.ConvTranspose2d(4 * c, 2 * c, 2, 2), block(4 * c, 2 * c)
        self.u1, self.d1 = nn.ConvTranspose2d(2 * c, c, 2, 2), block(2 * c, c)
        self.feat = nn.Sequential(nn.Conv2d(c, 32, 3, padding=1), nn.ReLU(inplace=True))
        self.prob, self.dist = nn.Conv2d(32, 1, 1), nn.Conv2d(32, rays, 1)
        nn.init.constant_(self.dist.bias, C.NOMINAL_RADIUS * .6)

    def forward(self, x, logits=False):
        x1 = self.e1(x)
        x2 = self.e2(F.max_pool2d(x1, 2))
        x3 = self.e3(F.max_pool2d(x2, 2))
        b = self.b(F.max_pool2d(x3, 2))
        y = self.d3(torch.cat([self.u3(b), x3], 1))
        y = self.d2(torch.cat([self.u2(y), x2], 1))
        y = self.d1(torch.cat([self.u1(y), x1], 1))
        f = self.feat(y)
        p = self.prob(f)
        return torch.cat([p if logits else torch.sigmoid(p), self.dist(f)], 1)


def prepare(scenes):
    """Pré-calcula, por cena e por escala, cinza reduzido e alvos (com moldura PAD)."""
    items = []
    for s in scenes:
        med_r = float(np.median(s['circles'][:, 2]))
        for rn in RNET:
            sc = rn / med_r
            small = C.resize_for_net(s['gray'], sc)
            cc = s['circles'].copy()
            cc[:, 0] = (cc[:, 0] + .5) * sc - .5
            cc[:, 1] = (cc[:, 1] + .5) * sc - .5
            cc[:, 2] *= sc
            pg = None if s.get('polygons') is None else [None if q is None else (q + .5) * sc - .5 for q in s['polygons']]
            lab = C.render_labels(small.shape, cc, pg)
            prob = C.prob_target(lab)
            dist = np.minimum(C.star_distances(lab, max_dist=4 * C.NOMINAL_RADIUS), 4 * C.NOMINAL_RADIUS)
            h, w = small.shape
            ph, pw = max(0, CROP - (h + 2 * PAD)), max(0, CROP - (w + 2 * PAD))
            pads = ((PAD, PAD + ph), (PAD, PAD + pw))
            items.append(dict(group=s['group'], small=small, pads=pads,
                              prob=np.pad(prob, pads), dist=np.pad(dist, ((0, 0),) + pads),
                              mask=np.pad(lab > 0, pads)))
    return items


def augment(small, rng):
    g = small.astype(np.float32) / 255
    g = np.clip(g, 0, 1) ** rng.uniform(.7, 1.5)
    g = (g - .5) * rng.uniform(.6, 1.3) + .5 + rng.uniform(-.12, .12)
    if rng.random() < .5:  # gradiente de iluminação
        yy, xx = np.mgrid[:g.shape[0], :g.shape[1]]
        a = rng.uniform(0, 2 * np.pi)
        ramp = (np.cos(a) * xx + np.sin(a) * yy) / max(g.shape)
        g = g * (1 + rng.uniform(-.35, .35) * (ramp - ramp.mean()))
    if rng.random() < .3:
        g = cv2.GaussianBlur(g, (0, 0), rng.uniform(.3, 1.0))
    g = g + rng.normal(0, rng.uniform(0, .025), g.shape)
    return np.clip(np.rint(g * 255), 0, 255).astype(np.uint8)


def sample(items, rng):
    it = items[rng.integers(len(items))]
    g = augment(it['small'], rng)
    stats = C.norm_stats(g)
    x = C.normalize(g, stats)
    fill = (stats[2] - stats[0]) / (stats[1] - stats[0])
    x = np.pad(x, it['pads'], constant_values=fill)
    H, W = x.shape
    y0, x0 = rng.integers(0, H - CROP + 1), rng.integers(0, W - CROP + 1)
    sl = (slice(y0, y0 + CROP), slice(x0, x0 + CROP))
    img, prob, mask = x[sl], it['prob'][sl], it['mask'][sl]
    dist = it['dist'][(slice(None),) + sl]
    fx, fy, tr = (bool(v) for v in rng.integers(0, 2, 3))
    img, prob, mask = (C.apply_d4(a, fx, fy, tr) for a in (img, prob, mask))
    dist = C.apply_d4(dist, fx, fy, tr)[C.ray_permutation(fx, fy, tr)]
    return img, prob, dist, mask


def batch(items, rng, n):
    xs, ps, ds, ms = zip(*(sample(items, rng) for _ in range(n)))
    t = lambda a: torch.from_numpy(np.ascontiguousarray(np.stack(a))).float()
    return t(xs)[:, None], t(ps)[:, None], t(ds), t(ms)[:, None]


def loss_fn(out, prob, dist, mask):
    lp = F.binary_cross_entropy_with_logits(out[:, :1], prob)
    err = (out[:, 1:] - dist).abs().mean(1, keepdim=True)
    ld = (prob * err).sum() / prob.sum().clamp_min(1e-6)
    reg = ((1 - mask) * out[:, 1:].abs()).mean()
    return lp + .2 * ld + 1e-4 * reg, lp.item(), ld.item()


# ---------------------------------------------------------------- correia (pseudo-rótulos com máscara de peso)

BELT_SIDE = 224          # lado máximo (px da rede) da janela pré-calculada por recorte e escala
BELT_SCALES = 2          # escalas RNET sorteadas por recorte
BELT_NEG_RADIUS = 12.0   # raio de referência dos recortes negativos (Disco 03: espaçamento 24 px)
DIST_Q = 4.              # distâncias guardadas em uint8 com passo de 0,25 px (máx. 63,75 < 4 x NOMINAL_RADIUS)


def load_belt(data_dir, split='treino'):
    """Recortes de correia: cinza, círculos (todos, inclusive parciais), máscara de peso 0/1."""
    data_dir = Path(data_dir)
    out = []
    for s in json.load(open(data_dir / 'annotations.json', encoding='utf-8')):
        if s['split'] != split:
            continue
        gray = cv2.imread(str(data_dir / s['image']), cv2.IMREAD_GRAYSCALE)
        w = cv2.imread(str(data_dir / s['weight_mask']), cv2.IMREAD_GRAYSCALE)
        if gray is None or w is None:
            raise FileNotFoundError(s['group'])
        circles = np.array([[a['x'], a['y'], a['radius']] for a in s['annotations']], np.float64).reshape(-1, 3)
        partial = np.array([bool(a.get('partial')) for a in s['annotations']], bool)
        full = circles[~partial, 2]
        med = float(np.median(full)) if len(full) >= 5 else (s['radius_px'] or BELT_NEG_RADIUS)
        out.append(dict(group=s['group'], source=s['source'], camera=s['camera'], negative=s['negative'], gray=gray,
                        circles=circles, weight=(w > 127).astype(np.uint8), med_r=med))
    return out


def _belt_item(args):
    """Janela aleatória do recorte na escala rn/med_r. Sem moldura artificial: se a janela tiver menos de CROP px,
    completa com peso 0. Pelotas cortadas pela janela recebem peso 0 (distâncias truncadas não ensinam forma)."""
    s, rn, u = args
    sc = rn / s['med_r']
    h, w = s['gray'].shape
    side = int(min(h, w, math.floor(BELT_SIDE / sc)))
    x0, y0 = int(u[0] * (w - side + 1)), int(u[1] * (h - side + 1))
    g = s['gray'][y0:y0 + side, x0:x0 + side]
    small = C.resize_for_net(g, sc)
    wsm = cv2.resize(s['weight'][y0:y0 + side, x0:x0 + side], (small.shape[1], small.shape[0]), interpolation=cv2.INTER_NEAREST)
    cc = s['circles'].copy()
    cc[:, 0] = (cc[:, 0] - x0 + .5) * sc - .5
    cc[:, 1] = (cc[:, 1] - y0 + .5) * sc - .5
    cc[:, 2] *= sc
    hh, ww = small.shape
    lab = C.render_labels(small.shape, cc)
    cut = np.nonzero((cc[:, 0] - cc[:, 2] < 0) | (cc[:, 1] - cc[:, 2] < 0) | (cc[:, 0] + cc[:, 2] > ww - 1) | (cc[:, 1] + cc[:, 2] > hh - 1))[0] + 1
    if len(cut):
        wsm[np.isin(lab, cut)] = 0
    prob = C.prob_target(lab)
    dist = np.minimum(C.star_distances(lab, max_dist=4 * C.NOMINAL_RADIUS), 255 / DIST_Q)
    ph, pw = max(0, CROP - hh), max(0, CROP - ww)
    pads = ((0, ph), (0, pw))
    return dict(group=s['group'], small=small, pads=pads, prob=np.pad(prob, pads).astype(np.float16),
                dist=np.rint(np.pad(dist, ((0, 0),) + pads) * DIST_Q).astype(np.uint8),
                mask=np.pad(lab > 0, pads), weight=np.pad(wsm, pads))


def prepare_belt(scenes, rng):
    """Por recorte, BELT_SCALES raios de rede sorteados em RNET; janela aleatória que cabe em BELT_SIDE px."""
    jobs = []
    for s in scenes:
        for rn in rng.choice(RNET, BELT_SCALES, replace=False):
            jobs.append((s, float(rn), rng.random(2)))
    from multiprocessing import Pool
    with Pool(max(1, os.cpu_count() or 1)) as pool:
        return pool.map(_belt_item, jobs, chunksize=4)


def sample_belt(items, rng):
    it = items[rng.integers(len(items))]
    g = augment(it['small'], rng)
    stats = C.norm_stats(g)
    x = C.normalize(g, stats)
    fill = (stats[2] - stats[0]) / (stats[1] - stats[0])
    x = np.pad(x, it['pads'], constant_values=fill)
    H, W = x.shape
    y0, x0 = rng.integers(0, H - CROP + 1), rng.integers(0, W - CROP + 1)
    sl = (slice(y0, y0 + CROP), slice(x0, x0 + CROP))
    img, prob, mask, wt = x[sl], it['prob'][sl].astype(np.float32), it['mask'][sl], it['weight'][sl].astype(np.float32)
    dist = it['dist'][(slice(None),) + sl].astype(np.float32) / DIST_Q
    fx, fy, tr = (bool(v) for v in rng.integers(0, 2, 3))
    img, prob, mask, wt = (C.apply_d4(a, fx, fy, tr) for a in (img, prob, mask, wt))
    dist = C.apply_d4(dist, fx, fy, tr)[C.ray_permutation(fx, fy, tr)]
    return img, prob, dist, mask, wt


def batch_belt(items, rng, n):
    xs, ps, ds, ms, ws = zip(*(sample_belt(items, rng) for _ in range(n)))
    t = lambda a: torch.from_numpy(np.ascontiguousarray(np.stack(a))).float()
    return t(xs)[:, None], t(ps)[:, None], t(ds), t(ms)[:, None], t(ws)[:, None]


def loss_fn_weighted(out, prob, dist, mask, wt):
    """Como loss_fn, mas peso 0 ignora o pixel (nem positivo nem negativo) na BCE e na distância."""
    bce = F.binary_cross_entropy_with_logits(out[:, :1], prob, reduction='none')
    lp = (wt * bce).sum() / wt.sum().clamp_min(1.)
    err = (out[:, 1:] - dist).abs().mean(1, keepdim=True)
    ld = (wt * prob * err).sum() / (wt * prob).sum().clamp_min(1e-6)
    reg = ((1 - mask) * wt * out[:, 1:].abs()).mean()
    return lp + .2 * ld + 1e-4 * reg, lp.item(), ld.item()


def main_belt(a):
    torch.manual_seed(SEED); rng = np.random.default_rng(SEED)
    torch.set_num_threads(max(1, os.cpu_count() or 1))
    torch.use_deterministic_algorithms(True, warn_only=True)
    scenes = load_belt(a.data, 'treino')
    t0 = time.time()
    items = prepare_belt(scenes, rng)
    cams = sorted({s['camera'] for s in scenes})
    print(f'alvos: {len(items)} pares recorte/escala de {len(scenes)} recortes ({sum(s["negative"] for s in scenes)} negativos), '
          f'cameras {cams}, em {time.time() - t0:.0f}s', flush=True)
    model = StarDistNet()
    opt = torch.optim.Adam(model.parameters(), lr=3e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, a.iterations, eta_min=3e-5)
    history = []
    t0 = time.time()
    for it in range(1, a.iterations + 1):
        model.train()
        x, p, d, m, w = batch_belt(items, rng, a.batch)
        out = model(x, logits=True)
        loss, lp, ld = loss_fn_weighted(out, p, d, m, w)
        opt.zero_grad(); loss.backward(); opt.step(); sched.step()
        history.append((lp, ld))
        if it % 100 == 0 or it == 1:
            h = np.array(history[-100:])
            print(f'it {it:5d}  bce {h[:, 0].mean():.4f}  dist_mae_pond {h[:, 1].mean():.3f}  {time.time() - t0:.0f}s', flush=True)
    out_path = Path(a.out); out_path.parent.mkdir(parents=True, exist_ok=True)
    export(model, out_path)
    metrics = dict(model_sha256=hashlib.sha256(out_path.read_bytes()).hexdigest(), conjunto='correia',
                   iterations=a.iterations, batch=a.batch, crop=CROP, seed=SEED, belt_side=BELT_SIDE, belt_scales=BELT_SCALES,
                   training_crops=len(scenes), training_negative_crops=int(sum(s['negative'] for s in scenes)),
                   training_objects=int(sum(len(s['circles']) for s in scenes)), training_cameras=cams,
                   training_sources=sorted({s['source'] for s in scenes}),
                   training_seconds=round(time.time() - t0), torch=torch.__version__,
                   parameters=int(sum(p.numel() for p in model.parameters())),
                   final_bce=float(np.mean([h[0] for h in history[-200:]])),
                   final_dist_mae_weighted_px=float(np.mean([h[1] for h in history[-200:]])),
                   loss='BCE ponderada pela máscara (peso 0 ignora) + 0,2 x MAE das distâncias ponderado por prob x peso')
    Path(a.metrics).write_text(json.dumps(metrics, indent=1, ensure_ascii=False), encoding='utf-8')


# ---------------------------------------------------------------- bancada real (0.16.0): cenas antigas + panela + bancada

REAL_MIX = (('cenas', .30), ('panela', .55), ('bancada_1pelota', .15))  # probabilidade de sortear cada fonte por amostra
PAN_JOBS = 24  # janelas (escala e posição sorteadas) por análise de panela


def load_from_onnx(model, path):
    """Pesos de um ONNX exportado por este script (Conv+BN dobradas) -> StarDistNet; BN vira identidade+viés e fica
    congelada. Confere a saída numa entrada aleatória (erro máximo < 1e-3) antes de devolver."""
    import onnx
    from onnx import numpy_helper
    g = onnx.load(str(path)).graph
    init = {i.name: numpy_helper.to_array(i) for i in g.initializer}
    nodes = [n for n in g.node if n.op_type in ('Conv', 'ConvTranspose')]
    mods = [m for m in model.modules() if isinstance(m, (nn.Conv2d, nn.ConvTranspose2d))]
    bn_after = {}
    for seq in model.modules():
        if isinstance(seq, nn.Sequential):
            for i in range(len(seq) - 1):
                if isinstance(seq[i], nn.Conv2d) and isinstance(seq[i + 1], nn.BatchNorm2d):
                    bn_after[id(seq[i])] = seq[i + 1]
    assert len(nodes) == len(mods), (len(nodes), len(mods))
    with torch.no_grad():
        for m, n in zip(mods, nodes):
            W = init[n.input[1]]
            B = init[n.input[2]] if len(n.input) > 2 else np.zeros(W.shape[1 if isinstance(m, nn.ConvTranspose2d) else 0], np.float32)
            assert tuple(m.weight.shape) == W.shape, (m, W.shape)
            m.weight.copy_(torch.from_numpy(W.copy()))
            bn = bn_after.get(id(m))
            if bn is not None:
                bn.running_mean.zero_(); bn.running_var.fill_(1.); bn.weight.fill_(math.sqrt(1 + bn.eps)); bn.bias.copy_(torch.from_numpy(B.copy()))
            else:
                m.bias.copy_(torch.from_numpy(B.copy()))
    model.eval()
    import onnxruntime as ort
    x = np.random.default_rng(0).normal(0, 1, (1, 1, C.TILE, C.TILE)).astype(np.float32)
    ref = ort.InferenceSession(str(path), providers=['CPUExecutionProvider']).run(None, {'input': x})[0]
    with torch.no_grad():
        got = model(torch.from_numpy(x)).numpy()
    err = float(np.abs(ref - got).max())
    assert err < 1e-3, f'pesos do ONNX não reproduzem a saída (erro {err})'
    return err


def prepare_real(scenes, rng, jobs_per_scene):
    jobs = []
    for s in scenes:
        for _ in range(jobs_per_scene(s)):
            jobs.append((s, float(rng.choice(RNET)), rng.random(2)))
    from multiprocessing import Pool
    with Pool(max(1, os.cpu_count() or 1)) as pool:
        return pool.map(_belt_item, jobs, chunksize=4)


def main_real(a):
    """Cenas antigas (peso 1) + panela de webcam e bancada de 1 pelota (pseudo-rótulos com máscara de peso).
    Mesma perda ponderada da correia; BN congelada quando parte de um ONNX."""
    torch.manual_seed(SEED); rng = np.random.default_rng(SEED)
    torch.set_num_threads(max(1, os.cpu_count() or 1))
    torch.use_deterministic_algorithms(True, warn_only=True)
    pan_dir = a.pan_data or str(Path(__file__).resolve().parents[1] / 'data_pan_20261027/pseudo')
    old = [s for s in C.load_scenes(str(Path(__file__).resolve().parents[1] / 'data')) if s['group'] not in {g for g in a.exclude.split(',') if g}]
    real = load_belt(pan_dir, 'treino')
    t0 = time.time()
    pools = {'cenas': prepare(old)}
    for it in pools['cenas']:
        it['weight'] = np.ones_like(it['mask'], dtype=np.uint8)
    pan = [s for s in real if s['source'].startswith('webcam')]
    bench = [s for s in real if not s['source'].startswith('webcam')]
    pools['panela'] = prepare_real(pan, rng, lambda s: PAN_JOBS)
    pools['bancada_1pelota'] = prepare_real(bench, rng, lambda s: 2)
    print('alvos: ' + ', '.join(f'{k} {len(v)}' for k, v in pools.items()) + f' em {time.time() - t0:.0f}s', flush=True)
    names, probs = zip(*REAL_MIX)

    def one():
        k = names[rng.choice(len(names), p=np.array(probs) / sum(probs))]
        items = pools[k]
        if k == 'cenas':
            img, prob, dist, mask = sample(items, rng)
            return img, prob, dist, mask, np.ones_like(prob, dtype=np.float32)
        return sample_belt(items, rng)

    model = StarDistNet()
    init_err = None
    if a.init_onnx:
        init_err = load_from_onnx(model, a.init_onnx)
    opt = torch.optim.Adam(model.parameters(), lr=3e-4 if not a.init_onnx else 1.5e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, a.iterations, eta_min=2e-5)
    history = []
    t0 = time.time()
    for it in range(1, a.iterations + 1):
        model.train()
        if a.init_onnx:
            for m in model.modules():
                if isinstance(m, nn.BatchNorm2d):
                    m.eval()
        xs, ps, ds, ms, ws = zip(*(one() for _ in range(a.batch)))
        t = lambda v: torch.from_numpy(np.ascontiguousarray(np.stack(v))).float()
        x, p, d, m, w = t(xs)[:, None], t(ps)[:, None], t(ds), t(ms)[:, None], t(ws)[:, None]
        out = model(x, logits=True)
        loss, lp, ld = loss_fn_weighted(out, p, d, m, w)
        opt.zero_grad(); loss.backward(); opt.step(); sched.step()
        history.append((lp, ld))
        if it % 100 == 0 or it == 1:
            h = np.array(history[-100:])
            print(f'it {it:5d}  bce {h[:, 0].mean():.4f}  dist_mae_pond {h[:, 1].mean():.3f}  {time.time() - t0:.0f}s', flush=True)
    out_path = Path(a.out); out_path.parent.mkdir(parents=True, exist_ok=True)
    export(model, out_path)
    metrics = dict(model_sha256=hashlib.sha256(out_path.read_bytes()).hexdigest(), conjunto='bancada_real',
                   iterations=a.iterations, batch=a.batch, crop=CROP, seed=SEED, mix=dict(REAL_MIX), pan_jobs=PAN_JOBS,
                   init_onnx=a.init_onnx, init_max_abs_diff=init_err, batchnorm='congelada (dobrada do ONNX)' if a.init_onnx else 'treinada',
                   training_old_scenes=[s['group'] for s in old], training_pan=[s['group'] for s in pan],
                   training_bench_photos=len(bench), training_objects_confident=int(sum(len(s['circles']) for s in real)),
                   training_seconds=round(time.time() - t0), torch=torch.__version__,
                   parameters=int(sum(p.numel() for p in model.parameters())),
                   final_bce=float(np.mean([h[0] for h in history[-200:]])),
                   final_dist_mae_weighted_px=float(np.mean([h[1] for h in history[-200:]])),
                   loss='BCE ponderada (peso 0 ignora) + 0,2 x MAE das distâncias ponderado por prob x peso',
                   label_origin='cenas: círculos provisórios; panela/bancada: pseudo-rótulos do app (make_pseudo.py), sem verdade-terreno humana')
    Path(a.metrics).write_text(json.dumps(metrics, indent=1, ensure_ascii=False), encoding='utf-8')


def export(model, path):
    model.eval()
    dummy = torch.zeros(1, 1, C.TILE, C.TILE)
    torch.onnx.export(model, dummy, str(path), opset_version=13, input_names=['input'], output_names=['output'],
                      do_constant_folding=True, dynamo=False)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--conjunto', choices=('bancada', 'correia', 'bancada_real'), default='bancada')
    ap.add_argument('--init-onnx', default=None, help='bancada_real: parte dos pesos deste ONNX (BN dobrada e congelada)')
    ap.add_argument('--pan-data', default=None, help='bancada_real: padrão ../data_pan_20261027/pseudo')
    ap.add_argument('--data', default=None, help='padrão: ../data (bancada) ou ../data_belt/pseudo (correia)')
    ap.add_argument('--exclude', default='')
    ap.add_argument('--iterations', type=int, default=1500)
    ap.add_argument('--batch', type=int, default=8)
    ap.add_argument('--out', required=True)
    ap.add_argument('--metrics', required=True)
    a = ap.parse_args()
    if a.data is None:
        a.data = str(Path(__file__).resolve().parents[1] / ('data' if a.conjunto == 'bancada' else 'data_belt/pseudo'))
    if a.conjunto == 'correia':
        return main_belt(a)
    if a.conjunto == 'bancada_real':
        return main_real(a)
    torch.manual_seed(SEED); rng = np.random.default_rng(SEED)
    torch.set_num_threads(max(1, os.cpu_count() or 1))
    torch.use_deterministic_algorithms(True, warn_only=True)
    scenes = C.load_scenes(a.data)
    excluded = {g for g in a.exclude.split(',') if g}
    train_scenes = [s for s in scenes if s['group'] not in excluded]
    t0 = time.time()
    items = prepare(train_scenes)
    print(f'alvos: {len(items)} pares cena/escala em {time.time() - t0:.0f}s', flush=True)
    model = StarDistNet()
    opt = torch.optim.Adam(model.parameters(), lr=3e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, a.iterations, eta_min=3e-5)
    history = []
    t0 = time.time()
    for it in range(1, a.iterations + 1):
        model.train()
        x, p, d, m = batch(items, rng, a.batch)
        out = model(x, logits=True)
        loss, lp, ld = loss_fn(out, p, d, m)
        opt.zero_grad(); loss.backward(); opt.step(); sched.step()
        history.append((lp, ld))
        if it % 100 == 0 or it == 1:
            h = np.array(history[-100:])
            print(f'it {it:5d}  bce {h[:, 0].mean():.4f}  dist_mae_pond {h[:, 1].mean():.3f}  {time.time() - t0:.0f}s', flush=True)
    out_path = Path(a.out); out_path.parent.mkdir(parents=True, exist_ok=True)
    export(model, out_path)
    sha = hashlib.sha256(out_path.read_bytes()).hexdigest()
    metrics = dict(model_sha256=sha, iterations=a.iterations, batch=a.batch, crop=CROP, seed=SEED,
                   training_groups=[s['group'] for s in train_scenes], excluded_groups=sorted(excluded),
                   training_seconds=round(time.time() - t0), torch=torch.__version__,
                   parameters=int(sum(p.numel() for p in model.parameters())),
                   final_bce=float(np.mean([h[0] for h in history[-200:]])),
                   final_dist_mae_weighted_px=float(np.mean([h[1] for h in history[-200:]])),
                   evaluation_protocol=C.PROTOCOL,
                   scenes=C.evaluate(out_path, scenes, {s['group'] for s in train_scenes}))
    Path(a.metrics).write_text(json.dumps(metrics, indent=1, ensure_ascii=False), encoding='utf-8')
    for g, m in metrics['scenes'].items():
        print(g, m, flush=True)


if __name__ == '__main__':
    main()
