#!/usr/bin/env python3
"""Ponte PelotaVision <-> X-AnyLabeling (formato LabelMe), na foto ORIGINAL ou na FILTRADA (ROI ampliada).

  pre   : JSON da analise do app -> pre-anotacao para revisar (auto_pelota, auto_trinca, ignorar, roi)
  treino: anotacao revisada (LabelMe) -> annotations.json do PelotaVision, SEMPRE em pixels da foto original
          (o app mede na original; a filtrada tem realce Gama+CLAHE e serve so para enxergar melhor)

A filtrada e o recorte da ROI ampliado: filtrada = (original - crop) * render_scale, com crop_left/crop_top e
render_scale gravados em report_viewport do JSON da analise. Os dois sentidos usam essa mesma relacao.

Rotulos (o que nao for 'pelota' nunca vira fundo):
  pelota           -> objeto com contorno confiavel (voce conferiu a borda)
  pelota_duvidosa  -> regiao ignorada
  ignorar          -> regiao ignorada (esfera de calibracao, po, reflexo)
  auto_*           -> regiao ignorada (pre-anotacao ainda nao revisada)
  trinca           -> marca a pelota que contem o centro da forma (lista em 'trincas'); nao entra na borda
  roi              -> fora deste poligono nada e supervisionado
Pelota detectada pelo app e nao anotada vira regiao ignorada (--sem-completar desliga). A esfera de calibracao
anotada como 'pelota' vira ignorada, e o diametro dela fica no resumo: se voce desenha a esfera e as pelotas
com a mesma convencao de borda, a escala tirada da SUA esfera cancela essa convencao.
Na filtrada, o que fica fora do circulo da ROI e sempre ignorado.

Uso:
  python tools/externos/xanylabeling_pelotavision.py pre --analise X.json --alvo filtrada
  python tools/externos/xanylabeling_pelotavision.py treino --analise X.json --anotacao X_filtrada.json \
      --saida ../PelotaVision-local/datasets/bancada_revisada
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

N_VERT = 48
VERSAO = "2.4.4"
IGNORADOS = ("pelota_duvidosa", "ignorar")


# ---------------------------------------------------------------- geometria

class Vista:
    """original (centro de pixel) <-> arquivo de anotacao (canto de pixel, como LabelMe/COCO)."""

    def __init__(self, analise: dict, alvo: str):
        self.alvo = alvo
        if alvo == "original":
            self.s, self.x0, self.y0 = 1.0, 0.0, 0.0
            src = analise.get("source", {})
            self.w = src.get("analyzed_width") or src.get("original_width")
            self.h = src.get("analyzed_height") or src.get("original_height")
            self.imagem = analise["original_file"]
        elif alvo == "filtrada":
            v = analise["report_viewport"]
            self.s, self.x0, self.y0 = float(v["render_scale"]), float(v["crop_left"]), float(v["crop_top"])
            self.w, self.h = int(v["render_width"]), int(v["render_height"])
            self.imagem = analise["report_images"]["filtered_file"]
        else:
            raise ValueError(alvo)

    def para_arquivo(self, x, y):
        # centro de pixel na original -> centro na vista -> canto (+0,5)
        return (x - self.x0) * self.s + 0.5, (y - self.y0) * self.s + 0.5

    def para_original(self, pts: np.ndarray) -> np.ndarray:
        q = np.asarray(pts, np.float64) - 0.5
        return np.stack([q[:, 0] / self.s + self.x0, q[:, 1] / self.s + self.y0], 1)


def elipse(cx, cy, eixo_maior, eixo_menor, angulo_deg, n=N_VERT):
    """Elipse do app (eixos completos; angulo do eixo MAIOR, convencao RotatedRect do OpenCV), em centro de pixel."""
    a, b, t = eixo_maior / 2, eixo_menor / 2, math.radians(angulo_deg)
    u = 2 * np.pi * np.arange(n) / n
    x, y = a * np.cos(u), b * np.sin(u)
    return np.stack([cx + x * math.cos(t) - y * math.sin(t), cy + x * math.sin(t) + y * math.cos(t)], 1)


def roi_circulo(analise):
    roi = analise.get("region_of_interest") or {}
    if roi.get("shape") != "CIRCLE":
        return None
    cx, cy = (roi["left_px"] + roi["right_px"]) / 2, (roi["top_px"] + roi["bottom_px"]) / 2
    r = min(roi["right_px"] - roi["left_px"], roi["bottom_px"] - roi["top_px"]) / 2
    return cx, cy, r


# ---------------------------------------------------------------- pre-anotacao

def forma(label, pts, tipo="polygon", descricao=""):
    return {"label": label, "points": [[round(float(x), 2), round(float(y), 2)] for x, y in pts], "group_id": None,
            "description": descricao, "difficult": False, "shape_type": tipo, "flags": {}, "attributes": {}}


def pre_anotacao(analise: dict, alvo: str) -> dict:
    v = Vista(analise, alvo)
    conv = lambda P: [v.para_arquivo(x, y) for x, y in P]  # noqa: E731
    trincas = {p["pellet_id"]: p for p in analise.get("cracks", {}).get("pellets", [])}
    shapes = []
    for d in analise.get("detections", []):
        tr = trincas.get(d["id"], {})
        desc = (f"app id {d['id']}; needs_review={str(d.get('needs_review')).lower()}; trinca={tr.get('status', d.get('crack_status'))}"
                + (" (revisada)" if tr.get("manually_reviewed") or d.get("crack_manually_reviewed") else ""))
        shapes.append(forma("auto_pelota", conv(elipse(d["x_px"], d["y_px"], d["ellipse_major_axis_px"],
                                                       d["ellipse_minor_axis_px"], d["ellipse_angle_deg"])), descricao=desc))
        runs = tr.get("marked_runs_x1_y1_x2_y2_px") or []
        if tr.get("status") == "POSSIBLE" and len(runs) >= 4:
            xs, ys = runs[0::2], runs[1::2]
            shapes.append(forma("auto_trinca", conv([(min(xs) - 2, min(ys) - 2), (max(xs) + 2, max(ys) + 2)]), "rectangle",
                                f"app id {d['id']}; score={tr.get('model_score', 0):.3f}"))
    ref = ((analise.get("physical_scale") or {}).get("selected_object") or {}).get("original_ellipse")
    if ref:
        shapes.append(forma("ignorar", conv(elipse(ref["x_px"], ref["y_px"], ref["major_axis_px"] + 6, ref["minor_axis_px"] + 6,
                                                   ref["angle_deg"])), descricao="esfera de calibracao"))
    c = roi_circulo(analise)
    if c and alvo == "original":
        shapes.append(forma("roi", conv(elipse(c[0], c[1], 2 * c[2], 2 * c[2], 0, n=96)), descricao="regiao analisada pelo app"))
    return {"version": VERSAO, "flags": {}, "shapes": shapes, "imagePath": v.imagem, "imageData": None,
            "imageHeight": v.h, "imageWidth": v.w,
            "description": f"pre-anotacao PelotaVision ({analise.get('app')}); renomeie para 'pelota' o que conferir"}


# ---------------------------------------------------------------- anotacao revisada -> treino

def pontos_forma(s) -> np.ndarray | None:
    p = np.asarray(s.get("points") or [], np.float64)
    t = s.get("shape_type", "polygon")
    if t == "rectangle" and len(p) == 2:
        (x0, y0), (x1, y1) = p
        return np.array([[x0, y0], [x1, y0], [x1, y1], [x0, y1]])
    if t == "circle" and len(p) == 2:
        (cx, cy), (px, py) = p
        r = math.hypot(px - cx, py - cy)
        u = 2 * np.pi * np.arange(48) / 48
        return np.stack([cx + r * np.cos(u), cy + r * np.sin(u)], 1)
    if t == "polygon" and len(p) >= 3:
        return p
    return None


def anotacao_para_cena(analise: dict, anot: dict, pasta_analise: Path, saida: Path, alvo: str | None = None,
                       completar_com_app: bool = True) -> tuple[dict, dict]:
    if alvo is None:
        nome = Path(anot.get("imagePath", "")).name
        alvo = "filtrada" if nome == analise.get("report_images", {}).get("filtered_file") else "original"
    v = Vista(analise, alvo)
    if (anot.get("imageWidth"), anot.get("imageHeight")) != (v.w, v.h):
        raise ValueError(f"anotacao {anot.get('imageWidth')}x{anot.get('imageHeight')} nao bate com a vista {alvo} {v.w}x{v.h}")
    src = analise.get("source", {})
    W, H = src.get("original_width"), src.get("original_height")
    peso = np.zeros((H, W), np.uint8)
    c = roi_circulo(analise)
    if c:
        cv2.circle(peso, (int(round(c[0])), int(round(c[1]))), int(c[2]), 255, -1)
    else:
        peso[:] = 255
    ignorar, objs, trincas, roi_user, cont = [], [], [], [], {}
    for s in anot.get("shapes", []):
        lab = str(s.get("label", "")).strip().lower()
        cont[lab] = cont.get(lab, 0) + 1
        p = pontos_forma(s)
        if p is None:
            continue
        po = v.para_original(p)
        if lab == "pelota":
            objs.append(po)
        elif lab == "trinca":
            trincas.append(po)
        elif lab == "roi":
            roi_user.append(po)
        elif lab in IGNORADOS or lab.startswith("auto_") or lab:
            ignorar.append(po)
    # Esfera de calibracao anotada como pelota: nao e pelota (cor, material) -> regiao ignorada.
    ref = ((analise.get("physical_scale") or {}).get("selected_object") or {}).get("original_ellipse")
    esfera_px = None
    if ref:
        rr = (ref["major_axis_px"] + ref["minor_axis_px"]) / 4
        resto = []
        for po in objs:
            c0 = po.mean(0)
            if math.hypot(c0[0] - ref["x_px"], c0[1] - ref["y_px"]) < 0.3 * rr:
                esfera_px = 2 * math.sqrt(abs(cv2.contourArea(po.astype(np.float32))) / math.pi)
                ignorar.append(po)
            else:
                resto.append(po)
        objs = resto
    # Deteccoes do app sem anotacao correspondente viram regiao ignorada (pelota que voce pulou nao vira fundo).
    completadas = 0
    if completar_com_app:
        mh = np.zeros((H, W), np.int32)
        for k, po in enumerate(objs + ignorar, 1):
            cv2.fillPoly(mh, [np.round(po).astype(np.int32)], k)
        for d in analise.get("detections", []):
            e = elipse(d["x_px"], d["y_px"], d["ellipse_major_axis_px"], d["ellipse_minor_axis_px"], d["ellipse_angle_deg"], 72)
            me = np.zeros((H, W), np.uint8)
            cv2.fillPoly(me, [np.round(e).astype(np.int32)], 1)
            area = int(me.sum())
            if area and (mh[me > 0] > 0).mean() < 0.3:
                ignorar.append(e)
                completadas += 1
    if roi_user:
        m = np.zeros_like(peso)
        for r in roi_user:
            cv2.fillPoly(m, [np.round(r).astype(np.int32)], 255)
        peso = np.minimum(peso, m)
    for q in ignorar:
        cv2.fillPoly(peso, [np.round(q).astype(np.int32)], 0)
    anns = []
    for po in objs:
        area = abs(cv2.contourArea(po.astype(np.float32)))
        if area < 9:
            continue
        parcial = bool(po[:, 0].min() <= 2 or po[:, 1].min() <= 2 or po[:, 0].max() >= W - 3 or po[:, 1].max() >= H - 3)
        if c:   # pelota cortada pela borda da ROI tambem e parcial
            parcial |= bool(np.max(np.hypot(po[:, 0] - c[0], po[:, 1] - c[1])) >= c[2] - 1)
        cx, cy = po.mean(0)
        tem_trinca = any(cv2.pointPolygonTest(po.astype(np.float32), tuple(map(float, t.mean(0))), False) >= 0 for t in trincas)
        anns.append({"polygon": np.round(po, 2).tolist(), "partial": parcial, "vertices": int(len(po)),
                     "contorno_confiavel": True, "trinca": tem_trinca, "raio_px": round(math.sqrt(area / math.pi), 2),
                     "label_origin": f"revisao_humana_{alvo}"})
    grupo = Path(analise["original_file"]).stem.replace("_original", "")
    (saida / "pesos").mkdir(parents=True, exist_ok=True)
    ok, buf = cv2.imencode(".png", peso)
    (saida / "pesos" / f"{grupo}.png").write_bytes(buf.tobytes())
    img = pasta_analise / analise["original_file"]
    cena = {"group": grupo, "image": os.path.relpath(img, saida).replace("\\", "/"), "weight_mask": f"pesos/{grupo}.png",
            "split": "a_definir", "grupo_origem": analise.get("output_folder") or grupo, "fonte": "revisao_humana",
            "licenca": "privado Vale", "label_origin": f"revisao_humana_{alvo}", "width": W, "height": H,
            "escala_px_por_mm": (analise.get("physical_scale") or {}).get("original_pixels_per_mm"),
            "annotations": anns}
    resumo = {"rotulos": cont, "pelotas": len(anns), "pelotas_inteiras": sum(not a["partial"] for a in anns),
              "com_trinca": sum(a["trinca"] for a in anns), "trincas_sem_pelota": sum(
                  not any(cv2.pointPolygonTest(o.astype(np.float32), tuple(map(float, t.mean(0))), False) >= 0 for o in objs) for t in trincas),
              "regioes_ignoradas": len(ignorar), "deteccoes_do_app_completadas_como_ignoradas": completadas,
              "esfera_anotada_px": None if esfera_px is None else round(esfera_px, 2),
              "esfera_app_px": (analise.get("physical_scale") or {}).get("reference_pixels"), "alvo": alvo}
    if esfera_px:
        resumo["esfera_razao_anotada_app"] = round(esfera_px / resumo["esfera_app_px"], 4)
        cena["escala_px_por_mm_anotacao"] = round(esfera_px / float((analise["physical_scale"]["selected_object"] or {}).get("real_dimension_mm") or 11), 4)
    return cena, resumo


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    a1 = sub.add_parser("pre")
    a1.add_argument("--analise", required=True)
    a1.add_argument("--alvo", choices=("original", "filtrada"), default="filtrada")
    a1.add_argument("--saida", default=None)
    a1.add_argument("--forcar", action="store_true")
    a2 = sub.add_parser("treino")
    a2.add_argument("--analise", required=True)
    a2.add_argument("--anotacao", required=True)
    a2.add_argument("--saida", required=True, help="pasta do conjunto (annotations.json e acumulado)")
    a2.add_argument("--sem-completar", action="store_true", help="nao usar deteccoes do app para ignorar pelotas puladas")
    a = ap.parse_args(argv)
    p = Path(a.analise)
    analise = json.loads(p.read_text(encoding="utf-8"))
    if a.cmd == "pre":
        out = pre_anotacao(analise, a.alvo)
        dest = Path(a.saida) if a.saida else p.with_name(out["imagePath"]).with_suffix(".json")
        if dest.exists() and not a.forcar:
            sys.exit(f"{dest} ja existe (pode ter revisao sua). Nada foi alterado.")
        dest.write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
        n = {}
        for s in out["shapes"]:
            n[s["label"]] = n.get(s["label"], 0) + 1
        print(f"{dest}: {n}")
        return 0
    saida = Path(a.saida)
    saida.mkdir(parents=True, exist_ok=True)
    anot = json.loads(Path(a.anotacao).read_text(encoding="utf-8"))
    cena, resumo = anotacao_para_cena(analise, anot, p.parent.resolve(), saida.resolve(), completar_com_app=not a.sem_completar)
    arq = saida / "annotations.json"
    cenas = json.loads(arq.read_text(encoding="utf-8")) if arq.exists() else []
    cenas = [c for c in cenas if c["group"] != cena["group"]] + [cena]   # reconverter substitui a mesma foto
    arq.write_text(json.dumps(cenas, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(resumo, ensure_ascii=False, indent=1))
    print(f"{arq}: {len(cenas)} foto(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
