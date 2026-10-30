# Auditoria e critérios de aceitação — 30/10/2026

## Referência
Base: `integracao/0.16.0` (PR #25). Não mesclar na main sem compilação Android e Windows e validação manual no dispositivo.

## Duas sessões fornecidas em anexo ao chat
- Sessão 10:19: **133 objetos**, referência padrão **11 mm** (não 11 m), 72,450375 px, **6,586398 px/mm**, referência ID 20 excluída da estatística. Método LoG; RETINEX.
- Sessão 10:47: **130 objetos**, referência **11 mm**, 75,903409 px, **6,900310 px/mm**, referência ID 33 excluída. Método LoG; processamento detalhado.
- A divergência relativa da escala é **4,766%** tomando a primeira como base. Não concluir erro de software sem verificar enquadramento, altura, perspectiva e posição das esferas.
- Os registros JSON/PNG não constituem máscaras verdade-terreno de borda nem rótulos independentes de trincas. Não declarar retreinamento sem anotação independente.

## Estado existente na branch de integração
- `ObjectCalibration` implementa `STANDARD_SPHERE`, mede o diâmetro equivalente de elipse e pode excluir o padrão.
- `ContainerRimDetector` propõe borda por votação de centro, perfil radial e ajuste robusto, sob confirmação.
- `DetectionTuner` faz busca limitada de perfil/método/sensibilidade, com escore heurístico, não acurácia.
- `HardwareAcceleration` e `docs/aceleracao-hardware.md`: ORT CUDA/CPU no Windows, LiteRT GPU/CPU no Android, paridade e retorno a OpenCV. Falta benchmark em hardware real.
- GitHub Actions do PR #25 teve falha (run 40805978986).

## Requisitos para implementação
1. Sugestão automática de esfera padrão configurável de 11 mm: circularidade/área/oclusão, destaque e confirmação pelo operador, intervalo de erro, trilha de auditoria e exclusão da granulometria.
2. Validar distância/perspectiva com fotos de mesmo arranjo e comparar escala entre sessões.
3. Benchmark e fallback de aceleração hardware reais em ambas plataformas, sem sacrificar paridade.
4. Retreino de borda e de trinca só com rótulos humanos e holdout por sessão; métricas precisão, recall, F1, FP/FN e matriz confusão; versionar modelos, reconverter ONNX/TFLite e validar SHA.
5. Testar borda do recipiente em fotos com referência; referência determina escala, não serve como rótulo da borda.
6. UI guiada em Android e Windows: Capturar → Área → Calibrar → Ajustar → Revisar → Salvar, esconder avançados em diálogos sem perder funcionalidades.
7. Preview Android amplo, preservar aspect ratio, responsividade/retrato/paisagem e funções câmera.
8. Otimização coarse-to-fine de iluminação (original/CLAHE/Retinex/gamma), filtros, método, tamanho físico mínimo/máximo e sensibilidade; penalizar duplicações, fragmentação e instabilidade temporal.
9. Avaliar guided/bilateral/normalização local por comparação quantitativa, sempre medir geometria/trincas nos pixels originais.
10. Benchmark StarDist vs Cellpose/Omnipose, YOLO-seg e alternativas offline em imagens anotadas; considerar latência/memória em Android.
11. Em cada pelota, opção reversível "Segmentação incorreta — excluir", mantida nos relatórios e propagada às estatísticas de contagem, diâmetros, trincas, RR, histogramas, CSV/JSON/PDF.

## Saída e bloqueio do merge
Criar novos testes de regressão e testes reais; compilar Android com `assembleDebug testDebugUnitTest lintDebug` e Windows com testes OpenCV/ORT. Entregar APK, instalador EXE e checksums apenas após existência e verificação dos binários. Não fazer merge na main até tudo isso passar.
