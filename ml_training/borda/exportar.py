#!/usr/bin/env python3
"""Exporta a rede de borda para ONNX (entrada fixa 1x1xTILExTILE, saida 1x3xTILExTILE de logits) e confere
ONNX Runtime x PyTorch. Grava <saida>/pellet_borda.onnx e pellet_borda.json (SHA-256, constantes, fontes e
licencas dos dados de treino, para atribuicao CC BY no app).

  python ml_training/borda/exportar.py --pt ../PelotaVision-local/models/borda/ajuste/melhor.pt --saida ../PelotaVision-local/models/borda/exportado
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import borda as B  # noqa: E402


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--pt", required=True)
    ap.add_argument("--saida", required=True)
    a = ap.parse_args(argv)
    import torch
    ck = torch.load(a.pt, map_location="cpu", weights_only=True)
    model = B.construir_modelo(torch)
    model.load_state_dict(ck["modelo"])
    model.eval()
    out = Path(a.saida)
    out.mkdir(parents=True, exist_ok=True)
    onnx_path = out / "pellet_borda.onnx"
    dummy = torch.zeros(1, 1, B.TILE, B.TILE)
    torch.onnx.export(model, dummy, str(onnx_path), opset_version=13, input_names=["input"], output_names=["output"],
                      do_constant_folding=True, dynamo=False)
    import onnxruntime as ort
    sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    rng = np.random.default_rng(0)
    x = rng.random((1, 1, B.TILE, B.TILE), np.float32)
    with torch.no_grad():
        ref = model(torch.from_numpy(x)).numpy()
    got = sess.run(None, {"input": x})[0]
    dif = float(np.abs(ref - got).max())
    if dif > 1e-3:
        sys.exit(f"ONNX difere do PyTorch: {dif}")
    treino = Path(a.pt).with_name("treino.json")
    info = json.loads(treino.read_text(encoding="utf-8")) if treino.exists() else {}
    meta = dict(modelo="pellet_borda", versao=1, sha256=hashlib.sha256(onnx_path.read_bytes()).hexdigest(),
                entrada="1x1x%dx%d cinza normalizada por percentis %.3f-%.3f da imagem reescalada" % (B.TILE, B.TILE, B.NORM_LOW, B.NORM_HIGH),
                saida="1x3 logits: " + ", ".join(B.CANAIS), R_NOM=B.R_NOM, TILE=B.TILE, SIGMA_BORDA=B.SIGMA_BORDA,
                recorte=dict(raios=B.RAIOS, r_in=B.R_IN, r_out=B.R_OUT, p_min_borda=B.P_MIN_BORDA,
                             min_validos=B.MIN_VALIDOS, area=[B.MIN_AREA, B.MAX_AREA]),
                onnx_x_pytorch_max_abs=dif, pt=str(Path(a.pt).resolve()), iteracao=ck.get("it"),
                fontes_treino=info.get("fontes"), fase=info.get("fase"), historico_validacao=info.get("historico_validacao"),
                validado_em_fotos_da_usina=False,
                aviso="Prototipo. So substitui o edge_v1 apos avaliar.py e AVALIAR_ESFERAS mostrarem ganho (ver README).")
    (out / "pellet_borda.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"ONNX ok (desvio {dif:.2e}) -> {onnx_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
