#!/usr/bin/env python3
"""Treina a rede de borda (interior/borda/separacao) em duas fases.

  pretreino : dados externos convertidos (Roboflow etc.), validacao deixando UMA FONTE inteira de fora
  ajuste    : fotos proprias com contorno revisado (ou pseudo-rotulos marcados), partindo do pretreino

Divisao sempre por grupo de origem (foto original / sessao), nunca por pelota nem por copia aumentada.
O melhor ponto e escolhido pelo MAE de diametro do recorte por raios na validacao (o que o app usa),
nao pela perda. Nenhum numero daqui e validacao metrologica: isso e feito com as esferas de 11 mm.

Exemplos:
  python ml_training/borda/treinar.py --fase pretreino --dados D1 D2 D3 --validar-fonte rf_iron_pellets_hamitouche \
      --saida ../PelotaVision-local/models/borda/pretreino --iteracoes 6000
  python ml_training/borda/treinar.py --fase ajuste --dados ../PelotaVision-local/datasets/bancada_revisada \
      --inicio ../PelotaVision-local/models/borda/pretreino/melhor.pt --saida ../PelotaVision-local/models/borda/ajuste
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import random
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import borda as B  # noqa: E402


def dividir(preps, validar_fonte, frac_val, semente):
    if validar_fonte:
        val = [p for p in preps if p["fonte"] in validar_fonte]
        tr = [p for p in preps if p["fonte"] not in validar_fonte]
    else:
        grupos = sorted({p["grupo_origem"] for p in preps})
        random.Random(semente).shuffle(grupos)
        nv = max(1, int(round(len(grupos) * frac_val))) if len(grupos) > 1 else 0
        gv = set(grupos[:nv])
        val = [p for p in preps if p["grupo_origem"] in gv]
        tr = [p for p in preps if p["grupo_origem"] not in gv]
    inter = {p["grupo_origem"] for p in val} & {p["grupo_origem"] for p in tr}
    if inter:
        raise RuntimeError(f"Vazamento: grupos em treino e validacao: {sorted(inter)[:5]}")
    return tr, val


def recorte_aleatorio(prep, rng, tile):
    x, a, w = prep["x"], prep["alvo"], prep["peso"]
    h, wd = x.shape
    ph, pw = max(0, tile - h), max(0, tile - wd)
    if ph or pw:
        x = np.pad(x, ((0, ph), (0, pw)), mode="reflect")
        a = np.pad(a, ((0, 0), (0, ph), (0, pw)))
        w = np.pad(w, ((0, 0), (0, ph), (0, pw)))         # enchimento sem supervisao
        h, wd = x.shape
    # 70% dos recortes centrados em pelotas (evita blocos so de fundo)
    if prep["objetos"] and rng.random() < .7:
        o = prep["objetos"][rng.integers(len(prep["objetos"]))]["poly"].mean(0)
        y0 = int(np.clip(o[1] - tile / 2 + rng.integers(-tile // 4, tile // 4 + 1), 0, h - tile))
        x0 = int(np.clip(o[0] - tile / 2 + rng.integers(-tile // 4, tile // 4 + 1), 0, wd - tile))
    else:
        y0, x0 = rng.integers(0, h - tile + 1), rng.integers(0, wd - tile + 1)
    sl = (slice(y0, y0 + tile), slice(x0, x0 + tile))
    return x[sl].copy(), a[(slice(None),) + sl].copy(), w[(slice(None),) + sl].copy()


def aumentar(x, a, w, rng):
    k = rng.integers(4)
    x, a, w = np.rot90(x, k), np.rot90(a, k, (1, 2)), np.rot90(w, k, (1, 2))
    if rng.random() < .5:
        x, a, w = x[:, ::-1], a[:, :, ::-1], w[:, :, ::-1]
    # fotometria: contraste, brilho, gama, ruido, desfoque leve (borda nao se move)
    x = np.clip(x * rng.uniform(.7, 1.3) + rng.uniform(-.15, .15), 0, 1)
    x = x ** rng.uniform(.7, 1.4)
    if rng.random() < .5:
        x = x + rng.normal(0, rng.uniform(0, .04), x.shape)
    if rng.random() < .3:
        import cv2
        x = cv2.GaussianBlur(np.ascontiguousarray(x, np.float32), (0, 0), rng.uniform(.3, .9))
    if rng.random() < .2:                                    # inverte polaridade: fundo claro x escuro
        x = 1 - x
    return np.ascontiguousarray(np.clip(x, 0, 1), np.float32), np.ascontiguousarray(a), np.ascontiguousarray(w)


def avaliar(model, torch, val, dispositivo):
    model.eval()
    erros = {"rede": [], "gradiente": []}
    with torch.no_grad():
        def fn(blk):
            return model(torch.from_numpy(blk).to(dispositivo)).cpu().numpy()
        for p in val:
            prob = B.prever(fn, p["x"])
            e = B.erro_diametro(p, prob[1])
            erros["rede"] += e["rede"]
            erros["gradiente"] += e["gradiente"]
    model.train()
    return {k: B.resumir_erros(v) for k, v in erros.items()}


def sha256(path):
    h = hashlib.sha256()
    h.update(Path(path).read_bytes())
    return h.hexdigest()


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--fase", choices=("pretreino", "ajuste"), required=True)
    ap.add_argument("--dados", nargs="+", required=True, help="pastas com annotations.json")
    ap.add_argument("--arquivo", default="annotations.json", help="ex.: annotations_auditado.json")
    ap.add_argument("--validar-fonte", nargs="*", default=[], help="fontes inteiras deixadas para validacao")
    ap.add_argument("--frac-val", type=float, default=.2)
    ap.add_argument("--inicio", default=None, help=".pt para continuar (ajuste)")
    ap.add_argument("--saida", required=True)
    ap.add_argument("--iteracoes", type=int, default=4000)
    ap.add_argument("--lote", type=int, default=16)
    ap.add_argument("--lr", type=float, default=2e-3)
    ap.add_argument("--avaliar-cada", type=int, default=500)
    ap.add_argument("--semente", type=int, default=7)
    ap.add_argument("--cpu", action="store_true")
    a = ap.parse_args(argv)
    import torch
    torch.manual_seed(a.semente)
    rng = np.random.default_rng(a.semente)
    dispositivo = "cuda" if torch.cuda.is_available() and not a.cpu else "cpu"
    saida = Path(a.saida)
    saida.mkdir(parents=True, exist_ok=True)
    t0 = time.time()
    cenas = B.carregar_cenas(a.dados, a.arquivo)
    preps = [B.preparar_cena(c) for c in cenas]
    tr, val = dividir(preps, a.validar_fonte, a.frac_val, a.semente)
    if not tr:
        sys.exit("Sem cenas de treino.")
    print(f"{len(tr)} cenas de treino, {len(val)} de validacao, dispositivo {dispositivo}, preparo {time.time()-t0:.0f}s")
    model = B.construir_modelo(torch).to(dispositivo)
    if a.inicio:
        model.load_state_dict(torch.load(a.inicio, map_location=dispositivo, weights_only=True)["modelo"])
    opt = torch.optim.AdamW(model.parameters(), lr=a.lr, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=a.lr, total_steps=a.iteracoes, pct_start=.1)
    pos_w = torch.tensor([1.0, 4.0, 8.0], device=dispositivo).view(1, 3, 1, 1)  # borda e separacao sao raras
    # amostragem: cada cena com peso proporcional ao n. de objetos (raiz), para fontes grandes nao dominarem
    pesos_cena = np.asarray([math.sqrt(max(1, len(p["objetos"]))) for p in tr], np.float64)
    pesos_cena /= pesos_cena.sum()
    hist, melhor, registro = [], None, []
    for it in range(1, a.iteracoes + 1):
        xb, ab, wb = [], [], []
        for _ in range(a.lote):
            p = tr[rng.choice(len(tr), p=pesos_cena)]
            x, al, w = aumentar(*recorte_aleatorio(p, rng, B.TILE), rng)
            xb.append(x[None]); ab.append(al); wb.append(w)
        xt = torch.from_numpy(np.stack(xb)).to(dispositivo)
        at = torch.from_numpy(np.stack(ab)).to(dispositivo)
        wt = torch.from_numpy(np.stack(wb)).to(dispositivo)
        lg = model(xt)
        bce = torch.nn.functional.binary_cross_entropy_with_logits(lg, at, reduction="none")
        bce = bce * (1 + (pos_w - 1) * at) * wt
        perda = bce.sum() / wt.sum().clamp_min(1)
        opt.zero_grad()
        perda.backward()
        opt.step()
        sched.step()
        hist.append(float(perda))
        if it % 100 == 0:
            print(f"it {it} perda {np.mean(hist[-100:]):.4f} ({time.time()-t0:.0f}s)", flush=True)
        if val and (it % a.avaliar_cada == 0 or it == a.iteracoes):
            m = avaliar(model, torch, val, dispositivo)
            registro.append(dict(it=it, perda=float(np.mean(hist[-100:])), **m))
            print(f"  validacao it {it}: rede {m['rede']} | gradiente {m['gradiente']}", flush=True)
            mae = m["rede"].get("mae", math.inf) / max(m["rede"].get("frac_aceitos", 0), 1e-3) ** 0.5
            if melhor is None or mae < melhor[0]:
                melhor = (mae, it)
                torch.save({"modelo": model.state_dict(), "it": it}, saida / "melhor.pt")
    torch.save({"modelo": model.state_dict(), "it": a.iteracoes}, saida / "final.pt")
    if melhor is None:
        torch.save({"modelo": model.state_dict(), "it": a.iteracoes}, saida / "melhor.pt")
    fontes = {}
    for c in cenas:
        fontes.setdefault(c["fonte"], {"cenas": 0, "licenca": c.get("licenca")})["cenas"] += 1
    meta = dict(fase=a.fase, dados=[str(Path(d).resolve()) for d in a.dados], arquivo=a.arquivo, fontes=fontes,
                cenas_treino=len(tr), cenas_validacao=len(val), validar_fonte=a.validar_fonte,
                grupos_validacao=sorted({p["grupo_origem"] for p in val})[:200],
                iteracoes=a.iteracoes, lote=a.lote, lr=a.lr, semente=a.semente, dispositivo=dispositivo,
                torch=torch.__version__, melhor_it=None if melhor is None else melhor[1], historico_validacao=registro,
                inicio=a.inicio, inicio_sha256=sha256(a.inicio) if a.inicio else None,
                segundos=round(time.time() - t0), R_NOM=B.R_NOM, TILE=B.TILE, SIGMA_BORDA=B.SIGMA_BORDA,
                aviso="Erros medidos contra contornos anotados (externos ou pseudo-rotulos): nao sao validacao metrologica.")
    (saida / "treino.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"pronto: {saida} (melhor it {meta['melhor_it']})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
