#!/usr/bin/env python3
"""Converte um export COCO Segmentation (Roboflow ou outro) para o formato de cenas do PelotaVision.

Saida: <saida>/annotations.json + mascaras de peso em <saida>/pesos/, no mesmo formato aceito por
ml_training/borda (e compativel com o campo "polygon" do StarDist):

  {"group", "image" (caminho relativo a annotations.json), "weight_mask", "split": "pretreino",
   "split_origem", "grupo_origem", "fonte", "licenca", "label_origin": "externo:<id>",
   "annotations": [{"polygon": [[x,y],...], "partial", "vertices", "contorno_confiavel", "raio_px"}]}

Regras (nenhuma inventa fundo):
- So categorias de pelota viram objetos (--classes; vazio = todas exceto a supercategoria do Roboflow).
- Anotacoes de outras classes viram regiao IGNORADA (peso 0), nunca fundo.
- Poligono com poucos vertices (< --min-vertices) fica como objeto, mas com contorno_confiavel=false:
  o treino usa o interior e ignora a faixa de borda dele (poligono grosseiro desloca a borda).
- Bounding box sem poligono: regiao ignorada (nao ha contorno).
- Coordenadas COCO (origem no canto do pixel) passam para centro de pixel (-0,5).
- Copias aumentadas do Roboflow ("<nome>_jpg.rf.<hash>.jpg") recebem o mesmo grupo_origem,
  para que nunca caiam em treino e validacao ao mesmo tempo.

Uso:
  python tools/externos/coco_para_pelotavision.py --entrada <pasta do export> --id rf_iron_ore_pellets_healer \
      --saida ../PelotaVision-local/datasets/externos/convertido/rf_iron_ore_pellets_healer [--classes pellet]
"""
from __future__ import annotations

import argparse
import json
import math
import os
import re
import sys
from pathlib import Path

import cv2
import numpy as np


def poligonos(seg) -> list[np.ndarray]:
    if not isinstance(seg, list):  # RLE (iscrowd) nao tem contorno confiavel
        return []
    out = []
    for p in seg:
        # COCO usa canto do pixel como origem ((0,0) = canto superior esquerdo); o PelotaVision (OpenCV,
        # StarDist, app) usa o CENTRO do pixel. Sem este -0,5 o contorno inteiro fica deslocado meio pixel.
        a = np.asarray(p, np.float64).reshape(-1, 2) - 0.5
        if len(a) >= 3:
            out.append(a)
    return out


def area_poligono(p: np.ndarray) -> float:
    x, y = p[:, 0], p[:, 1]
    return abs(0.5 * float(np.sum(x * np.roll(y, -1) - np.roll(x, -1) * y)))


def grupo_origem(nome: str) -> str:
    base = Path(nome).name
    m = re.match(r"(.+?)_(jpe?g|png|bmp|tiff?)\.rf\.[0-9a-f]+\.\w+$", base, re.I)
    return (m.group(1) if m else Path(base).stem).lower()


def ler_imagem_shape(path: Path):
    img = cv2.imdecode(np.fromfile(str(path), np.uint8), cv2.IMREAD_UNCHANGED)
    return None if img is None else img.shape[:2]


