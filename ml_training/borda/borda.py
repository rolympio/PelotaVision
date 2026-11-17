"""Rede de BORDA de pelotas: partes comuns (dados, alvos, modelo, recorte por raios, metricas).

A rede recebe a imagem em cinza ja reescalada para que o raio tipico das pelotas seja R_NOM px e devolve,
por pixel, tres probabilidades independentes (sigmoides):
  0 interior   — pixel dentro de alguma pelota
  1 borda      — perfil gaussiano centrado no contorno (sigma SIGMA_BORDA px): o maximo ao longo de um raio
                 e a posicao da borda, com resolucao subpixel
  2 separacao  — zona de contato entre duas pelotas (onde o limiar de brilho funde vizinhas)

Uso no app (proposta 'edge_v2', ver README): no recorte do SAM 2 (Sam2Refiner.snapEdge), trocar o
'maior degrau de brilho' de cada um dos 72 raios pelo maximo da probabilidade de borda. O resto
(janela 0,80-1,15 do raio, mediana movel, limites de area, fallback para a mascara) nao muda.
Por isso recortar_por_raios() aqui e o espelho Python desse procedimento: mudou um, mude o outro.
"""
from __future__ import annotations

import json
import math
from pathlib import Path

import cv2
import numpy as np

R_NOM = 16.0                 # raio tipico na escala da rede (igual ao NOMINAL_RADIUS do StarDist)
TILE = 128                   # entrada fixa do ONNX: 1x1xTILExTILE
SIGMA_BORDA = 0.75           # px na escala da rede
CONTATO_PX = 2.0             # dois objetos a <= 2 px um do outro = zona de separacao
FAIXA_IGNORADA = 3.0         # px em torno de contorno nao confiavel: sem supervisao
NORM_LOW, NORM_HIGH = 0.01, 0.998
CANAIS = ("interior", "borda", "separacao")
# recorte por raios (espelho de Sam2Refiner: EDGE_RAYS, EDGE_IN, EDGE_OUT, EDGE_MIN_VALID, EDGE_MIN_AREA, EDGE_MAX_AREA)
RAIOS, R_IN, R_OUT, MIN_VALIDOS, MIN_AREA, MAX_AREA = 72, .80, 1.15, .6, .78, 1.06
P_MIN_BORDA = 0.30


# ---------------------------------------------------------------- dados

def ler_cinza(path: Path) -> np.ndarray:
    img = cv2.imdecode(np.fromfile(str(path), np.uint8), cv2.IMREAD_GRAYSCALE)
    if img is None:
        raise ValueError(f"Imagem ilegivel: {path}")
    return img


def carregar_cenas(pastas, arquivo="annotations.json", splits=None) -> list[dict]:
    """Le cenas de uma ou mais pastas no formato do PelotaVision (polygon ou x/y/radius, weight_mask opcional)."""
    cenas = []
    for pasta in pastas:
        pasta = Path(pasta)
        arq = pasta / arquivo
        if not arq.exists() and arquivo != "annotations.json":
            arq = pasta / "annotations.json"
        for s in json.loads(arq.read_text(encoding="utf-8")):
            if splits and s.get("split") not in splits:
                continue
            img = pasta / s["image"] if s.get("image") else pasta / f"{s['group']}.png"
            objs = []
            for a in s.get("annotations", []):
                if a.get("polygon"):
                    p = np.asarray(a["polygon"], np.float64)
                else:
                    t = np.linspace(0, 2 * math.pi, 64, endpoint=False)
                    p = np.stack([a["x"] + a["radius"] * np.cos(t), a["y"] + a["radius"] * np.sin(t)], 1)
                objs.append(dict(poly=p, parcial=bool(a.get("partial", False)),
                                 confiavel=bool(a.get("contorno_confiavel", True)) and not a.get("ignore", False)))
            cenas.append(dict(group=s["group"], imagem=img, peso=(pasta / s["weight_mask"]) if s.get("weight_mask") else None,
                              objetos=objs, grupo_origem=s.get("grupo_origem") or s.get("source") or s["group"],
                              fonte=s.get("fonte") or s.get("label_origin") or pasta.name, licenca=s.get("licenca"),
                              negativa=bool(s.get("negative", False)), raio_px=s.get("radius_px")))
    return cenas


def raio_equivalente(p: np.ndarray) -> float:
    return math.sqrt(abs(cv2.contourArea(p.astype(np.float32))) / math.pi)


