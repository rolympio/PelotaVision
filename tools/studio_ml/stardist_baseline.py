"""Baseline StarDist existente, sem pesos novos ou integração com o Studio.

Uso: python -m tools.studio_ml.stardist_baseline --run <pasta watershed>
Lê a imagem original indicada em record.json e grava um run privado.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import cv2
import numpy as np

from .pipeline import (PRIVATE, boundary, compare_instances, json_write,
                       new_run, read_image, sha256, write_image)

def load_reference():
    root = Path(__file__).resolve().parents[2]
    sys.path.insert(0, str(root / "ml_training" / "stardist"))
    import common  # noqa
    return common

def rasterize(shape: tuple[int, int], polygons):
    """Copiado em lógica do common.render_labels, para rasterizar polígonos finais."""
    h, w = shape
    labels = np.zeros((h, w), np.uint16)
    for i, polygon in enumerate(polygons, 1):
        if not len(polygon):
            continue
        poly = np.asarray(polygon, np.float64)
        x0, x1 = max(0, int(poly[:, 0].min())), min(w, int(poly[:, 0].max()) + 2)
        y0, y1 = max(0, int(poly[:, 1].min())), min(h, int(poly[:, 1].max()) + 2)
        if x1 <= x0 or y1 <= y0:
            continue
        yy, xx = np.mgrid[y0:y1, x0:x1].astype(np.float64)
        inside = np.zeros(xx.shape, bool)
        for (ax, ay), (bx, by) in zip(poly, np.roll(poly, -1, 0)):
            if ay == by:
                continue
            cross = ((ay > yy) != (by > yy)) & (xx < ax + (yy - ay) * (bx - ax) / (by - ay))
            inside ^= cross
        sub = labels[y0:y1, x0:x1]
        take = inside & (sub == 0)
        sub[take] = i
    return labels

def run(run_dir):
    run_dir = Path(run_dir).resolve(strict=True)
    if not run_dir.is_relative_to(Path(PRIVATE).resolve()):
        raise ValueError("Run watershed deve ficar na raiz privada autorizada")
    summary = json.loads((run_dir / "summary.json").read_text(encoding="utf-8"))
    common = load_reference()
    model = common.Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "assets" / "pellet_stardist.onnx"
    if not model.is_file():
        raise FileNotFoundError(f"Modelo StarDist ausente: {model}")
    model_sha = sha256(model)
    out = new_run("stardist-baseline")
    session = cv2.dnn.readNetFromONNX(str(model))
    session.setPreferableBackend(cv2.dnn.DNN_BACKEND_OPENCV)
    session.setPreferableTarget(cv2.dnn.DNN_TARGET_CPU)
    def run_model(tile):
        blob = cv2.dnn.blobFromImage(tile, scalefactor=1.0, size=(384, 384), swapRB=False)
        session.setInput(blob)
        output = session.forward()
        if output.shape != (33, 384, 384) and output.shape != (1, 33, 384, 384):
            raise ValueError(f"Saída StarDist inesperada: {output.shape}")
        return output[0] if output.ndim == 4 else output
    results = []
    for row in summary["rows"]:
        folder = run_dir / row["directory"]
        with (folder / "record.json").open(encoding="utf-8") as f:
            record = json.load(f)
        source = Path(record["source"])
        if not source.is_file():
            raise FileNotFoundError(record["source"])
        before = sha256(source)
        if before != record["source_sha256"]:
            raise RuntimeError("Original mudou desde o watershed")
        original = read_image(source)
        h, w = original.shape[:2]
        scale = min(1.0, 1024 / max(h, w))
        processed = cv2.resize(original, (round(w * scale), round(h * scale)), interpolation=cv2.INTER_AREA) if scale < 1 else original
        gray = cv2.cvtColor(processed, cv2.COLOR_BGR2GRAY)
        img_out = out / row["directory"]
        img_out.mkdir(parents=True, exist_ok=False)
        t0 = time.monotonic()
        # Recupera polígonos finais via candidates_for_scale+NMS públicos do common,
        # com a mesma escala final escolhida por common.detect.
        detections, ncand, limit, final_scale = (None, 0, False, None)
        trials = []
        for frac in common.PROFILE_RADII[3]:
            sc = common.NOMINAL_RADIUS / (frac * gray.shape[1])
            cands, kept, _ = common._pass(run_model, gray, sc, common.threshold(75))
            cover, rnet = common._scale_stats(kept, sc)
            trials.append((sc, len(cands), kept, rnet))
        chosen = min((t for t in trials if t[2]), key=lambda t: abs(np.log(t[3] / common.NOMINAL_RADIUS))) if any(t[2] for t in trials) else None
        polygons = []
        if chosen:
            sc, _, _, rnet = chosen
            factor = min(common.REFINE_LIMITS[1], max(common.REFINE_LIMITS[0], common.NOMINAL_RADIUS / rnet))
            final_scale = sc * factor
            cands, kept, limit = common._pass(run_model, gray, final_scale, common.threshold(75))
            ncand = sum(t[1] for t in trials) + len(cands)
            polygons = [k["poly"].tolist() for k in kept]
            detections = [common.describe(k, gray.shape[1], gray.shape[0]) for k in kept]
        else:
            detections, ncand, limit = [], sum(t[1] for t in trials), False
        detections = sorted(detections, key=lambda d: (d["y"], d["x"]))
        labels = rasterize(gray.shape, polygons)
        write_image(img_out / "stardist_instances_uint16.png", labels)
        # Visualização sobre image original
        color = np.stack([(labels.astype(np.uint32) * 73) % 255, (labels.astype(np.uint32) * 139) % 255,
                          (labels.astype(np.uint32) * 211) % 255], axis=-1).astype(np.uint8)
        overlay = processed.copy()
        if (labels > 0).any():
            overlay[labels > 0] = cv2.addWeighted(processed, .65, color, .35, 0)[labels > 0]
            overlay[boundary(labels) > 0] = (0, 255, 255)
        write_image(img_out / "stardist_overlay.jpg", overlay)
        metas = [{**d, "poly": None} for d in detections]
        comp = compare_instances(read_image(folder / "instances_uint16.png", cv2.IMREAD_UNCHANGED), labels)
        if sha256(source) != before:
            raise RuntimeError("Original mudou durante o StarDist")
        results.append({"directory": row["directory"], "source_sha256": before, "instances_stardist": len(detections),
                        "ncandidates": ncand, "limit": bool(limit), "seconds": round(time.monotonic() - t0, 4),
                        "comparison_watershed_stardist": comp, "split": "dev", "group_status": "unconfirmed",
                        "warning": "Baseline StarDist treinado em pseudo-rótulos, sem verdade independente; concordância não é acurácia."})
    hashes = {str(p.relative_to(out)): sha256(p) for p in sorted(out.rglob("*")) if p.is_file()}
    json_write(out / "summary.json", {"schema": "pelotavision.studio.stardist_baseline.v1",
                                      "model": {"path": "app/src/main/assets/pellet_stardist.onnx", "sha256": model_sha},
                                      "results": results,
                                      "note": "Pesos existentes, inferência CPU OpenCV; nenhuma otimização ou véu científico."})
    json_write(out / "artifact_hashes.json", hashes)
    return out

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--run", required=True, type=Path)
    args = p.parse_args()
    out = run(args.run)
    print(out)


if __name__ == "__main__":
    raise SystemExit(main())
