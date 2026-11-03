"""Verifica o venv instalado sem reinstalar, preservando eventuais logs de timeout."""
import base64
import hashlib
import importlib.metadata
import json
import subprocess
import sys
import time
from pathlib import Path

from .pipeline import PRIVATE, environment, json_write, new_run, sha256


def main():
    import torch
    import onnx
    import cv2
    import numpy as np
    expected = PRIVATE.parents[1] / "envs" / "studio-ml"
    if Path(sys.prefix).resolve() != expected.resolve() or sys.prefix == sys.base_prefix:
        raise ValueError("Executar no venv privado studio-ml, nao no conda/base")
    out = new_run("environment-verification")
    start = time.monotonic()
    commands = []
    for suffix in ("check", "freeze"):
        command = [sys.executable, "-m", "pip", "--isolated", "--disable-pip-version-check", suffix]
        commands.append(command)
        result = subprocess.run(command, capture_output=True, text=True, timeout=40)
        with (out / f"pip-{suffix}.log").open("x", encoding="utf-8") as f:
            f.write(json.dumps(command)+"\n"+result.stdout+result.stderr)
        if result.returncode:
            raise RuntimeError(f"pip {suffix} falhou")
    checked = {}
    for name in ("torch", "numpy", "opencv-python-headless", "onnx"):
        dist = importlib.metadata.distribution(name)
        count = 0
        selected = []
        for entry in dist.files or []:
            if entry.hash is None or entry.hash.mode != "sha256":
                continue
            # Auditoria completa de milhares de .py excedeu 120s neste disco.
            # Confere os binários e o __init__ principal, sem declarar cobertura completa.
            text = str(entry).replace("\\", "/")
            top_init = text == name.replace("-", "_")+"/__init__.py"
            if not (text.lower().endswith((".dll", ".pyd")) or top_init):
                continue
            if time.monotonic()-start > 120:
                raise TimeoutError("Auditoria de arquivos excedeu 120s; nao houve reinstalacao")
            path = Path(dist.locate_file(entry))
            actual = base64.urlsafe_b64encode(hashlib.sha256(path.read_bytes()).digest()).decode().rstrip("=")
            if actual != entry.hash.value:
                raise ValueError(f"Arquivo instalado difere do RECORD: {name}/{entry}")
            count += 1
            selected.append(text)
        checked[name] = {"version": dist.version, "files_hash_verified": count,
                         "selected_files": selected, "scope": "binarios_DLL_PYD_e_init_principal_nao_todos_arquivos",
                         "license": dist.metadata.get("License-Expression") or dist.metadata.get("License"),
                         "license_classifiers": [s for s in dist.metadata.get_all("Classifier", []) if "License ::" in s]}
    if torch.version.cuda is not None or torch.cuda.is_available():
        raise ValueError("Esta rodada exige somente PyTorch CPU")
    json_write(out / "verification.json", {"status": "venv_cpu_funcional_binarios_record_conferidos", "commands": commands,
                                            "packages": checked, "environment": environment(),
                                            "onnx": onnx.__version__, "opencv": cv2.__version__, "numpy": np.__version__,
                                            "cuda_runtime": torch.version.cuda,
                                            "torch_cpu_smoke_sum": float(torch.ones(2, 3).sum()),
                                            "elapsed_seconds": time.monotonic()-start,
                                            "note": "Timeout anterior de instalacao e da auditoria completa de arquivos preservados; verificacao limitada a binarios, nao reinstala pacotes."})
    json_write(out / "artifact_hashes.json", {p.name: sha256(p) for p in out.iterdir() if p.is_file()})
    print(out)


if __name__ == "__main__":
    main()
