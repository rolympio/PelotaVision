#!/usr/bin/env python3
"""Ensaio A da rajada (repetibilidade em cena parada): resume exportações do PelotaVision por modo.

Organize as pastas exportadas pelo app (cada uma com PelotaVision_*.csv) assim:

  RAIZ/
    sem_tratamento/  <exportação 1>/  <exportação 2>/ ...   (foto única de cada rajada)
    mediana_n4/      ...
    mediana_n10/     ...
    mediana_n20/     ...

Para cada modo: contagem (média e CV), D10/D50/D90 em mm (média e desvio entre repetições), CV de D50
e fração de pelotas "needs_review". Com --d50-referencia (peneira ou paquímetro, em mm), também o
erro absoluto médio de D50. Critério do prompt master (§5 A) para habilitar um modo: −20% de erro de
diâmetro OU −30% de CV de D50 contra "sem_tratamento", sem aumentar as inconclusivas.

Uso: python ensaio_a_repetibilidade.py --raiz PASTA [--d50-referencia 12.4] [--saida resumo.csv]
Só lê as pastas; não altera nada.
"""
import argparse
import csv
import glob
import math
import os
import statistics as st


def percentil(valores, q):
    v = sorted(valores)
    if not v:
        return float("nan")
    pos = q * (len(v) - 1)
    i = int(math.floor(pos))
    j = min(len(v) - 1, i + 1)
    return v[i] + (v[j] - v[i]) * (pos - i)


def ler_exportacao(pasta):
    """Devolve (contagem, diâmetros_mm, n_revisar) do CSV por pelota da exportação."""
    arquivos = [f for f in glob.glob(os.path.join(pasta, "PelotaVision_*.csv")) if not f.endswith("_granulometria.csv")]
    if not arquivos:
        return None
    with open(arquivos[0], encoding="utf-8") as f:
        linhas = list(csv.DictReader(f, delimiter=";"))
    diam = []
    revisar = 0
    for l in linhas:
        if l.get("needs_review", "").lower() == "true":
            revisar += 1
        if l.get("included_in_histogram", "").lower() != "true":
            continue
        try:
            diam.append(float(l["equivalent_diameter_mm"]))
        except (KeyError, ValueError):
            pass
    return len(linhas), diam, revisar


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--raiz", required=True)
    ap.add_argument("--d50-referencia", type=float, default=None)
    ap.add_argument("--saida", default=None)
    a = ap.parse_args()
    linhas = []
    for modo in sorted(os.listdir(a.raiz)):
        pm = os.path.join(a.raiz, modo)
        if not os.path.isdir(pm):
            continue
        cont, d10, d50, d90, rev = [], [], [], [], []
        for exp in sorted(os.listdir(pm)):
            r = ler_exportacao(os.path.join(pm, exp))
            if r is None:
                continue
            n, diam, nrev = r
            cont.append(n)
            rev.append(nrev / n if n else 0)
            if diam:
                d10.append(percentil(diam, 0.1)); d50.append(percentil(diam, 0.5)); d90.append(percentil(diam, 0.9))
        if not cont:
            continue
        m = lambda v: st.mean(v) if v else float("nan")
        s = lambda v: st.stdev(v) if len(v) > 1 else float("nan")
        linha = {"modo": modo, "repeticoes": len(cont), "contagem_media": m(cont),
                 "contagem_cv": s(cont) / m(cont) if cont and m(cont) else float("nan"),
                 "d10_mm": m(d10), "d50_mm": m(d50), "d50_desvio_mm": s(d50),
                 "d50_cv": s(d50) / m(d50) if d50 and m(d50) else float("nan"), "d90_mm": m(d90),
                 "fracao_revisar": m(rev)}
        if a.d50_referencia is not None and d50:
            linha["erro_abs_medio_d50_mm"] = m([abs(x - a.d50_referencia) for x in d50])
        linhas.append(linha)
    if not linhas:
        raise SystemExit("Nenhuma exportação encontrada (PelotaVision_*.csv).")
    chaves = list(dict.fromkeys(k for l in linhas for k in l))
    print(";".join(chaves))
    for l in linhas:
        print(";".join(f"{l.get(k, ''):.4f}" if isinstance(l.get(k), float) else str(l.get(k, "")) for k in chaves))
    if a.saida:
        with open(a.saida, "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=chaves, delimiter=";")
            w.writeheader()
            w.writerows(linhas)


if __name__ == "__main__":
    main()
