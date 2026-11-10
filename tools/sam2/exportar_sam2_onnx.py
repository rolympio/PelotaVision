"""Exporta o SAM 2.1 Hiera Tiny oficial para dois ONNX usados pelo PelotaVision (Windows e Android).

    python tools/sam2/exportar_sam2_onnx.py --checkpoint sam2.1_hiera_tiny.pt --saida <pasta>

Saídas (todas fora do Git, copiadas para o pacote pelo script de compilação):
  sam2_encoder.onnx  imagem [1,3,1024,1024] (RGB normalizado ImageNet) ->
                     image_embed [1,256,64,64], high_res_feats_0 [1,32,256,256], high_res_feats_1 [1,64,128,128]
  sam2_decoder.onnx  embeddings + point_coords [B,3,2] + point_labels [B,3] (caixa 2/3 + ponto 1, coordenadas
                     no quadro 1024) -> low_res_masks [B,1,256,256] (logits), iou_predictions [B,1]
  sam2.json          metadados: SHA-256 dos dois ONNX e do checkpoint, B, normalização, licença.

O checkpoint é conferido pelo SHA-256 oficial antes de carregar. --sem-pesos exporta a mesma arquitetura
com pesos aleatórios, só para testar o encanamento (o app recusa esse modelo: "demonstration": true e
"random_weights": true).
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import torch

CHECKPOINT_SHA256 = '7402e0d864fa82708a20fbd15bc84245c2f26dff0eb43a4b5b93452deb34be69'
CHECKPOINT_URL = 'https://dl.fbaipublicfiles.com/segment_anything_2/092824/sam2.1_hiera_tiny.pt'
CONFIG = 'configs/sam2.1/sam2.1_hiera_t.yaml'
BATCH = 16
SIZE = 1024


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for block in iter(lambda: f.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()


class Encoder(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model
        self.sizes = [(256, 256), (128, 128), (64, 64)]

    def forward(self, image):
        backbone_out = self.model.forward_image(image)
        _, vision_feats, _, _ = self.model._prepare_backbone_features(backbone_out)
        if self.model.directly_add_no_mem_embed:
            vision_feats[-1] = vision_feats[-1] + self.model.no_mem_embed
        feats = [f.permute(1, 2, 0).reshape(1, -1, *s) for f, s in zip(vision_feats[::-1], self.sizes[::-1])][::-1]
        return feats[2], feats[0], feats[1]


class Decoder(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, image_embed, high_res_feats_0, high_res_feats_1, point_coords, point_labels):
        sparse, dense = self.model.sam_prompt_encoder(points=(point_coords, point_labels), boxes=None, masks=None)
        masks, iou, _, _ = self.model.sam_mask_decoder(
            image_embeddings=image_embed,
            image_pe=self.model.sam_prompt_encoder.get_dense_pe(),
            sparse_prompt_embeddings=sparse,
            dense_prompt_embeddings=dense,
            multimask_output=False,
            repeat_image=True,
            high_res_features=[high_res_feats_0, high_res_feats_1],
        )
        return torch.clamp(masks, -32.0, 32.0), iou


def build(checkpoint: Path | None):
    from sam2.build_sam import build_sam2
    model = build_sam2(CONFIG, None, device='cpu', apply_postprocessing=False)
    if checkpoint is not None:
        state = torch.load(checkpoint, map_location='cpu', weights_only=True)['model']
        missing, unexpected = model.load_state_dict(state)
        if missing or unexpected:
            raise RuntimeError(f'Checkpoint incompatível: faltam {missing}, sobram {unexpected}')
    return model.eval()


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--checkpoint', type=Path)
    p.add_argument('--saida', type=Path, required=True)
    p.add_argument('--sem-pesos', action='store_true', help='pesos aleatórios (só teste de encanamento)')
    a = p.parse_args()
    if a.checkpoint is None and not a.sem_pesos:
        p.error(f'informe --checkpoint (oficial: {CHECKPOINT_URL}) ou --sem-pesos')
    ckpt_hash = None
    if a.checkpoint is not None:
        ckpt_hash = sha256(a.checkpoint)
        if ckpt_hash != CHECKPOINT_SHA256:
            raise SystemExit(f'SHA-256 do checkpoint não confere: {ckpt_hash}')
    torch.manual_seed(0)
    model = build(None if a.sem_pesos else a.checkpoint)
    a.saida.mkdir(parents=True, exist_ok=True)
    enc_path, dec_path = a.saida / 'sam2_encoder.onnx', a.saida / 'sam2_decoder.onnx'
    with torch.no_grad():
        image = torch.randn(1, 3, SIZE, SIZE)
        encoder = Encoder(model)
        emb, f0, f1 = encoder(image)
        torch.onnx.export(encoder, (image,), str(enc_path), input_names=['image'],
                          output_names=['image_embed', 'high_res_feats_0', 'high_res_feats_1'],
                          opset_version=17, dynamo=False)
        coords = torch.rand(BATCH, 3, 2) * SIZE
        labels = torch.tensor([[2, 3, 1]], dtype=torch.float32).repeat(BATCH, 1)
        torch.onnx.export(Decoder(model), (emb, f0, f1, coords, labels), str(dec_path),
                          input_names=['image_embed', 'high_res_feats_0', 'high_res_feats_1', 'point_coords', 'point_labels'],
                          output_names=['low_res_masks', 'iou_predictions'], opset_version=17, dynamo=False)
    meta = {
        'id': 'sam2.1_hiera_tiny' + ('_pesos_aleatorios' if a.sem_pesos else ''),
        'encoder_sha256': sha256(enc_path), 'decoder_sha256': sha256(dec_path),
        'checkpoint_sha256': ckpt_hash, 'checkpoint_url': CHECKPOINT_URL, 'config': CONFIG,
        'input_size': SIZE, 'batch': BATCH, 'mask_size': 256,
        'mean': [0.485, 0.456, 0.406], 'std': [0.229, 0.224, 0.225],
        'license': 'Apache-2.0 (código e pesos SAM 2, Meta)',
        'random_weights': bool(a.sem_pesos), 'demonstration': bool(a.sem_pesos),
        'status_note': 'SAM 2.1 Tiny sem ajuste fino em pelotas; máscaras guiadas pelas detecções. Sem validação metrológica independente.',
    }
    (a.saida / 'sam2.json').write_text(json.dumps(meta, indent=2, ensure_ascii=False), encoding='utf-8')
    print(json.dumps(meta, indent=2, ensure_ascii=False))


if __name__ == '__main__':
    main()
