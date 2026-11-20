#!/usr/bin/env python3
"""Compara a saída do BurstCli com a verdade de uma rajada simulada (simular_rajada.py).

Mede (sempre na mesma região, sem 8 px de borda):
  * erro dos deslocamentos estimados (px) contra os reais;
  * PSNR do quadro base ruidoso e da empilhada contra o quadro base limpo (Trilha 1);
  * PSNR da SR 2x, da empilhada ampliada (bicúbica) e do quadro único ampliado contra a verdade na
    grade fina (Trilha 2), se a SR foi produzida.
Imprime e grava avaliacao.json na pasta de saída do BurstCli.

Uso: python avaliar_rajada.py --simulacao PASTA_SIM --resultado PASTA_BURSTCLI
"""
import argparse
import json
import os

import cv2
import numpy as np

B = 8


def ler(caminho):
    img = cv2.imdecode(np.fromfile(caminho, dtype=np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        raise SystemExit(f"Não abriu {caminho}")
    return img.astype(np.float64)


def psnr(a, b, borda=B):
    a = a[borda:-borda, borda:-borda]
    b = b[borda:-borda, borda:-borda]
    mse = np.mean((a - b) ** 2)
    return float("inf") if mse == 0 else 10 * np.log10(255.0 ** 2 / mse)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--simulacao", required=True)
    ap.add_argument("--resultado", required=True)
    a = ap.parse_args()
    meta = json.load(open(os.path.join(a.simulacao, "offsets.json"), encoding="utf-8"))
    burst = json.load(open(os.path.join(a.resultado, "rajada.json"), encoding="utf-8"))["burst"]
    base = burst["base_frame"]
    real = {o["indice"]: (o["dx"], o["dy"]) for o in meta["offsets"]}
    bx, by = real[base]
    erros = []
    for f in burst["frames"]:
        if f["index"] == base or not f["used"]:
            continue
        ex, ey = real[f["index"]][0] - bx, real[f["index"]][1] - by
        erros.append(float(np.hypot(f["dx"] - ex, f["dy"] - ey)))
    out = {"veredito": burst["verdict"], "modo": burst["mode"], "quadros_usados": burst["frames_used"],
           "erro_offset_px": {"mediana": float(np.median(erros)) if erros else None,
                               "p90": float(np.percentile(erros, 90)) if erros else None},
           "registro_interno_rmse_px": burst.get("registration_rmse_px"),
           "tremor_real_px": meta["tremor_px"], "ruido_real": meta["ruido"], "grade": meta["grade"]}

    limpo = ler(os.path.join(a.simulacao, "limpos", f"quadro_{base:02d}.png"))
    unico = ler(os.path.join(a.simulacao, "quadros", f"quadro_{base:02d}.png"))
    out["psnr_quadro_unico_db"] = psnr(unico, limpo)
    emp = os.path.join(a.resultado, "rajada_empilhada.png")
    if os.path.exists(emp):
        e = ler(emp)
        out["psnr_empilhada_db"] = psnr(e, limpo)
        out["ganho_empilhada_db"] = out["psnr_empilhada_db"] - out["psnr_quadro_unico_db"]

    srp = os.path.join(a.resultado, "rajada_sr2x.png")
    if os.path.exists(srp) and burst.get("sr_output"):
        sr = ler(srp)
        c = burst["sr_output"]["crop_in_base_photo"]
        verdade = ler(os.path.join(a.simulacao, "verdade_hr.png"))
        h, w = verdade.shape[:2]
        m = np.float32([[1, 0, 2 * bx], [0, 1, 2 * by]])
        verdade_base = cv2.warpAffine(verdade, m, (w, h), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REFLECT)
        ref = verdade_base[2 * c["y"]:2 * (c["y"] + c["height"]), 2 * c["x"]:2 * (c["x"] + c["width"])]
        rec = lambda img: img[c["y"]:c["y"] + c["height"], c["x"]:c["x"] + c["width"]]
        up = lambda img: cv2.resize(img, (2 * c["width"], 2 * c["height"]), interpolation=cv2.INTER_CUBIC)
        out["psnr_sr2x_db"] = psnr(sr, ref, 2 * B)
        if os.path.exists(emp):
            out["psnr_empilhada_bicubica_2x_db"] = psnr(up(rec(ler(emp))), ref, 2 * B)
        out["psnr_unico_bicubica_2x_db"] = psnr(up(rec(unico)), ref, 2 * B)
    with open(os.path.join(a.resultado, "avaliacao.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print(json.dumps(out, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
