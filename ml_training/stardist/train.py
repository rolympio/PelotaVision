"""Treino reproduzível do detector StarDist de pelotas (PyTorch, CPU ou GPU) e exportação ONNX.

Uso:
  python train.py --out modelo.onnx --metrics metricas.json [--exclude cluster,paired40] [--iterations 1500]

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
            lab = C.render_labels(small.shape, cc)
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


def export(model, path):
    model.eval()
    dummy = torch.zeros(1, 1, C.TILE, C.TILE)
    torch.onnx.export(model, dummy, str(path), opset_version=13, input_names=['input'], output_names=['output'],
                      do_constant_folding=True, dynamo=False)


def ort_runner(path):
    import onnxruntime as ort
    sess = ort.InferenceSession(str(path), providers=['CPUExecutionProvider'])
    return lambda tile: sess.run(None, {'input': tile[None, None]})[0][0]


def evaluate(path, scenes, groups_in_training):
    run = ort_runner(path)
    res = {}
    for s in scenes:
        t0 = time.time()
        dets, ncand, limit = C.detect(run, s['gray'], profile=3, sensitivity=75)
        m = C.match(dets, s['circles'], s['partial'])
        m.update(role='treino' if s['group'] in groups_in_training else 'validacao_cena_inteira_fora',
                 candidates=ncand, candidate_limit=limit, seconds=round(time.time() - t0, 2))
        res[s['group']] = m
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--data', default=str(Path(__file__).resolve().parents[1] / 'data'))
    ap.add_argument('--exclude', default='')
    ap.add_argument('--iterations', type=int, default=1500)
    ap.add_argument('--batch', type=int, default=8)
    ap.add_argument('--out', required=True)
    ap.add_argument('--metrics', required=True)
    a = ap.parse_args()
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
                   evaluation_protocol='perfil Auto, sensibilidade 75, pós-processamento common.detect sobre o ONNX exportado; '
                                       'casamento guloso centro<0,5r e razão de raios 0,7-1,43 contra círculos PROVISÓRIOS',
                   scenes=evaluate(out_path, scenes, {s['group'] for s in train_scenes}))
    Path(a.metrics).write_text(json.dumps(metrics, indent=1, ensure_ascii=False), encoding='utf-8')
    for g, m in metrics['scenes'].items():
        print(g, m, flush=True)


if __name__ == '__main__':
    main()
