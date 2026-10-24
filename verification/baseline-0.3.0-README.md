# PelotaVision — Android 0.3.0

Protótipo de identificação de pelotas, granulometria por imagem e triagem
experimental de possíveis trincas na superfície visível. Não contém um modelo
neural treinado para trincas. Uma indicação não confirma uma trinca, e ausência
de indicação não garante uma pelota sem trincas.

## Instalar

Abrir `PelotaVision_0.3.0_Android.apk` em um celular ARM64 com Android 10 ou
superior. Autorizar a instalação pelo aplicativo que abriu o APK e permitir
a câmera. O pacote e a assinatura de desenvolvimento são preservados em
relação a 0.2.0, 0.2.1 e 0.2.2, permitindo atualização sobre essas versões.
A chave da primeira entrega 0.1.0 era diferente. Não há publicação na Play Store.

## Analisar e salvar

1. Fotografar ou abrir um JPEG, PNG ou WebP, até 100 MB. A câmera ao vivo
   continua identificando pelotas; a análise de trincas ocorre na foto.
2. Conferir círculos, linhas vermelhas e as contagens. Vermelho indica possível
   trinca; verde, nenhuma indicação encontrada; amarelo, análise inconclusiva.
   Linhas vermelhas realçam os pixels candidatos. IDs identificam as pelotas.
3. Tocar em uma pelota para abrir sua imagem ampliada. Classificar como
   Trinca visível, Sem trinca visível ou Inconclusiva, ou restaurar o automático.
   A revisão muda o estado e a contagem; não inventa o traçado de uma trinca
   que o detector não localizou. Ao revisar para sem trinca, suas linhas são
   ocultadas no relatório. Estados automático e revisado constam do JSON.
4. O botão Trincas ajusta a sensibilidade e reanalisa. Maior sensibilidade pode
   marcar mais textura, poros e sombras. Reanalisar, inclusive em Ajustes,
   descarta revisões de trincas; mantém a calibração da mesma foto.
5. Salvar relatório. O PNG `_resultado.png` contém, nesta ordem:
   - imagem original orientada, sem marcações;
   - imagem anotada com circunferências, IDs e possíveis trincas;
   - contagens e legenda;
   - histograma granulométrico por número.

Cada pelota é contada uma vez, mesmo com vários segmentos de trinca.
Inconclusivas incluem regiões parciais, regiões com alerta geométrico e
objetos pequenos/escuros para os quais nenhuma indicação foi localizada.
Uma trinca candidata visível pode ser contada mesmo numa pelota parcialmente
cortada. As contagens de trincas usam todas as detecções; o histograma pode
excluir regiões cortadas e, opcionalmente, regiões com alerta de geometria.
Assim, o n do histograma pode ser menor que a contagem de pelotas.

O PNG usa a foto orientada e, quando necessário, reduzida a até 2400 pixels
no maior lado. A primeira imagem da composição não tem marcas nem recorte
adicional. O arquivo `_original` separado preserva os bytes e a resolução da
entrada. O relatório é uma composição de duas imagens mais o histograma;
não possui as mesmas dimensões da entrada.

Fotos ficam em Pictures/PelotaVision (Galeria), dados em Download/PelotaVision.
Após salvar, Abrir imagem abre o PNG do relatório. São cinco arquivos:
original, relatório PNG, CSV por pelota, CSV de histograma e JSON.
A entrada não é sobrescrita. O app só pede permissão de câmera; seleção pelo
seletor Android e gravação pelo MediaStore. Análise funciona sem internet.

## Escala e histograma

A análise de trincas e o salvamento não exigem px/mm. Sem escala física,
o histograma é explicitamente em pixels do painel orientado da foto, com
classes de 10 px. O CSV usa lower_px/upper_px; JSON histogram.unit é px e
physical_scale é null. Campos diameter_mm e area_mm2 ficam vazios/null.

Para granulometria em mm, tocar em Escala / mm:

- informar px/mm da foto ORIGINAL orientada;
- calcular por comprimento de referência: pixels / comprimento real em mm;
- ou escolher explicitamente uma média conhecida externamente da mesma
  amostra. Nesse último modo, a escala é marcada ESTIMADA, e a média passa a
  definir a escala em vez de constituir uma medição independente.

Ex.: referência de 240 px / 12 mm = 20 px/mm. Usar pixels do arquivo original,
não da tela ou de uma miniatura. Uma referência deve estar no mesmo plano
aproximado da amostra. O app corrige seu próprio redimensionamento.
O tamanho conhecido da amostra não é preenchido automaticamente.

