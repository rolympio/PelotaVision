"""Formato PVF1 (big-endian) usado por CrackModel.java.

Cabeçalho: magic b'PVF1', versão int32 1, atributos int32, árvores int32.
Cada árvore: nós int32; cada nó: atributo, esquerdo, direito (int32), limiar float64, escore float64.
Folhas: atributo -2, filhos -1. Entrada float32, comparação `x <= limiar` (como sklearn e Java).
"""
import struct
from pathlib import Path

import numpy as np

MAGIC = b'PVF1'
NODE = np.dtype([('f', '>i4'), ('l', '>i4'), ('r', '>i4'), ('t', '>f8'), ('p', '>f8')])


def write_pvrf(model, path, n_features=72):
    """Serializa um RandomForestClassifier binário (classe 1 = cracked)."""
    if list(model.classes_) != [0, 1]:
        raise ValueError('modelo deve ter classes [0, 1]')
    with open(path, 'wb') as f:
        f.write(struct.pack('>4siii', MAGIC, 1, n_features, len(model.estimators_)))
        for est in model.estimators_:
            t = est.tree_
            if t.node_count > 65535:
                raise ValueError('árvore excede o limite do CrackModel.java (65535 nós)')
            f.write(struct.pack('>i', t.node_count))
            for i in range(t.node_count):
                v = t.value[i, 0]
                f.write(struct.pack('>iiidd', int(t.feature[i]), int(t.children_left[i]), int(t.children_right[i]),
                                    float(t.threshold[i]), float(v[1] / v.sum())))


def read_pvrf(path, n_features=72):
    """Lê e valida com as mesmas regras de CrackModel.java."""
    b = Path(path).read_bytes()
    magic, ver, nf, nt = struct.unpack('>4siii', b[:16])
    if magic != MAGIC or ver != 1 or nf != n_features:
        raise ValueError('modelo de trincas incompatível')
    if not 1 <= nt <= 1024:
        raise ValueError('floresta inválida')
    o, trees = 16, []
    for _ in range(nt):
        (n,) = struct.unpack('>i', b[o:o + 4])
        o += 4
        if not 1 <= n <= 65535:
            raise ValueError('árvore inválida')
        a = np.frombuffer(b[o:o + n * NODE.itemsize], dtype=NODE)
        o += n * NODE.itemsize
        leaf = a['f'] == -2
        if np.any(leaf & ((a['l'] != -1) | (a['r'] != -1))):
            raise ValueError('folha inválida')
        idx = np.arange(n)
        inner = ~leaf
        if np.any(inner & ((a['f'] < 0) | (a['f'] >= nf) | (a['l'] <= idx) | (a['r'] <= idx) | (a['l'] >= n) | (a['r'] >= n))):
            raise ValueError('ramo inválido')
        if np.any((a['p'] < 0) | (a['p'] > 1)) or not np.all(np.isfinite(a['t'])):
            raise ValueError('nó inválido')
        trees.append(a)
    if o != len(b):
        raise ValueError('dados excedentes no modelo')
    return trees


def score_pvrf(trees, X):
    """Inferência equivalente a CrackModel.score (atributo float32 <= limiar float64)."""
    X = np.asarray(X, np.float32)
    s = np.zeros(len(X))
    rows = np.arange(len(X))
    for t in trees:
        idx = np.zeros(len(X), np.int64)
        while True:
            f = t['f'][idx]
            inner = f != -2
            if not inner.any():
                break
            ri, ii, fi = rows[inner], idx[inner], f[inner]
            go_left = X[ri, fi].astype(np.float64) <= t['t'][ii]
            idx[inner] = np.where(go_left, t['l'][ii], t['r'][ii])
        s += t['p'][idx]
    return s / len(trees)
