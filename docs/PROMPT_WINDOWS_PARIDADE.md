# Prompt — PelotaVision Windows: paridade com o Android 0.11.0

> Cole tudo abaixo da linha numa nova sessão do Claude (Claude Code na nuvem, com o
> repositório `Rolympio/PelotaVision` anexado com permissão de escrita). Se a sessão
> não tiver o repositório, peça para anexar `Rolympio/PelotaVision` com acesso "push".

---

## Papel e objetivo

Você vai levar o **PelotaVision Windows** (pasta `desktop/`, versão 0.1.0) à paridade
funcional com o **PelotaVision Android 0.11.0** (pasta `app/`), reaproveitando o núcleo
de análise comum (pasta `core/`). Trabalhe em incrementos pequenos, cada um compilado,
testado e conferido no GitHub Actions antes do próximo. Responda e escreva a interface
em português do Brasil.

## Contexto que você precisa saber antes de começar

**Repositório e estrutura**
- `core/src/main/java/br/com/pelotavision/` — 25 classes Java puras + OpenCV, sem Android:
  `PelletDetector`, `AdaptivePelletDetector`, `ContourSeeds`, `EllipseFitter`,
  `DetectionTuner`, `DetectionAssessment`, `DetectionEvidence`, `AnalysisWindow`,
  `InterestRegion`, `ObjectCalibration`, `BeltCalibration`, `BeltEdgeDetector`,
  `DetailedAnalysis`, `TilePlan`, `DetailGeometry`, `FsrcnnUpscaler`, `CrackDetector`,
  `HeuristicCrackDetector`, `CrackFeatures`, `CrackModel`, `CrackTrace`, `CrackAnalysis`,
  `Granulometry`, `ShapeStatistics`, `ReportTables`.
  O Android compila esta pasta via `sourceSets` em `app/build.gradle`.
- `app/` — Android. **Referência de comportamento.** Leia `MainActivity.java` (844 linhas
  longas; use grep pelos métodos `showRegionEditor`, `applyRegion`, `activateRoi`,
  `showObjectCalibration*`, `showBeltCalibration`, `reviewCrackAt`, `showCrackSettings`,
  `showSuggestions`, `startSuggestionSearch`, `showSegmentationOptions`,
  `processDetailedPhoto`, `showDetailSummary`, `showCalibration`, `commitScale`,
  `refreshDistribution`, `savePhoto`), `ResultSaver.java` (JSON/CSV),
  `PhotoComposer.java` + `ReportViewport.java` (PNGs do relatório), `OriginalRoi.java`
  e `ActivePhoto.java` (leitura do original e ROI), `RegionEditorView`, `BeltEditorView`.
- `desktop/` — Windows: `DesktopApp` (entrada, carga da DLL, `--autoteste`),
  `MainFrame` (Swing), `CameraSource` (webcam DirectShow/MSMF), `DesktopAnalysis`
  (fluxo de foto equivalente a `processPhoto`), `ImageView` (zoom/pan/cliques e
  marcações), `HistogramView`, `DesktopExport` (PNG/CSV/JSON), `Mats` (imagem),
  teste `desktop/src/test/.../DesktopPipelineTest.java`. Build independente:
  `gradlew -p desktop test jar`.
- `verification/` — evidências históricas e imagens de teste. Úteis para os testes:
  `coverage-0.8.1/reference_input.webp` (1536×865), `fixtures/1000411645..47.jpg`,
  `fixtures/crack_example.png` + `crack_example_analysis.json`,
  `belt-0.9.0/input_correia.jpg` + `analysis.json`, `detail-0.11.0/analysis.json`,
  `roi-active-0.10.0/`, `reference-0.7.0/`. Os testes Android em `app/src/test/` mostram
  como cada recurso deve se comportar: **leia o teste Android correspondente antes de
  implementar cada item** (`ActiveRoiWorkflowTest`, `RegionTest`, `ObjectCalibrationTest`,
  `BeltWorkflowTest`, `CrackExportTest`, `CoverageSuggestionTest`,
  `DetectionSuggestionTest`, `DetailedWorkflowTest`, `PhotoExportTest`).
- `README.md` e `PelotaVision_Base_de_Retomada.md` (se anexado) têm as regras de negócio.

