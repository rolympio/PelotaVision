"""Reavalia um ONNX já treinado com o pós-processamento atual (common.detect), sem treinar.

Uso: python evaluate.py --model holdout.onnx --metrics holdout_metrics.json
Reescreve o campo "scenes" e o protocolo; preserva os demais campos do treino. Confere o SHA-256.
"""
import argparse, json, hashlib
from pathlib import Path
import common as C


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--data', default=str(Path(__file__).resolve().parents[1] / 'data'))
    ap.add_argument('--model', required=True)
    ap.add_argument('--metrics', required=True)
    a = ap.parse_args()
    m = json.loads(Path(a.metrics).read_text(encoding='utf-8'))
    sha = hashlib.sha256(Path(a.model).read_bytes()).hexdigest()
    if sha != m['model_sha256']:
        raise SystemExit(f'Modelo {a.model} não corresponde às métricas ({sha} != {m["model_sha256"]})')
    m['scenes'] = C.evaluate(a.model, C.load_scenes(a.data), set(m['training_groups']))
    m['evaluation_protocol'] = C.PROTOCOL
    m['reevaluated_without_training'] = True
    Path(a.metrics).write_text(json.dumps(m, indent=1, ensure_ascii=False), encoding='utf-8')
    for g, s in m['scenes'].items():
        print(g, s, flush=True)


if __name__ == '__main__':
    main()
