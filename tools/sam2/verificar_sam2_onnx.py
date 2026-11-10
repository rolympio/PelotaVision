"""Confere os ONNX exportados contra o SAM2ImagePredictor oficial (PyTorch) numa imagem sintética de pelotas.

    python tools/sam2/verificar_sam2_onnx.py --modelo <pasta com sam2_*.onnx> --checkpoint sam2.1_hiera_tiny.pt

Compara embeddings do encoder e as máscaras (logits 256x256) do decoder para prompts caixa+ponto. Falha com
código 1 se o IoU médio das máscaras binárias ficar abaixo de 0,98 ou se o erro máximo do embedding passar
de 1e-2. Também testa um lote do tamanho exato do ONNX (16), como o app usa.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from exportar_sam2_onnx import build, CONFIG  # noqa: E402

MEAN = np.array([0.485, 0.456, 0.406], np.float32)
STD = np.array([0.229, 0.224, 0.225], np.float32)


def synthetic(size=1024, seed=3):
    rng = np.random.default_rng(seed)
    img = np.full((size, size), 70, np.float32) + rng.normal(0, 6, (size, size))
    yy, xx = np.mgrid[0:size, 0:size]
    disks = []
    for _ in range(16):
        cx, cy, r = rng.uniform(80, size - 80), rng.uniform(80, size - 80), rng.uniform(25, 55)
        d = np.hypot(xx - cx, yy - cy)
        img = np.where(d < r, 170 + 40 * (1 - d / r) + rng.normal(0, 4, img.shape), img)
        disks.append((cx, cy, r))
    return np.clip(img, 0, 255).astype(np.uint8), disks


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--modelo', type=Path, required=True)
    p.add_argument('--checkpoint', type=Path)
    p.add_argument('--sem-pesos', action='store_true')
    a = p.parse_args()
    meta = json.loads((a.modelo / 'sam2.json').read_text(encoding='utf-8'))
    batch = int(meta['batch'])
    gray, disks = synthetic()
    x = ((np.repeat(gray[..., None], 3, -1) / 255.0 - MEAN) / STD).transpose(2, 0, 1)[None].astype(np.float32)
    torch.manual_seed(0)
    model = build(None if a.sem_pesos else a.checkpoint)
    from sam2.sam2_image_predictor import SAM2ImagePredictor
    pred = SAM2ImagePredictor(model)
    pred.set_image(np.repeat(gray[..., None], 3, -1))
    enc = ort.InferenceSession(str(a.modelo / 'sam2_encoder.onnx'), providers=['CPUExecutionProvider'])
    dec = ort.InferenceSession(str(a.modelo / 'sam2_decoder.onnx'), providers=['CPUExecutionProvider'])
    emb, f0, f1 = enc.run(None, {'image': x})
    ref = pred._features
    err = max(float(np.abs(emb - ref['image_embed'].numpy()).max()),
              float(np.abs(f0 - ref['high_res_feats'][0].numpy()).max()),
              float(np.abs(f1 - ref['high_res_feats'][1].numpy()).max()))
    coords = np.zeros((batch, 3, 2), np.float32)
    for k, (cx, cy, r) in enumerate(disks[:batch]):
        b = 1.15 * r
        coords[k] = [[cx - b, cy - b], [cx + b, cy + b], [cx, cy]]
    labels = np.tile(np.array([[2, 3, 1]], np.float32), (batch, 1))
    masks, iou = dec.run(None, {'image_embed': emb, 'high_res_feats_0': f0, 'high_res_feats_1': f1,
                                'point_coords': coords, 'point_labels': labels})
    ious = []
    for k in range(min(batch, len(disks))):
        box = np.array([coords[k, 0, 0], coords[k, 0, 1], coords[k, 1, 0], coords[k, 1, 1]])
        _, _, low = pred.predict(point_coords=coords[k, 2:3], point_labels=np.array([1]), box=box,
                                 multimask_output=False, return_logits=True, normalize_coords=True)
        a_ = masks[k, 0] > 0
        b_ = low[0] > 0
        u = np.logical_or(a_, b_).sum()
        ious.append(1.0 if u == 0 else np.logical_and(a_, b_).sum() / u)
    out = {'embedding_max_abs_error': err, 'mask_iou_mean': float(np.mean(ious)), 'mask_iou_min': float(np.min(ious)),
           'onnx_iou_pred_mean': float(iou.mean()), 'config': CONFIG}
    print(json.dumps(out, indent=2))
    (a.modelo / 'verificacao_onnx.json').write_text(json.dumps(out, indent=2), encoding='utf-8')
    if err > 1e-2 or out['mask_iou_mean'] < .98:
        print('FALHOU: ONNX diverge do PyTorch.', file=sys.stderr)
        sys.exit(1)
    print('OK: ONNX equivale ao SAM2ImagePredictor oficial.')


if __name__ == '__main__':
    main()