def fator_escala(cena: dict) -> float:
    rs = [raio_equivalente(o["poly"]) for o in cena["objetos"] if not o["parcial"]]
    if not rs:
        rs = [raio_equivalente(o["poly"]) for o in cena["objetos"]]
    r = float(np.median(rs)) if rs else float(cena.get("raio_px") or R_NOM)
    return R_NOM / max(r, 1.0)


def reescalar_ponto(p: np.ndarray, f: float) -> np.ndarray:
    return (p + 0.5) * f - 0.5      # convencao de centro de pixel (igual ao cv2.resize)


def normalizar(gray: np.ndarray, valida: np.ndarray | None = None) -> np.ndarray:
    v = gray[valida > 0] if valida is not None and np.any(valida) else gray.ravel()
    lo, hi = np.quantile(v, [NORM_LOW, NORM_HIGH])
    return np.clip((gray.astype(np.float32) - lo) / max(hi - lo, 1.0), 0, 1).astype(np.float32)


# ---------------------------------------------------------------- alvos

def _distancia_poligono(xx, yy, p):
    """Distancia (px) de cada ponto ao contorno do poligono, vetorizada nos segmentos."""
    a, b = p, np.roll(p, -1, 0)
    best = np.full(xx.shape, np.inf)
    for (ax, ay), (bx, by) in zip(a, b):
        dx, dy = bx - ax, by - ay
        L = dx * dx + dy * dy
        t = np.clip(((xx - ax) * dx + (yy - ay) * dy) / L, 0, 1) if L > 0 else 0
        best = np.minimum(best, np.hypot(xx - (ax + t * dx), yy - (ay + t * dy)))
    return best


def _dentro(xx, yy, p):
    inside = np.zeros(xx.shape, bool)
    for (ax, ay), (bx, by) in zip(p, np.roll(p, -1, 0)):
        if ay == by:
            continue
        inside ^= ((ay > yy) != (by > yy)) & (xx < ax + (yy - ay) * (bx - ax) / (by - ay))
    return inside


def alvos(shape, objetos, peso_base=None):
    """Alvos (3,H,W) float32 e pesos (3,H,W) na escala da rede, a partir de poligonos ja reescalados."""
    h, w = shape
    alvo = np.zeros((3, h, w), np.float32)
    peso = np.ones((3, h, w), np.float32) if peso_base is None else np.repeat((peso_base > 0).astype(np.float32)[None], 3, 0)
    perto = np.zeros((h, w), np.uint8)                      # quantos objetos a <= CONTATO_PX
    m = int(math.ceil(max(3 * SIGMA_BORDA, CONTATO_PX, FAIXA_IGNORADA))) + 1
    for o in objetos:
        p = o["poly"]
        x0, x1 = max(0, int(p[:, 0].min()) - m), min(w, int(p[:, 0].max()) + m + 1)
        y0, y1 = max(0, int(p[:, 1].min()) - m), min(h, int(p[:, 1].max()) + m + 1)
        if x1 <= x0 or y1 <= y0:
            continue
        yy, xx = np.mgrid[y0:y1, x0:x1].astype(np.float64)
        d = _distancia_poligono(xx, yy, p)
        dentro = _dentro(xx, yy, p)
        alvo[0, y0:y1, x0:x1] = np.maximum(alvo[0, y0:y1, x0:x1], dentro)
        g = np.exp(-0.5 * (d / SIGMA_BORDA) ** 2).astype(np.float32)
        alvo[1, y0:y1, x0:x1] = np.maximum(alvo[1, y0:y1, x0:x1], g)
        perto[y0:y1, x0:x1] += (dentro | (d <= CONTATO_PX)).astype(np.uint8)
        if not o["confiavel"]:
            faixa = d <= FAIXA_IGNORADA
            peso[0, y0:y1, x0:x1][faixa] = 0
            peso[1, y0:y1, x0:x1][faixa] = 0
    alvo[2] = (perto >= 2).astype(np.float32)
    return alvo, peso


