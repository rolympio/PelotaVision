"""Instalação explicitamente autorizada: venv privado, wheels oficiais, CPU e TLS padrão.

Não altera ambientes existentes. Interrompe downloads/subprocessos em até 15 minutos.
"""
import hashlib
import json
import os
import platform
import subprocess
import sys
import time
import urllib.request
import uuid
import zipfile
from datetime import datetime, timezone
from email.parser import BytesParser
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
LOCAL = REPO.parent / "PelotaVision-local"
ENV = LOCAL / "envs" / "studio-ml"
ROOT = LOCAL / "experiments" / "studio-instances-20261103"


def main():
    if sys.version_info[:2] != (3, 12) or platform.machine().lower() not in ("amd64", "x86_64"):
        raise RuntimeError("Executar com Python 3.12 x64 existente (py -3.12)")
    out = ROOT / f"environment-install-{datetime.now(timezone.utc):%Y%m%dT%H%M%SZ}-{uuid.uuid4().hex[:8]}"
    out.mkdir(parents=True, exist_ok=False)
    started = time.monotonic()
    record = {"status": "iniciado", "commands": [], "environment": str(ENV),
              "python_base": sys.executable, "tls_verification": True, "cuda_installed": False,
              "torch_license_source": "https://raw.githubusercontent.com/pytorch/pytorch/v2.10.0/LICENSE",
              "torch_cpu_index": "https://download.pytorch.org/whl/cpu",
              "torch_expected_sha256": "21cb5436978ef47c823b7a813ff0f8c2892e266cfe0f1d944879b5fba81bf4e1"}

    def command(args, name, max_seconds=500):
        record["commands"].append(args)
        remaining = min(max_seconds, 900-(time.monotonic()-started))
        if remaining <= 0:
            raise TimeoutError("Orcamento de 15 minutos esgotado")
        env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1", PIP_CONFIG_FILE=os.devnull)
        with (out / f"{name}.log").open("x", encoding="utf-8") as log:
            log.write(json.dumps(args)+"\n")
            log.flush()
            subprocess.run(args, stdout=log, stderr=subprocess.STDOUT, env=env, timeout=remaining, check=True)

    try:
        if ENV.exists():
            raise FileExistsError(f"Ambiente ja existe; nao sera modificado: {ENV}")
        license_text = urllib.request.urlopen(record["torch_license_source"], timeout=25).read()
        if b"Redistribution and use" not in license_text or b"3." not in license_text:
            raise RuntimeError("Licenca PyTorch nao reconhecida como BSD-3")
        (out / "pytorch-LICENSE.txt").write_bytes(license_text)
        command([sys.executable, "-m", "venv", str(ENV)], "create-venv", 90)
        python = str(ENV / "Scripts" / "python.exe")
        wheels = out / "wheels"
        wheels.mkdir()
        pip = [python, "-m", "pip", "--isolated", "--disable-pip-version-check"]
        common = ["--only-binary=:all:", "--timeout", "20", "--retries", "1", "--dest", str(wheels)]
        command(pip + ["download", "torch==2.10.0+cpu", "--index-url", record["torch_cpu_index"]] + common,
                "download-torch", 480)
        command(pip + ["download", "numpy==2.2.6", "opencv-python-headless==4.12.0.88",
                       "onnx==1.18.0", "--index-url", "https://pypi.org/simple"] + common,
                "download-imaging-onnx", 180)
        licenses = []
        for wheel in sorted(wheels.glob("*.whl")):
            digest = hashlib.sha256(wheel.read_bytes()).hexdigest()
            with zipfile.ZipFile(wheel) as z:
                metadata = BytesParser().parsebytes(z.read(next(n for n in z.namelist() if n.endswith(".dist-info/METADATA"))))
                info = {"name": metadata["Name"], "version": metadata["Version"],
                        "requires_python": metadata["Requires-Python"],
                        "license": metadata["License-Expression"] or metadata["License"],
                        "classifiers": [x for x in metadata.get_all("Classifier", []) if "License ::" in x],
                        "wheel": wheel.name, "sha256": digest}
                if not info["license"] and not info["classifiers"]:
                    raise RuntimeError(f"Licenca ausente: {wheel.name}")
                if any("AGPL" in str(v).upper() for v in (info["license"], info["classifiers"])):
                    raise RuntimeError(f"Licenca exige revisao adicional: {wheel.name}")
                if info["name"].lower() == "torch" and digest != record["torch_expected_sha256"]:
                    raise RuntimeError("Hash da wheel torch difere do indice oficial consultado")
                licenses.append(info)
        (out / "wheel-licenses-hashes.json").write_text(json.dumps(licenses, indent=2), encoding="utf-8")
        command(pip + ["install", "--no-index", "--find-links", str(wheels), "--report", str(out / "pip-install-report.json"),
                       "torch==2.10.0+cpu", "numpy==2.2.6", "opencv-python-headless==4.12.0.88", "onnx==1.18.0"],
                "install-offline", 180)
        command(pip + ["check"], "pip-check", 40)
        command([python, "-c", "import torch,cv2,numpy,onnx;print(torch.__version__,torch.version.cuda,torch.cuda.is_available());print(cv2.__version__,numpy.__version__,onnx.__version__);print(torch.ones(2,3).sum().item())"], "smoke-imports", 60)
        command(pip + ["freeze"], "pip-freeze", 30)
        record["status"] = "instalado_e_importado_cpu"
    except Exception as exc:
        record["status"], record["error"] = "bloqueado", repr(exc)
    record["elapsed_seconds"] = time.monotonic()-started
    (out / "installation.json").write_text(json.dumps(record, indent=2), encoding="utf-8")
    print(json.dumps({"status": record["status"], "log_directory": str(out), "error": record.get("error")}))
    return 0 if record["status"] == "instalado_e_importado_cpu" else 2


if __name__ == "__main__":
    raise SystemExit(main())
