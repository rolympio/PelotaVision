"""Compatibilidade de arredondamento com o OpenCV 4.x (Android/Windows usam 4.12).

O OpenCV 5 mudou o warpAffine INTER_LINEAR para coordenadas em ponto flutuante.
O 4.x (o que roda no app, via Java) quantiza as coordenadas em 1/32 de pixel e
usa pesos inteiros de 15 bits. Os 72 atributos dependem disso: com o cv2 5.0 os
atributos de referência divergiam em até 0,6. Esta emulação em numpy reproduz o
4.x bit a bit nos 209 recortes de referência (erro máximo 0).

Só cobre o caso usado por features.extract: matriz de escala+translação,
WARP_INVERSE_MAP, INTER_LINEAR, BORDER_REFLECT_101, imagem uint8 de 1 canal.
"""
import cv2
import numpy as np

CV_MAJOR = int(cv2.__version__.split('.')[0])


def _reflect101(i, n):
    i = np.abs(i)
    i = np.where(i >= n, 2 * (n - 1) - i, i)
    return np.clip(i, 0, n - 1)


def warp_inverse_linear_cv4(gray, matrix, size):
    """Equivalente a cv2.warpAffine(gray, matrix, (size, size), INTER_LINEAR|WARP_INVERSE_MAP, BORDER_REFLECT_101) do OpenCV 4.x."""
    AB_SCALE = 1024
    round_delta = 16  # AB_SCALE / INTER_TAB_SIZE / 2
    x = np.arange(size)
    y = np.arange(size)
    adelta = np.rint(matrix[0, 0] * x * AB_SCALE).astype(np.int64)
    bdelta = np.rint(matrix[1, 0] * x * AB_SCALE).astype(np.int64)
    X0 = np.rint((matrix[0, 1] * y + matrix[0, 2]) * AB_SCALE).astype(np.int64) + round_delta
    Y0 = np.rint((matrix[1, 1] * y + matrix[1, 2]) * AB_SCALE).astype(np.int64) + round_delta
    X = (X0[:, None] + adelta[None, :]) >> 5
    Y = (Y0[:, None] + bdelta[None, :]) >> 5
    xi, yi, fx, fy = X >> 5, Y >> 5, X & 31, Y & 31
    h, w = gray.shape
    g = gray.astype(np.int64)
    x0, x1 = _reflect101(xi, w), _reflect101(xi + 1, w)
    y0, y1 = _reflect101(yi, h), _reflect101(yi + 1, h)
    v = (g[y0, x0] * (32 - fx) * (32 - fy) + g[y0, x1] * fx * (32 - fy)
         + g[y1, x0] * (32 - fx) * fy + g[y1, x1] * fx * fy) * 32
    return ((v + (1 << 14)) >> 15).astype(np.uint8)


def warp_crop(gray, matrix, size):
    """Recorte normalizado idêntico ao do Java (OpenCV 4.x), qualquer que seja o cv2 instalado."""
    if CV_MAJOR == 4:
        return cv2.warpAffine(gray, matrix, (size, size), flags=cv2.INTER_LINEAR | cv2.WARP_INVERSE_MAP,
                              borderMode=cv2.BORDER_REFLECT_101)
    return warp_inverse_linear_cv4(gray, matrix, size)
