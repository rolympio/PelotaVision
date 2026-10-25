"""Extratores de atributos sobre recortes de UMA pelota.

Cada amostra do manifesto aponta para um recorte PNG e para o círculo da pelota
nas coordenadas do recorte (cx, cy, r). Os recortes são feitos com margem e com
preenchimento BORDER_REFLECT_101 da foto original (build_manifest.crop_pellet),
de modo que `pv72` no recorte é bit a bit igual a `features.extract` na foto inteira.

pv72    72 atributos morfológicos do app (ml_training/features.py == CrackFeatures.java)
hoglbp  HOG 128x128 + LBP uniforme + média/desvio (kit de treinamento; só Python)
pixels  48x48 tons de cinza normalizados dentro do disco (entrada da MLP leve)
"""
import sys
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'ml_training'))
from features import extract as _pv72_extract, lighting  # noqa: E402

N_LIGHTING_VARIANTS = 6


def pv72(gray, cx, cy, r):
    return _pv72_extract(gray, cx, cy, r)


def lit_variant(gray, index, source_width=None, x_offset=0):
    """Variante de iluminação do treino original (features.lighting).

    A variante 4 é um gradiente horizontal sobre a FOTO inteira; com source_width/x_offset
    o gradiente é reproduzido exatamente no recorte."""
    if index != 4 or source_width is None:
        return lighting(gray, index)
    g = gray.astype(np.float32) / 255
    xx = np.arange(g.shape[1])[None, :] + x_offset
    g = g * (.5 + .5 * xx / max(1, source_width - 1))
    return np.clip(np.rint(g * 255), 0, 255).astype('uint8')


def _square(gray, cx, cy, r):
    """Quadrado [cx-r, cx+r] reamostrado, com reflexão fora do recorte."""
    return cv2.getRectSubPix(gray, (max(2, int(round(2 * r))),) * 2, (float(cx), float(cy)))


def hoglbp(gray, cx, cy, r):
    from skimage.feature import hog, local_binary_pattern
    a = cv2.resize(_square(gray, cx, cy, r), (128, 128), interpolation=cv2.INTER_AREA)
    h = hog(a, orientations=9, pixels_per_cell=(16, 16), cells_per_block=(2, 2), feature_vector=True)
    lbp = local_binary_pattern(a, 8, 1, method='uniform')
    hist = np.histogram(lbp, bins=np.arange(11), range=(0, 10))[0].astype(float)
    hist /= max(hist.sum(), 1)
    return np.concatenate((h, hist, [a.mean() / 255, a.std() / 255])).astype(np.float32)


_PIX = 48
_yy, _xx = np.mgrid[:_PIX, :_PIX]
_DISC = ((_xx - (_PIX - 1) / 2) ** 2 + (_yy - (_PIX - 1) / 2) ** 2) < (0.78 * (_PIX - 1) / 2) ** 2


def pixels(gray, cx, cy, r):
    a = cv2.resize(_square(gray, cx, cy, r), (_PIX, _PIX), interpolation=cv2.INTER_AREA).astype(np.float32)
    v = a[_DISC]
    lo, hi = np.percentile(v, [5, 95])
    a = np.clip((a - lo) / max(hi - lo, 8.0), 0, 1)
    a[~_DISC] = 0.5
    return a.ravel()


EXTRACTORS = {'pv72': pv72, 'hoglbp': hoglbp, 'pixels': pixels}


def extract_set(names, gray, cx, cy, r):
    return np.concatenate([EXTRACTORS[n](gray, cx, cy, r) for n in names]).astype(np.float32)
