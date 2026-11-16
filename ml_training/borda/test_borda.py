"""Testes sinteticos da rede de borda (sem dados privados). Treino curto em CPU: so prova o encanamento.

python -m pytest ml_training/borda/test_borda.py -q
"""
import json
import math
import sys
from pathlib import Path

import cv2
import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import borda as B  # noqa: E402


def cena_sintetica(pasta: Path, nome: str, semente: int, n=14, h=200, w=260, encostar=True):
    rng = np.random.default_rng(semente)
    k = 6
    yy, xx = (np.mgrid[0:h * k, 0:w * k] + 0.5) / k - 0.5
    frac = np.zeros((h * k, w * k), np.float32)
    discos = []
    tent = 0
    while len(discos) < n and tent < 2000:
        tent += 1
        r = rng.uniform(16, 24)
        x, y = rng.uniform(r, w - r), rng.uniform(r, h - r)
        if any(math.hypot(x - a, y - b) < (r + c) * (0.97 if encostar else 1.15) for a, b, c in discos):
            continue
        discos.append((x, y, r))
    for x, y, r in discos:
        frac[(xx - x) ** 2 + (yy - y) ** 2 <= r * r] = 1
    f = cv2.resize(frac, (w, h), interpolation=cv2.INTER_AREA)
    sombra = np.linspace(0.8, 1.2, w)[None]
    img = (35 + 150 * f * sombra + rng.normal(0, 4, (h, w))).clip(0, 255).astype(np.uint8)
    cv2.imwrite(str(pasta / f"{nome}.png"), img)
    anns = []
    for x, y, r in discos:
        t = np.linspace(0, 2 * math.pi, 48, endpoint=False)
        anns.append({"polygon": np.stack([x + r * np.cos(t), y + r * np.sin(t)], 1).round(3).tolist(), "partial": False})
    return {"group": nome, "fonte": "sintetico", "grupo_origem": nome, "annotations": anns}


@pytest.fixture(scope="module")
def dados(tmp_path_factory):
    pasta = tmp_path_factory.mktemp("borda")
    cenas = [cena_sintetica(pasta, f"s{i}", i) for i in range(6)]
    (pasta / "annotations.json").write_text(json.dumps(cenas))
    return pasta


def test_alvos_borda_no_contorno(dados):
    c = B.carregar_cenas([dados])[0]
    p = B.preparar_cena(c)
    o = p["objetos"][0]["poly"]
    cx, cy = o.mean(0)
    r = B.raio_equivalente(o)
    # ao longo de um raio horizontal, o maximo do alvo de borda fica no contorno (+- 0,5 px)
    xs = np.arange(int(cx), min(p["x"].shape[1] - 1, int(cx + 1.5 * r)))
    perfil = p["alvo"][1, int(round(cy)), xs]
    assert abs(xs[int(np.argmax(perfil))] - (cx + r)) <= 1.0
    assert p["alvo"][2].sum() > 0                         # ha contatos nas cenas sinteticas
    assert abs(B.fator_escala(c) * 20 - B.R_NOM) < 4


def test_gradiente_mede_bem_disco_limpo(dados):
    c = B.carregar_cenas([dados])[0]
    p = B.preparar_cena(c)
    e = B.erro_diametro(p, None)
    r = B.resumir_erros(e["gradiente"])
    # a janela de area (0,78-1,06 da priori) recusa por projeto quando a priori e menor que a pelota
    assert r["aceitos"] >= 0.4 * r["n"] and r["mae"] < 0.03 and abs(r["vies"]) < 0.01, r


def test_treino_curto_exporta_e_avalia(dados, tmp_path):
    pytest.importorskip("torch")
    pytest.importorskip("onnxruntime")
    import treinar, exportar, avaliar
    out = tmp_path / "modelo"
    assert treinar.main(["--fase", "pretreino", "--dados", str(dados), "--saida", str(out), "--iteracoes", "60",
                         "--lote", "4", "--avaliar-cada", "60", "--cpu"]) == 0
    meta = json.loads((out / "treino.json").read_text())
    assert meta["cenas_validacao"] >= 1 and not (set(meta["grupos_validacao"]) & {"s0"} and meta["cenas_treino"] == 6)
    assert exportar.main(["--pt", str(out / "melhor.pt"), "--saida", str(tmp_path / "exp")]) == 0
    j = json.loads((tmp_path / "exp" / "pellet_borda.json").read_text())
    assert j["onnx_x_pytorch_max_abs"] < 1e-3 and len(j["sha256"]) == 64
    assert avaliar.main(["--modelo", str(tmp_path / "exp" / "pellet_borda.onnx"), "--dados", str(dados),
                         "--saida", str(tmp_path / "av"), "--figuras", "1", "--sementes", "1"]) == 0
    assert (tmp_path / "av" / "avaliacao.json").exists()
