"""Exportação experimental isolada; não grava assets e não equivale a benchmark."""
import importlib.util
import warnings

import cv2
import numpy as np

from .pipeline import json_write, sha256


def export_model(torch, model, out):
    report = {"status": "nao_exportado", "opset": 17,
              "input_shape": [1, 1, 128, 128], "output_shape": [1, 3, 128, 128],
              "output_values": "logits", "integration_authorized": False}
    if importlib.util.find_spec("onnx") is None:
        report["reason"] = "Dependencia onnx ausente; nao instala automaticamente"
    else:
        try:
            import onnx
            rng = np.random.default_rng(20261103)
            sample = rng.random((1, 1, 128, 128), dtype=np.float32)
            path = out / "tiny_multitask_cpu_128.onnx"
            model.eval().cpu()
            with warnings.catch_warnings(record=True) as caught:
                warnings.simplefilter("always")
                torch.onnx.export(model, torch.from_numpy(sample), str(path), dynamo=False,
                                  opset_version=17, input_names=["gray01"], output_names=["logits"],
                                  do_constant_folding=True)
            report["warnings"] = [str(w.message) for w in caught]
            onnx.checker.check_model(onnx.load(str(path)))
            report.update(status="exportado_checker_ok", onnx_version=onnx.__version__, sha256=sha256(path))
            # Smoke de execução OpenCV, não benchmark nem paridade completa do executável.
            net = cv2.dnn.readNetFromONNX(str(path))
            net.setPreferableBackend(cv2.dnn.DNN_BACKEND_OPENCV)
            net.setPreferableTarget(cv2.dnn.DNN_TARGET_CPU)
            net.setInput(sample)
            actual = net.forward()
            with torch.inference_mode():
                expected = model(torch.from_numpy(sample)).numpy()
            report["opencv_smoke"] = {"version": cv2.__version__, "max_absolute_logit_error": float(np.max(np.abs(actual-expected))),
                                      "mean_absolute_logit_error": float(np.mean(np.abs(actual-expected))),
                                      "finite": bool(np.isfinite(actual).all()), "shape": list(actual.shape),
                                      "tolerance": 1e-4, "passed": bool(np.allclose(actual, expected, atol=1e-4, rtol=1e-4))}
        except Exception as exc:
            report["reason"] = repr(exc)
    json_write(out / "onnx_export.json", report)
    return report
