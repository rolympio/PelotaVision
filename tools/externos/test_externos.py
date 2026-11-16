"""Testes sinteticos (sem rede, sem imagens privadas): conversao COCO e auditoria de borda.

python -m pytest tools/externos/test_externos.py -q
"""
import json
import math
import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import auditar_borda_anotada as aud  # noqa: E402
import coco_para_pelotavision as conv  # noqa: E402


def _cena(tmp: Path, deslocamento_px: float, vertices: int = 40, nome="train"):
    rng = np.random.default_rng(0)
    h, w = 240, 320
    discos = [(70, 70, 22), (140, 72, 22), (230, 120, 28), (90, 170, 18)]
    # disco de borda exata: superamostragem 8x e media por area (o centro do pixel (i,j) e (i,j))
    k = 8
    yy, xx = (np.mgrid[0:h * k, 0:w * k] + 0.5) / k - 0.5
    alto = np.zeros((h * k, w * k), np.float32)
    for x, y, r in discos:
        alto[(xx - x) ** 2 + (yy - y) ** 2 <= r * r] = 1
    frac = cv2.resize(alto, (w, h), interpolation=cv2.INTER_AREA)
    img = np.repeat((40 + 160 * frac)[..., None], 3, 2)
    img = np.clip(img + rng.normal(0, 3, img.shape), 0, 255).astype(np.uint8)
    pasta = tmp / nome
    pasta.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(pasta / "a_jpg.rf.0123abcd.jpg"), img, [cv2.IMWRITE_JPEG_QUALITY, 98])
    anns = []
    for i, (x, y, r) in enumerate(discos):
        t = np.linspace(0, 2 * math.pi, vertices, endpoint=False)
        rr = r - deslocamento_px
        poly = (np.stack([x + rr * np.cos(t), y + rr * np.sin(t)], 1) + 0.5).ravel().tolist()  # COCO: canto do pixel
        anns.append({"id": i + 1, "image_id": 0, "category_id": 1, "segmentation": [poly], "bbox": [x - r, y - r, 2 * r, 2 * r]})
    anns.append({"id": 99, "image_id": 0, "category_id": 2, "segmentation": [[5, 5, 30, 5, 30, 30, 5, 30]], "bbox": [5, 5, 25, 25]})
    coco = {"images": [{"id": 0, "file_name": "a_jpg.rf.0123abcd.jpg", "width": w, "height": h}],
            "categories": [{"id": 0, "name": "pellets-raiz"}, {"id": 1, "name": "pellet"}, {"id": 2, "name": "fiber"}],
            "annotations": anns}
    (pasta / "_annotations.coco.json").write_text(json.dumps(coco))
    return discos


def test_conversao_ignora_outras_classes_e_agrupa_aumentos(tmp_path):
    _cena(tmp_path / "exp", 0.0)
    out = tmp_path / "conv"
    r = conv.converter(tmp_path / "exp", out, "teste", ["pellet"], 12, "CC BY 4.0")
    cenas = json.loads((out / "annotations.json").read_text())
    assert r["objetos"] == 4 and r["ignorados_outras_classes"] == 1
    assert cenas[0]["grupo_origem"] == "teste:a"
    peso = cv2.imread(str(out / cenas[0]["weight_mask"]), cv2.IMREAD_GRAYSCALE)
    assert peso[15, 15] == 0 and peso[200, 300] == 255      # fibra ignorada, fundo mantido
    raios = sorted(o["raio_px"] for o in cenas[0]["annotations"])
    assert abs(raios[0] - 18) < 0.3


def test_poligono_grosseiro_fica_sem_borda_confiavel(tmp_path):
    _cena(tmp_path / "exp", 0.0, vertices=6)
    r = conv.converter(tmp_path / "exp", tmp_path / "conv", "teste", ["pellet"], 12, None)
    assert r["objetos_contorno_grosseiro"] == 4


def test_auditoria_detecta_anotacao_encolhida(tmp_path):
    _cena(tmp_path / "exp", 1.5)
    conv.converter(tmp_path / "exp", tmp_path / "conv", "teste", ["pellet"], 12, None)
    r = aud.auditar(tmp_path / "conv", tmp_path / "aud", pranchas=1, aplicar=True, corrigir=True)
    assert r["objetos_medidos"] == 4
    assert 1.0 < r["offset_px_mediana"] < 2.0, r                # anotacao 1,5 px para dentro
    assert r["vies_diametro_rel_mediana"] > 0.04
    corr = json.loads((tmp_path / "conv" / "annotations_auditado.json").read_text())
    p = np.asarray(corr[0]["annotations"][0]["polygon"])
    raio = math.sqrt(cv2.contourArea(p.astype(np.float32)) / math.pi)
    assert abs(raio - 22) < 0.8                                  # corrigido volta para a borda real


def test_auditoria_anotacao_correta_tem_vies_pequeno(tmp_path):
    _cena(tmp_path / "exp", 0.0)
    conv.converter(tmp_path / "exp", tmp_path / "conv", "teste", ["pellet"], 12, None)
    r = aud.auditar(tmp_path / "conv", tmp_path / "aud")
    assert abs(r["offset_px_mediana"]) < 0.3 and r["frac_dentro_tolerancia"] == 1.0


def test_url_do_universe():
    import roboflow_baixar as rb
    assert rb.parse_url("https://universe.roboflow.com/hamitouche/iron-pellets/dataset/1") == ("hamitouche", "iron-pellets", 1)
    assert rb.parse_url("https://universe.roboflow.com/healer-vt39f/iron-ore-pellets") == ("healer-vt39f", "iron-ore-pellets", None)
    reg = json.loads(rb.REGISTRO.read_text(encoding="utf-8"))
    assert len({f["id"] for f in reg["fontes"]}) == len(reg["fontes"])


def test_mascara_do_sam2_vira_poligono():
    import caixas_para_contorno_sam2 as cx
    yy, xx = np.mgrid[0:80, 0:80]
    pts = np.stack([xx[(xx - 40) ** 2 + (yy - 40) ** 2 <= 15 ** 2], yy[(xx - 40) ** 2 + (yy - 40) ** 2 <= 15 ** 2]], 1).astype(float)
    p = cx.mascara_para_poligono(pts, (80, 80))
    r = math.sqrt(cv2.contourArea(p.astype(np.float32)) / math.pi)
    n = ((xx - 40) ** 2 + (yy - 40) ** 2 <= 15 ** 2).sum()
    assert p is not None and abs(r - math.sqrt(n / math.pi)) < 0.15
