"""Treino opcional interior/borda/separação, só ajuste exploratório em pseudo-rótulos dev.

Não seleciona modelo em teste, não exporta assets e não instala dependências.
"""
import argparse
import importlib.util
import json
import random
import time
from pathlib import Path

import cv2
import numpy as np

from .pipeline import (PRIVATE, boundary, compare_instances, environment, json_write, new_run,
                       read_image, sha256, watershed_instances, write_image)


def build_model(torch):
    """U-Net pequena original (16/32 canais), sem pesos de terceiros."""
    nn = torch.nn

    class TinyMultitask(nn.Module):
        def __init__(self):
            super().__init__()
            self.encoder = nn.Sequential(nn.Conv2d(1, 16, 3, padding=1), nn.ReLU(),
                                         nn.Conv2d(16, 16, 3, padding=1), nn.ReLU())
            self.bottleneck = nn.Sequential(nn.Conv2d(16, 32, 3, padding=1), nn.ReLU(),
                                            nn.Conv2d(32, 32, 3, padding=1), nn.ReLU())
            self.decoder = nn.Sequential(nn.Conv2d(48, 16, 3, padding=1), nn.ReLU(),
                                         nn.Conv2d(16, 16, 3, padding=1), nn.ReLU(), nn.Conv2d(16, 3, 1))

        def forward(self, x):
            high = self.encoder(x)
            low = self.bottleneck(nn.functional.avg_pool2d(high, 2))
            up = nn.functional.interpolate(low, size=high.shape[-2:], mode="bilinear", align_corners=False)
            return self.decoder(torch.cat((high, up), dim=1))

    return TinyMultitask()


def validated_supervision(gray, targets, weight, instances):
    """Fundo não revisado e pixels incertos nunca são exemplos negativos."""
    if gray.ndim != 2 or gray.dtype != np.uint8 or min(gray.shape) < 2:
        raise ValueError("Entrada deve ser cinza uint8 HxW, lados >= 2; normalizacao fixa /255")
    if instances.shape != gray.shape or instances.dtype != np.uint16:
        raise ValueError("Instancias devem ser uint16 na mesma grade da imagem")
    if targets.shape != (3, *gray.shape) or not np.isfinite(targets).all() or not np.isin(targets, (0, 1)).all():
        raise ValueError("Alvos devem ser binarios finitos 3xHxW; rotulo incerto nao pode virar negativo")
    if weight.shape != gray.shape or weight.dtype != np.uint8 or not np.isin(weight, (0, 255)).all():
        raise ValueError("Peso deve ser uint8 binario 0/255 na mesma grade")
    # Os baselines antigos incluem 'fundo por limiar'. Ele NÃO foi revisado.
    safe = np.where((weight > 0) & (instances > 0), 255, 0).astype(np.uint8)
    return gray, targets.astype(np.float32), safe


def load_dev(run):
    run = run.resolve()
    if not run.is_relative_to(PRIVATE.resolve()):
        raise ValueError("O treino aceita somente execucoes na raiz privada autorizada")
    summary = json.loads((run / "summary.json").read_text(encoding="utf-8"))
    hashes = {k.replace("\\", "/"): v for k, v in json.loads((run / "artifact_hashes.json").read_text(encoding="utf-8")).items()}
    data, inputs = [], []
    for row in summary["rows"]:
        if row.get("split") != "dev":
            raise ValueError("Treino exploratorio recusa train/val/test/holdout: exige dev explicito")
        path = (run / row["directory"] / "training_dev.npz").resolve()
        if not path.is_relative_to(run):
            raise ValueError("Caminho fora da execucao")
        digest = sha256(path)
        if hashes.get(path.relative_to(run).as_posix()) != digest:
            raise ValueError("Hash do NPZ difere do manifesto de origem")
        with np.load(path, allow_pickle=False) as z:
            if str(z["split"]) != "dev" or str(z["group_status"]) != "unconfirmed":
                raise ValueError("Contrato dev/grupo nao confirmado invalido")
            if str(z["source_sha256"]) != row["source_sha256"]:
                raise ValueError("Hash de origem incoerente")
            gray, targets, weight, instances = [z[k].copy() for k in ("gray", "targets", "weight", "instances")]
        uncertainty = read_image(path.parent / "uncertainty_uint8.png", cv2.IMREAD_UNCHANGED)
        if not np.array_equal(weight, 255-uncertainty):
            raise ValueError("Peso de pseudo-rotulo nao coincide com mapa de incerteza")
        ignored_bg = int(((weight > 0) & (instances == 0)).sum())
        gray, targets, weight = validated_supervision(gray, targets, weight, instances)
        if not np.any((targets[0] > 0) & (weight > 0)):
            continue
        data.append((gray, targets, weight))
        inputs.append({"path": str(path), "sha256": digest, "source_sha256": row["source_sha256"],
                       "mask_shape_hw": list(gray.shape), "fit_valid_pixels": int((weight > 0).sum()),
                       "unreviewed_background_pixels_removed": ignored_bg,
                       "positive_pixels_per_head": ((targets > 0) & (weight[None] > 0)).sum(axis=(1, 2)).tolist()})
    if not data:
        raise ValueError("Nenhum pixel de interior confiavel: revisar mascaras, nao fabricar rotulos")
    return data, inputs


