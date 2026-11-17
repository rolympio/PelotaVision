#!/usr/bin/env python3
"""Mede se o contorno ANOTADO esta em cima da borda REAL da imagem (vale para Roboflow, SAM 2 ou revisao manual).

Para cada objeto: reamostra o poligono em N pontos, toma a normal para fora e procura, entre -s e +s px,
o maior degrau de brilho (polaridade dominante do objeto). A mediana desses deslocamentos e o
'offset_borda_px' (positivo = anotacao por DENTRO da borda real = objeto anotado menor que o real).
Vies relativo no diametro ~ offset / raio.

Isto e o que decide se um dataset externo pode ensinar BORDA: um vies sistematico de +1 px numa pelota
de 15 px de raio e +6,7% no diametro, maior que todo o erro restante do app (~2%).

Saidas (em --saida): auditoria.json (resumo + por objeto), auditoria.csv e, com --pranchas N, imagens
com o contorno anotado (amarelo) e a borda achada (ciano). Com --aplicar grava annotations_auditado.json
marcando contorno_confiavel=false onde o offset passa da tolerancia ou o contraste e fraco.
Com --corrigir, os vertices sao movidos para a borda achada (label_origin ganha '+snap_borda_v1').

Uso:
  python tools/externos/auditar_borda_anotada.py --dados <pasta com annotations.json> --saida <pasta> [--pranchas 12] [--aplicar] [--corrigir]
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import sys
from pathlib import Path

import cv2
import numpy as np

PONTOS = 64


def ler_cinza(path: Path):
    img = cv2.imdecode(np.fromfile(str(path), np.uint8), cv2.IMREAD_GRAYSCALE)
    if img is None:
        raise ValueError(f"Imagem ilegivel: {path}")
    return img


def caminho_imagem(base: Path, cena: dict) -> Path:
    return base / cena["image"] if cena.get("image") else base / f"{cena['group']}.png"


def reamostrar(p: np.ndarray, n: int = PONTOS) -> np.ndarray:
    q = np.vstack([p, p[:1]])
    seg = np.hypot(*np.diff(q, axis=0).T)
    s = np.concatenate([[0], np.cumsum(seg)])
    if s[-1] <= 0:
        return np.repeat(p[:1], n, 0)
    t = np.linspace(0, s[-1], n, endpoint=False)
    return np.stack([np.interp(t, s, q[:, 0]), np.interp(t, s, q[:, 1])], 1)


def normais_para_fora(p: np.ndarray) -> np.ndarray:
    tang = np.roll(p, -1, 0) - np.roll(p, 1, 0)
    nrm = np.stack([tang[:, 1], -tang[:, 0]], 1)
    nrm /= np.maximum(np.linalg.norm(nrm, axis=1, keepdims=True), 1e-9)
    # orientacao: a normal deve apontar para longe do centroide
    c = p.mean(0)
    if np.mean(np.sum((p - c) * nrm, 1)) < 0:
        nrm = -nrm
    return nrm


def ruido_imagem(gray: np.ndarray) -> float:
    """Desvio do ruido (0-255) por MAD do residuo de um filtro de mediana 3x3."""
    res = gray.astype(np.float32) - cv2.medianBlur(gray, 3).astype(np.float32)
    return float(1.4826 * np.median(np.abs(res)))


def medir_objeto(gray_f: np.ndarray, poly: np.ndarray, passo: float = 0.25, ruido: float = 2.0):
    r = math.sqrt(max(cv2.contourArea(poly.astype(np.float32)), 1.0) / math.pi)
    s = max(3.0, 0.15 * r)
    pts = reamostrar(poly)
    nrm = normais_para_fora(pts)
    offs = np.arange(-s, s + 1e-9, passo)
    xs = (pts[:, None, 0] + nrm[:, None, 0] * offs[None]).astype(np.float32)
    ys = (pts[:, None, 1] + nrm[:, None, 1] * offs[None]).astype(np.float32)
    prof = cv2.remap(gray_f, xs, ys, cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
    der = np.gradient(prof, passo, axis=1)                  # dI/dn (para fora)
    pol = 1.0 if np.sum(np.clip(der, 0, None)) >= np.sum(np.clip(-der, 0, None)) else -1.0
    sd = der * pol
    k = np.argmax(sd, 1)
    pico = sd[np.arange(len(k)), k]
    validos = pico > max(3.0, 4 * ruido)                    # degrau claro (dI/dn por px, escala 0-255)
    # pontos no extremo da janela: a borda pode estar alem; nao contam
    validos &= (k > 0) & (k < len(offs) - 1)
    desloc = offs[k]
    if validos.sum() < 0.5 * len(pts):
        return dict(raio_px=r, offset_borda_px=None, frac_validos=float(validos.mean()), contraste=float(np.median(pico)),
                    pontos_borda=None)
    off = float(np.median(desloc[validos]))
    novo = pts + nrm * np.where(validos, desloc, off)[:, None]
    return dict(raio_px=r, offset_borda_px=off, frac_validos=float(validos.mean()), contraste=float(np.median(pico[validos])),
                pontos_borda=novo)


def auditar(dados: Path, saida: Path, pranchas: int = 0, aplicar: bool = False, corrigir: bool = False,
            tol_rel: float = 0.04, tol_px: float = 1.5, max_cenas: int | None = None) -> dict:
    cenas = json.loads((dados / "annotations.json").read_text(encoding="utf-8"))
    saida.mkdir(parents=True, exist_ok=True)
    linhas, rel = [], []
    for ci, cena in enumerate(cenas[:max_cenas] if max_cenas else cenas):
        gray = ler_cinza(caminho_imagem(dados, cena))
        g = cv2.GaussianBlur(gray, (0, 0), 1.0).astype(np.float32)
        ruido = ruido_imagem(gray)
        vis = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR) if ci < pranchas else None
        for oi, ob in enumerate(cena["annotations"]):
            if not ob.get("polygon") or ob.get("partial"):
                continue
            poly = np.asarray(ob["polygon"], np.float64)
            m = medir_objeto(g, poly, ruido=ruido)
            off = m["offset_borda_px"]
            vr = None if off is None else 2 * off / (2 * m["raio_px"])
            tol = max(tol_px, tol_rel * m["raio_px"])
            ok = off is not None and abs(off) <= tol
            linhas.append(dict(cena=cena["group"], objeto=oi, fonte=cena.get("fonte", ""), raio_px=round(m["raio_px"], 2),
                               vertices=len(poly), offset_borda_px=None if off is None else round(off, 3),
                               vies_diametro_rel=None if vr is None else round(vr, 4), frac_validos=round(m["frac_validos"], 3),
                               contraste=round(m["contraste"], 2), dentro_tolerancia=ok))
            if vr is not None:
                rel.append(vr)
            if aplicar:
                ob["offset_borda_px"] = None if off is None else round(off, 3)
                ob["contorno_confiavel"] = bool(ob.get("contorno_confiavel", True) and ok)
                if corrigir and m["pontos_borda"] is not None and off is not None:
                    ob["polygon"] = np.round(m["pontos_borda"], 2).tolist()
                    ob["contorno_confiavel"] = True
                    cena["label_origin"] = str(cena.get("label_origin", "")) + "+snap_borda_v1"
            if vis is not None:
                cv2.polylines(vis, [np.round(poly).astype(np.int32)], True, (0, 255, 255), 1)
                if m["pontos_borda"] is not None:
                    cv2.polylines(vis, [np.round(m["pontos_borda"]).astype(np.int32)], True, (255, 255, 0), 1)
        if vis is not None:
            ok_, buf = cv2.imencode(".jpg", vis, [cv2.IMWRITE_JPEG_QUALITY, 90])
            (saida / f"prancha_{ci:03d}.jpg").write_bytes(buf.tobytes())
    arr = np.asarray(rel, np.float64)
    med = [l for l in linhas if l["offset_borda_px"] is not None]
    resumo = dict(
        objetos=len(linhas), objetos_medidos=len(med),
        offset_px_mediana=round(float(np.median([l["offset_borda_px"] for l in med])), 3) if med else None,
        vies_diametro_rel_mediana=round(float(np.median(arr)), 4) if arr.size else None,
        vies_diametro_rel_p10_p90=[round(float(x), 4) for x in np.percentile(arr, [10, 90])] if arr.size else None,
        frac_dentro_tolerancia=round(float(np.mean([l["dentro_tolerancia"] for l in linhas])), 3) if linhas else None,
        tolerancia=f"max({tol_px} px, {tol_rel:.0%} do raio)",
        leitura="positivo = anotacao menor que a borda real. Pre-treino de BORDA so com |mediana| < 1% e frac_dentro_tolerancia >= 0,8.")
    (saida / "auditoria.json").write_text(json.dumps({"resumo": resumo, "objetos": linhas}, ensure_ascii=False, indent=1), encoding="utf-8")
    if linhas:
        with (saida / "auditoria.csv").open("w", newline="", encoding="utf-8") as f:
            wr = csv.DictWriter(f, fieldnames=list(linhas[0]))
            wr.writeheader()
            wr.writerows(linhas)
    if aplicar:
        (dados / "annotations_auditado.json").write_text(json.dumps(cenas, ensure_ascii=False), encoding="utf-8")
    return resumo


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dados", required=True)
    ap.add_argument("--saida", required=True)
    ap.add_argument("--pranchas", type=int, default=0)
    ap.add_argument("--aplicar", action="store_true")
    ap.add_argument("--corrigir", action="store_true")
    ap.add_argument("--max-cenas", type=int, default=None)
    a = ap.parse_args(argv)
    if a.corrigir and not a.aplicar:
        ap.error("--corrigir exige --aplicar")
    r = auditar(Path(a.dados).resolve(), Path(a.saida).resolve(), a.pranchas, a.aplicar, a.corrigir, max_cenas=a.max_cenas)
    print(json.dumps(r, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
