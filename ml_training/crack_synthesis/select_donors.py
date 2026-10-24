"""Seleciona as pelotas doadoras (sem trinca visível) nas fotos reais do repositório.

Uso: python select_donors.py [--sheets PASTA]

Os círculos vêm de HoughCircles (HOUGH_GRADIENT_ALT). As exclusões são MANUAIS,
feitas pelo assistente olhando folhas de contato ampliadas (opção --sheets gera
as mesmas folhas). O resultado fica versionado em donors.json, que é o que o
gerador usa; este script só documenta a procedência. Como o Hough pode variar
entre versões do OpenCV, os índices das exclusões valem para o OpenCV usado na
geração (registrado em donors.json).
"""
import argparse
import json
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
# foto: (raio mínimo, raio máximo, param2), exclusões {índice: motivo}
PHOTOS = {
    'verification/fixtures/1000411645.jpg': (28, 70, 0.7, {
        0: 'desfocada/ocluída', 1: 'toca a borda branca', 6: 'desfocada', 11: 'desfocada',
        16: 'duas pelotas', 17: 'ocluída', 19: 'círculo deslocado', 20: 'círculo deslocado',
        24: 'possível trinca', 29: 'desfocada', 31: 'círculo deslocado', 34: 'marca escura (possível trinca)',
        35: 'desfocada', 40: 'desfocada', 43: 'desfocada', 44: 'desfocada', 46: 'círculo deslocado',
        47: 'duas pelotas', 48: 'desfocada', 49: 'desfocada', 54: 'ocluída', 55: 'toca a borda branca'}),
    'verification/fixtures/1000411646.jpg': (60, 115, 0.6, {
        0: 'marca escura (possível trinca)', 2: 'fenda escura (possível trinca)', 3: 'fenda escura (possível trinca)'}),
    'verification/fixtures/1000411647.jpg': (24, 55, 0.7, {
        0: 'ocluída', 1: 'círculo deslocado', 12: 'duas pelotas', 16: 'círculo deslocado', 17: 'desfocada',
        20: 'círculo deslocado', 21: 'duas pelotas', 24: 'ocluída', 26: 'círculo deslocado', 30: 'círculo deslocado',
        45: 'desfocada', 53: 'círculo deslocado', 71: 'círculo deslocado', 78: 'desfocada', 88: 'círculo deslocado',
        97: 'círculo deslocado', 98: 'círculo deslocado', 101: 'círculo deslocado', 104: 'desfocada',
        106: 'marca d\'água', 107: 'marca d\'água', 109: 'círculo deslocado', 112: 'marca d\'água'}),
}


def candidates(path, rmin, rmax, p2):
    im = cv2.imread(str(ROOT / path))
    g = cv2.GaussianBlur(cv2.cvtColor(im, cv2.COLOR_BGR2GRAY), (5, 5), 1.5)
    c = cv2.HoughCircles(g, cv2.HOUGH_GRADIENT_ALT, 1.5, rmin * 1.5, param1=120, param2=p2, minRadius=rmin, maxRadius=rmax)[0]
    h, w = g.shape
    c = [t for t in c if t[0] - t[2] > 2 and t[1] - t[2] > 2 and t[0] + t[2] < w - 2 and t[1] + t[2] < h - 2]
    return im, sorted(c, key=lambda t: (round(t[1] / 80), t[0]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--sheets', help='pasta para gravar folhas de contato numeradas')
    a = ap.parse_args()
    out = {'opencv': cv2.__version__, 'origem': 'Hough + exclusão manual do assistente', 'photos': []}
    for path, (rmin, rmax, p2, excl) in PHOTOS.items():
        im, cs = candidates(path, rmin, rmax, p2)
        keep = [[round(float(x), 2), round(float(y), 2), round(float(r), 2)] for i, (x, y, r) in enumerate(cs) if i not in excl]
        out['photos'].append({'image': path, 'width': im.shape[1], 'height': im.shape[0], 'candidates': len(cs),
                              'excluded': {str(k): v for k, v in sorted(excl.items())}, 'donors': keep})
        print(path, 'candidatos', len(cs), 'doadoras', len(keep), 'raio mediano', np.median([k[2] for k in keep]))
        if a.sheets:
            Path(a.sheets).mkdir(parents=True, exist_ok=True)
            T, cols = 150, 10
            sh = np.full((((len(cs) + cols - 1) // cols) * T, cols * T, 3), 255, np.uint8)
            for i, (x, y, r) in enumerate(cs):
                s = 1.15 * r
                c = cv2.warpAffine(im, np.array([[2 * s / T, 0, x - s], [0, 2 * s / T, y - s]]), (T, T),
                                   flags=cv2.INTER_CUBIC | cv2.WARP_INVERSE_MAP)
                cv2.circle(c, (T // 2, T // 2), int(r / s * T / 2), (0, 0, 255) if i in excl else (0, 255, 0), 1)
                cv2.putText(c, str(i), (2, 12), 0, .42, (0, 255, 255), 1)
                sh[(i // cols) * T:(i // cols + 1) * T, (i % cols) * T:(i % cols + 1) * T] = c
            cv2.imwrite(str(Path(a.sheets) / (Path(path).stem + '_doadoras.jpg')), sh, [cv2.IMWRITE_JPEG_QUALITY, 88])
    (Path(__file__).parent / 'donors.json').write_text(json.dumps(out, indent=1, ensure_ascii=False))


if __name__ == '__main__':
    main()
