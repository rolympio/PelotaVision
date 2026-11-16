#!/usr/bin/env python3
"""Datasets SO DE CAIXAS (deteccao, ex.: 'Rubber Pellets Detection', 'pellets-models') -> contornos via SAM 2.

Caixa nao ensina borda. Aqui cada caixa vira prompt (caixa + ponto central) para o SAM 2.1 Tiny ja exportado
pelo app (models/sam2), com o MESMO codigo do avaliar_sam2.py (espelho do Sam2Refiner.java), e a mascara
vira poligono. Resultado: PSEUDO-rotulo de contorno (label_origin 'sam2_de_caixa:<fonte>').

Regras:
- Mascara recusada pelo SAM 2 (IoU baixo, area fora, vazamento) -> a caixa vira regiao IGNORADA, nunca fundo.
- Todo contorno sai com contorno_confiavel=false. So vira confiavel depois de
  auditar_borda_anotada.py --aplicar [--corrigir] (offset dentro da tolerancia).
- O recorte pela borda real do SAM 2 (edge_v1) fica DESLIGADO aqui por padrao, para a rede de borda nao
  aprender a copiar o proprio edge_v1 (circularidade). Use --com-recorte so para comparar.

Uso:
  python tools/externos/caixas_para_contorno_sam2.py --entrada <export COCO> --id rf_rubber_pellets \
      --sam2 ../PelotaVision-local/models/sam2 --saida ../PelotaVision-local/datasets/externos/convertido/rf_rubber_pellets \
      [--classes pellet] [--max-imagens 300]
"""
from __future__ import annotations

import argparse
import json
import math
import os
import sys
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "sam2"))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from coco_para_pelotavision import grupo_origem  # noqa: E402


