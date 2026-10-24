"""Decide se o modelo CANDIDATO de trincas substitui o modelo atual, usando a bandeja real.

Uso:
  python avaliar_bandeja.py --rotulos rotulos.csv [--fotos PASTA_ORIGINAIS] RESULTADO1.json [RESULTADO2.json ...] [--saida PASTA]

Cada RESULTADO*.json é o JSON salvo pelo PelotaVision (Android ou Windows) para uma foto da
bandeja; a foto original (campo original_file, "<base>_original.jpg") precisa estar na mesma
pasta ou na pasta indicada em --fotos (no Android: Pictures/PelotaVision). O script NÃO usa o escore gravado no JSON: recorta de novo cada pelota a partir da foto
original e dos campos crack_crop_x/y/radius_px, extrai os 72 atributos (features.py, idêntico
ao app) e pontua com as DUAS florestas. Assim, atual e candidato veem exatamente o mesmo recorte.

rotulos.csv (separador ; ou ,):  foto;pelota;trinca
   foto   = nome do JSON sem extensão (ou o arquivo original), pelota = número do relatório,
   trinca = 1 (trincada), 0 (íntegra), ? (não sei: fica fora).
Pelotas parciais (borda), com revisão ou com raio de recorte < 20 px ficam fora (o app as torna
inconclusivas de qualquer jeito); o número de excluídas é relatado.

CRITÉRIO DE TROCA (definido ANTES de ver os dados; não altere depois de olhar os resultados):
  0. mínimo de 15 pelotas trincadas e 30 íntegras avaliáveis; senão "INSUFICIENTE".
  1. AUC(candidato) - AUC(atual): limite inferior do IC 95% (bootstrap estratificado, 5000
     reamostragens por FOTO e pelota) > 0  -> o candidato é melhor em ordenação.
  2. Sensibilidade a especificidade fixa de 90% (limiar = quantil 90% dos escores das íntegras
     de cada modelo): candidato >= atual, e IC 95% da diferença com limite inferior > -0,05.
  3. Na política do app (sensibilidade 50: >=0,6 possível, <=0,4 sem indicação), a proporção
     de íntegras marcadas como "possível" pelo candidato não pode exceder a do atual em mais
     de 5 pontos percentuais.
  TROCAR só se 1, 2 e 3 forem satisfeitos. Caso contrário: MANTER o atual.
Se o candidato vencer, ainda é preciso recalibrar os limiares 0,4/0,6 com outra bandeja
(estes dados já foram usados para decidir) - o script informa os limiares sugeridos apenas
como ponto de partida.
"""
import argparse
import csv
import json
import sys
from pathlib import Path

import cv2
import numpy as np
from sklearn.metrics import roc_auc_score

HERE = Path(__file__).resolve().parent
ML = HERE.parent
sys.path.insert(0, str(ML))
from features import extract  # noqa: E402
from train_synth import read_pvrf, score_pvrf  # noqa: E402

MODELS = {'atual': ML.parent / 'app/src/main/assets/crack_forest.pvrf',
          'candidato': ML / 'training_synth/crack_forest_candidate.pvrf'}
MIN_POS, MIN_NEG, BOOT = 15, 30, 5000


def read_labels(path):
    text = Path(path).read_text(encoding='utf-8-sig')
    delim = ';' if text.count(';') >= text.count(',') else ','
    out = {}
    for row in csv.reader(text.splitlines(), delimiter=delim):
        if not row or row[0].strip().lower() in ('foto', '#') or row[0].startswith('#'):
            continue
        foto, pel, lab = (c.strip() for c in row[:3])
        key = (Path(foto).stem.replace('_original', ''), int(pel))
        out[key] = None if lab in ('?', '') else int(lab)
    return out


