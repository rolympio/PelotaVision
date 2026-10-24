"""Imagens de revisão: recortes de validação com o consenso e as previsões dos modelos.

Uso (na raiz): python ml_training/data_belt/review_overlays.py
Cada JPG tem três painéis lado a lado: foto | consenso | modelo de correia.
  consenso: círculos verdes (amarelos = parciais); vermelho translúcido = peso 0 (material sem consenso)
  modelo de correia: contornos magenta (pipeline do app, cena Correia, Auto, sens. 75)
Painel extra no quadro vazio: modelo de demonstração (laranja) e modelo de correia (magenta).
"""
import json, sys
from pathlib import Path
import numpy as np, cv2

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'ml_training' / 'stardist'))
import common as C  # noqa: E402

BELT = ROOT / 'ml_training' / 'data_belt'
PSEUDO = BELT / 'pseudo'
ASSETS = ROOT / 'app' / 'src' / 'main' / 'assets'
# (quadro, x0, y0, lado) — um por câmera de validação, com borda de material e região densa
PICKS = [('img765.jpg', 760, 220, 300), ('img552.jpg', 400, 300, 300), ('20171227_11-41-37.jpg', 300, 250, 300),
         ('20171227_11-41-44.jpg', 820, 300, 300), ('img714.jpg', 500, 100, 300)]
EMPTY = ('img588.jpg', 400, 200, 300)
ZOOM = 2


def polys(dets_poly, x0, y0, side):
    out = []
    for p in dets_poly:
        q = (p - [x0, y0]) * ZOOM
        if (q[:, 0].max() < 0) or (q[:, 1].max() < 0) or (q[:, 0].min() > side * ZOOM) or (q[:, 1].min() > side * ZOOM):
            continue
        out.append(np.rint(q).astype(np.int32))
    return out


def detect_polys(run, gray):
    """Mesmo pipeline de common.detect (cena Correia), devolvendo os polígonos."""
    info = {}
    C.detect(run, gray, 3, 75, info, belt=True)
    if info['final_scale'] is None:
        return []
    _, kept, _ = C._pass(run, gray, info['final_scale'], C.threshold(75), True)
    return [k['poly'] for k in kept]


def panel(gray, x0, y0, side):
    sub = cv2.cvtColor(gray[y0:y0 + side, x0:x0 + side], cv2.COLOR_GRAY2BGR)
    return cv2.resize(sub, None, fx=ZOOM, fy=ZOOM, interpolation=cv2.INTER_CUBIC)


def label(img, text):
    cv2.rectangle(img, (0, 0), (img.shape[1], 18), (0, 0, 0), -1)
    cv2.putText(img, text, (4, 13), cv2.FONT_HERSHEY_SIMPLEX, .42, (255, 255, 255), 1, cv2.LINE_AA)
    return img


def main():
    out = PSEUDO / 'review'
    out.mkdir(parents=True, exist_ok=True)
    belt, demo = C.ort_runner(ASSETS / 'pellet_stardist_belt.onnx'), C.ort_runner(ASSETS / 'pellet_stardist.onnx')
    frames = {f['file']: f for f in json.load(open(PSEUDO / 'val_frames.json', encoding='utf-8'))}
    written = []
    for fname, x0, y0, side in PICKS:
        if fname not in frames:
            print('fora da validação/subamostragem:', fname); continue
        f = frames[fname]
        gray = cv2.cvtColor(cv2.imread(str(BELT / 'raw' / fname)), cv2.COLOR_BGR2GRAY)
        h, w = gray.shape
        x0, y0 = min(x0, w - side), min(y0, h - side)
        wm = cv2.imread(str(PSEUDO / f['weight_mask']), cv2.IMREAD_GRAYSCALE)[y0:y0 + side, x0:x0 + side]
        a = panel(gray, x0, y0, side)
        b = a.copy()
        zero = cv2.resize((wm == 0).astype(np.uint8), None, fx=ZOOM, fy=ZOOM, interpolation=cv2.INTER_NEAREST) > 0
        b[zero] = (b[zero] * .55 + np.array([0, 0, 160]) * .45).astype(np.uint8)
        nc = 0
        for c in f['consensus']:
            cx, cy = (c['x'] - x0) * ZOOM, (c['y'] - y0) * ZOOM
            if -20 < cx < side * ZOOM + 20 and -20 < cy < side * ZOOM + 20:
                cv2.circle(b, (int(round(cx)), int(round(cy))), int(round(c['radius'] * ZOOM)), (0, 255, 255) if c['partial'] else (0, 230, 0), 1, cv2.LINE_AA)
                nc += 1
        pb = polys(detect_polys(belt, gray), x0, y0, side)
        c3 = a.copy()
        cv2.polylines(c3, pb, True, (255, 0, 255), 1, cv2.LINE_AA)
        label(a, f"{fname} {f['camera']} x{x0} y{y0} {side}px x{ZOOM}")
        label(b, f'consenso: {nc} (vermelho = ignorado)')
        label(c3, f'modelo de correia: {len(pb)}')
        name = out / f"{Path(fname).stem}_x{x0}_y{y0}.jpg"
        cv2.imwrite(str(name), np.hstack([a, b, c3]), [cv2.IMWRITE_JPEG_QUALITY, 85])
        written.append(str(name.relative_to(ROOT)))
    fname, x0, y0, side = EMPTY
    gray = cv2.cvtColor(cv2.imread(str(BELT / 'raw' / fname)), cv2.COLOR_BGR2GRAY)
    a = panel(gray, x0, y0, side)
    pd, pb = polys(detect_polys(demo, gray), x0, y0, side), polys(detect_polys(belt, gray), x0, y0, side)
    b, c3 = a.copy(), a.copy()
    cv2.polylines(b, pd, True, (0, 140, 255), 1, cv2.LINE_AA)
    cv2.polylines(c3, pb, True, (255, 0, 255), 1, cv2.LINE_AA)
    label(a, f'{fname} Disco 03 correia VAZIA (nunca no treino)')
    label(b, f'demonstracao: {len(pd)}')
    label(c3, f'modelo de correia: {len(pb)}')
    name = out / f'{Path(fname).stem}_vazia.jpg'
    cv2.imwrite(str(name), np.hstack([a, b, c3]), [cv2.IMWRITE_JPEG_QUALITY, 85])
    written.append(str(name.relative_to(ROOT)))
    print('\n'.join(written))


if __name__ == '__main__':
    main()