def mascara_para_poligono(pts: np.ndarray, shape) -> np.ndarray | None:
    h, w = shape
    m = np.zeros((h, w), np.uint8)
    q = np.round(pts).astype(int)
    ok = (q[:, 0] >= 0) & (q[:, 1] >= 0) & (q[:, 0] < w) & (q[:, 1] < h)
    m[q[ok, 1], q[ok, 0]] = 1
    m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))
    cs, _ = cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    if not cs:
        return None
    c = max(cs, key=cv2.contourArea).reshape(-1, 2).astype(np.float64)
    if len(c) < 8:
        return None
    # findContours passa pelos CENTROS dos pixels da borda: a area fica ~meio pixel de anel menor que a mascara.
    # Reescala em torno do centroide para a area do poligono igualar a contagem de pixels da componente.
    comp = np.zeros_like(m)
    cv2.drawContours(comp, [c.astype(np.int32).reshape(-1, 1, 2)], -1, 1, -1)
    a = cv2.contourArea(c.astype(np.float32))
    if a > 0:
        cen = c.mean(0)
        c = cen + (c - cen) * np.sqrt(comp.sum() / a)
    return c


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--entrada", required=True)
    ap.add_argument("--saida", required=True)
    ap.add_argument("--id", required=True)
    ap.add_argument("--sam2", required=True, help="pasta com sam2_encoder.onnx, sam2_decoder.onnx e sam2.json")
    ap.add_argument("--classes", nargs="*", default=[])
    ap.add_argument("--max-imagens", type=int, default=None)
    ap.add_argument("--com-recorte", action="store_true", help="liga o edge_v1 do SAM 2 (so para comparacao)")
    ap.add_argument("--licenca", default=None)
    a = ap.parse_args(argv)
    import avaliar_sam2 as S  # noqa: E402  (precisa de onnxruntime e do StarDist common)
    if not a.com_recorte:
        S.snap_edge = lambda *args, **kw: None
    sam = S.Sam2(Path(a.sam2))
    entrada, saida = Path(a.entrada).resolve(), Path(a.saida).resolve()
    saida.mkdir(parents=True, exist_ok=True)
    (saida / "pesos").mkdir(exist_ok=True)
    lic = a.licenca
    for cand in (entrada / "proveniencia.json", entrada.parent / "proveniencia.json"):
        if lic is None and cand.exists():
            p = json.loads(cand.read_text(encoding="utf-8"))
            lic = p.get("licenca_api") or p.get("licenca_registro")
    cenas, n_img, resumo = [], 0, {"caixas": 0, "contornos": 0, "recusadas_ignoradas": 0}
    for js in sorted(entrada.rglob("_annotations.coco.json")):
        d = json.loads(js.read_text(encoding="utf-8"))
        cats = {c["id"]: c["name"] for c in d.get("categories", [])}
        usados = {x.get("category_id") for x in d.get("annotations", [])}
        alvo = {i for i, n in cats.items() if (n.lower() in {k.lower() for k in a.classes})} if a.classes else usados
        por_img = {}
        for x in d.get("annotations", []):
            por_img.setdefault(x["image_id"], []).append(x)
        for im in d.get("images", []):
            if a.max_imagens and n_img >= a.max_imagens:
                break
            path = js.parent / im["file_name"]
            gray = cv2.imdecode(np.fromfile(str(path), np.uint8), cv2.IMREAD_GRAYSCALE)
            if gray is None:
                continue
            h, w = gray.shape
            peso = np.full((h, w), 255, np.uint8)
            dets = []
            for x in por_img.get(im["id"], []):
                bx, by, bw, bh = x["bbox"]
                if x.get("category_id") not in alvo:
                    cv2.rectangle(peso, (int(bx), int(by)), (int(bx + bw), int(by + bh)), 0, -1)
                    continue
                dets.append(dict(x=bx + bw / 2 - .5, y=by + bh / 2 - .5, radius=(bw + bh) / 4, semi_major=max(bw, bh) / 2,
                                 bbox=[bx, by, bw, bh]))
            if not dets:
                continue
            n_img += 1
            resumo["caixas"] += len(dets)
            refinados, _ = sam.refine(gray, dets)
            objs = []
            for dd in refinados:
                bx, by, bw, bh = dd["bbox"]
                poly = mascara_para_poligono(dd["mask"], (h, w)) if dd.get("sam2") and dd.get("mask") is not None else None
                if poly is None:
                    resumo["recusadas_ignoradas"] += 1
                    m = 2
                    cv2.rectangle(peso, (int(bx) - m, int(by) - m), (int(bx + bw) + m, int(by + bh) + m), 0, -1)
                    continue
                resumo["contornos"] += 1
                parcial = bool(poly[:, 0].min() <= 2 or poly[:, 1].min() <= 2 or poly[:, 0].max() >= w - 3 or poly[:, 1].max() >= h - 3)
                objs.append({"polygon": np.round(poly, 2).tolist(), "partial": parcial, "vertices": int(len(poly)),
                             "contorno_confiavel": False, "sam2_iou": round(float(dd["iou"]), 3),
                             "raio_px": round(math.sqrt(cv2.contourArea(poly.astype(np.float32)) / math.pi), 2)})
            if not objs:
                continue
            split_origem = js.parent.name
            grupo = f"{a.id}__{split_origem}__{Path(im['file_name']).stem}"[:180]
            ok, buf = cv2.imencode(".png", peso)
            (saida / "pesos" / f"{grupo}.png").write_bytes(buf.tobytes())
            cenas.append({"group": grupo, "image": os.path.relpath(path, saida).replace("\\", "/"),
                          "weight_mask": f"pesos/{grupo}.png", "split": "pretreino", "split_origem": split_origem,
                          "grupo_origem": f"{a.id}:{grupo_origem(im['file_name'])}", "fonte": a.id, "licenca": lic,
                          "label_origin": f"sam2_de_caixa:{a.id}" + ("+edge_v1" if a.com_recorte else ""),
                          "width": w, "height": h, "annotations": objs})
            if n_img % 20 == 0:
                print(f"{n_img} imagens, {resumo['contornos']} contornos", flush=True)
    resumo["imagens"] = len(cenas)
    (saida / "annotations.json").write_text(json.dumps(cenas, ensure_ascii=False), encoding="utf-8")
    (saida / "conversao.json").write_text(json.dumps({"fonte": a.id, "metodo": "caixa->SAM 2.1 Tiny (prompt caixa+centro)",
                                                      "edge_v1": a.com_recorte, "sam2": str(Path(a.sam2).resolve()),
                                                      "licenca": lic, "resumo": resumo}, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps(resumo, ensure_ascii=False))
    return 0 if cenas else 1


if __name__ == "__main__":
    sys.exit(main())