def load_result(path, fotos=None):
    j = json.loads(Path(path).read_text(encoding='utf-8'))
    base = Path(path).stem
    # Android grava o JSON em Download/PelotaVision e a original em Pictures/PelotaVision
    cands = [Path(path).parent / j['original_file']] + ([Path(fotos) / j['original_file']] if fotos else [])
    orig = next((c for c in cands if c.exists()), None)
    if orig is None:
        raise SystemExit(f"Foto original ausente: {j['original_file']} (procure em Pictures/PelotaVision e use --fotos)")
    bgr = cv2.imread(str(orig), cv2.IMREAD_COLOR)
    # o app analisa a foto "em pé" possivelmente reduzida; as coordenadas são da foto analisada
    W, H = j['analysis_photo_width'], j['analysis_photo_height']
    if (bgr.shape[1], bgr.shape[0]) != (W, H):
        if (bgr.shape[0], bgr.shape[1]) == (W, H):
            raise SystemExit(f'{orig}: orientação EXIF não aplicada; exporte a foto em pé ou gire-a antes.')
        bgr = cv2.resize(bgr, (W, H), interpolation=cv2.INTER_AREA)
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    return base, j, gray


def bootstrap(y, s_a, s_b, photos, rng):
    """IC 95% para AUC_b-AUC_a e sens@spec90_b - sens@spec90_a, reamostrando fotos e pelotas."""
    ups = np.unique(photos)
    d_auc, d_sens = [], []
    for _ in range(BOOT):
        pick = rng.choice(ups, len(ups))
        idx = np.concatenate([rng.choice(np.where(photos == p)[0], (photos == p).sum()) for p in pick])
        yy = y[idx]
        if yy.min() == yy.max():
            continue
        d_auc.append(roc_auc_score(yy, s_b[idx]) - roc_auc_score(yy, s_a[idx]))
        d_sens.append(sens_at_spec(yy, s_b[idx]) - sens_at_spec(yy, s_a[idx]))
    q = lambda v: [float(np.quantile(v, .025)), float(np.quantile(v, .975))]
    return q(d_auc), q(d_sens)


