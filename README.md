# PelotaVision — Visão computacional para análise de pelotas de minério de ferro

**Android e Windows · Granulometria · Inspeção de trincas · Análise de imagens**

O **PelotaVision** é um software de pesquisa e desenvolvimento para análise de fotografias de pelotas de minério de ferro. Combina processamento de imagens, segmentação de objetos e métodos de aprendizado de máquina para apoiar a caracterização granulométrica e a inspeção visual.

> **Estado de desenvolvimento:** funcionalidades e modelos podem ser experimentais. Contagens, diâmetros e classificações não substituem ensaios de referência ou validação metrológica em condições reais de operação.

## Principais recursos

- **Detecção e segmentação individual:** métodos clássicos de bordas e LoG, além de alternativas neurais experimentais, com parâmetros ajustáveis para cenas de bancada e correia.
- **Granulometria:** estimativa de dimensões projetadas, distribuição de tamanhos, histogramas e sobreposição de cores translúcidas por faixa granulométrica.
- **Inspeção de trincas:** identificação assistida de possíveis trincas, revisão visual e distribuição granulométrica específica das pelotas classificadas como trincadas.
- **Calibração:** escala em pixel/mm, referência dimensional, largura conhecida da correia e diâmetro interno de recipiente redondo com borda detectada automaticamente ([detalhes](docs/calibracao-recipiente.md)), sempre com confirmação do usuário.
- **ROI:** seleção e processamento de região de interesse, preservando a fotografia original.
- **Qualidade de imagem:** ajustes de contraste e iluminação, análise detalhada em blocos e super-resolução FSRCNN 2× opcional e experimental.
- **Rastreabilidade:** exportações e registros de configurações, parâmetros, medidas e evidências visuais, conforme o suporte de cada versão.
- **Plataformas:** aplicativo Android e aplicação Windows, com fluxos de captura, importação, análise e exportação.

## Fluxo básico

1. Capture ou importe uma fotografia e selecione a cena adequada.
2. Defina a escala física e a região de interesse.
3. Ajuste ou busque parâmetros de detecção e execute a segmentação.
4. Inspecione os contornos, as possíveis trincas e os histogramas.
5. Revise os resultados e exporte os dados e imagens disponíveis na versão instalada.

## Limitações e validação

Acurácia de segmentação, granulometria e detecção de trincas deve ser medida em **fotografias reais reservadas para teste**, com anotações independentes e, para dimensões, medições físicas de referência. Modelos treinados em imagens sintéticas ou conjuntos de demonstração **não estão automaticamente validados para uso industrial**. Super-resolução pode modificar a aparência sem melhorar a exatidão dimensional. Fotografias escuras, oclusões, perspectiva e iluminação heterogênea podem comprometer as estimativas.

## Instalação e documentação

Consulte os artefatos das execuções em **GitHub Actions** e as instruções de compilação abaixo. A disponibilidade de APK, executável Windows e PDF depende da versão e do resultado dos respectivos builds. Não se deve presumir que uma funcionalidade esteja distribuída apenas por ter sido desenvolvida em uma branch.

---

<details>
<summary><strong>Documentação técnica e histórico de versões</strong></summary>

# PelotaVision — Android 0.14.1 · Windows 0.5.0

Análise de imagens de pelotas de minério de ferro: localização e ajuste elíptico, forma 2D b/a, granulometria por número, mapa granulométrico e triagem de possíveis trincas. Android (ARM64) e Windows 11 compartilham o mesmo núcleo em `core/`.

**Estado:** protótipo de P&D. Nenhuma versão foi validada em fotos da usina com verdade-terreno nem testada em telefone físico; os números abaixo servem ao desenvolvimento, não à metrologia.

Releases: `v0.14.0-build34` (APK) e `windows-v0.5.0-build33` (pacote portátil).

## Novo na 0.15.2 (Android) / Windows 0.5.2

- **Relatório PDF com figuras individuais**: original, **filtrada** (só quando há realce de iluminação), segmentada, mapa granulométrico e resumo + histograma, cada uma com título e legenda próprios, duas por página quando cabem. Os painéis são recortados do `_resultado.png`, pixel a pixel iguais a ele.
- **Filtro de iluminação visível**: com realce aplicado (automático ou manual em "Luz"), a tela, a ROI ampliada, a imagem segmentada e o mapa mostram a imagem filtrada; a imagem original continua no painel 1 e no `_original`. Nova saída `_filtrada.png`; JSON: bloco aditivo `report_images` (`filtered_file`, `segmented_base`, `map_base`). Elipses, diâmetros e trincas continuam medidos nos pixels **originais**.
- versionCode 22.
- **Windows 0.5.2 — paridade com o Android**: segmentação **detalhada** em blocos nos pixels originais da ROI (comparação de contraste local e ruído leve, pré-ajuste num bloco, FSRCNN 2× opcional; resumo dos blocos; `detailed_analysis` no JSON); **escolha manual do realce de imagem escura** com prévia ("Luz / imagem escura…"); **reconexão automática da webcam** (até ~30 s, sem capturar quadro velho); LEIA-ME do pacote atualizado.
- **Windows 0.5.2**: passa a gerar o mesmo relatório PDF (`_relatorio.pdf` na pasta escolhida), com o mesmo conteúdo do Android — textos, tabelas e legendas vêm de `ReportPdfContent`, no núcleo comum; o desenho usa Apache PDFBox 3 (Apache 2.0). O Windows também passa a registrar o bloco `low_light` (recuperação automática na ROI) e, quando houve realce, mostra a imagem filtrada na tela, na segmentação e no mapa, e grava `_filtrada.png`.

