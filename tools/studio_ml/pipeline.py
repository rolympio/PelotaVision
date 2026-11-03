"""Baseline OpenCV auditável. Pseudo-rótulos NÃO são verdade-terreno.

Uso: python -m tools.studio_ml.pipeline --help
Somente lê imagens originais; todos os artefatos ficam na raiz privada autorizada.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import importlib.util
import json
import math
import platform
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path

import cv2
import numpy as np

REPO = Path(__file__).resolve().parents[2]
PRIVATE = REPO.parent / "PelotaVision-local" / "experiments" / "studio-instances-20261103"
CLASSES = {0: "fundo_provisorio", 1: "pelota_indeterminada", 2: "esfera_candidata_2d",
           3: "objeto_desconhecido_ou_fora_roi"}
WARN = ("Pseudo-rotulos dependentes, sem ground truth; circularidade 2D nao e esfericidade 3D; "
        "ausencia de sinal de trinca nao demonstra integridade estrutural.")


def sha256(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def json_write(path, value):
    with Path(path).open("x", encoding="utf-8") as f:
        json.dump(value, f, ensure_ascii=False, indent=2, allow_nan=False)


def new_run(prefix, root=PRIVATE):
    root = Path(root).resolve()
    if not root.is_relative_to(PRIVATE.resolve()):
        raise ValueError(f"Saida deve permanecer na raiz privada: {PRIVATE}")
    name = f"{prefix}-{datetime.now(timezone.utc):%Y%m%dT%H%M%SZ}-{uuid.uuid4().hex[:10]}"
    out = root / name
    out.mkdir(parents=True, exist_ok=False)
    return out


def read_image(path, flags=cv2.IMREAD_COLOR):
    image = cv2.imdecode(np.fromfile(str(path), np.uint8), flags)
    if image is None:
        raise ValueError(f"Imagem ilegivel: {path}")
    return image


def write_image(path, image):
    path = Path(path)
    ok, encoded = cv2.imencode(path.suffix, image)
    if not ok:
        raise ValueError(f"Falha de codificacao: {path}")
    with path.open("xb") as f:
        f.write(encoded.tobytes())


def environment():
    dependencies = {}
    for name in ("numpy", "opencv-python", "opencv-python-headless", "torch"):
        try:
            d = importlib.metadata.distribution(name)
            dependencies[name] = {"version": d.version, "license": d.metadata.get("License", "nao informada")}
        except importlib.metadata.PackageNotFoundError:
            pass
    return {"python": sys.version, "executable": sys.executable, "platform": platform.platform(),
            "numpy": np.__version__, "opencv": cv2.__version__, "packages": dependencies,
            "torch_available": importlib.util.find_spec("torch") is not None,
            "gpu_used": False, "opencv_build": cv2.getBuildInformation()}


def ellipse_roi(shape, ellipse=None):
    roi = np.ones(shape, np.uint8)
    if ellipse is not None:
        cx, cy, rx, ry = ellipse
        if not (0 <= cx <= 1 and 0 <= cy <= 1 and 0 < rx <= 1 and 0 < ry <= 1):
            raise ValueError("ROI normalizada invalida")
        h, w = shape
        yy, xx = np.ogrid[:h, :w]
        roi = (((xx / w - cx) / rx) ** 2 + ((yy / h - cy) / ry) ** 2 <= 1).astype(np.uint8)
    return roi


def foreground(gray, roi, variant="raw"):
    smooth = cv2.GaussianBlur(gray, (5, 5), 0)
    if variant == "clahe":
        smooth = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8)).apply(smooth)
    valid = smooth[roi > 0]
    if not valid.size:
        raise ValueError("ROI vazia")
    threshold, _ = cv2.threshold(valid.reshape(-1, 1), 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    fg = ((smooth > threshold) & (roi > 0)).astype(np.uint8)
    fg = cv2.morphologyEx(fg, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    fg = cv2.morphologyEx(fg, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))
    fg[roi == 0] = 0
    return fg, float(threshold)


def watershed_instances(bgr, fg, min_area=60, seed_radius=12):
    """Transformada de distância + máximos locais + watershed, sem StarDist."""
    dist = cv2.distanceTransform(fg, cv2.DIST_L2, 5)
    r = max(1, int(seed_radius))
    maxima = ((dist >= cv2.dilate(dist, np.ones((2*r+1, 2*r+1), np.uint8)) - 1e-5)
              & (dist >= max(2., r * .35))).astype(np.uint8)
    n, seeds = cv2.connectedComponents(maxima)
    if n <= 1:
        return np.zeros(fg.shape, np.uint16)
    markers = seeds.astype(np.int32) + 1
    markers[(fg > 0) & (seeds == 0)] = 0
    cv2.watershed(bgr, markers)
    raw = np.where((markers > 1) & (fg > 0), markers - 1, 0)
    ids, counts = np.unique(raw, return_counts=True)
    keep = ids[(ids > 0) & (counts >= min_area)]
    if len(keep) > 65535:
        raise ValueError("Mais de 65535 instancias: nao cabe em uint16")
    lut = np.zeros(int(raw.max()) + 1, np.uint16)
    lut[keep] = np.arange(1, len(keep) + 1, dtype=np.uint16)
    return lut[raw]


def boundary(labels):
    """Borda interna dos próprios IDs, incluindo contato entre instâncias."""
    padded = np.pad(labels, 1)
    same = np.ones(labels.shape, bool)
    for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        same &= labels == padded[1+dy:1+dy+labels.shape[0], 1+dx:1+dx+labels.shape[1]]
    return ((labels > 0) & ~same).astype(np.uint8)


def compare_instances(a, b, iou_threshold=.5, tolerance=2):
    """Matching guloso determinístico por IoU; concordância, nunca acurácia."""
    if a.shape != b.shape:
        raise ValueError("Mascaras devem estar na mesma resolucao/grade")
    if not 0 < iou_threshold <= 1 or tolerance < 0:
        raise ValueError("Limiar/tolerancia invalido")
    if a.ndim != 2 or b.ndim != 2 or a.dtype.kind not in "ui" or b.dtype.kind not in "ui":
        raise ValueError("Instancias devem ser matrizes 2D de inteiros nao negativos")
    ia, aa = np.unique(a[a > 0], return_counts=True)
    ib, ab = np.unique(b[b > 0], return_counts=True)
    area_a, area_b = dict(zip(ia.tolist(), aa.tolist())), dict(zip(ib.tolist(), ab.tolist()))
    overlap = (a > 0) & (b > 0)
    pairs, intersections = np.unique(np.stack([a[overlap], b[overlap]], axis=1), axis=0, return_counts=True)
    candidates = []
    for (i, j), inter in zip(pairs, intersections):
        i, j, inter = int(i), int(j), int(inter)
        iou = inter / (area_a[i] + area_b[j] - inter)
        if iou >= iou_threshold:
            candidates.append((iou, i, j, 2 * inter / (area_a[i] + area_b[j])))
    used_a, used_b, matches = set(), set(), []
    for iou, i, j, dice in sorted(candidates, key=lambda c: (-c[0], c[1], c[2])):
        if i not in used_a and j not in used_b:
            used_a.add(i)
            used_b.add(j)
            matches.append({"a": i, "b": j, "iou": iou, "dice": dice})
    ba, bb = boundary(a), boundary(b)
    kernel = np.ones((2*tolerance+1, 2*tolerance+1), np.uint8)
    pa = float((ba & cv2.dilate(bb, kernel)).sum() / ba.sum()) if ba.any() else None
    pb = float((bb & cv2.dilate(ba, kernel)).sum() / bb.sum()) if bb.any() else None
    bf = (2 * pa * pb / (pa + pb) if pa + pb else 0.) if pa is not None and pb is not None else None
    contour_distance = None
    if ba.any() and bb.any():
        da = cv2.distanceTransform(1-ba, cv2.DIST_L2, 5)
        db = cv2.distanceTransform(1-bb, cv2.DIST_L2, 5)
        contour_distance = float((da[bb > 0].mean() + db[ba > 0].mean()) / 2)
    union = ((a > 0) | (b > 0)).sum()
    total = (a > 0).sum() + (b > 0).sum()
    return {"status": "concordancia_sem_ground_truth", "matching": "greedy_descending_iou_one_to_one",
            "iou_threshold": iou_threshold, "instances_a": len(ia), "instances_b": len(ib),
            "matches": matches, "matched_count": len(matches),
            "unmatched_a": len(ia)-len(used_a), "unmatched_b": len(ib)-len(used_b),
            "mean_matched_iou": float(np.mean([m['iou'] for m in matches])) if matches else None,
            "mean_matched_dice": float(np.mean([m['dice'] for m in matches])) if matches else None,
            "foreground_iou": float(overlap.sum()/union) if union else None,
            "foreground_dice": float(2*overlap.sum()/total) if total else None,
            "boundary_f1": bf, "boundary_tolerance_px_chebyshev": tolerance,
            "symmetric_contour_distance_px": contour_distance,
            "meanings": "Bordas/contornos globais incluem objetos sem par; IoU/Dice medios somente pares aceitos."}


def describe_instances(labels, roi, min_area):
    semantic = np.where(roi > 0, 0, 3).astype(np.uint8)
    descriptions = []
    roi_edge = cv2.dilate((1-roi).astype(np.uint8), np.ones((3, 3), np.uint8)) > 0
    for ident in np.unique(labels):
        if ident == 0:
            continue
        mask = (labels == ident).astype(np.uint8)
        contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        c = max(contours, key=cv2.contourArea)
        area = int(mask.sum())
        perimeter = cv2.arcLength(c, True)
        circularity = min(1., 4*math.pi*cv2.contourArea(c)/(perimeter**2)) if perimeter else 0.
        x, y, w, h = cv2.boundingRect(c)
        partial = x == 0 or y == 0 or x+w == labels.shape[1] or y+h == labels.shape[0] or bool((mask & roi_edge).any())
        aspect = min(w, h) / max(w, h)
        category = 2 if circularity >= .72 and aspect >= .75 and not partial else 1
        if area > labels.size * .18 or area < min_area:
            category = 3
        semantic[mask > 0] = category
        descriptions.append({"id": int(ident), "area_px": area, "bbox_xywh": [x, y, w, h],
                             "circularity_2d": circularity, "aspect_bbox": aspect, "partial": partial,
                             "semantic": category, "structural_integrity": "nao_avaliada",
                             "contours_xy": [p.reshape(-1, 2).tolist() for p in contours]})
    return semantic, descriptions


def consensus(a, b, comparison, descriptions, roi, valid_background=None):
    accepted = {m["a"]: m["b"] for m in comparison["matches"] if m["iou"] >= .7}
    eligible = {d["id"] for d in descriptions if d["semantic"] == 2 and not d["partial"]}
    reliable = np.zeros(a.shape, np.uint8)
    instances = np.zeros(a.shape, np.uint16)
    for i, j in accepted.items():
        if i in eligible:
            overlap = (a == i) & (b == j)
            instances[overlap] = i
            reliable[overlap] = 255
    bg = (a == 0) & (b == 0) & (roi > 0)
    if valid_background is not None:
        bg &= valid_background
    # Ignora fundo junto aos objetos e todo o exterior da ROI.
    bg = cv2.erode(bg.astype(np.uint8), np.ones((7, 7), np.uint8)) > 0
    reliable[bg] = 255
    uncertain = (255 - reliable).astype(np.uint8)
    return instances, reliable, uncertain


def possible_cracks(gray, labels):
    """Black-hat morfológico: sinal óptico exploratório separado, sem diagnóstico."""
    blackhat = cv2.morphologyEx(gray, cv2.MORPH_BLACKHAT, np.ones((9, 9), np.uint8))
    interior = (labels > 0).astype(np.uint8)
    interior[boundary(labels) > 0] = 0
    interior = cv2.erode(interior, np.ones((7, 7), np.uint8))
    values = blackhat[interior > 0]
    threshold = max(12., float(np.percentile(values, 95))) if values.size else 255.
    mask = ((blackhat > threshold) & (interior > 0)).astype(np.uint8) * 255
    return mask, {"method": "blackhat_9x9_dark_lines", "threshold": threshold,
                  "candidate_pixels": int((mask > 0).sum()), "label": "possivel_trinca_nao_confirmada",
                  "warning": "Textura, sombra, contato e risco superficial geram falsos positivos. Integridade nao avaliada."}


def multitask_targets(labels):
    edge = boundary(labels)
    interior = ((labels > 0) & (edge == 0)).astype(np.float32)
    # Separação: pixels próximos de pelo menos duas identidades diferentes.
    separation = np.zeros(labels.shape, np.uint8)
    seen = np.zeros(labels.shape, np.uint8)
    for ident in np.unique(labels):
        if ident == 0:
            continue
        near = cv2.dilate((labels == ident).astype(np.uint8), np.ones((5, 5), np.uint8))
        separation |= near & seen
        seen |= near
    return np.stack([interior, edge.astype(np.float32), separation.astype(np.float32)])


def process_image(source, out, args):
    before = sha256(source)
    original = read_image(source)
    h0, w0 = original.shape[:2]
    scale = min(1., args.max_side / max(h0, w0))
    bgr = cv2.resize(original, (round(w0*scale), round(h0*scale)), interpolation=cv2.INTER_AREA) if scale < 1 else original
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    roi = ellipse_roi(gray.shape, args.roi_ellipse)
    fg_a, ta = foreground(gray, roi, "raw")
    fg_b, tb = foreground(gray, roi, "clahe")
    a = watershed_instances(bgr, fg_a, args.min_area, args.seed_radius)
    b = watershed_instances(bgr, fg_b, args.min_area, args.seed_radius)
    comparison = compare_instances(a, b)
    semantic, descriptions = describe_instances(a, roi, args.min_area)
    # Primeiro plano descartado por área/semente não pode virar fundo confiável.
    semantic[(fg_a > 0) & (a == 0)] = 3
    agreed, weight, uncertainty = consensus(a, b, comparison, descriptions, roi,
                                            valid_background=(fg_a == 0) & (fg_b == 0))
    cracks, cracks_info = possible_cracks(gray, a)
    for name, image in (("instances_uint16.png", a), ("instances_clahe_uint16.png", b),
                        ("semantic_uint8.png", semantic), ("boundary_uint8.png", boundary(a)*255),
                        ("uncertainty_uint8.png", uncertainty), ("possible_cracks_uint8.png", cracks),
                        ("consensus_instances_uint16.png", agreed), ("pseudo_weight_uint8.png", weight),
                        ("roi_uint8.png", roi*255)):
        write_image(out / name, image)
    # Peso de borda: elimina regiões em desacordo e mantém supervisão apenas do consenso.
    targets = multitask_targets(a)
    np.savez_compressed(out / "training_dev.npz", gray=gray, targets=targets, weight=weight,
                        instances=a, source_sha256=before, split="dev", group_status="unconfirmed")
    color = np.stack([(a.astype(np.uint32)*73) % 255, (a.astype(np.uint32)*139) % 255,
                      (a.astype(np.uint32)*211) % 255], axis=-1).astype(np.uint8)
    overlay = bgr.copy()
    overlay[a > 0] = cv2.addWeighted(bgr, .65, color, .35, 0)[a > 0]
    overlay[boundary(a) > 0] = (0, 255, 255)
    write_image(out / "overlay_review.jpg", overlay)
    after = sha256(source)
    if before != after:
        raise RuntimeError("Original mudou durante a execucao: resultado invalido")
    record = {"source": str(source.resolve()), "source_sha256": before, "source_sha256_after": after,
              "source_shape_hw": [h0, w0], "mask_shape_hw": list(gray.shape),
              "transform": {"scale_xy": [gray.shape[1]/w0, gray.shape[0]/h0], "origin_xy": [0, 0],
                            "mapping": "x_mask=x_source*scale_x; y_mask=y_source*scale_y; nearest para IDs"},
              "roi_ellipse_normalized": args.roi_ellipse,
              "acquisition_candidate": source.parent.name, "confirmed_independent_group": None,
              "split": "dev", "group_status": "unconfirmed_no_train_val_test_split",
              "license_data": "privado; autorizacao de redistribuicao nao estabelecida",
              "classes": CLASSES, "threshold_raw": ta, "threshold_clahe": tb,
              "parameters": {"min_area_px": args.min_area, "seed_radius_px": args.seed_radius},
              "instances": descriptions, "comparison": comparison,
              "comparison_dependence": "mesma imagem, mesmo watershed; CLAHE nao e rotulador independente",
              "confidence_status": "heuristica_nao_calibrada; fundo por limiar pode conter pelotas escuras omitidas",
              "consensus_objects": int(len(np.unique(agreed[agreed > 0]))),
              "pseudo_reliable_pixels": int((weight > 0).sum()),
              "uncertain_fraction": float((uncertainty > 0).mean()),
              "possible_cracks": cracks_info, "warning": WARN}
    json_write(out / "record.json", record)
    return {"directory": out.name, "source_sha256": before, "split": "dev", "group_status": "unconfirmed",
            "instances": len(descriptions), "consensus_objects": record["consensus_objects"],
            "matched_count": comparison["matched_count"], "mean_matched_iou": comparison["mean_matched_iou"],
            "mean_matched_dice": comparison["mean_matched_dice"], "boundary_f1": comparison["boundary_f1"],
            "uncertain_fraction": record["uncertain_fraction"],
            "possible_crack_pixels": cracks_info["candidate_pixels"]}


def run(args):
    if args.max_side < 64 or args.min_area < 1 or args.seed_radius < 1 or args.limit < 1:
        raise ValueError("Parametros de tamanho/limite devem ser positivos")
    source = Path(args.source).resolve()
    if source.is_file():
        candidates = [source]
    else:
        candidates = sorted(p for p in source.rglob("*") if p.is_file() and not p.is_symlink()
                            and "original" in p.stem.lower() and p.suffix.lower() in {".jpg", ".jpeg", ".png", ".tif", ".tiff"})
    if not candidates:
        raise ValueError("Nenhum original candidato encontrado")
    out = new_run("watershed", args.output_root)
    json_write(out / "environment.json", environment())
    rows, duplicates, errors, seen = [], [], [], set()
    for image in candidates:
        if len(rows) >= args.limit:
            break
        digest = sha256(image)
        if digest in seen:
            duplicates.append({"path": str(image), "sha256": digest})
            continue
        seen.add(digest)
        folder = out / f"image-{len(rows):04d}-{digest[:12]}"
        folder.mkdir(exist_ok=False)
        try:
            row = process_image(image, folder, args)
            rows.append(row)
            print(json.dumps(row, ensure_ascii=False), flush=True)
        except Exception as exc:
            errors.append({"path": str(image), "error": str(exc)})
            json_write(folder / "error.json", errors[-1])
    summary = {"schema": "pelotavision.studio.instances.v1", "warning": WARN,
               "argv": sys.argv, "candidates_discovered": len(candidates), "processed": len(rows),
               "rows": rows, "duplicates_skipped": duplicates, "errors": errors,
               "training": {"status": "nao_executado", "reason": "etapa opcional separada; consultar environment.json"},
               "split_policy": "todos dev; independencia de aquisicao/amostra nao confirmada; nenhum teste utilizado",
               "code_sha256": {p.name: sha256(p) for p in Path(__file__).parent.glob("*.py")}}
    json_write(out / "summary.json", summary)
    json_write(out / "artifact_hashes.json", {str(p.relative_to(out)): sha256(p) for p in sorted(out.rglob("*")) if p.is_file()})
    print(f"PRIVATE_RUN={out}", flush=True)
    return 0 if rows and not errors else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, help="Arquivo ou árvore; diretórios selecionam somente *original*")
    parser.add_argument("--output-root", type=Path, default=PRIVATE)
    parser.add_argument("--limit", type=int, default=3)
    parser.add_argument("--max-side", type=int, default=1024)
    parser.add_argument("--min-area", type=int, default=60)
    parser.add_argument("--seed-radius", type=int, default=12, help="Pixels na grade de saída; hipótese de escala")
    parser.add_argument("--roi-ellipse", type=float, nargs=4, metavar=("CX", "CY", "RX", "RY"),
                        help="ROI manual normalizada; nunca aplicar mesma ROI a cenas diferentes sem revisar")
    return run(parser.parse_args())


if __name__ == "__main__":
    raise SystemExit(main())