def preparar_cena(cena: dict) -> dict:
    """Imagem normalizada, alvos e pesos na escala da rede (cacheavel)."""
    gray = ler_cinza(cena["imagem"])
    f = fator_escala(cena)
    h, w = gray.shape
    W, H = max(8, int(round(w * f))), max(8, int(round(h * f)))
    interp = cv2.INTER_AREA if f < 1 else cv2.INTER_LINEAR
    g = cv2.resize(gray, (W, H), interpolation=interp)
    pb = None
    if cena.get("peso"):
        pb = cv2.imread(str(cena["peso"]), cv2.IMREAD_GRAYSCALE)
        pb = cv2.resize(pb, (W, H), interpolation=cv2.INTER_NEAREST)
    objs = [dict(o, poly=reescalar_ponto(o["poly"], f)) for o in cena["objetos"]]
    alvo, peso = alvos((H, W), objs, pb)
    return dict(group=cena["group"], x=normalizar(g, pb), alvo=alvo, peso=peso, fator=f, objetos=objs,
                grupo_origem=cena["grupo_origem"], fonte=cena["fonte"])


# ---------------------------------------------------------------- modelo

def construir_modelo(torch, base: int = 16):
    """U-Net de 3 niveis (16-32-64, gargalo 128), ~0,47 M parametros, sem pesos de terceiros."""
    nn = torch.nn

    def bloco(i, o):
        return nn.Sequential(nn.Conv2d(i, o, 3, padding=1, bias=False), nn.BatchNorm2d(o), nn.ReLU(inplace=True),
                             nn.Conv2d(o, o, 3, padding=1, bias=False), nn.BatchNorm2d(o), nn.ReLU(inplace=True))

    class UNetBorda(nn.Module):
        def __init__(self):
            super().__init__()
            c = [base, base * 2, base * 4, base * 8]
            self.e0, self.e1, self.e2, self.g = bloco(1, c[0]), bloco(c[0], c[1]), bloco(c[1], c[2]), bloco(c[2], c[3])
            self.u2, self.d2 = nn.ConvTranspose2d(c[3], c[2], 2, 2), bloco(c[3], c[2])
            self.u1, self.d1 = nn.ConvTranspose2d(c[2], c[1], 2, 2), bloco(c[2], c[1])
            self.u0, self.d0 = nn.ConvTranspose2d(c[1], c[0], 2, 2), bloco(c[1], c[0])
            self.saida = nn.Conv2d(c[0], 3, 1)

        def forward(self, x):
            e0 = self.e0(x)
            e1 = self.e1(nn.functional.max_pool2d(e0, 2))
            e2 = self.e2(nn.functional.max_pool2d(e1, 2))
            g = self.g(nn.functional.max_pool2d(e2, 2))
            d2 = self.d2(torch.cat([self.u2(g), e2], 1))
            d1 = self.d1(torch.cat([self.u1(d2), e1], 1))
            d0 = self.d0(torch.cat([self.u0(d1), e0], 1))
            return self.saida(d0)

    return UNetBorda()


def prever(fn_bloco, x: np.ndarray, tile: int = TILE, margem: int = 16) -> np.ndarray:
    """Inferencia em blocos fixos TILE com sobreposicao; fn_bloco(1x1xTxT float32) -> 1x3xTxT logits.
    Devolve probabilidades (3,H,W). Bordas da imagem: reflexo."""
    h, w = x.shape
    nucleo = tile - 2 * margem
    ny, nx = max(1, math.ceil(h / nucleo)), max(1, math.ceil(w / nucleo))
    pad = np.pad(x, ((margem, ny * nucleo - h + margem), (margem, nx * nucleo - w + margem)), mode="reflect")
    out = np.zeros((3, ny * nucleo, nx * nucleo), np.float32)
    for iy in range(ny):
        for ix in range(nx):
            blk = pad[iy * nucleo: iy * nucleo + tile, ix * nucleo: ix * nucleo + tile][None, None].astype(np.float32)
            lg = fn_bloco(blk)[0]
            out[:, iy * nucleo:(iy + 1) * nucleo, ix * nucleo:(ix + 1) * nucleo] = lg[:, margem:margem + nucleo, margem:margem + nucleo]
    return 1 / (1 + np.exp(-out[:, :h, :w]))


# ---------------------------------------------------------------- recorte por raios (espelho do app)

def _perfil(img, cx, cy, r0):
    passo = .25
    ns = int(math.ceil((R_OUT - R_IN) * r0 / passo)) + 1
    t = 2 * np.pi * np.arange(RAIOS) / RAIOS
    rr = R_IN * r0 + (np.arange(-1, ns + 1)) * passo
    xs = (cx + np.cos(t)[:, None] * rr[None]).astype(np.float32)
    ys = (cy + np.sin(t)[:, None] * rr[None]).astype(np.float32)
    return cv2.remap(img.astype(np.float32), xs, ys, cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE), rr, passo