def sample_patch(item, patch, rng):
    gray, targets, weight = item
    h, w = gray.shape
    ph, pw = max(0, patch-h), max(0, patch-w)
    gray = np.pad(gray, ((0, ph), (0, pw)), mode="reflect")
    targets = np.pad(targets, ((0, 0), (0, ph), (0, pw)))
    weight = np.pad(weight, ((0, ph), (0, pw)))
    y, x = rng.integers(0, gray.shape[0]-patch+1), rng.integers(0, gray.shape[1]-patch+1)
    image, target, valid = gray[y:y+patch, x:x+patch], targets[:, y:y+patch, x:x+patch], weight[y:y+patch, x:x+patch]
    if rng.random() < .5:
        image, target, valid = image[:, ::-1], target[:, :, ::-1], valid[:, ::-1]
    if rng.random() < .5:
        image, target, valid = image[::-1], target[:, ::-1], valid[::-1]
    return (np.ascontiguousarray(image[None, None], dtype=np.float32)/255,
            np.ascontiguousarray(target[None], dtype=np.float32),
            np.ascontiguousarray(valid[None, None], dtype=np.float32)/255)


def weighted_loss(torch, logits, target, weight, active_heads):
    """Zero de peso zera também o gradiente; nenhum padding vira fundo negativo."""
    pos = (target*weight).sum(dim=(0, 2, 3))
    neg = ((1-target)*weight).sum(dim=(0, 2, 3))
    balance = (neg / pos.clamp_min(1)).clamp(1, 10).reshape(1, 3, 1, 1)
    loss_map = torch.nn.functional.binary_cross_entropy_with_logits(logits, target, reduction="none", pos_weight=balance)
    per_head = (loss_map*weight).sum(dim=(0, 2, 3)) / weight.sum().clamp_min(1)
    return (per_head*active_heads).sum()/active_heads.sum().clamp_min(1), per_head