**Ambiente desta sessão (limitações reais, já verificadas)**
- Não há Android SDK e os servidores do Google/Maven Central estão bloqueados: **não
  tente compilar com Gradle localmente**. Toda compilação real é no GitHub Actions.
- O GitHub responde pela API com credenciais injetadas pelo proxy. **Logs e artefatos
  das execuções não podem ser baixados** (armazenamento bloqueado). A única saída legível
  são as **anotações** (`::notice`, `::warning`, `::error`). Por isso os workflows já
  publicam testes, lint, assinatura e resultados como anotações. Use
  `tools/ci_status.sh [RUN_ID...]` para esperar e ler o resultado.
- Disparar workflow: `curl -X POST -H "Content-Type: application/json"
  https://api.github.com/repos/Rolympio/pelotavision/actions/workflows/windows.yml/dispatches
  -d '{"ref":"main","inputs":{"publicar":"true"}}'` (o mesmo vale para `android.yml`).
  Segredos do repositório não podem ser criados daqui.
- **Compilação local rápida (faça sempre antes de enviar):** baixe as fontes Java do
  OpenCV 4.12 e compile `core` + `desktop` com `javac` para pegar erros em segundos:
  ```bash
  mkdir -p ~/opencv && cd ~/opencv
  curl -sSL -o sdk.zip https://github.com/opencv/opencv/releases/download/4.12.0/opencv-4.12.0-android-sdk.zip
  python3 -I -c "import zipfile;z=zipfile.ZipFile('sdk.zip');n=[i for i in z.namelist() if '/sdk/java/src/org/opencv/' in i and i.endswith('.java') and '/android/' not in i];z.extractall('x',n)"
  rm sdk.zip; find x -name '*.java' > src.txt; mkdir -p cls && javac -nowarn -d cls @src.txt && (cd cls && jar cf ../opencv-api-4.12.0.jar .)
  cd ~/pelotavision && javac -Xlint:all -cp ~/opencv/opencv-api-4.12.0.jar -d /tmp/pv core/src/main/java/br/com/pelotavision/*.java desktop/src/main/java/br/com/pelotavision/*.java
  ```
  Para compilar os testes localmente, crie stubs mínimos de `org.junit` (Test, BeforeClass,
  Assert). Não há DLL nativa aqui: execução real só no runner Windows.
- Armadilhas já encontradas: (1) escreva BOM como escape `﻿`, nunca o caractere
  literal (o lint Android falha com `ByteOrderMark`); (2) no Git Bash do runner não use
  `grep -P`; (3) com `pipefail`, evite `| head` após comandos longos (SIGPIPE);
  (4) `concurrency` cancela a execução anterior do mesmo ramo: dispare um workflow de cada
  vez e espere.

## Regras que não podem ser quebradas

1. **Android intocado no comportamento.** Ao final de cada envio, o workflow Android tem
   que continuar com 93/93 testes (ou mais, se você adicionar), **zero erros de lint** e
   certificado `0d77d2917bd808a17f92e52568dbb6bc2b1767be0e25ca93fadc8e41c8d66b38`.
   Se precisar mover lógica do Android para `core/` (ex.: geometria de `ReportViewport`,
   montagem do JSON de `ResultSaver`, matemática de `OriginalRoi`), mova **só a parte sem
   `android.*`**, mantenha o Android chamando a versão do núcleo e prove que a saída não
   mudou (teste novo comparando antes/depois quando possível). Não altere algoritmos do
   núcleo para "melhorar" resultados.
