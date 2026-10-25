# PelotaVision 0.14.0 (Android) / 0.5.0 (Windows) — redes neurais, mapa granulométrico e histograma de trincadas

Branch `feature/redes-neurais-mapa-histograma`, criada sobre `integracao/0.13.0` (PR #8) + `feature/external-pellet-datasets` (PR #6). Nada foi mesclado na `main`.

## 1. Auditoria: o que já existia e o que faltava (25/10/2026)

| Item do prompt | Situação encontrada | Feito nesta branch |
|---|---|---|
| Segmentação neural | StarDist (BSD-3) já integrado como 3ª opção (PR #1); modelo de demonstração + modelo de correia com pseudo-rótulos | Mantido. Seletor "Modelos de análise" com Clássica (bordas/LoG), Neural StarDist, YOLO11-seg e Híbrida |
| YOLO11-seg | Não existia | **Não implementado, deliberadamente** — ver §5 |
| RF de trincas | RF 72 atributos (`crack_forest.pvrf`, `CrackModel.java`); benchmark do PR #6 mostrou AUC 0,49 na única foto real | Mantido como padrão |
| MobileNetV3-Small | Não treinado (PR #6: sem PyTorch no ambiente) | Treinado no CI (§3), exportado em ONNX, integrado via OpenCV DNN como opção **experimental** + modo de comparação |
| U-Net de trincas | — | Desabilitada: não existem máscaras reais de trinca |
| Mapa granulométrico | Branch do PR #7: só tela Android, sem controle de opacidade na UI, inclusão divergente do histograma, sem exportação, sem Windows, sem testes | Reescrito sobre o núcleo comum: mesma regra de inclusão e escala do histograma, opacidade 0–70% (padrão 25%), escala relativa/fixa, rótulos sem sobreposição, legenda contínua, exportação PNG, Windows |
| Histograma de trincadas | Não existia | Série laranja sobreposta (mesmas classes/eixos), indicadores, 3 normalizações, "classificação indisponível" |
| Exportação | JSON sem versão de esquema | JSON `report_schema_version: 2`, `_mapa.png`, CSV de granulometria com 2 colunas no fim |

## 2. Arquivos

Núcleo (`core/`, comum a Android e Windows):
- `GranulometryColorScale.java` (novo) — escala contínua HSV 240°→0°, relativa/fixa, cor intermediária quando todos iguais, mistura source-over e pintura pixel a pixel da exportação (idêntica nas duas plataformas).
- `CrackHistogram.java` (novo) — série de trincadas como subconjunto, mesmas classes (`Distribution.binIndex`), normalizações, indisponibilidade explícita.
- `ReportVisuals.java` (novo) — parâmetros de visualização gravados no relatório.
- `MobileNetCrackModel.java` (novo) — inferência ONNX via OpenCV DNN, pré-processamento espelho do script de treino, verificação de SHA-256.
- `CrackClassifierChoice.java` (novo) — RF / MobileNetV3 / Comparação, com fallback explícito.
- `CrackDetector.java`, `CrackAnalysis.java`, `DetailedAnalysis.java`, `Granulometry.java`, `ReportJson.java`, `ReportTables.java` (alterados).

Android: `HistogramView`, `GranulometricColorMap`, `MainActivity`, `PhotoComposer`, `ResultSaver` (alterados); `MapComposer` (novo); assets `crack_mobilenetv3.onnx/.json` (novos, gerados pelo CI). Testes: `VisualsDemoTest` (Robolectric, telas reais).

Windows: `HistogramView`, `ImageView`, `MainFrame`, `DesktopAnalysis`, `DesktopExport`, `PhotoSession`, `DesktopApp` (alterados). Testes: `GranulometryVisualsTest` (12 testes), `MobileNetParityTest`, `DesktopVisualsTest`.

Treino: `tools/cracks/train_mobilenet.py`, `.github/workflows/mobilenet-cracks.yml`. Capturas: `.github/workflows/visual-captures.yml`.

## 3. Treinamento (CI, CPU, 25/10/2026)

Mesmo manifesto do benchmark do RF (`verification/cracks-benchmark-20261025`, conferido linha a linha no CI): 1517 recortes.

| Conjunto | Papel | Trincadas | Íntegras | Incertas (fora) |
|---|---|---|---|---|
| 6 cenas internas 0.5.0 (aparência gerada, rótulos provisórios) | dev, LeaveOneGroupOut por cena | 29 | 140 | 16 |
| paired40 + sintéticos procedurais sobre doadoras reais | só treino | 470 | 740 | — |
| crack_example.png (foto real, rótulos provisórios registrados antes dos escores) | **teste final, usado uma vez** | 7 | 75 | 40 |

Imagens externas aproveitadas: **0** (auditoria do PR #6: sem licença, sem acesso ou fora do domínio). Nenhuma pelota verde rotulada.

Protocolo fixado a priori: MobileNetV3-Small com pesos ImageNet (torchvision), entrada 128×128 cinza, 10 épocas, AdamW, perda ponderada por classe, sintéticos com peso 0,5, aumentos plausíveis (rotação 90°, espelho, ganho/gama/offset leves, desfoque leve). Limiar 0,5 e política do app 0,4/0,6. O holdout não participou de nenhuma escolha.

| Modelo | Dev AUC | Dev P/R/F1 | Holdout AUC (IC95, reamostrando pelotas) | Holdout [[VN,FP],[FN,VP]] | Holdout P/R/F1 | App 0,4/0,6: possível (trinc./ínteg.) |
|---|---|---|---|---|---|---|
| RF embarcado 0.5.0 | 0,962 | 0,89/0,86/0,88 | 0,49 (0,28–0,73) | [[19,56],[1,6]] | 0,10/0,86/0,17 | 4 / 48 |
| MobileNetV3 só dados reais | 0,975 | 1,00/0,83/0,91 | 0,66 (0,45–0,86) | [[75,0],[7,0]] | —/0,00/0,00 | 0 / 0 |
| **MobileNetV3 + sintéticos** | 0,994 | 0,72/0,97/0,82 | **0,96 (0,90–1,00)** | [[64,11],[0,7]] | 0,39/1,00/0,56 | 5 / 6 |

ΔAUC (MobileNetV3+sint. − RF) no holdout: IC95 +0,22 a +0,70 (pelotas reamostradas).

Por tamanho (terços de raio no holdout; 1, 1 e 5 trincadas): AUC 0,77 / 1,00 / 0,97 (RF: 0,58 / 0,96 / 0,48).

Execução (CPU do runner, 2 núcleos; não é o celular): ONNX 6,1 MB, 1,52 M parâmetros; 1,2 ms/pelota no ONNX Runtime e 3,3 ms/pelota no OpenCV DNN 4.12. Paridade OpenCV DNN × PyTorch: 5×10⁻⁶. Paridade Java × Python verificada no Windows por `MobileNetParityTest` (CI).

**Leitura honesta:** é o melhor sinal obtido até agora, mas não é evidência para trocar o modelo. Uma única foto, 7 trincas, rótulos provisórios do assistente, IC que reamostra pelotas e não fotos, e critério pré-registrado (≥15 trincadas e ≥30 íntegras em fotos independentes) não atendido. **Decisão: o RF continua o padrão; a MobileNetV3 entra como opção experimental e no modo de comparação.**

Segmentação: nenhuma métrica nova de instância (sem máscaras com rótulo humano). As métricas existentes do StarDist estão em `ml_training/stardist/output`.

## 4. Visualizações

Mapa granulométrico: cor só dentro da elipse ajustada das pelotas **incluídas no histograma** (mesma regra de exclusão de parciais/alertas e mesma escala px/mm); nada fora da ROI ou no fundo da correia. Opacidade 0–70% (padrão 25% cor / 75% foto). Escala relativa (mín.–máx. da foto) ou fixa (mm configuráveis; fora da faixa satura nas pontas). Rótulos de diâmetro opcionais, omitidos quando pequenos na tela ou sobrepostos (legível com centenas de pelotas: ver captura da correia com 404). Legenda contínua com 5 valores e o modo da escala, também no PNG exportado.

Histograma: azul-petróleo 55% (todas) e laranja 65% (trincadas), sobrepostos (não lado a lado, não somados), mesmas classes e eixos. Com a série desligada, o gráfico original é preservado (Android: % por número, cor original; Windows: contagens). Indicadores: total, trincadas, % das avaliáveis (incertas fora do denominador), incertas. Modos: absoluto; % do total (subconjunto preservado); condicional (cada série soma 100%, rotulado). Sem classificação: "classificação indisponível", nunca zero.

## 5. YOLO11-seg: por que não foi implementado

1. Não há máscaras de instância com rótulo humano no repositório; treinar YOLO com rótulos gerados pelo StarDist/método clássico mediria concordância entre algoritmos, não exatidão.
2. Código e pesos da Ultralytics são AGPL-3.0 (licença comercial à parte). Distribuir o app com esse runtime/pesos exige revisão jurídica da Vale antes.
3. O StarDist (BSD-3) já cumpre o papel de segmentação neural experimental no app.

Destrava: ~200 fotos com contornos revisados por especialista + decisão de licença.

## 6. Migração do JSON (esquema 2)

Campos novos, todos aditivos (leitores antigos continuam funcionando): `report_schema_version` (ausente = 1), `analysis_models`, `crack_histogram`, `granulometric_map` (inclui `file`), `visualization`; em `cracks`: `classifier`, `crack_crop_pixels`, `comparison` e, por pelota, `comparison_score`/`comparison_method`. CSV de granulometria: colunas `count_cracked;percent_cracked_of_all` **acrescentadas no fim** (vazias se indisponível). Novo arquivo `_mapa.png`. Ordem dos arquivos anteriores inalterada.

## 7. Verificação executada (CI do PR #9)

| Item | Resultado |
|---|---|
| Android: build + 103 testes unitários (Robolectric) + lint | aprovado; APK assinado com a chave oficial (atualiza a 0.11.0+) |
| Windows: build + 31 testes (OpenCV nativo 4.12), incl. paridade MobileNetV3 Java × Python | aprovado |
| Windows: autoteste do EXE empacotado (6 arquivos, JSON v2, MobileNetV3 no pacote) | aprovado |
| Pipeline Python de trincas | aprovado |
| Núcleo, 12 testes do item 9 (local) | 12/12 |
| Capturas reais das telas | `verification/visuals-0.14.0/` (Activity renderizada pelo Robolectric e componentes Swing) |

Observação da captura da correia (404 pelotas): o RF marcou 76 de 81 pelotas avaliáveis como possível trinca (94%). É o mesmo padrão de falso positivo visto no benchmark, agora visível no histograma laranja.

## 8. Limitações conhecidas

- MobileNetV3 não validada em fotos independentes nem em pelotas verdes; escore não calibrado. Pesos de partida treinados no ImageNet (termos de uso de pesquisa): revisar antes de distribuição comercial.
- Mapa colore a elipse ajustada, não uma máscara de pixels; oclusões na correia continuam medidas pela borda visível.
- Com o mapa ativo, o toque para revisar trincas fica desligado (volte para "Seg.").
- Trocar o classificador ou a segmentação reprocessa a foto pelo modo rápido (o modo detalhado precisa ser refeito).
- Desempenho medido no runner do CI, não em celular ARM64.
- Builds e testes Android/Windows rodam no CI do PR; este ambiente não tem Android SDK nem Maven.