def recortar_por_raios(score_fn, cx, cy, r0):
    """score_fn(perfis (RAIOS, ns+2), passo) -> (RAIOS, ns) de 'quanto e borda' e limiar.
    Devolve raio equivalente do poligono achado ou None (sem borda clara / recorte fora dos limites)."""
    raios_ok, validos = np.full(RAIOS, np.nan), 0
    sc, limiar, rr = score_fn(cx, cy, r0)
    for t in range(RAIOS):
        k = int(np.argmax(sc[t]))
        if sc[t, k] < limiar:
            continue
        d = 0.0
        if 0 < k < sc.shape[1] - 1:                           # refinamento parabolico
            a, b, c = sc[t, k - 1], sc[t, k], sc[t, k + 1]
            den = a - 2 * b + c
            d = 0.5 * (a - c) / den if abs(den) > 1e-9 else 0.0
        raios_ok[t] = rr[k] + max(-.5, min(.5, d)) * (rr[1] - rr[0])
        validos += 1
    if validos < MIN_VALIDOS * RAIOS:
        return None
    cheio = np.empty(RAIOS)
    for t in range(RAIOS):                                     # mediana movel de 5 ignorando raios sem borda
        win = [raios_ok[(t + j) % RAIOS] for j in range(-2, 3)]
        win = [v for v in win if not np.isnan(v)]
        cheio[t] = np.median(win) if win else np.nanmedian(raios_ok)
    th = 2 * np.pi * np.arange(RAIOS) / RAIOS
    area = 0.5 * abs(np.sum(cheio * np.roll(cheio, -1) * np.sin(np.roll(th, -1) - th)))
    rel = area / (math.pi * r0 * r0)
    if not (MIN_AREA <= rel <= MAX_AREA):
        return None
    return math.sqrt(area / math.pi)


def score_rede(prob_borda):
    def fn(cx, cy, r0):
        prof, rr, _ = _perfil(prob_borda, cx, cy, r0)
        return prof[:, 1:-1], P_MIN_BORDA, rr[1:-1]
    return fn


def score_gradiente(gray01):
    """Baseline = edge_v1 do app: maior degrau de brilho dI/dr com a polaridade dominante."""
    def fn(cx, cy, r0):
        prof, rr, passo = _perfil(gray01 * 255.0, cx, cy, r0)
        der = (prof[:, 2:] - prof[:, :-2]) / (2 * passo)
        pol = 1.0 if np.sum(np.clip(der, 0, None)) >= np.sum(np.clip(-der, 0, None)) else -1.0
        return pol * der, 3.0, rr[1:-1]
    return fn


def erro_diametro(prep: dict, prob_borda: np.ndarray | None, semente: int = 0, jitter: float = .10):
    """Para cada pelota confiavel e inteira: priori = circulo equivalente da anotacao com +-jitter no raio e
    +-1 px no centro (imita a mascara do SAM 2); mede o raio pela rede e pelo gradiente.
    Devolve listas de erro relativo de diametro (None = recorte recusado, fica a priori)."""
    rng = np.random.default_rng(semente)
    out = {"rede": [], "gradiente": [], "priori": []}
    fns = {"gradiente": score_gradiente(prep["x"])}
    if prob_borda is not None:
        fns["rede"] = score_rede(prob_borda)
    for o in prep["objetos"]:
        if o["parcial"] or not o["confiavel"]:
            continue
        p = o["poly"]
        r = raio_equivalente(p)
        m = cv2.moments(p.astype(np.float32))
        if m["m00"] == 0:
            continue
        cx, cy = m["m10"] / m["m00"] + rng.uniform(-1, 1), m["m01"] / m["m00"] + rng.uniform(-1, 1)
        r0 = r * (1 + rng.uniform(-jitter, jitter))
        out["priori"].append(r0 / r - 1)
        for nome, fn in fns.items():
            est = recortar_por_raios(fn, cx, cy, r0)
            out[nome].append(None if est is None else est / r - 1)
    return out


def resumir_erros(lst):
    v = np.asarray([e for e in lst if e is not None], np.float64)
    n = len(lst)
    if not v.size:
        return dict(n=n, aceitos=0)
    return dict(n=n, aceitos=int(v.size), frac_aceitos=round(v.size / max(n, 1), 3), vies=round(float(v.mean()), 4),
                mae=round(float(np.abs(v).mean()), 4), p90_abs=round(float(np.percentile(np.abs(v), 90)), 4))