2. **Mesmas regras de negócio do Android** (estão nos testes e na Base de Retomada):
   - Referência no mesmo plano; zoom e recorte nunca mudam a escala física.
   - Calibração por objeto: o usuário escolhe e informa a medida (paquímetro: eixo maior,
     eixo menor ou esfera de diâmetro conhecido); a referência precisa ter contorno
     inteiro, aceito e sem alerta; esfera excluída das estatísticas por padrão, pelota
     incluída por padrão; exclusão editável; marca `REF` visível; se a reanálise mudar o
     contorno da referência, a calibração é removida com aviso.
   - Correia: largura física inclui as faixas sem material; sugestão de bordas + edição
     de 4 pontos ou 2 linhas; confirmação obrigatória; **variação > 8% bloqueia**; sem
     correção de perspectiva.
   - ROI retangular/circular; vira a imagem ativa e a entrada da análise; alterar a ROI
     limpa marcações e bloqueia salvar até reanalisar; exterior da ROI circular mascarado;
     elipses que cruzam o limite excluídas; coordenadas exportadas referidas à imagem
     inteira, com transformações registradas.
   - Busca de ajuste: até 24 (+1) combinações, 30 s; pontos não são probabilidade;
     "cobertura" não é taxa de acerto; Rosin–Rammler só critério auxiliar; mínimo esperado
     (padrão 5 mm) só pontua com escala física independente e nunca apaga objetos.
   - Trincas: vermelho possível, amarelo revisão/inconclusivo, tracejado forma sem
     confiança; revisão manual por pelota com restaurar; reanalisar pode descartar
     revisões e deve avisar.
   - Detalhada: blocos ≤ 640×640 com ~35% de sobreposição, orçamento ~8 MP com amostragem
     em potência de dois, 6.000 propostas, 3 min; cancelamento ou limite mantém a análise
     anterior; contornos reajustados e trincas analisadas em pixels originais.
   - FSRCNN 2×: experimental, desmarcado por padrão, blocos 320×320, pixels reconstruídos
     nunca usados para trincas; propostas sem correspondência recebem alerta.
3. **Honestidade nos textos e relatórios.** Não declarar precisão, validação ou ganho que
   não foi medido. Separar: implementado, testado em software, demonstrado em imagem de
   verificação, validado com amostra física (este último ainda não existe).
4. **Saídas compatíveis com o Android.** CSV por pelota com as mesmas 32 colunas
   (`ReportTables`), CSV do histograma idêntico, JSON com as **mesmas chaves** do
   `ResultSaver` sempre que o recurso existir no Windows (acrescente só o bloco
   `source.camera`). Original preservado byte a byte.
5. **Um item por commit**, mensagem em português, terminando com as linhas de atribuição
   exigidas pela sessão.

## O que falta implementar (em ordem de prioridade)

Itens já existentes no Windows 0.1.0: câmera ao vivo + captura, abrir imagem,
busca automática, detecção + elipses, triagem de trincas (modelo RF), escala px/mm
digitada ou referência por dois cliques, histograma com classe configurável,
D10/D50/D90, forma b/a, números, exportação PNG/CSV/JSON básica.

| # | Item | Núcleo já pronto | Falta no Windows |
|---|---|---|---|
| 1 | **ROI retangular e circular** | `InterestRegion`, `AnalysisWindow` | editor por mouse (arrastar, redimensionar, mover), "Imagem inteira", ROI como imagem ativa, invalidação/bloqueio de salvar, máscara circular, exclusão de elipses no limite |
| 2 | **Calibração por objeto selecionado** | `ObjectCalibration` | clique numa pelota/esfera, escolha eixo maior/menor/esfera, medida em mm, validação do contorno, `REF`, exclusão editável, invalidação após reanálise, "Editar referência selecionada" |
| 3 | **Calibração pela largura da correia** | `BeltCalibration`, `BeltEdgeDetector` | "Sugerir bordas", editor de 4 pontos/2 linhas, largura real, confirmação, bloqueio > 8%, aviso de limites |
| 4 | **Revisão de trincas por pelota** | `CrackAnalysis.review/restore` | clique na pelota → diálogo "Pelota N" (possível / sem indicação / inconclusivo / restaurar automático, e calibrar por esta pelota), contagem de revisadas, **reanálise só das trincas** com nova sensibilidade sem refazer contornos (aviso de perda das revisões) |
| 5 | **Comparação de ajustes** | `DetectionTuner.Report` | tabela com todas as opções (ajuste, propostas, válidas, pontos, métricas), aplicar outra opção, botão "Buscar melhor ajuste" sob demanda, aviso de sugestão fraca, campo "mínimo esperado (mm)" |
| 6 | **Escala por média conhecida** | `Granulometry.Settings.estimatedMean` | opção no painel de escala, marcada como hipótese em tela, PNG e JSON |
| 7 | **Relatório equivalente** | `ReportTables` (CSV) | PNGs do Android (ROI limpa, ROI marcada, resumo, histograma; layout de `PhotoComposer`/`ReportViewport`), JSON com as chaves do `ResultSaver` (`region_of_interest`, `processing_scope`, `report_viewport`, `detection_suggestion` com `trials`, `cracks`, `physical_scale` com referência, `shape_analysis`, `detailed_analysis`) |
| 8 | **Segmentação detalhada 0.11.0** | `DetailedAnalysis`, `TilePlan`, `DetailGeometry` | leitura da região no original (equivalente a `OriginalRoi`), opções e progresso, cancelamento preservando o anterior, resolução efetiva e amostragem na tela e no JSON, tela de comparação de tratamentos |
| 9 | **FSRCNN 2× opcional** | `FsrcnnUpscaler` | caixa experimental (desmarcada), carregar `modelos/FSRCNN_x2_features.pb`, registro no JSON, avisos |
| 10 | **Detalhes de forma** | `ShapeStatistics` | diálogo "Forma projetada" com n incluído/excluído e critério |
| 11 | **Webcam: robustez** | — | lista de câmeras com nome (não só índice), reabrir após desconexão, escolher backend (DSHOW/MSMF), registrar ajustes do driver no JSON a cada captura, captura média de N quadros opcional (reduz ruído) |
| 12 | **Distribuição** | — | instalador MSI opcional (`jpackage --type msi`, WiX) mantendo o .zip portátil; ícone; versão no título |