A classe agrupa diâmetros; ela não calibra a imagem. Classes de 1 mm numa faixa
8–16 mm produzem 8–9, 9–10, …, 15–16 mm. A largura escolhida é preservada.
A faixa exibida cobre as classes ocupadas, mantendo gaps entre elas. Limites
são múltiplos da largura, intervalos [a,b), com último limite superior inclusivo.
Até 5000 classes: acima disso, um erro mantém o formulário aberto e solicita
classe maior, sem aumento automático. D10/D50/D90 usam interpolação em (n−1)q.

Distribuição por NÚMERO, não por massa ou peneiramento. Diâmetro equivalente
à área projetada visível: 2 sqrt(área/pi). Perspectiva, alturas diferentes e
oclusão podem causar erro. Alterar zoom, distância, recorte e lente muda escala;
ela é removida a cada foto nova. Não foi implementada autocalibração ArUco.

## Método experimental de trincas

1. Usar a luminância da foto orientada, até 1800 pixels no maior lado para esta
   etapa. Coordenadas dos resultados são devolvidas ao painel da foto.
2. Dentro de cada circunferência, selecionar uma região interior e atribuir
   áreas sobrepostas à pelota de menor distância relativa ao centro.
3. Suavizar e aplicar black-hat morfológico com tamanho relativo à pelota,
   realçando linhas escuras mais finas que o elemento estruturante.
4. Usar limiar local de resposta, fechamento e componentes conexos.
5. Filtrar componentes por comprimento, alongamento, espessura aproximada e
   área. Reduzir o efeito de sombras muito escuras na periferia.
6. Guardar os traçados candidatos e classificar a pelota uma única vez.

Esse método é uma heurística de imagem, não um classificador validado.
Trincas curtas/finas ou próximas à borda podem faltar. Textura, poros alongados,
sombras, separações e fragmentos podem ser falsos positivos. Revisão visual é
necessária. Começar com iluminação difusa, foco, câmera parada, camada única
e menos pelotas por foto para preservar detalhe. Não há promessa de precisão,
recall, detecção de trincas internas nem desempenho em tempo real.

A identificação de pelotas continua usando LoG multiescala, supressão de
candidatos, máscara Otsu e watershed por marcadores. Não houve mudança no
algoritmo de pelotas. As regiões são automáticas e podem ser agrupadas ou
omitidas. IDs não acompanham objetos entre quadros ou reanálises.

## Exportações

As coordenadas x/y, raios e traçados de trincas usam o painel da foto orientada,
antes da composição. Para converter y para o painel anotado do PNG, somar
annotated_panel_top. JSON também informa original_panel_top, histogram_top,
photo_panel_width e photo_panel_height. Não confundir com coordenadas do
arquivo original de alta resolução.

CSV por pelota inclui crack_status (POSSIBLE, NO_INDICATION, INCONCLUSIVE),
crack_manually_reviewed, centro, raio, área, alertas e medidas em mm quando
calibradas. JSON cracks registra o método, contagens, sensibilidade, estados
automático/revisado e marked_runs_x1_y1_x2_y2_px. Esses runs são destaques dos
pixels candidatos, não uma largura de trinca validada metrologicamente.

Os cinco arquivos são criados pendentes e publicados depois de todos gravados.
Falhas tentam remover arquivos parciais. Interrupções abruptas podem deixar
entradas pendentes; não há transação atômica entre coleções do MediaStore.

## Compilar e verificar

JDK 17, Android SDK Platform 35, Build Tools 35.0.0, Gradle 8.9, AGP 8.7.3.
CameraX 1.4.2, Activity 1.9.3, ExifInterface 1.3.7, OpenCV Android 4.12.0.
Robolectric 4.13 e JUnit 4.13.2 são somente dependências de teste.

```bash
./gradlew assembleDebug lintDebug testDebugUnitTest
```

O projeto gera APKs ARM64 e x86_64 em app/build/outputs/apk/debug.
A chave signing/prototype.keystore preserva as atualizações: alias
androiddebugkey, senha android. É uma chave pública de desenvolvimento,
inadequada para uma distribuição de produção.

verification/VALIDACAO.md descreve testes, exemplo e limitações. A foto de
exemplo é analisada automaticamente; não é verdade-terreno de um especialista.
CrackDetectorCheck.java exercita o algoritmo em JVM com JNI OpenCV 4.9 desktop,
incluindo trincas, poros, superfície uniforme e redimensionamento. O compositor
é testado em Android simulado com gráficos nativos. O salvamento real do código
é exercitado com um provedor MediaStore de teste, não com Galeria física.
Não houve teste físico no celular do usuário nem validação de precisão.
