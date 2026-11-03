"""Inferência CPU por blocos sem redimensionar máscaras; contrato experimental."""
import numpy as np


def model_contract():
    return {"schema": "pelotavision.studio.multitask.v1", "experimental": True,
            "architecture": "TinyMultitask_original_16_32_16", "input": {"dtype": "float32", "layout": "NCHW",
            "channels": 1, "color": "GRAY_uint8_OpenCV_BGR2GRAY", "normalization": "float32(gray)/255.0",
            "valid_range": [0., 1.], "min_hw": 2},
            "output": {"dtype": "float32", "layout": "NCHW", "channels": ["interior", "borda", "separacao"],
                       "network_values": "logits", "probabilities": "sigmoid_independente_por_canal",
                       "spatial_shape": "igual_a_entrada"},
            "inference": {"device": "cpu", "core_hw": 128, "context_px": 32, "padding": "reflect",
                          "resize": False, "entropy": "media_entropia_Bernoulli_normalizada_nao_calibrada"},
            "training_domain": "dev_pseudo_consenso_dependente; nenhum fundo revisado disponivel",
            "instance_postprocess": "interior>0.5; remove borda>0.5 ou separacao>0.5; watershed OpenCV",
            "warning": "Sem benchmark independente; nao integrar na app; nao diagnostica integridade/trinca."}


def predict_tiled(torch, model, gray, core=128, context=32):
    if gray.ndim != 2 or gray.dtype != np.uint8 or min(gray.shape) < 2:
        raise ValueError("Inferencia requer cinza uint8 HxW com lados >=2")
    if core < 16 or context < 0:
        raise ValueError("Bloco/contexto invalido")
    h, w = gray.shape
    pad_h, pad_w = (-h) % core, (-w) % core
    padded = np.pad(gray, ((context, context+pad_h), (context, context+pad_w)), mode="reflect")
    result = np.empty((3, h, w), np.float32)
    model.eval().cpu()
    with torch.inference_mode():
        for y in range(0, h, core):
            for x in range(0, w, core):
                tile = padded[y:y+core+2*context, x:x+core+2*context]
                tensor = torch.from_numpy(np.ascontiguousarray(tile[None, None], dtype=np.float32)/255.)
                logits = model(tensor)
                if logits.shape != (1, 3, *tile.shape):
                    raise ValueError("Saida da rede viola o contrato espacial/3 cabecas")
                probs = torch.sigmoid(logits)[0].numpy()
                dh, dw = min(core, h-y), min(core, w-x)
                result[:, y:y+dh, x:x+dw] = probs[:, context:context+dh, context:context+dw]
    if not np.isfinite(result).all():
        raise ValueError("Probabilidades nao finitas")
    return result
