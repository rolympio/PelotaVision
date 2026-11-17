#!/usr/bin/env python3
"""Compara o recorte por raios com a REDE de borda x o GRADIENTE de brilho (edge_v1 atual do app).

Mede o erro relativo de diametro contra contornos de referencia em cenas que NAO entraram no treino.
A referencia so vale o que vale a anotacao: use contornos revisados por pessoa (ou corrigidos e
conferidos com auditar_borda_anotada.py). Grava avaliacao.json e, com --figuras, sobreposicoes.

Criterio de implantacao proposto (decidir antes de olhar os numeros):
  rede.mae <= 0,8 x gradiente.mae  E  |rede.vies| <= 0,5%  E  rede.frac_aceitos >= gradiente.frac_aceitos - 0,05
nas fotos proprias revisadas, e nao piorar nas esferas de 11 mm (AVALIAR_ESFERAS).

  python ml_training/borda/avaliar.py --modelo ../PelotaVision-local/models/borda/exportado/pellet_borda.onnx \
      --dados ../PelotaVision-local/datasets/bancada_revisada --saida ../PelotaVision-local/results/borda-avaliacao
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import borda as B  # noqa: E402


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--modelo", required=True, help=".onnx exportado")
    ap.add_argument("--dados", nargs="+", required=True)
    ap.add_argument("--arquivo", default="annotations.json")
    ap.add_argument("--splits", nargs="*", default=None, help="ex.: validacao teste (padrao: todas as cenas)")
    ap.add_argument("--saida", required=True)
    ap.add_argument("--figuras", type=int, default=6)
    ap.add_argument("--sementes", type=int, default=3, help="repeticoes do jitter da priori")
    a = ap.parse_args(argv)
    import onnxruntime as ort
    sess = ort.InferenceSession(a.modelo, providers=["CPUExecutionProvider"])

    def fn(blk):
        return sess.run(None, {"input": blk})[0]

    saida = Path(a.saida)
    saida.mkdir(parents=True, exist_ok=True)
    cenas = B.carregar_cenas(a.dados, a.arquivo, a.splits)
    tot, por_fonte = {"rede": [], "gradiente": [], "priori": []}, {}
    for i, c in enumerate(cenas):
        p = B.preparar_cena(c)
        prob = B.prever(fn, p["x"])
        for s in range(a.sementes):
            e = B.erro_diametro(p, prob[1], semente=s)
            for k in tot:
                tot[k] += e[k]
                por_fonte.setdefault(p["fonte"], {"rede": [], "gradiente": [], "priori": []})[k] += e[k]
        if i < a.figuras:
            vis = cv2.cvtColor((p["x"] * 255).astype(np.uint8), cv2.COLOR_GRAY2BGR)
            cor = np.zeros_like(vis)
            cor[..., 1] = (prob[0] * 255).astype(np.uint8)
            cor[..., 2] = (prob[1] * 255).astype(np.uint8)
            cor[..., 0] = (prob[2] * 255).astype(np.uint8)
            fig = np.hstack([vis, cv2.addWeighted(vis, .5, cor, .5, 0)])
            for o in p["objetos"]:
                cv2.polylines(fig, [np.round(o["poly"]).astype(np.int32)], True, (0, 255, 255), 1)
            ok, buf = cv2.imencode(".jpg", fig)
            (saida / f"cena_{i:03d}.jpg").write_bytes(buf.tobytes())
    res = dict(modelo=a.modelo, cenas=len(cenas), sementes=a.sementes,
               total={k: B.resumir_erros(v) for k, v in tot.items()},
               por_fonte={f: {k: B.resumir_erros(v) for k, v in d.items()} for f, d in por_fonte.items()})
    r, g = res["total"]["rede"], res["total"]["gradiente"]
    if r.get("aceitos") and g.get("aceitos"):
        res["criterio"] = dict(mae_ok=r["mae"] <= .8 * g["mae"], vies_ok=abs(r["vies"]) <= .005,
                               aceitos_ok=r["frac_aceitos"] >= g["frac_aceitos"] - .05)
        res["criterio"]["aprovado_nestas_cenas"] = all(res["criterio"].values())
    (saida / "avaliacao.json").write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps(res["total"], ensure_ascii=False, indent=1))
    if "criterio" in res:
        print("criterio:", res["criterio"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
