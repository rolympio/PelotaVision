#!/usr/bin/env python3
"""Rajada simulada a partir de uma foto real (ensaio C e verificação de software do PelotaVision 0.19.0).

A foto (recorte) é a "verdade" na grade fina. Cada quadro é a verdade deslocada (sub-pixel), integrada
em pixels 2x maiores (sensor 2x pior), com ruído gaussiano e quantização de 8 bits. Grava:

  quadros/quadro_XX.png      entrada da rajada (vai para o BurstCli ou para o app)
  limpos/quadro_XX.png       o mesmo quadro sem ruído (referência da Trilha 1)
  verdade_hr.png             verdade na grade fina (referência da SR 2x)
  offsets.json               deslocamentos reais em pixels do quadro (convenção do app:
                             quadro_k(p + d_k) = quadro_0(p))

Não altera a foto de origem. Uso:
  python simular_rajada.py --foto F.jpg --saida PASTA [--n 12] [--tremor 0.5] [--ruido 10]
                           [--grade] [--lado 512] [--semente 1]
--grade: deslocamentos em fases 0/0,5 px ("pixel shift feito em casa") + tremor residual.
"""
import argparse
import json
import os

import cv2
import numpy as np


def ler(caminho):
    dados = np.fromfile(caminho, dtype=np.uint8)
    img = cv2.imdecode(dados, cv2.IMREAD_COLOR)
    if img is None:
        raise SystemExit(f"Não abriu {caminho}")
    return img


def gravar(caminho, img):
    ok, buf = cv2.imencode(".png", img)
    if not ok:
        raise SystemExit(f"Falha ao gravar {caminho}")
    buf.tofile(caminho)


def quadro(verdade, dx, dy):
    """Quadro LR: verdade (grade fina) deslocada por (2dx, 2dy) px finos e integrada 2x2.

    O quadro com deslocamento d mostra em q o que o quadro 0 mostra em q - d.
    """
    h, w = verdade.shape[:2]
    m = np.float32([[1, 0, 2 * dx], [0, 1, 2 * dy]])
    desl = cv2.warpAffine(verdade.astype(np.float32), m, (w, h), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REFLECT)
    # Pixel LR (i, j) integra os pixels finos 2i..2i+1 (área), como o operador A do app.
    return desl.reshape(h // 2, 2, w // 2, 2, -1).mean(axis=(1, 3))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--foto", required=True)
    ap.add_argument("--saida", required=True)
    ap.add_argument("--n", type=int, default=12)
    ap.add_argument("--tremor", type=float, default=0.5, help="desvio do tremor em px do quadro")
    ap.add_argument("--ruido", type=float, default=10.0, help="desvio do ruído em níveis de 8 bits")
    ap.add_argument("--grade", action="store_true")
    ap.add_argument("--lado", type=int, default=512, help="lado do recorte da verdade (par)")
    ap.add_argument("--semente", type=int, default=1)
    a = ap.parse_args()

    img = ler(a.foto)
    h, w = img.shape[:2]
    lado = min(a.lado, h, w) // 4 * 4
    y0, x0 = (h - lado) // 2, (w - lado) // 2
    verdade = img[y0:y0 + lado, x0:x0 + lado].copy()
    rng = np.random.default_rng(a.semente)
    os.makedirs(os.path.join(a.saida, "quadros"), exist_ok=True)
    os.makedirs(os.path.join(a.saida, "limpos"), exist_ok=True)
    gravar(os.path.join(a.saida, "verdade_hr.png"), verdade)
    offs = []
    for k in range(a.n):
        if k == 0:
            dx = dy = 0.0
        elif a.grade:
            dx = 0.5 * (k % 2) + a.tremor * rng.standard_normal()
            dy = 0.5 * ((k // 2) % 2) + a.tremor * rng.standard_normal()
        else:
            dx, dy = a.tremor * rng.standard_normal(2)
        limpo = quadro(verdade, dx, dy)
        ruidoso = np.clip(np.round(limpo + a.ruido * rng.standard_normal(limpo.shape)), 0, 255).astype(np.uint8)
        gravar(os.path.join(a.saida, "quadros", f"quadro_{k:02d}.png"), ruidoso)
        gravar(os.path.join(a.saida, "limpos", f"quadro_{k:02d}.png"), np.clip(np.round(limpo), 0, 255).astype(np.uint8))
        offs.append({"indice": k, "dx": float(dx), "dy": float(dy)})
    meta = {"foto": os.path.basename(a.foto), "recorte_hr": [int(x0), int(y0), lado, lado], "n": a.n, "tremor_px": a.tremor,
            "ruido": a.ruido, "grade": a.grade, "semente": a.semente, "offsets": offs,
            "convencao": "quadro_k(p + d_k) = quadro_0(p); pixels do quadro (LR)"}
    with open(os.path.join(a.saida, "offsets.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)
    print(f"{a.n} quadros {lado // 2}x{lado // 2} em {a.saida}")


if __name__ == "__main__":
    main()
