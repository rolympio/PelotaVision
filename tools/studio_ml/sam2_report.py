"""Audita um piloto SAM concluído e produz relatório exclusivamente privado."""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import cv2
import numpy as np
import psutil

from .pipeline import (PRIVATE, foreground, json_write, new_run, read_image,
                       sha256, watershed_instances)
from .review import load_candidate
from .sam2_candidates import child, load_source


def report(run, source_map):
    run = Path(run).resolve(strict=True)
    if not run.is_relative_to(PRIVATE.resolve()):
        raise ValueError('Run fora da raiz privada')
    manifest = json.loads((run / 'artifact_hashes.json').read_text(encoding='utf-8'))
    for relative, digest in manifest.items():
        if sha256(child(run, relative)) != digest:
            raise ValueError(f'Artefato alterado: {relative}')
    summary = json.loads((run / 'summary.json').read_text(encoding='utf-8'))
    mapping = json.loads(Path(source_map).read_text(encoding='utf-8')) if source_map else {}
    watershed = Path(summary['source_run']).resolve(strict=True)
    if not watershed.is_relative_to(PRIVATE.resolve()):
        raise ValueError('Watershed fora da raiz privada')
    out = new_run('sam2-report')
    process = psutil.Process()
    cv2.setNumThreads(4)
    rows = []
    lines = ['# Piloto SAM 2.1 — relatório privado', '',
             f'Run: `{run}`', '',
             'Contagens de propostas, não acurácia. IDs de imagem na ordem do run.', '',
             '| Imagem | Watershed | StarDist | SAM grade | SAM centros | Rejeições grade/centros | SAM total (s) | RSS amostrado máximo (MiB) |',
             '|---|---:|---:|---:|---:|---:|---:|---:|']
    for row in summary['results']:
        directory = row['directory']
        folder = child(watershed, directory)
        record, source, before, bgr, roi = load_source(folder, mapping)
        assert before == row['source_sha256_before'] == row['source_sha256_after']
        # Mede novamente somente gray + foreground raw + watershed, fora do SAM.
        # Não substitui nem inventa um tempo histórico do run original.
        start = time.perf_counter()
        gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
        fg, _ = foreground(gray, roi.astype(np.uint8), 'raw')
        labels = watershed_instances(bgr, fg, record['parameters']['min_area_px'],
                                     record['parameters']['seed_radius_px'])
        seconds = time.perf_counter() - start
        reference = read_image(folder / 'instances_uint16.png', cv2.IMREAD_UNCHANGED)
        exact = bool(np.array_equal(labels, reference))
        if not exact:
            raise ValueError('Reexecucao watershed difere do run historico')
        methods = {}
        for name, variant in row['variants'].items():
            candidate = load_candidate(child(run, f'{directory}/{name}'))
            assert len(candidate.meta) == variant['accepted']
            assert candidate.record['candidate_method'] == name
            assert not candidate.background.any() and not candidate.cracks.any()
            assert variant['candidates'] == variant['accepted'] + sum(variant['rejected'].values())
            assert all(m['origem'] == f'sam2.1_hiera_tiny:{name}' for m in candidate.meta.values())
            methods[name] = {k: variant[k] for k in ('prompts', 'candidates', 'accepted',
                'rejected', 'seconds', 'rss_sampled_peak_bytes', 'process_peak_wset_bytes')}
        assert sha256(source) == before
        rows.append({'directory': directory, 'source_sha256': before,
                     'original_preservado': True, 'editor_verificado': list(methods),
                     'watershed_reexecution_seconds': seconds,
                     'watershed_exact_labels': exact,
                     'watershed_rss_after_bytes': process.memory_info().rss,
                     'watershed_rejections': None, 'watershed_overlap_rejections': None,
                     'stardist_historical_seconds': row['stardist_historical_seconds'],
                     'stardist_memory_historical_bytes': None,
                     'stardist_rejections_by_reason': None,
                     'sam': methods})
        grid, centers = methods['grid'], methods['stardist_centers']
        memory = max(m['rss_sampled_peak_bytes'] for m in methods.values()) / 2**20
        lines.append(f"| {directory.split('-')[1]} | {row['baseline_counts']['watershed']} | "
                     f"{row['baseline_counts']['stardist']} | {grid['accepted']} | {centers['accepted']} | "
                     f"{sum(grid['rejected'].values())}/{sum(centers['rejected'].values())} | "
                     f"{row['image_seconds']:.2f} | {memory:.1f} |")
    lines += ['', '## Limitações', '',
              '- Escala física não utilizada: fornecer pixels/mm rastreáveis na grade processada, '
              'com referência, método, incerteza e imagens aplicáveis para medições físicas.',
              '- Sem ground truth, anotação independente ou estimativa de acurácia.',
              '- A grade de 64 px pode omitir objetos; a ROI histórica cobre a imagem toda. '
              'Máscaras de recipiente/fundo podem ser aceitas. Limites de área são exploratórios.',
              '- Centros StarDist dependem das detecções StarDist. Três alternativas por prompt '
              'geram duplicações; rejeição por qualquer sobreposição pode remover objetos válidos.',
              '- RSS amostrado após prompts; pico Windows cumulativo, não por imagem. '
              'O encoder é compartilhado e medido separadamente.',
              '- Watershed: reexecução medida separadamente, apenas gray/foreground raw/watershed; '
              'máscaras comparadas pixel a pixel com as históricas. Não equivale ao pipeline completo.',
              '- StarDist: tempo histórico do run, memória histórica indisponível. '
              'Rejeições por motivo/sobreposição dos baselines não foram instrumentadas nos runs antigos; '
              'null não significa zero.',
              '- Teste do editor cobre carregamento e proveniência; interação gráfica humana não realizada.',
              '- Instalação CPU sem extensão CUDA opcional. Nenhum treinamento ou release.']
    (out / 'relatorio.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    json_write(out / 'audit.json', {'run': str(run), 'artifacts_verified': len(manifest),
        'report_code_sha256': sha256(Path(__file__)), 'rows': rows,
        'watershed_timing_scope': 'gray + foreground raw + watershed; 4 threads OpenCV; exclui I/O',
        'summary_sha256': sha256(run / 'summary.json')})
    json_write(out / 'artifact_hashes.json', {p.name: sha256(p) for p in out.iterdir() if p.is_file()})
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--source-map', type=Path)
    args = parser.parse_args()
    print(report(args.run, args.source_map))


if __name__ == '__main__':
    main()
