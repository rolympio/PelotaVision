# Trincas: fotos de bancada de 26/10/2026 (enviadas em 27/10)

## Dados
- **Recebido:** 80 fotos JPEG, 47 em "Trincadas" e 33 em "Sem trinca". Pelotas escuras sobre mesa clara.
- **Rótulo por pelota:** só em fotos com **uma** pelota inteira, com o rótulo da pasta.
  - Fotos com várias pelotas viraram `uncertain`.
  - Em "Trincadas" não se sabe qual pelota está trincada.
  - A separação automática de pelotas encostadas errou centro e raio (conferido nos overlays).
- **Resultado:** 57 pelotas (28 trincadas, 29 sem trinca) em **21 rajadas** (13 trincadas, 8 sem trinca).
  - Rajada = mesma pelota fotografada em sequência (até 12 s, mesma pasta). Toda validação deixa a rajada inteira de fora.
- **Escalas:** cada pelota entra em duas.
  - Original: r ≈ 100–160 px.
  - Reduzida 0,3×: r ≈ 30–48 px, como a webcam 1920×1080 sobre a panela.
- **Exclusão:** 1 foto (`IMG_20261026_144231_1.jpg`), par fundido numa mancha. Motivo em `tools/cracks/labels/bench_20261026_exclusions.json`.

## Resultados (AUC; limiar 0,5)

| Modelo | Bancada original | Bancada reduzida | Holdout antigo (7 trincas / 82) |
|---|---|---|---|
| RF embarcado até a 0.15.4 (era o padrão) — externo | 0,735 · recall 21% | 0,788 · recall 50% | 0,49 |
| MobileNetV3 embarcada até a 0.15.4 — externo | 0,966 · recall 82% · 0 FP | 0,984 · recall 75% · 0 FP | 0,96 · FN 0 · FP 11 |
| RF retreinado (cenas + bancada), rajada fora | 0,901 · recall 64% | 0,954 · recall 82% | 0,73 |
| **MobileNetV3 retreinada (0.15.5), rajada fora** | **0,968 · recall 93% · 1 FP** | **0,942 · recall 89% · 1 FP** | **0,975 · FN 0 · FP 12** |

- O critério para trocar o modelo do app foi fixado antes do treino (`gate_mobilenet.json`), e a retreinada **passou**:
  - AUC na bancada ≥ 0,951;
  - recall ≥ 0,786;
  - holdout ≥ 0,94 e FN ≤ 1.
- **Externo × rajada fora:** os modelos embarcados nunca viram a bancada (teste externo). Os retreinados viram as outras rajadas da mesma sessão de fotos. Os números não são estritamente comparáveis.
- **Teste mais duro (RF, treino numa sessão e teste na outra):** 0,877 / 0,947.

## Decisões
- **MobileNetV3 é o padrão** desde a 0.15.5, com o modelo `mobilenetv3_small_cracks_20261027_v2`.
- **Random Forest** fica como reserva (`random_forest_20261027_v2`, retreinado com cenas + bancada).

## Limites
- Pelotas isoladas sobre mesa clara são mais fáceis que pelotas cruas encostadas na panela ou na correia.
- Nenhum modelo foi validado em produção.
- 21 rajadas é pouco: os intervalos de confiança são largos (ver `metrics.json`).
- O escore da rede não é probabilidade calibrada.
- Fotos com várias pelotas em "Trincadas" ainda podem virar rótulo, se alguém indicar qual pelota de cada foto está trincada.
