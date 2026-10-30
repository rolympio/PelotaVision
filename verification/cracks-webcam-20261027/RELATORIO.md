# Trincas: análises de webcam de 27/10/2026 (enviadas em 28/10)

## Dados
- **Recebido:** 7 análises exportadas pelo app (Windows 0.5.4/0.5.5), webcam 1920×1080 sobre panela, ~124–145 pelotas cada.
- **Rótulo:** só as pelotas **revisadas à mão** no app (`crack_manually_reviewed = true`): 31 trincadas (POSSIBLE), 103 sem trinca (NO_INDICATION), 12 inconclusivas (não usadas). Uma "sem trinca" tocava a borda e virou incerta: **133 rotuladas** em **6 análises** (a de 13:27 não tinha revisão).
- **Não revisadas ficaram de fora:** o status delas é a opinião do classificador da época, não um rótulo.
- **Pelotas remexidas entre análises** (centros não coincidem: < 2% de pares a menos de 4 px), mas do **mesmo lote físico**. A validação "análise inteira fora" não garante pelota inédita.
- **Viés de seleção:** a revisão foi feita sobre o que o classificador antigo marcou (o usuário confere mais as marcadas). As métricas abaixo valem para pelotas desse tipo, não para uma amostra aleatória.
- Importador: `tools/cracks/import_pelotavision_exports.py`. Dados: `ml_training/data_webcam_20261027/`.

## Protocolo (fixado antes do treino: `gate_mobilenet.json`)
1. **Teste externo do modelo embarcado (v2)** nas 133 pelotas, que ele nunca viu: `external_embedded_v2.json`.
2. **Retreino:** ajuste fino a partir da v2 (pesos lidos do ONNX, BN congelada; os pesos ImageNet não estão acessíveis neste ambiente), 6 épocas, LR 2e-4, mesmos dados da 0.15.5 + webcam.
3. **Validação:** análise inteira fora (6 dobras); holdout antigo (1 foto, 7 trincas) uma vez.

## Resultados (limiar 0,5)

| | AUC | Trincadas achadas | Falsos alarmes | Trincadas "sem indicação" (política 0,4/0,6) |
|---|---|---|---|---|
| v2 embarcada (0.15.5), teste externo | 0,872 | 22 de 31 (71%) | 5 de 102 | 8 |
| **v3 retreinada, análise fora** | **0,922** | **26 de 31 (84%)** | **1 de 102** | **3** |
| v3 no holdout antigo | 0,977 (IC95 0,93–1,00) | 7 de 7 | 9 de 75 | 0 |

Por análise (AUC v2 → v3): 15:16 1,00 → 1,00 · 15:20 0,97 → 1,00 · 15:26 0,81 → 1,00 · 15:31 0,81 → 1,00 · 16:02 0,95 → 1,00 · **16:09 0,80 → 0,88** (10 trincas; 4 ainda passam).

- Critério: **aprovado** (`gate_result.json`). O asset `crack_mobilenetv3.onnx` passa a ser `mobilenetv3_small_cracks_20261028_v3`.
- A AUC agrupada (0,92) é menor que as por análise porque os escores de dobras diferentes não são calibrados entre si.
- Paridade: OpenCV DNN 4.12 × PyTorch, diferença máxima 5e-6.

## Limites
- 31 trincas em 6 análises do mesmo lote: intervalos largos; falta validar em outro dia/lote.
- A bancada (rajadas de 26/10) não entra no critério: a v2, ponto de partida, já tinha visto todas as rajadas.
- O **Random Forest de reserva não foi retreinado** nesta rodada (continua `random_forest_20261027_v2`).
- Nada aqui é validação de produção; o escore não é probabilidade.

## Para a próxima rodada
- Revise também algumas pelotas **não marcadas** (amostra aleatória), para medir trincas que o modelo nem sinaliza.
- Uma panela de outro dia ou lote, com 10+ trincas, como teste externo de verdade.
