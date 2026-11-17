#!/usr/bin/env python3
"""Baixa datasets publicos do Roboflow Universe (COCO Segmentation) para a area privada.

- A chave vem SO da variavel ROBOFLOW_API_KEY (nunca de argumento, arquivo ou log).
- Destino: <local>/datasets/externos/roboflow/<id>/v<N>/  (fora do Git e do OneDrive de codigo).
- Grava proveniencia.json: URL, versao, data, licenca declarada no registro e a devolvida pela API,
  contagens por divisao e SHA-256 de cada arquivo baixado. Nada e apagado ou sobrescrito.

Uso:
  set ROBOFLOW_API_KEY=...            (Windows)   |  export ROBOFLOW_API_KEY=...  (Linux)
  python tools/externos/roboflow_baixar.py --listar
  python tools/externos/roboflow_baixar.py --ids rf_iron_ore_pellets_healer rf_iron_pellets_hamitouche
  python tools/externos/roboflow_baixar.py --prioridade 2          (todas com prioridade <= 2)
  python tools/externos/roboflow_baixar.py --url https://universe.roboflow.com/ws/proj/dataset/3 --id meu_id
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
REGISTRO = Path(__file__).with_name("fontes_roboflow.json")
API = "https://api.roboflow.com"
FORMATO = "coco-segmentation"


def local_root(arg: str | None) -> Path:
    return Path(arg).resolve() if arg else (REPO.parent / "PelotaVision-local").resolve()


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for bloco in iter(lambda: f.read(1 << 20), b""):
            h.update(bloco)
    return h.hexdigest()


def parse_url(url: str):
    m = re.search(r"universe\.roboflow\.com/([^/]+)/([^/?#]+)(?:/(?:dataset|model)/(\d+))?", url)
    if not m:
        raise ValueError(f"URL do Universe nao reconhecida: {url}")
    return m.group(1), m.group(2), (int(m.group(3)) if m.group(3) else None)


def chave() -> str:
    k = os.environ.get("ROBOFLOW_API_KEY", "").strip()
    if not k:
        sys.exit("Defina ROBOFLOW_API_KEY (Roboflow > Settings > API Keys). A chave nao e gravada em lugar nenhum.")
    return k


def api_json(caminho: str, key: str) -> dict:
    req = urllib.request.Request(f"{API}/{caminho}?api_key={key}", headers={"User-Agent": "PelotaVision/externos"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode("utf-8"))


def sem_chave(obj, key):
    """Remove qualquer ocorrencia da chave antes de gravar."""
    txt = json.dumps(obj, ensure_ascii=False)
    return json.loads(txt.replace(key, "***")) if key else obj


def contar_coco(pasta: Path) -> dict:
    out = {}
    for js in sorted(pasta.rglob("_annotations.coco.json")):
        d = json.loads(js.read_text(encoding="utf-8"))
        cats = {c["id"]: c["name"] for c in d.get("categories", [])}
        por_cat = {}
        for a in d.get("annotations", []):
            nome = cats.get(a.get("category_id"), "?")
            por_cat[nome] = por_cat.get(nome, 0) + 1
        out[js.parent.name] = {"imagens": len(d.get("images", [])), "anotacoes": len(d.get("annotations", [])),
                               "por_categoria": por_cat,
                               "poligonos": sum(1 for a in d.get("annotations", []) if a.get("segmentation"))}
    return out


def baixar(fonte: dict, raiz: Path, key: str, forcar_versao: int | None = None) -> Path:
    ws, proj = fonte["workspace"], fonte["project"]
    info = api_json(f"{ws}/{proj}", key)
    versoes = sorted(int(str(v.get("id", "")).rsplit("/", 1)[-1]) for v in info.get("versions", [])
                     if str(v.get("id", "")).rsplit("/", 1)[-1].isdigit())
    versao = forcar_versao or fonte.get("version") or (versoes[-1] if versoes else None)
    if versao is None:
        raise RuntimeError(f"{fonte['id']}: projeto sem versao gerada no Universe; nao ha export para baixar.")
    destino = raiz / "datasets" / "externos" / "roboflow" / fonte["id"] / f"v{versao}"
    if (destino / "proveniencia.json").exists():
        print(f"{fonte['id']} v{versao}: ja baixado em {destino} (nada sobrescrito).")
        return destino
    destino.mkdir(parents=True, exist_ok=True)
    try:
        import roboflow  # noqa: F401  (pip install roboflow)
    except ImportError:
        sys.exit("Instale o cliente: python -m pip install roboflow")
    from roboflow import Roboflow
    rf = Roboflow(api_key=key)
    ds = rf.workspace(ws).project(proj).version(versao).download(FORMATO, location=str(destino / "dados"), overwrite=False)
    pasta = Path(ds.location)
    arquivos = {p.relative_to(destino).as_posix(): sha256(p) for p in sorted(pasta.rglob("*")) if p.is_file()}
    proj_info = info.get("project", {})
    prov = {
        "id": fonte["id"], "url": fonte.get("url"), "workspace": ws, "project": proj, "versao": versao,
        "versoes_disponiveis": versoes, "formato": FORMATO,
        "baixado_em": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "licenca_registro": fonte.get("licenca_pagina"),
        "licenca_api": proj_info.get("license"),
        "tipo_api": proj_info.get("type"), "classes_api": proj_info.get("classes"),
        "contagens": contar_coco(pasta), "sha256": arquivos,
        "aviso": "Rotulos de terceiros: nao sao verdade-terreno de tamanho. Uso: pre-treino de contorno, apos auditar_borda_anotada.py.",
    }
    (destino / "proveniencia.json").write_text(json.dumps(sem_chave(prov, key), ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"{fonte['id']} v{versao}: {sum(c['imagens'] for c in prov['contagens'].values())} imagens -> {destino}")
    if prov["licenca_api"] and fonte.get("licenca_pagina") and str(prov["licenca_api"]).lower() not in str(fonte["licenca_pagina"]).lower():
        print(f"  ATENCAO: licenca da API ({prov['licenca_api']}) difere do registro ({fonte['licenca_pagina']}). Conferir antes de usar.")
    return destino


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--listar", action="store_true")
    ap.add_argument("--ids", nargs="*", default=[])
    ap.add_argument("--prioridade", type=int, default=None, help="baixa todas as candidatas com prioridade <= N")
    ap.add_argument("--url", default=None)
    ap.add_argument("--id", default=None, help="id para --url")
    ap.add_argument("--versao", type=int, default=None)
    ap.add_argument("--local", default=None, help="raiz privada (padrao: ../PelotaVision-local)")
    a = ap.parse_args(argv)
    reg = json.loads(REGISTRO.read_text(encoding="utf-8"))
    fontes = {f["id"]: f for f in reg["fontes"]}
    if a.listar:
        for f in reg["fontes"]:
            print(f"[{f['prioridade']}] {f['id']:32s} {f['status']:10s} {f['licenca_pagina']:22s} {f['url']}")
        return 0
    escolha = []
    if a.url:
        ws, proj, ver = parse_url(a.url)
        escolha.append({"id": a.id or f"rf_{ws}_{proj}".replace("-", "_"), "url": a.url, "workspace": ws, "project": proj,
                        "version": ver, "licenca_pagina": "nao registrada", "status": "candidato"})
    escolha += [fontes[i] for i in a.ids]
    if a.prioridade is not None:
        escolha += [f for f in reg["fontes"] if f["prioridade"] <= a.prioridade and f["id"] not in a.ids]
    escolha = [f for f in escolha if f.get("status") != "bloqueado"]
    if not escolha:
        ap.error("nada escolhido: use --ids, --prioridade ou --url")
    key, raiz, falhas = chave(), local_root(a.local), 0
    for f in escolha:
        try:
            baixar(f, raiz, key, a.versao)
        except Exception as e:  # segue com as demais; o erro nao contem a chave
            falhas += 1
            print(f"{f['id']}: FALHOU - {str(e).replace(key, '***')}")
    return 1 if falhas else 0


if __name__ == "__main__":
    sys.exit(main())