def sens_at_spec(y, s, spec=0.9):
    t = np.quantile(s[y == 0], spec)
    return float((s[y == 1] > t).mean())


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--rotulos', required=True)
    ap.add_argument('--saida', default='resultado_bandeja')
    ap.add_argument('--fotos', help='pasta das fotos *_original.* se não estiverem junto dos JSON')
    ap.add_argument('--seed', type=int, default=20261024)
    ap.add_argument('resultados', nargs='+')
    a = ap.parse_args()
    labels = read_labels(a.rotulos)
    forests = {k: read_pvrf(v) for k, v in MODELS.items()}
    rows, excl = [], {'sem_rotulo': 0, 'incerta': 0, 'parcial_ou_revisao': 0, 'raio<20': 0, 'gate_contraste': 0}
    for rpath in a.resultados:
        base, j, gray = load_result(rpath, a.fotos)
        for d in j['detections']:
            key = (base, int(d['id']))
            if key not in labels:
                excl['sem_rotulo'] += 1
                continue
            lab = labels[key]
            if lab is None:
                excl['incerta'] += 1
                continue
            if d.get('partial_edge') or d.get('needs_review'):
                excl['parcial_ou_revisao'] += 1
                continue
            r = d['crack_crop_radius_px']
            if r < 20:
                excl['raio<20'] += 1
                continue
            f, _, info = extract(gray, d['crack_crop_x_px'], d['crack_crop_y_px'], r, return_marks=True)
            if info['spread'] < 10 or not 15 <= info['mean'] <= 242:
                excl['gate_contraste'] += 1
                continue
            rows.append({'foto': base, 'pelota': int(d['id']), 'trinca': lab, 'raio_px': r, 'f': f,
                         'escore_app': d.get('crack_score'), 'metodo_app': d.get('crack_method')})
    out = Path(a.saida)
    out.mkdir(parents=True, exist_ok=True)
    if not rows:
        raise SystemExit('Nenhuma pelota avaliável.')
    X = np.array([r['f'] for r in rows])
    y = np.array([r['trinca'] for r in rows])
    photos = np.array([r['foto'] for r in rows])
    S = {k: score_pvrf(t, X) for k, t in forests.items()}
    # conferência: o escore gravado pelo app deve bater com o modelo que o app usava
    app_scores = np.array([np.nan if r['escore_app'] is None else r['escore_app'] for r in rows], float)
    ok = np.isfinite(app_scores)
    check = {}
    for k in S:
        if ok.any():
            check[k] = float(np.nanmax(np.abs(app_scores[ok] - S[k][ok])))
    rep = {'pelotas_avaliadas': int(len(y)), 'trincadas': int(y.sum()), 'integras': int((1 - y).sum()), 'fotos': sorted(set(photos)),
           'excluidas': excl, 'conferencia_escore_app_max_dif': check,
           'criterio': 'ver docstring: IC95 dAUC>0, sens@spec90 cand>=atual com IC95 inferior>-0,05, FP na política do app <= atual+5pp'}
    for k, s in S.items():
        neg, pos = y == 0, y == 1
        rep[k] = {'auc': float(roc_auc_score(y, s)) if pos.any() and neg.any() else None,
                  'sens@spec90': sens_at_spec(y, s) if pos.any() and neg.sum() >= 10 else None,
                  'sens@0.5': float((s[pos] >= .5).mean()) if pos.any() else None,
                  'spec@0.5': float((s[neg] < .5).mean()) if neg.any() else None,
                  'app_possivel_integras': float((s[neg] >= .6).mean()) if neg.any() else None,
                  'app_possivel_trincadas': float((s[pos] >= .6).mean()) if pos.any() else None,
                  'app_inconclusivas': float(((s > .4) & (s < .6)).mean())}
    if y.sum() < MIN_POS or (1 - y).sum() < MIN_NEG:
        rep['decisao'] = f'INSUFICIENTE: são necessárias >= {MIN_POS} trincadas e >= {MIN_NEG} íntegras avaliáveis.'
    else:
        rng = np.random.default_rng(a.seed)
        ci_auc, ci_sens = bootstrap(y, S['atual'], S['candidato'], photos, rng)
        c1 = ci_auc[0] > 0
        c2 = rep['candidato']['sens@spec90'] >= rep['atual']['sens@spec90'] and ci_sens[0] > -0.05
        c3 = rep['candidato']['app_possivel_integras'] <= rep['atual']['app_possivel_integras'] + 0.05
        rep['ic95_dif_auc'] = ci_auc
        rep['ic95_dif_sens@spec90'] = ci_sens
        rep['criterios'] = {'1_auc': bool(c1), '2_sens_spec90': bool(c2), '3_fp_politica_app': bool(c3)}
        rep['decisao'] = 'TROCAR pelo candidato (recalibrar limiares numa segunda bandeja)' if (c1 and c2 and c3) else 'MANTER o modelo atual'
        if c1 and c2 and c3:
            s, neg = S['candidato'], y == 0
            rep['limiares_sugeridos_candidato'] = {'possivel>=': float(np.quantile(s[neg], .9)), 'sem_indicacao<=': float(np.quantile(s[neg], .6))}
    with (out / 'escores_por_pelota.csv').open('w', encoding='utf-8') as f:
        f.write('foto;pelota;trinca;raio_px;escore_atual;escore_candidato;escore_gravado_app;metodo_app\n')
        for r, a1, b1 in zip(rows, S['atual'], S['candidato']):
            f.write(f"{r['foto']};{r['pelota']};{r['trinca']};{r['raio_px']:.1f};{a1:.4f};{b1:.4f};{r['escore_app']};{r['metodo_app']}\n")
    (out / 'decisao.json').write_text(json.dumps(rep, indent=1, ensure_ascii=False), encoding='utf-8')
    print(json.dumps(rep, indent=1, ensure_ascii=False))


if __name__ == '__main__':
    main()