def converter(entrada: Path, saida: Path, fonte_id: str, classes: list[str], min_vertices: int,
              licenca: str | None, borda_px: float = 2.0) -> dict:
    jsons = sorted(entrada.rglob("_annotations.coco.json")) or sorted(entrada.rglob("*.json"))
    if not jsons:
        raise FileNotFoundError(f"Nenhum COCO JSON em {entrada}")
    saida.mkdir(parents=True, exist_ok=True)
    (saida / "pesos").mkdir(exist_ok=True)
    cenas, resumo = [], {"objetos": 0, "objetos_contorno_grosseiro": 0, "ignorados_outras_classes": 0,
                         "ignorados_sem_poligono": 0, "imagens_sem_pelota": 0, "imagens_ilegiveis": 0}
    for js in jsons:
        d = json.loads(js.read_text(encoding="utf-8"))
        cats = {c["id"]: c for c in d.get("categories", [])}
        # Roboflow cria uma supercategoria-raiz (id 0) com o nome do projeto e sem anotacoes.
        usados = {a.get("category_id") for a in d.get("annotations", [])}
        if classes:
            alvo = {i for i, c in cats.items() if c["name"].lower() in {k.lower() for k in classes}}
        else:
            alvo = {i for i in cats if i in usados}
        por_img = {}
        for a in d.get("annotations", []):
            por_img.setdefault(a["image_id"], []).append(a)
        split_origem = js.parent.name if js.parent != entrada else "unico"
        for im in d.get("images", []):
            img_path = js.parent / im["file_name"]
            shape = ler_imagem_shape(img_path)
            if shape is None:
                resumo["imagens_ilegiveis"] += 1
                continue
            h, w = shape
            peso = np.full((h, w), 255, np.uint8)
            objs = []
            for a in por_img.get(im["id"], []):
                polys = poligonos(a.get("segmentation"))
                if a.get("category_id") not in alvo:
                    resumo["ignorados_outras_classes"] += 1
                    for p in polys:
                        cv2.fillPoly(peso, [np.round(p).astype(np.int32)], 0)
                    if not polys and a.get("bbox"):
                        x, y, bw, bh = a["bbox"]
                        cv2.rectangle(peso, (int(x), int(y)), (int(x + bw), int(y + bh)), 0, -1)
                    continue
                if not polys:
                    resumo["ignorados_sem_poligono"] += 1
                    if a.get("bbox"):
                        x, y, bw, bh = a["bbox"]
                        cv2.rectangle(peso, (int(x), int(y)), (int(x + bw), int(y + bh)), 0, -1)
                    continue
                p = max(polys, key=area_poligono)  # partes soltas do mesmo objeto: fica a maior
                if len(polys) > 1:
                    for q in polys:
                        if q is not p:
                            cv2.fillPoly(peso, [np.round(q).astype(np.int32)], 0)
                ar = area_poligono(p)
                if ar < 9:
                    continue
                parcial = bool(p[:, 0].min() <= borda_px or p[:, 1].min() <= borda_px
                               or p[:, 0].max() >= w - 1 - borda_px or p[:, 1].max() >= h - 1 - borda_px)
                confiavel = len(p) >= min_vertices
                resumo["objetos"] += 1
                resumo["objetos_contorno_grosseiro"] += int(not confiavel)
                objs.append({"polygon": np.round(p, 2).tolist(), "partial": parcial, "vertices": int(len(p)),
                             "contorno_confiavel": confiavel, "raio_px": round(math.sqrt(ar / math.pi), 2)})
            if not objs:
                resumo["imagens_sem_pelota"] += 1
                continue
            grupo = f"{fonte_id}__{split_origem}__{Path(im['file_name']).stem}"[:180]
            peso_rel = f"pesos/{grupo}.png"
            ok, buf = cv2.imencode(".png", peso)
            (saida / peso_rel).write_bytes(buf.tobytes())
            cenas.append({"group": grupo, "image": os.path.relpath(img_path, saida).replace("\\", "/"),
                          "weight_mask": peso_rel, "split": "pretreino", "split_origem": split_origem,
                          "grupo_origem": f"{fonte_id}:{grupo_origem(im['file_name'])}", "fonte": fonte_id,
                          "licenca": licenca, "label_origin": f"externo:{fonte_id}", "width": w, "height": h,
                          "annotations": objs})
    raios = [o["raio_px"] for c in cenas for o in c["annotations"] if not o["partial"]]
    resumo.update(imagens=len(cenas), grupos_origem=len({c["grupo_origem"] for c in cenas}),
                  raio_px_mediana=round(float(np.median(raios)), 2) if raios else None,
                  vertices_mediana=int(np.median([o["vertices"] for c in cenas for o in c["annotations"]])) if cenas else None)
    (saida / "annotations.json").write_text(json.dumps(cenas, ensure_ascii=False), encoding="utf-8")
    (saida / "conversao.json").write_text(json.dumps({"fonte": fonte_id, "entrada": str(entrada), "classes": classes,
                                                      "min_vertices": min_vertices, "licenca": licenca, "resumo": resumo},
                                                     ensure_ascii=False, indent=1), encoding="utf-8")
    return resumo


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--entrada", required=True, help="pasta do export (contem train/valid/test)")
    ap.add_argument("--saida", required=True)
    ap.add_argument("--id", required=True)
    ap.add_argument("--classes", nargs="*", default=[], help="categorias que sao pelota (padrao: todas usadas)")
    ap.add_argument("--min-vertices", type=int, default=12)
    ap.add_argument("--licenca", default=None, help="padrao: lida de proveniencia.json, se existir")
    a = ap.parse_args(argv)
    entrada = Path(a.entrada).resolve()
    lic = a.licenca
    for cand in (entrada / "proveniencia.json", entrada.parent / "proveniencia.json"):
        if lic is None and cand.exists():
            p = json.loads(cand.read_text(encoding="utf-8"))
            lic = p.get("licenca_api") or p.get("licenca_registro")
    r = converter(entrada, Path(a.saida).resolve(), a.id, a.classes, a.min_vertices, lic)
    print(json.dumps(r, ensure_ascii=False, indent=1))
    return 0 if r["imagens"] else 1


if __name__ == "__main__":
    sys.exit(main())
