#!/usr/bin/env python3
"""Diagnóstico não destrutivo de proveniência e paridade do Random Forest embarcado."""
import hashlib
import json
from pathlib import Path
import sys

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.cracks import pvrf

def main():
    model = ROOT / "app/src/main/assets/crack_forest.pvrf"
    meta = json.loads((ROOT / "app/src/main/assets/metadata.json").read_text())
    reference = ROOT / "ml_training/training/reference_vectors.npz"
    digest = hashlib.sha256(model.read_bytes()).hexdigest()
    print("model_name:", meta.get("model"))
    print("embedded_sha256:", digest)
    print("metadata_sha256_matches:", digest == meta.get("sha256"))
    if digest != meta.get("sha256"):
        raise SystemExit("ERRO: modelo difere do SHA256 documentado")
    vectors = np.load(reference)
    features = vectors["features"]
    expected = vectors["score_final"]
    actual = pvrf.score_pvrf(pvrf.read_pvrf(model), features)
    difference = np.abs(actual - expected)
    worst = int(np.argmax(difference))
    print("vectors:", len(features), "features:", features.shape[1])
    print("max_abs_diff:", float(difference[worst]))
    print("worst_row:", worst, "reference:", float(expected[worst]),
          "embedded:", float(actual[worst]))
    print("rows_mismatched_1e-12:", int(np.count_nonzero(difference >= 1e-12)))
    if difference.max() >= 1e-12:
        print("INCOMPATIBILIDADE: referência e modelo não concordam. "
              "Verifique a proveniência/versionamento dos escores. "
              "Não regenere a referência sem um modelo original auditável.")
        return 1
    print("PARIDADE OK")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
