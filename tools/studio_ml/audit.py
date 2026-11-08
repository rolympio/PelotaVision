"""Confere hashes dos artefatos/originais e registra os testes em pasta privada nova."""
import argparse
import json
import unittest
from pathlib import Path

import cv2
import numpy as np

from .pipeline import PRIVATE, environment, json_write, new_run, read_image, sha256


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--run", type=Path, action="append", required=True)
    p.add_argument("--run-tests", action="store_true")
    args = p.parse_args()
    out = new_run("audit")
    reports = []
    for run in args.run:
        run = run.resolve()
        if not run.is_relative_to(PRIVATE.resolve()):
            raise ValueError("Execucao fora da raiz privada")
        expected = json.loads((run / "artifact_hashes.json").read_text(encoding="utf-8"))
        checked = 0
        for relative, digest in expected.items():
            path = (run / relative).resolve()
            if not path.is_relative_to(run) or sha256(path) != digest:
                raise ValueError(f"Hash/caminho invalido: {relative}")
            checked += 1
        if (run / "training.json").exists():
            training = json.loads((run / "training.json").read_text(encoding="utf-8"))
            if training["test_used"] or training["validation"] is not None or training["demo_used_for_selection"]:
                raise ValueError("Treino experimental declarou uso indevido de teste/validacao/demo")
            originals = 0
            for input_file in ("inputs.json", "demo_inputs.json"):
                for item in json.loads((run / input_file).read_text(encoding="utf-8")):
                    path = Path(item["path"]).resolve()
                    if not path.is_relative_to(PRIVATE.resolve()) or sha256(path) != item["sha256"]:
                        raise ValueError("Entrada de treino/demo alterada")
                    record = json.loads((path.parent / "record.json").read_text(encoding="utf-8"))
                    if sha256(record["source"]) != item["source_sha256"]:
                        raise ValueError("Original de treino/demo alterado")
                    originals += 1
            for path in run.glob("*_probabilities.npz"):
                with np.load(path, allow_pickle=False) as z:
                    probs = z["probabilities"]
                    if probs.ndim != 3 or probs.shape[0] != 3 or not np.isfinite(probs).all() or probs.min() < 0 or probs.max() > 1:
                        raise ValueError("Probabilidades invalidas")
            reports.append({"run": str(run), "kind": "multitask_dev_only", "artifacts_hash_verified": checked,
                            "originals_hash_verified": originals, "status": "ok"})
            continue
        summary = json.loads((run / "summary.json").read_text(encoding="utf-8"))
        originals = 0
        for row in summary["rows"]:
            folder = (run / row["directory"]).resolve()
            if not folder.is_relative_to(run):
                raise ValueError("Pasta de imagem fora da execucao")
            record = json.loads((folder / "record.json").read_text(encoding="utf-8"))
            if record["source_sha256"] != sha256(record["source"]) or record["source_sha256_after"] != record["source_sha256"]:
                raise ValueError("Original alterado")
            if record["split"] != "dev" or record["confirmed_independent_group"] is not None:
                raise ValueError("Contrato de grupos/splits alterado")
            instances = read_image(folder / "instances_uint16.png", cv2.IMREAD_UNCHANGED)
            semantic = read_image(folder / "semantic_uint8.png", cv2.IMREAD_UNCHANGED)
            if instances.dtype != np.uint16 or semantic.dtype != np.uint8 or not set(np.unique(semantic)).issubset({0, 1, 2, 3}):
                raise ValueError("Tipo/classes invalidos")
            if any(d["structural_integrity"] != "nao_avaliada" for d in record["instances"]):
                raise ValueError("Rotulo estrutural indevido")
            originals += 1
        reports.append({"run": str(run), "artifacts_hash_verified": checked,
                        "originals_hash_verified": originals, "status": "ok"})
    tests = None
    if args.run_tests:
        suite = unittest.defaultTestLoader.loadTestsFromName("tools.studio_ml.test_pipeline")
        with (out / "synthetic_tests.log").open("x", encoding="utf-8") as log:
            result = unittest.TextTestRunner(stream=log, verbosity=2).run(suite)
        tests = {"run": result.testsRun, "failures": len(result.failures), "errors": len(result.errors),
                 "successful": result.wasSuccessful()}
    json_write(out / "audit.json", {"reports": reports, "synthetic_tests": tests, "environment": environment()})
    print(json.dumps({"audit": str(out), "reports": reports, "tests": tests}, ensure_ascii=False))
    return 0 if tests is None or tests["successful"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
