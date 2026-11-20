# Verificação da rajada — 0.19.0 (20/11/2026)

**Estado:** testado em software e demonstrado em imagem de verificação. **Não validado com amostra
física** (ensaio A pendente).

## Testes JUnit (cenas sintéticas, sem câmera nem GPU)
`BurstDiagnosticsTest` (6), `BurstFuseTest` (3) e `BurstSrTest` (5), em `desktop/src/test`. Os 14
passaram.

Nesta sessão, a suíte do desktop rodou com OpenCV 4.9 (openpnp, Linux) e Java 21: 143 testes, 1 falha
pré-existente de ambiente (`StudioShellTest` do ícone, que depende dos recursos empacotados pelo
Gradle). Falta rodar com OpenCV 4.12 no Windows (`COMPILAR_PELOTAVISION.bat`).

| Garantia | Resultado |
|---|---|
| Sinal dos offsets (`quadro_k(p+d) = base(p)`) e exatidão | erro < 0,08 px em ±1,5 px |
| Tripé sem tremor | `SOMENTE_MEDIANA`, motivo "tremor" |
| Quadro com +20 níveis | rejeitado (`deriva_brilho`); não vira base |
| Fases ½ px controladas, ruído 2 | `SR_2X_PLAUSIVEL`, cobertura ≥ 75% |
| Mão, ruído 25 | `SOMENTE_MEDIANA`, motivo "ruído" |
| Empilhada N=10, ruído 12 | RMSE ≥ 2,5× menor; diâmetro dos 8 discos = cena limpa ± 0,15 px |
| Objeto intruso em 1 quadro | sem fantasma (< 12 níveis) |
| Adjunta | erro relativo < 1e-9 (medido ~1e-14) |
| Campo plano | viés < 0,5 nível; desvio < 2 |
| MTF no Nyquist LR | SR > média alinhada + 5 níveis de amplitude |
| Offsets com erro de 0,3 px | SR rejeitada (χ² > 2,5 e > 2× o χ² correto) |

## Rajada simulada em fotos reais do repositório
`tabela_simulacao.csv`:
- Fotos:
  - `ml_training/data_belt/raw/20170619_09-52-13.jpg` (correia);
  - `ml_training/data_webcam_20261027/images/PelotaVision_20261027_132735_386_original.png` (webcam);
  - `ml_training/data_bench_20261026/images/IMG_20261026_134344.jpg` (bancada).
- Recorte central de 512² como verdade. Quadros de 256², sensor 2× pior: verdade deslocada, integrada
  em 2×2, mais ruído gaussiano por canal.
- PSNR contra a verdade, sem 8 px de borda.
- Reproduzir com `simular_rajada.py` → `BurstCli` → `avaliar_rajada.py` (sementes fixas, ver
  `tools/burst_validate/README.md`).

Colunas: `psnr_unico` e `psnr_empilhada` contra o quadro base limpo (Trilha 1). `unico_bic2x`,
`empilhada_bic2x` e `sr2x` contra a verdade 2× (Trilha 2). Resumo e leitura em
[`docs/rajada-burst.md`](../../docs/rajada-burst.md#o-que-foi-medido-resumo-de-verificationburst-0190).

- `comparacao_recortes.png` mostra, em recortes ampliados, 1 quadro, a empilhada, a SR 2× e a verdade:
  - bancada com ruído 3: a SR ganha 2,8 dB;
  - bancada com ruído 10: a SR ganha 1,3 dB;
  - correia com ruído 35, SR forçada: a SR não ganha nada. O laudo bloqueia esse caso.
- `exemplo_rajada_bancada_grade_r10.json` traz o bloco `burst` completo, como sai no JSON das
  exportações.

## Limites desta verificação
- Ruído gaussiano, translação pura e sem desfoque óptico. Rajada real de JPEG de celular tem ruído
  correlacionado, compressão e às vezes rotação. O χ² e o laudo devem reprovar mais SRs.
- O PSNR mede a imagem, não a granulometria. O efeito em D50 e contagem só sai do ensaio A.
- O ganho da empilhada em cena parada foi de 9 a 14 dB, próximo do √N teórico. O ensaio de 20/11 com
  N=24 achou +11 dB e erro de diâmetro 4× menor.