def infer_demo(torch, model, data, inputs, out, role):
    """Inferência CPU na grade original do NPZ; demonstração, não avaliação independente."""
    from .infer import predict_tiled
    reports = []
    model.eval().cpu()
    for i, ((gray, _, _), info) in enumerate(zip(data, inputs)):
        probs = predict_tiled(torch, model, gray)
        p = np.clip(probs, 1e-7, 1-1e-7)
        entropy = -(p*np.log(p)+(1-p)*np.log(1-p)).mean(axis=0)/np.log(2)
        fg = (probs[0] > .5).astype(np.uint8)
        fg[(probs[1] > .5) | (probs[2] > .5)] = 0
        labels = watershed_instances(cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR), fg)
        prefix = f"{role}_{i:04d}"
        write_image(out / f"{prefix}_predicted_instances_uint16.png", labels)
        write_image(out / f"{prefix}_boundary_uint8.png", boundary(labels)*255)
        write_image(out / f"{prefix}_entropy_uint8.png", np.rint(np.clip(entropy, 0, 1)*255).astype(np.uint8))
        np.savez_compressed(out / f"{prefix}_probabilities.npz", probabilities=probs,
                            entropy_uncertainty=entropy, source_sha256=info["source_sha256"],
                            scale_xy=np.array([1., 1.]), role=role)
        ref_path = Path(info["path"]).parent / "instances_uint16.png"
        comparison = compare_instances(labels, read_image(ref_path, cv2.IMREAD_UNCHANGED))
        comparison["status"] = "demonstracao_dev_concordancia_pseudo_rotulos_nao_validacao"
        json_write(out / f"{prefix}_comparison_pseudo.json", comparison)
        reports.append({"prefix": prefix, "source_sha256": info["source_sha256"],
                        "shape_hw": list(gray.shape), "instances": int(len(np.unique(labels[labels > 0]))),
                        "foreground_fraction": float((labels > 0).mean()),
                        "mean_entropy": float(entropy.mean()), "mean_matched_iou": comparison["mean_matched_iou"],
                        "mean_matched_dice": comparison["mean_matched_dice"],
                        "group_independence_confirmed": False})
    return reports


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--run", required=True, type=Path)
    p.add_argument("--steps", type=int, default=100)
    p.add_argument("--patch", type=int, default=128)
    p.add_argument("--seed", type=int, default=20261103)
    p.add_argument("--device", choices=("cpu", "cuda"), default="cpu")
    p.add_argument("--infer-dev", action="store_true", help="Inferencia no proprio dev; nunca validacao")
    p.add_argument("--demo-run", type=Path, help="Execucao dev separada, excluida do ajuste; independencia nao presumida")
    p.add_argument("--export-onnx", action="store_true", help="Opcional: exporter legado, requer onnx licenciado")
    args = p.parse_args()
    if args.steps < 1 or args.patch < 16 or args.patch > 512:
        raise ValueError("steps >= 1 e patch entre 16 e 512")
    data, inputs = load_dev(args.run)
    demo_data, demo_inputs = load_dev(args.demo_run) if args.demo_run else ([], [])
    if {i["source_sha256"] for i in inputs} & {i["source_sha256"] for i in demo_inputs}:
        raise ValueError("Imagem de demonstracao tambem aparece no ajuste")
    out = new_run("multitask")
    json_write(out / "environment.json", environment())
    json_write(out / "inputs.json", inputs)
    json_write(out / "demo_inputs.json", demo_inputs)
    if importlib.util.find_spec("torch") is None:
        json_write(out / "blocked.json", {"status": "bloqueado_sem_treino", "reason": "PyTorch nao instalado neste interpretador",
                                          "weights_created": False, "dependencies_installed": False,
                                          "required_review": "PyTorch BSD-3, wheel compativel Python/Windows; GPU requer suporte sm_61 e runtime adequado. CPU e suficiente."})
        print(f"BLOQUEADO: PyTorch ausente; registro privado: {out}")
        return 2
    import torch
    if args.device == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("CUDA indisponivel neste PyTorch; use --device cpu")
    random.seed(args.seed)
    np.random.seed(args.seed)
    torch.manual_seed(args.seed)
    torch.set_num_threads(2)
    torch.use_deterministic_algorithms(True)
    rng = np.random.default_rng(args.seed)
    model = build_model(torch).to(args.device)
    positives = np.sum([i["positive_pixels_per_head"] for i in inputs], axis=0)
    active = torch.tensor(positives > 0, dtype=torch.float32, device=args.device)
    opt = torch.optim.Adam(model.parameters(), lr=1e-3)
    start = time.monotonic()
    with (out / "training_log.jsonl").open("x", encoding="utf-8") as log:
        for step in range(args.steps):
            for _ in range(100):
                x, target, weight = sample_patch(data[int(rng.integers(len(data)))], args.patch, rng)
                if weight.sum() > 0:
                    break
            if weight.sum() == 0:
                raise RuntimeError("Nao encontrou recorte confiavel; revisar pseudo-rotulos")
            x, target, weight = [torch.from_numpy(v).to(args.device) for v in (x, target, weight)]
            logits = model(x)
            loss, per_head = weighted_loss(torch, logits, target, weight, active)
            if not torch.isfinite(loss):
                raise RuntimeError("Loss nao finita; treino interrompido sem declarar sucesso")
            opt.zero_grad()
            loss.backward()
            opt.step()
            row = {"step": step+1, "loss_fit_pseudo_dev": float(loss.detach().cpu()),
                   "head_losses": per_head.detach().cpu().tolist(), "valid_pixels": float(weight.sum().cpu())}
            log.write(json.dumps(row) + "\n")
            log.flush()
    # state_dict apenas; carregar futuramente com weights_only=True.
    torch.save(model.cpu().state_dict(), out / "tiny_multitask_state_dict.pt")
    # Recarrega o arquivo efetivamente salvo para exercitar o contrato de checkpoint.
    model = build_model(torch)
    model.load_state_dict(torch.load(out / "tiny_multitask_state_dict.pt", weights_only=True, map_location="cpu"), strict=True)
    demos = infer_demo(torch, model, demo_data, demo_inputs, out, "demo_excluded_from_fit") if demo_data else []
    fit_demos = infer_demo(torch, model, data, inputs, out, "fit_dev") if args.infer_dev else []
    from .infer import model_contract
    contract = model_contract()
    contract["checkpoint_sha256"] = sha256(out / "tiny_multitask_state_dict.pt")
    json_write(out / "model_contract.json", contract)
    export = None
    if args.export_onnx:
        from .export_onnx import export_model
        export = export_model(torch, model, out)
    json_write(out / "training.json", {"status": "ajuste_exploratorio_pseudo_dev", "steps": args.steps,
                                       "patch": args.patch, "seed": args.seed, "device": args.device,
                                       "elapsed_seconds": time.monotonic()-start, "images": len(data),
                                       "heads": ["interior", "borda", "separacao"],
                                       "positive_pixels_per_head": positives.tolist(),
                                       "active_heads": (positives > 0).tolist(),
                                       "background_supervision": "nenhuma; fundo por limiar nao revisado foi removido",
                                       "demo_excluded_from_fit": demos, "fit_dev_inference": fit_demos,
                                       "demo_used_for_selection": False, "onnx_export": export,
                                       "evaluation": None, "validation": None, "test_used": False,
                                       "torch": torch.__version__, "script_sha256": sha256(__file__),
                                       "warning": "Ajuste em pseudo-rotulos dependentes; nao constitui validacao cientifica."})
    json_write(out / "artifact_hashes.json", {f.name: sha256(f) for f in out.iterdir() if f.is_file()})
    print(out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