Não se aplicam ao Windows: lanterna ("Luz") e toque para focar (substituídos por
"Ajustes do driver" e pelo travamento de foco/exposição).

## Como trabalhar (para gastar menos tempo)

1. **Antes de qualquer código:** `git pull`; rode `tools/ci_status.sh` para ver o estado;
   leia este arquivo, `desktop/` inteiro (é pequeno) e, para o item da vez, o método
   correspondente em `MainActivity.java`, o teste Android correspondente e a pasta de
   `verification/` daquela versão (VALIDACAO.md). Não leia os 160 MB de `verification/`
   inteiros; use `ls` e leia só o necessário.
2. **Planeje por item** em 3–5 linhas: classes do núcleo usadas, telas novas, dados no
   JSON, teste que prova o comportamento. Crie uma lista de tarefas com um item por linha
   da tabela e marque conforme concluir.
3. **Implemente reaproveitando o núcleo.** A interface Swing só coleta entradas e
   desenha; regras ficam no núcleo. Se uma regra estiver presa em classe Android, extraia
   para `core/` (regra 1).
4. **Teste em três camadas:**
   - teste JUnit no `desktop/` usando as imagens de `verification/` e, quando houver,
     os resultados registrados do Android (ex.: `belt-0.9.0/analysis.json`,
     `detail-0.11.0/analysis.json`) — compare contagens, escalas e campos;
   - lógica de interface separada em classes testáveis sem tela (ex.: modelo do editor de
     ROI com coordenadas), testadas em modo headless;
   - `--autoteste` do pacote montado (amplie para cobrir o recurso, ex.: ROI e escala).
   Registre resultados com `build/parity/summary.txt` (já vira anotação no CI).
5. **Ciclo de envio:** compilação local com `javac` → commit → push → `tools/ci_status.sh`
   nas duas execuções (Android e Windows) → corrigir até verde. Só então o próximo item.
6. **Versões:** cada item concluído sobe o Windows (`desktop/build.gradle` e
   `DesktopApp.VERSION`): 0.2.0, 0.3.0… Ao final de um bloco útil (ex.: itens 1–4),
   dispare `windows.yml` com `publicar=true` para gerar a Release. Se algum código do
   `app/` mudar de comportamento, o Android sobe para 0.11.1 / versionCode 15 antes de
   qualquer Release Android.
7. **Atualize** `desktop/LEIA-ME.txt` (o que funciona e limites) e a seção do README a cada
   versão publicada.

## Entrega esperada ao final

- Itens 1 a 7 implementados e publicados numa Release do Windows (prioridade máxima,
  porque viabilizam o uso real com webcam). Itens 8 a 12 na sequência, se houver tempo.
- Para cada item: o que foi feito, teste que prova, resultado do CI (testes Android e
  Windows, lint, assinatura) e o que **não** foi possível verificar (ex.: interface com
  webcam real, que o CI não tem).
- Lista curta do que precisa do Ricardo: testes com webcam real, fotos com referência
  física, decisões pendentes.

Se encontrar uma regra ambígua entre Android e Windows, siga o Android e registre a
dúvida no relatório final em vez de parar o trabalho.