## Novo na 0.15.1 (Android; núcleo comum também no Windows)

- **Mapa granulométrico mais evidente**: opacidade padrão 55% (antes 25%), máximo 90%; contorno de cada pelota mais opaco (+30 p.p.) para separar pelotas encostadas; com ≥ 20 pelotas a escala relativa usa **D10–D90** (abaixo = azul, acima = vermelho), em vez de mínimo–máximo, que poucos extremos esticavam; rótulos de diâmetro omitidos acima de 250 pelotas. A preferência de opacidade foi renomeada para que o novo padrão valha também para quem já tinha 25% salvo. JSON: `scale_basis`, `rim_opacity`.

## Novo na 0.15.0 (Android)

- **Relatório PDF** (`_relatorio.pdf` em Downloads / PelotaVision), gerado a partir do mesmo JSON salvo: resumo (contagem, média, D10/D50/D90, escala, trincas, ROI), bloco de **iluminação e tratamento de imagem escura**, método e modelos, tabela de classes, limitações, imagem de resultado e mapa granulométrico. Falha no PDF não impede a gravação dos demais arquivos (`pdf_error` no JSON).
- **Imagem escura**: inclui a visualização e a escolha manual do realce em "Luz" (PR #14) sobre a recuperação adaptativa de ROI da 0.14.2. O JSON ganha o bloco aditivo `low_light` (seleção automática/manual, modo, nível médio de cinza, contraste, saturação e escores heurísticos).
- versionCode 20. Windows continua na 0.5.1, ainda sem PDF.

## Novo na 0.14.0 / Windows 0.5.0
- **Histograma todas × trincadas:** série laranja sobreposta (mesmas classes e eixos), com modos absoluto, % do total e condicional. Sem classificação, o app mostra "classificação indisponível", nunca zero.
- **Mapa granulométrico reescrito no núcleo:** colore só as elipses incluídas no histograma; opacidade 0–70% (padrão 25%), escala relativa ou fixa em mm, rótulos de diâmetro sem sobreposição, legenda contínua. Agora também no Windows e exportado como `_mapa.png`.
- **Seletor "Modelos de análise":** segmentação Clássica (Bordas/LoG) ou Neural (StarDist); classificador de trincas RF (padrão), **MobileNetV3-Small (experimental)** ou Comparação. Trocar o classificador refaz a triagem.
- **MobileNetV3-Small:** treinada no CI, ONNX via OpenCV DNN. No único holdout real, AUC 0,96 contra 0,49 do RF, mas com 1 foto e 7 trincas. **O RF continua o padrão** até haver validação em fotos independentes.
- **Exportação:** 6 arquivos por análise; JSON com `report_schema_version: 2` (campos só aditivos); CSV de granulometria ganha `count_cracked;percent_cracked_of_all` no fim.
- **YOLO11-seg não implementado:** faltam máscaras com rótulo humano e a licença AGPL-3.0 exige revisão jurídica.

**Android 0.14.1:** a câmera pede a maior resolução JPEG do sensor traseiro, modo de qualidade máxima e JPEG 95%, e mostra a resolução efetiva da última foto (toque no rótulo para detalhes). A prévia e a análise ao vivo continuam em 640×480.

Detalhes, métricas, licenças e limitações: [`docs/redes-neurais-mapa-histograma.md`](docs/redes-neurais-mapa-histograma.md). Capturas: `verification/visuals-0.14.0/`.

## Integração 0.13.0
Reuniu num único build:
- Rede neural StarDist e cena Bancada/Correia (PR #1, antes 0.12.0);
- Mapa granulométrico colorido, botão **Mapa** no Android (PR #7);
- Correção da circularidade após escala anisotrópica e autoteste Windows mais rígido (PR #3);
- Importador de datasets externos com bloqueio de licença e pipeline de recortes de trincas (PR #6, sem alterar o modelo do app).

Continuam fora da `main`: trincas sintéticas (PRs #2 e #4).

## Cena Bancada / Correia — novo na 0.12.0

O app passa a ter duas cenas:

- **Bancada:** é o padrão. Mantém exatamente os perfis, os limites e a lista de busca da 0.11.0.
- **Correia:** para fotos de câmera fixa sobre a correia, com pelotas pequenas e numerosas.

No Android, a escolha fica em Ajustes de detecção → Cena. No Windows, fica no seletor "Cena".

O que muda na **Correia**:

- **Perfis de raio:** de 0,4% a 1,6% da largura da foto (Finas, Médias, Grossas e Auto).
- **Até 5000 objetos por foto.** A bancada continua com 600.
- **Resolução:** o trabalho usa até 1600 px, e o ajuste elíptico amplia a imagem até 2× para que pelotas de 5 a 10 px tenham borda suficiente.
- **Ajustes internos:** as bandas Hough e as escalas da rede são próprias da correia, e a evidência da busca é calculada em 1024 px.
- **Modo detalhado indisponível.** Ele mede os perfis pelo bloco, não pela foto.
- **Triagem de trincas:** fica inconclusiva por construção, porque o recorte da pelota tem menos de 20 px.
- **Pelotas encobertas:** são ajustadas só pela borda visível.
- **JSON:** ganha o bloco `scene` com todos esses parâmetros.

**Primeira execução nas 371 fotos reais de correia**, em `ml_training/data_belt`, sem rótulos e no CI do Windows. Os números abaixo são contagens de 8 quadros de discos diferentes, sem verdade-terreno:

- **LoG:** passou de 1 a 309 objetos na bancada para 1000 a 2500 na correia. O raio mediano ficou coerente com o espaçamento entre pelotas.
- **Bordas:** encontra poucas pelotas (40 a 150), porque o Hough não lida bem com empacotamento denso.
- **Rede de demonstração:** chega a 1300 a 3300 objetos, mas é lenta e **marca cerca de 1100 objetos numa correia vazia**. Ela nunca viu esse tipo de foto, então não use a rede na correia antes de treinar com dados de correia rotulados.

Por ora, o método recomendado para correia é o **LoG**.

## Rede neural StarDist — novo na 0.12.0 (experimental)

Há um terceiro método de localização, ao lado de **Bordas** e **LoG**: a **Rede neural (StarDist)**. No Android, fica em Ajustes de detecção → Método de localização. No Windows, fica no seletor "Metodo".

A rede prevê cada pelota como um polígono estrelado de 32 raios. Esse formato separa bem pelotas que se tocam. A rede só **propõe** os objetos. A elipse final continua medida nas bordas da foto, com os mesmos gates, e trincas, calibração, CSV e PNG não mudaram.

O JSON ganha o bloco `neural_model`, que registra:

- id e SHA-256 do modelo;
- limiar usado;
- descrição do treino;
- validação com cenas fora do treino;
- `validated_on_plant_photos:false`.

A busca automática de ajuste **só inclui a rede quando ela é o método escolhido**. Com Bordas ou LoG, o comportamento da 0.11.0 é preservado.

**O modelo distribuído é de demonstração.** Foi treinado nas 7 cenas provisórias de `ml_training/data`, não em fotos da usina. A comparação abaixo foi medida no CI do Windows, com o núcleo Java real e OpenCV 4.12, no perfil Auto com sensibilidade 75. A referência são os círculos provisórios, então os números servem ao desenvolvimento, não à metrologia.

| Cena fora do treino | Bordas F1 | LoG F1 | Rede F1 |
|---|---|---|---|
| cluster (34 pelotas encostadas) | 0,97 | 0,39 | 1,00 |
| paired40 (40 pelotas, outro fundo) | 0,98 | 0,11 | 0,98 |

Nessas duas cenas, a rede empata com Bordas. Isso **não** demonstra superioridade. O LoG fica em desvantagem porque foi travado no perfil Auto, enquanto a busca do app normalmente escolheria outro perfil para ele. O ganho real só pode ser medido com fotos rotuladas da usina.

O retreino é reproduzível: localmente, ou pelo GitHub Actions ("StarDist - treinar modelo de pelotas"), que grava o modelo de volta no branch. A paridade Java × Python é verificada no CI: 30/30 objetos, com desvio de centro abaixo de 0,001 px. Detalhes, formato de anotação por contorno e próximos passos estão em `ml_training/stardist/README.md`.

## Segmentação detalhada — novo na 0.11.0

Depois de abrir/fotografar e aplicar a ROI, toque em **Segmentar ROI → Detalhada**. Em imagem inteira, o botão é **Segmentar foto**. O diálogo permite comparar os pixels originais com contraste local (CLAHE suave) e redução leve de ruído (bilateral), e buscar previamente os perfis de detecção em um bloco original representativo, na mesma resolução dos blocos da análise. **Rápida** mantém o fluxo anterior. Ao escolher detalhada, **Buscar ajuste na ROI** e a reanálise manual passam a aplicar esse modo na mesma foto. Abrir outra foto começa pelo fluxo rápido, antes de uma seleção explícita.

O modo detalhado recupera a região diretamente do arquivo original, antes da redução de exibição. Não amplia uma cópia já reduzida para simular resolução. A leitura regional respeita as oito orientações EXIF, incluindo espelhamento. A ROI continua editável na foto inteira, mas o processamento e o relatório usam a área ativa. Para proteger memória, a área decodificada é limitada a aproximadamente 8 MP por uma amostragem inteira de potência de dois; a resolução usada e a amostragem ficam visíveis e no JSON. Acima desse orçamento não se promete resolução nativa completa. Reduzir a ROI pode permitir recuperar mais detalhe real.

O algoritmo usa blocos de até 640×640 pixels da região original, com sobreposição de aproximadamente 35%; os últimos blocos são ancorados ao limite, mantendo a mesma largura. Os perfis de raios são relativos à largura de cada bloco, não à foto inteira. Cada centro pertence à faixa delimitada pelos pontos médios dos centros vizinhos. Uma supressão geométrica adicional remove propostas quase coincidentes, sem somar duas vezes os objetos da sobreposição. Os blocos não são redimensionados para o detector normal: ficam abaixo dos limites de 800/1024 pixels. Contornos de tratamento são reajustados com os pixels originais, e todas as trincas são classificadas/localizadas na região original em cinza. A seleção de tratamento usa o mesmo tipo de evidência heurística de cobertura e geometria; não é uma avaliação de acurácia.

### Super-resolução opcional

**Testar super-resolução FSRCNN 2× (experimental)** vem desmarcado. O modelo embarcado é o FSRCNN geral de Saafke/OpenCV, pré-treinado em T91/General100, sem treinamento nas pelotas desta aplicação. Não é DLSS, não usa nuvem e não foi validado para medição granulométrica. Licença, modelo de origem, hashes e adaptação estão em `third_party/FSRCNN`.

Com SR habilitada, blocos de 320×320 são ampliados 2×, permanecendo abaixo dos limites internos do detector. A proposta de elipse é convertida de volta e reajustada na imagem original. Propostas de SR sem correspondência com a análise original recebem alerta de revisão. A imagem reconstruída nunca é usada para inferir trincas. A SR concorre com a análise sem tratamento e só é escolhida onde sua pontuação superar a alternativa corrente por mais de 2 pontos. Portanto, marcar a opção não significa que SR será usada em todos os blocos. A comparação informa quantos blocos escolheram cada tratamento.

A contagem de referência **Sem tratamento** da comparação é obtida com o mesmo plano de blocos daquela execução. Como o plano de SR usa blocos menores, sua contagem não deve ser interpretada como ganho da rede quando comparada com uma execução em blocos de 640. Mais marcações podem ser finos, poros ou divisões indevidas. O ganho de acurácia e de repetibilidade ainda exige fotos reais com contagens e medidas de referência.

### Uso e rastreabilidade

1. Calibre pela correia, por px/mm ou por referência escolhida; aplique a ROI.
2. Abra **Segmentar ROI**, mantenha a comparação de tratamentos e, se desejar, a busca de configuração. Ative SR somente para ensaio.
3. Toque **Detalhada** e acompanhe os blocos. O pré-ajuste usa o orçamento anterior de 30 s; os blocos têm orçamento de 3 minutos, verificado entre etapas. Operações nativas em andamento não são interrompidas no meio.
4. Cancelar ou atingir um limite mantém a análise anterior; um resultado parcial não é apresentado como relatório completo. Há limites de quantidade de blocos e 6.000 propostas coletadas; uma ROI excessiva pede redução da área.
5. Toque em **Detalhada · ver comparação** para conferir resolução, amostragem, blocos, configuração e contagens por tratamento. A original de entrada continua preservada.
6. Confira elipses/trincas e salve. O JSON acrescenta `detailed_analysis`: dimensões de entrada, mapeamento afim para a prévia, amostragem, blocos, tratamentos escolhidos, SR testada e uso de pixels originais para contornos/trincas. O CSV continua com 32 colunas e as medidas usam a mesma referência física. A calibração pela correia/px-mm permanece independente do zoom. Se uma referência de objeto interna mudar de contorno na reanálise, a escala baseada nela é removida com aviso, como no fluxo anterior; selecione novamente essa referência.

O modo detalhado muda propostas e pode descartar revisões manuais de trincas ao reanalisar. A calibração posterior altera as medidas, mas não refaz automaticamente as escolhas de tratamento dos blocos; reexecute para aplicar o novo contexto físico à seleção heurística. A forma continua sendo a razão projetada 2D b/a. Não se afirma ganho de acurácia a partir da aparência da imagem ou da pontuação automática. Evidências atuais em `verification/detail-0.11.0`.

A área de interesse passa a ser a **imagem ativa na tela e a entrada efetiva da análise**, mantendo a fotografia inteira para editar a seleção e calibrar. Depois de aplicar uma ROI retangular ou circular, somente o recorte fica visível. Os comandos **Segmentar ROI** e **Buscar ajuste na ROI** processam esse recorte: iluminação, parâmetros relativos ao tamanho da imagem, contornos, trincas e comparação de ajustes deixam de usar a região descartada. O relatório usa a mesma ROI. O RF de trincas é o mesmo da 0.5.0; não houve novo treinamento.

## Instalação

Android 10 ou superior, ARM64. Extraia o ZIP de instalação e abra o APK. O pacote e o certificado de desenvolvimento continuam os mesmos, permitindo atualizar instalações a partir da 0.11.0. O projeto inclui também a compilação x86_64 para testes. Esta entrega não atualiza o iPhone.

## Fluxo recomendado: correia, ROI e segmentação

1. Fotografe ou abra a foto inteira, com as duas bordas da correia visíveis, incluindo as faixas sem material.
2. Em **Escala / mm → Calibrar pela largura da correia**, informe a largura real em mm. Confira a proposta de linhas e arraste seus quatro pontos ou as próprias linhas para corrigir as bordas. Não use a largura do leito de material.
3. Marque **Conferi as bordas e a largura real da correia** e aplique. O editor de ROI será aberto. A medida é sempre informada e confirmada pela pessoa.
4. Escolha **Retângulo** ou **Círculo**. Arraste as alças para ajustar lados/cantos, arraste dentro para mover e use dois dedos para redimensionar. **Visualizar ROI com zoom** permite inspecionar; não redefine a área analisada.
5. Toque em **Aplicar**. O aplicativo mostra somente a ROI, enquadrada na área da imagem. O exterior de uma ROI circular fica mascarado. A seleção não é destrutiva: **Área → Imagem inteira** recupera a fotografia.
6. Escolha **Segmentar ROI** para usar o ajuste atual, ou **Buscar ajuste na ROI** para comparar perfis/sensibilidades de acordo com a iluminação do recorte. O comando **Ajustes → Aplicar manual** e a reanálise de trincas também passam a usar a ROI. Ao voltar para a imagem inteira após analisar um recorte, segmente a foto novamente.
7. Confira as elipses e os alertas. Toque numa pelota para revisar a trinca ou informar uma medida de referência. Sem escala física, o histograma permanece em pixels; a classe em mm não define a escala.
8. Toque em **Salvar relatório**. O PNG contém a ROI sem marcações, a ROI com elipses/possíveis trincas e o histograma. A foto inteira de entrada é salva separadamente com `_original`, byte a byte.

Ao alterar a ROI, as marcações e estatísticas anteriores deixam de aparecer; o botão de salvar fica bloqueado até analisar a área ativa. Reaplicar a mesma seleção preserva o resultado. Se uma análise falhar, a foto, o recorte e a calibração existentes ficam disponíveis para tentar novamente.

## Escala, coordenadas e referências

O recorte de entrada é feito em **1:1 pixel**, sem redimensionamento. A ampliação na tela é só de apresentação. As coordenadas dos resultados e do CSV são traduzidas para a foto inteira; os eixos e áreas das elipses não são multiplicados pelo zoom. A calibração pela correia ou por px/mm original usa as dimensões originais, inclusive quando as bordas já não aparecem na ROI. Uma referência de objeto que ficou fora da área analisada permanece arquivada e pode ser editada em **Escala / mm → Editar referência selecionada**. Ela não é vinculada a outra pelota por número. Se a referência estiver dentro da nova análise e seu contorno mudar, a escala baseada nela é removida e o app pede nova seleção.

O JSON inclui `processing_scope` com dimensões reais da entrada, deslocamento local→foto inteira e confirmação de que o recorte não altera a escala. `report_viewport` registra a transformação para os dois painéis do PNG. `detection_suggestion.input_scope` informa em qual entrada a busca ocorreu. Na ROI circular, pixels externos são substituídos na entrada do algoritmo pela mediana calculada somente dos pixels internos; o exterior original não participa da iluminação ou das trincas. Apenas elipses inteiras dentro da ROI entram no resultado. Um recorte quadrado do RF que atravesse a máscara circular fica inconclusivo.

A sugestão usa os mesmos métodos de bordas e LoG, com correção de iluminação e escore de cobertura. Não há estimativa física automática da largura da correia, nem tamanhos forçados para uma média. O mínimo esperado de 5 mm só influencia a pontuação quando existe uma escala física independente, informada pelo usuário.

**Limites mantidos:** referência no mesmo plano, trecho plano e câmera aproximadamente perpendicular. Não há retificação de perspectiva nem compensação de altura do leito, lente ou abaulamento. Variação de largura marcada superior a 8% bloqueia a calibração pela correia; esse limite não é incerteza metrológica validada. Pelotas pequenas/desfocadas podem ficar inconclusivas, e trinca não indicada não comprova ausência de defeito. O relatório é uma medida projetada por número, não um ensaio de peneiramento. Não houve teste desta versão em telefone físico nem validação industrial.

## Estrutura multiplataforma (a partir de 24/10/2026)

| Pasta | Conteúdo |
|---|---|
| `core/` | Núcleo de análise comum (Java puro + OpenCV): detecção, elipses, sugestão de ajuste, trincas, calibração, granulometria e tabelas CSV (`ReportTables`). Sem dependência de Android. |
| `app/` | Android. Compila `core/` junto (`sourceSets`), mantendo pacote, versão e assinatura. |
| `desktop/` | Windows 11 (Java Swing + OpenCV oficial 4.12.0 x64), webcam e arquivos. Build independente: `gradlew -p desktop test jar -PopencvJava=C:/opencv/build/java`. |

Workflows principais: `android.yml` (APK assinado) e `windows.yml` (pacote portátil .zip, testes do núcleo com OpenCV nativo e autoteste do pacote montado). Releases publicadas por *Run workflow* com "publicar" marcado. Auxiliares: `stardist-train.yml` e `mobilenet-cracks.yml` (treino no CI), `crack-pipeline.yml`, `visual-captures.yml` (capturas de tela) e `import-external-pellets.yml` (importação manual com revisão de licença).

## Pipeline experimental de trincas

`tools/cracks/` e `tools/train_cracks.py` fazem recorte por pelota, manifesto com rótulos `cracked`/`intact`/`uncertain`, sintéticos apenas no treino, validação agrupada por foto e comparação com o RF embarcado, com paridade PVF1 Python × `CrackModel.java`. A auditoria de fontes externas está em `docs/datasets/fontes.json`. Todas estão bloqueadas, e o importador recusa fontes não verificadas. Os resultados de 25/10/2026 estão em `verification/cracks-benchmark-20261025/RELATORIO.md`. O RF 0.5.0 continua o classificador padrão do app. Desde a 0.14.0, a MobileNetV3-Small (`tools/cracks/train_mobilenet.py`, workflow `mobilenet-cracks.yml`) está disponível como opção experimental; nenhum candidato demonstrou melhoria em fotos reais independentes. Testes: `python -m pytest tools/tests -q`.

## Projeto e compilação

Java 17, Gradle 8.9, Android Gradle Plugin 8.7.3, SDK/build-tools 35. As dependências continuam em `app/build.gradle`. Configure o SDK em `local.properties` ou no Android Studio. Execute:

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

O modelo e todos os arquivos de produção estão em `app/src/main`. `ml_training` e `verification` incluem material de treinamento e evidências históricas, que conservam os números e versões originais. Evidências da ROI ativa ficam em `verification/roi-active-0.10.0`; as da calibração pela correia permanecem em `verification/belt-0.9.0`.

## Funcionalidades mantidas da 0.8.1

A versão conserva busca inicial de ajustes, calibração por objeto escolhido, ajuste de elipses, forma aparente 2D b/a, revisão de trincas, classes fixas e exportação PNG/CSV/JSON. Uma foto não mede esfericidade 3D nem equivale a um ensaio de peneiramento.

## Sugestão inicial de detecção — correção 0.8.1

Ao abrir ou fotografar, a busca compara **quatro perfis × três sensibilidades (55/75/90%) × dois métodos (bordas e LoG)**, totalizando 24 combinações, ou 25 se o ajuste atual for diferente. A configuração atual vem primeiro. LoG médio com sensibilidade de 75% é testado logo a seguir, mesmo quando LoG não estava selecionado. Os métodos são intercalados, para que a restrição de tempo não deixe LoG sempre por último.

O problema da 0.8.0 foi demonstrado pelos prints enviados: o automático tinha menos marcações que o manual com LoG médio/74%. Havia duas limitações: LoG só entrava na busca se fosse o ajuste atual; a pontuação recompensava a proporção de elipses válidas e podia preferir uma seleção incompleta. Não bastava aumentar a sensibilidade ou escolher a maior contagem, porque algumas opções dividiam objetos e ultrapassavam 180 marcações.

**Critério novo:** a busca mede quanto da região visual de primeiro plano é explicada pela união das marcações, e quanto dessa área tem elipses válidas. Contornos duplicados não multiplicam cobertura. A qualidade das bordas é ponderada por área, para evitar premiar muitas elipses minúsculas em detrimento de pelotas maiores. Também são consideradas pureza de primeiro plano, estabilidade e sobreposições. Ajustes parciais/inconclusivos mantêm seus alertas; o algoritmo não os transforma artificialmente em elipses válidas nem os inclui indevidamente na esfericidade média.

A evidência de imagem usa uma cópia em cinza de até 384 px, suavização e limiar Otsu. Quando há uma tendência forte de iluminação, um plano de fundo estimado por quantis locais é subtraído antes do limiar; os indicadores dessa compensação são registrados no JSON. A polaridade claro/escuro vem do contraste entre interior e exterior de elipses confiáveis, com escolha de um resultado de apoio entre as opções. Baixo contraste, polaridade ambígua ou máscara quase vazia/cheia desativam esse critério; nesse caso usa-se a pontuação geométrica anterior, com aviso de pouca evidência quando aplicável. Não é uma segmentação verdadeira conhecida e pode confundir sombra com objeto. A cobertura percentual é **área visual explicada**, não acurácia nem percentual de pelotas corretamente identificadas.

Com evidência disponível, o escore soma 45×cobertura do primeiro plano + 25×cobertura por elipses válidas + 15×qualidade de ajuste ponderada por área + 10×concordância + 5×pureza + até 1 ponto por Rosin–Rammler, e desconta sobreposição, objetos válidos abaixo do mínimo quando calibrados e limite de candidatos. O resultado é limitado a 0–100. Não é probabilidade. As contagens não entram diretamente como prêmio nesse critério. Sem evidência suficiente, mantém-se o escore geométrico v1. Os pesos são experimentais.

**Rosin–Rammler/Weibull**, por número e com n≥12, continua sendo auxiliar. Não é normal e não altera diâmetros nem força uma curva. A comparação não pressupõe uma média de 12 mm. O mínimo esperado de **5 mm** continua configurável (0 desativa), e só pontua com escala física externa independente; escala estimada pela média não ativa o critério. Nenhum objeto é apagado por essa regra. O arquivo de teste recebido não tem escala física validada e foi comparado em pixels.

A busca mantém o orçamento de 30 s verificado entre opções, sem interromper uma operação JNI em andamento; o preparo das evidências e a análise de trincas podem acrescentar tempo. A interface mostra progresso e permite cancelar a busca usando o ajuste atual. A triagem de trincas roda somente na opção aplicada. A comparação é na foto parada; a câmera ao vivo usa um único ajuste. É possível desligar **Sugerir ajuste ao abrir ou fotografar** e usar **Aplicar manual**.

**Sugestão → comparação:** mostra método, perfil, sensibilidade, pontos, elipses válidas/total, cobertura visual, sobreposições e discrepância RR. Na 0.10.0, todos esses critérios são calculados no recorte ativo. Alterar a ROI invalida a comparação antiga e exige segmentação ou busca explícita. Alterar apenas a calibração recalcula a recomendação no mesmo conjunto de opções, sem trocar contornos automaticamente. A referência excluída é localizada por sua geometria arquivada, nunca por um índice que possa apontar para outra pelota após o recorte. A área dessa referência não entra na pontuação. Trocar ajuste reanalisa trincas e descarta as revisões manuais.

**Rastreabilidade:** JSON registra `photo_setting_search_coverage_v2`, opções aplicadas/recomendadas, parâmetros, cobertura total/válida, pureza, qualidade ponderada, resolução/limiar/polaridade da evidência e hipóteses de escala. CSV mantém 32 colunas. O detector, refinador elíptico, medidas, compositor PNG e classificador de trincas não foram retreinados nem modificados nesta correção.

**Arquivo enviado:** a reprodução no original de 1536×865 px obteve 68 marcações no automático 0.8.0, 122 no manual LoG médio/74% e 125 no novo automático LoG médio/90%. As 122 marcações do manual tiveram correspondência um a um no novo resultado; as três adicionais são inconclusivas, duas de borda. Isso é concordância com o ajuste de referência, **não verdade-terreno**. Os prints do celular mostram 80/124; são referências de uso e não devem ser confundidos com a reprodução no arquivo original aqui disponível. Outros arquivos, resoluções, codificações e versões de OpenCV podem produzir resultados diferentes.

A comparação visual e as evidências completas estão em `verification/coverage-0.8.1`. É necessário revisar as marcações e validar em celular físico e amostras reais.

## Calibrar tocando na referência

1. Fotografe ou abra a imagem e espere a identificação das pelotas. Se usar uma esfera padrão, coloque-a no mesmo plano e distância das pelotas, com boa visibilidade do contorno.
2. Toque na elipse da pelota que você consegue localizar fisicamente. No mesmo painel usado para revisar trincas, toque em **Calibrar**. Se o classificador estiver indisponível, o toque abre a calibração diretamente. O aplicativo nunca escolhe sozinho qual objeto é o padrão.
3. Na imagem ampliada, escolha **Pelota · eixo maior**, **Pelota · eixo menor** ou **Esfera padrão · diâmetro**. A linha amarela indica a dimensão. Meça com paquímetro o mesmo eixo e informe o valor em **mm** (aceita vírgula ou ponto). Na esfera, informe o diâmetro conhecido.
4. Confira a prévia de px/mm, eixos em mm, média e número de classes. Escolha a largura de classe (por exemplo, 1 mm) e toque em **Aplicar escala**. A escala de toda a foto e o histograma são recalculados imediatamente.
5. A referência recebe a marca **REF**. Toque nela para editar a medida. Em uma pelota incluída na amostra, o toque abre o painel de revisão, com o botão Calibrar; em uma referência excluída ou fora da ROI, abre diretamente a calibração.

A esfera padrão fica **excluída das estatísticas por padrão**. A pelota medida com paquímetro permanece incluída por padrão. O campo **Excluir referência das estatísticas** permite alterar essa escolha. A exclusão remove o objeto da contagem de pelotas, contagem de trincas, média de forma e histograma; o contorno REF continua visível. As revisões manuais ficam preservadas na lista completa e reaparecem se o objeto for reincluído. Não confunda um número renumerado na ROI com a identidade original da referência.

Só é possível calibrar por uma elipse inteira, ajustada e sem alerta. Contorno errado gera escala errada: confira a sobreposição antes de aplicar. O formulário amplia a referência, mas não oferece ajuste manual de sua borda nesta versão. **Cancelar** ou informar medida inválida mantém a escala anterior. A escolha da dimensão importa: para eixos de 12 e 10 mm, informar 12 mm como eixo maior não torna o diâmetro equivalente igual a 12 mm.

**Cálculo:** px/mm = dimensão da elipse na imagem original orientada / dimensão real informada. Para a esfera padrão, usa-se o diâmetro equivalente à área, √(eixo maior × eixo menor); os dois eixos são mostrados para conferência. Uma projeção alongada exibe uma observação. Para os eixos maior e menor, a elipse é mapeada de volta à imagem original antes da medição, inclusive se os fatores de redimensionamento horizontal e vertical diferirem. O histograma continua usando diâmetro equivalente à área e classes definidas em mm, sem forçar uma média ou faixa.

**Vínculo à foto:** a referência e sua escala não passam para outra imagem. Alterar a ROI conserva a escala e a marca REF, inclusive fora da área; os objetos da amostra são recalculados. Reanalisar a mesma foto conserva a calibração apenas quando o mesmo índice de objeto, dimensões e geometria permanecem idênticos; se o contorno mudar, a referência é removida e deve ser escolhida novamente. Não há busca automática de uma referência semelhante. Outra distância, zoom ou plano exige nova referência.

**Escala / mm** mantém as opções manuais e acrescenta **Usar objeto selecionado na foto** para mudar as classes/exclusões sem perder a referência. **Remover escala** elimina também a referência e sua exclusão, retornando ao histograma em pixels; as regras da ROI continuam valendo.

**Registro:** o PNG mantém original → imagem com marcações/REF → resumo da medida e escala → histograma. O JSON `physical_scale.selected_object` registra seleção pelo usuário, identificador único, data, índice da detecção na imagem completa, número exibido quando escolhida, tipo de medida, valor em mm, comprimento em pixels originais, px/mm, elipse original/analisada e exclusão. O CSV acrescenta seis colunas de calibração, totalizando 32; as 26 anteriores mantêm posição. Quando a referência estiver excluída, ela fica no bloco de calibração do JSON, sem aparecer como uma pelota da amostra.

Esta é uma **calibração de escala 2D por uma referência**, sem correção de perspectiva, distorção de lente ou diferenças de profundidade. A medida digitada não é verificada pelo aplicativo. Uma referência bem medida não corrige contornos detectados incorretamente nem garante equivalência com granulometria por peneiramento.

## Granulometria e ROI

Para elipses válidas, área projetada estimada = πab, raio equivalente = √(ab), diâmetro equivalente = 2√(ab). O histograma conserva classes configuráveis, inclusive 1 mm, e usa o diâmetro equivalente à área. Eixos individuais são registrados em pixels e em mm quando houver calibração. O campo de px/mm continua se referindo à imagem original orientada e o redimensionamento interno é corrigido. Não se força média de 12 mm nem faixa de 8–16 mm.

A ROI retangular exige a elipse inteira dentro dos limites usando suas extensões rotacionadas exatas. A ROI circular calcula a maior distância da borda da elipse ao centro da ROI, em vez de substituir a elipse por um círculo de raio equivalente. A ROI não recorta/redimensiona a imagem nem altera px/mm. Mudar a seleção atualiza a média de forma, a contagem e o histograma; as revisões de trincas são preservadas para os objetos que voltarem à seleção.

Objetos sem ajuste confiável retêm a estimativa anterior de localização/área e ficam marcados para revisão. Podem entrar no histograma se o usuário permitir objetos de revisão; nunca entram na média b/a. A escolha “Localização alternativa · LoG” muda a geração de candidatos, mas os marcadores finais também passam pelo ajuste elíptico.

## Método e limites

O detector anterior localiza candidatos com gradientes/Hough e CLAHE, com alternativa LoG. Para cenas esparsas, contornos de objetos isolados ajudam quando pelotas alongadas não geram candidatos circulares. Ao redor de cada candidato, o ajuste busca gradientes de borda em 96 direções, seleciona observações na vizinhança esperada e usa ajuste robusto de elipse com amostragem determinística, refinamento e gates de cobertura, resíduo e coerência de polaridade. Este método clássico não é segmentação neural (a opção neural é o StarDist, acima), não corrige perspectiva e não garante contorno correto em pelotas sobrepostas. Propostas circulares internas são apenas sementes de localização; a elipse final é medida a partir de bordas observadas.

Os gates incluem cobertura ≥55% das direções, pelo menos sete dos oito setores, resíduo normalizado RMS ≤0,055 e ≥70% de coerência de polaridade entre bordas usadas. São parâmetros experimentais, não uma probabilidade de acerto. Elipses muito alongadas, fora da faixa inicial, ou sem borda suficiente podem ficar inconclusivas.

O modelo de trincas usa o mesmo recorte circular de referência da versão 0.5.0, mesmo que a elipse desloque o centro ou altere a área. Isso preserva seus 72 atributos e o escore; os traçados são limitados ao interior da elipse. Elipses com b/a<0,65 ficam inconclusivas na triagem de trincas porque diferem muito do domínio de treinamento. Essa restrição do classificador não exclui uma elipse geometricamente válida da média de forma. As limitações do treino sintético e dos rótulos provisórios continuam; não houve validação na usina.

## Saída e dados

O PNG conserva a sequência: original sem marcações → imagem com elipses/trincas/ROI → resumo com média b/a e n → histograma (desde a 0.14.0, com a série de trincadas). O mapa granulométrico sai em `_mapa.png`, separado. O arquivo original separado permanece inalterado. CSV e JSON preservam os campos anteriores e acrescentam eixos maior/menor, orientação, b/a, validade do ajuste, inclusão na média, eixos em mm e indicadores de ajuste. Valores de forma sem ajuste são vazios/null, não 1.

O JSON `shape_analysis` registra fórmula, média, população, exclusões e `true_3d_sphericity:false`. O campo `radius_px` é o raio equivalente à área; não é o semieixo maior. `ellipse_major_axis_px` e `ellipse_minor_axis_px` são diâmetros completos. A orientação é a do semieixo maior em graus [0,180), a partir do eixo x da foto, no sentido horário em coordenadas com y crescente para baixo. A circularidade `roundness` de elipses ajustadas é derivada do perímetro aproximado da elipse, não do perímetro real segmentado.

## Compilar e verificar

JDK 17, SDK Platform 35, Build Tools 35.0.0, Gradle 8.9, AGP 8.7.3, OpenCV Android 4.12.0. `./gradlew assembleDebug testDebugUnitTest lintDebug`.

A chave de desenvolvimento (alias androiddebugkey) mantém compatibilidade de atualização com a 0.11.0. Ela **não fica no repositório**: para compilar localmente, copie-a para `signing/prototype.keystore` (ignorado pelo git). Não é chave de publicação em produção.

### Compilação automática (GitHub Actions)

Cada envio para `main` executa `.github/workflows/android.yml`: `assembleDebug testDebugUnitTest lintDebug`, conferência do certificado do APK (SHA-256 `0D77D291…D66B38`) e publicação do APK e dos relatórios como artefatos da execução. Segredos necessários em *Settings → Secrets and variables → Actions*: `PV_KEYSTORE_BASE64` (keystore em base64), `PV_STORE_PASSWORD`, `PV_KEY_PASSWORD`, `PV_KEY_ALIAS`. Sem o segredo da chave, o APK é gerado com chave temporária e não atualiza instalações existentes. O projeto gera APKs ARM64 e x86_64, mas a entrega é ARM64.

Verificações da sugestão: `verification/autotune-0.8.0`. Verificações da calibração: `verification/reference-0.7.0`; histórico geométrico: `verification/ellipse-0.6.0`. Ensaios analíticos verificam eixos, razão e ângulo em 24 elipses de contraste/polaridade/orientação conhecidos; também testam região uniforme e objeto isolado alongado. Testes Android simulados cobrem ROI rotacionada/circular, média, exclusões, calibração de eixos, CSV/JSON e PNG sem alteração da original. Os relatórios PNG foram desenhados pelo compositor real do app em Robolectric com gráficos nativos; as detecções foram calculadas pelas mesmas classes Java em JNI desktop. Não são capturas da câmera de um telefone.

O código e os dados do treino da versão 0.5.0 continuam em `ml_training`; documentação e validações anteriores foram preservadas como histórico. Não houve execução em celular físico nem validação metrológica em amostras reais com verdade-terreno de forma 3D.


</details>
