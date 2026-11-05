# Inferência multitarefa experimental isolada

O responsável solicitou implementar a IA na próxima versão. A implementação está
ligada à interface exclusivamente como **prévia experimental opt-in**, sem alterar
análise, sessão científica, PDF ou exportação. O contrato original não autoriza
promoção dos pesos a modelo aprovado; o usuário seleciona o arquivo privado e o
aplicativo só aceita o SHA-256 documentado abaixo. Não há validação independente nem
garantia de desempenho, e não se avaliam trincas ou integridade. Pseudo-rótulo não
é referência. Cores, contagens e entropia não são medidas, esfericidade ou diagnóstico.

## Interface Java

Após carregar OpenCV 4.12, fora da EDT:

```java
ExperimentalMultitask.Result result = ExperimentalMultitask.infer(arquivoOnnx, imagemBgr);
```

`imagemBgr` é `Mat` CV_8UC3 na grade analisada e continua pertencendo ao chamador;
não é alterada. O usuário seleciona o arquivo local, cuja SHA-256 obrigatória é
`eaf79162c6693b3e502613254c47a0ea016faae8212604a36625faf0f9488c31`.
O arquivo não é baixado, copiado ou incluído no pacote. Os bytes verificados
alimentam o DNN; em chamadas posteriores a
mesma rede já verificada é reutilizada após nova checagem do arquivo selecionado.
Arquivo >10 MiB é rejeitado. Exceções indicam falha; não há retorno parcial.

`result.overlay` contém ARGB na grade analisada, com verde = interior, vermelho =
borda, azul = separação e cores combinadas para canais simultâneos. Canais são
sigmoides independentes; cada cor indica saída sigmoid **estritamente maior que
0,5**, limiar heurístico, sem calibração probabilística. `entropyOverlay` é magenta com opacidade
proporcional à média das três entropias Bernoulli normalizadas, também não calibrada.
`interiorPixels`, `boundaryPixels`, `separationPixels` e `meanEntropy` são calculados
na **grade de processamento**, sem correção de área, e não são contagens de objetos.
`warning` deve permanecer visível; não usar em `DesktopAnalysis`, `PhotoSession`,
estatísticas, PDF, exportações ou como máscara de referência.

## Grades e diferenças do Python

Os lados da grade analisada devem estar entre 2 e 4096 e totalizar até 4.194.304
pixels. Como `pipeline.py` usa por padrão lado máximo 1024 no treino dev, primeiro
aplica-se `scale = min(1, 1024/max(largura,altura))`, depois as dimensões
`round(largura*scale)` e `round(altura*scale)`, com empates para o inteiro par,
como Python. Quando reduzida, a imagem BGR é reamostrada com OpenCV `INTER_AREA`
**antes** de `BGR2GRAY`. Se algum lado reduzido for menor que 2, a chamada falha.
As dimensões são `processingWidth`, `processingHeight`, `analyzedWidth` e
`analyzedHeight`; `scaleX=processingWidth/analyzedWidth` e
`scaleY=processingHeight/analyzedHeight`, origem (0,0). São transformações de
coordenadas de imagem, não escala física.

Na grade reduzida, a entrada é cinza uint8/255 float32. Para o ONNX de entrada fixa
`1x1x128x128`, usam-se núcleo 64, halo 32, reflexo equivalente a `numpy.pad(...,
mode="reflect")` e saída `1x3x128x128` de logits. O contrato de inferência Python
`predict_tiled` **por padrão usa núcleo 128 e entrada 192**, incompatível com este
ONNX fixo: a comparação deve passar `core=64, context=32`. A inferência em si não
redimensiona os blocos; a redução anterior é uma escolha explícita para aproximar
a grade de treino e não faz parte de `infer.py`. Nenhuma paridade com o padrão
Python de núcleo 128 é alegada.

Para visualização, cada pixel `(x,y)` da grade analisada consulta a grade da rede
em `(floor(x*processingWidth/analyzedWidth), floor(y*processingHeight/analyzedHeight))`,
vizinho mais próximo. Não se interpola probabilidade nem se inventa detalhe na
resolução maior; `overlay` e `entropyOverlay` retornam à grade analisada.

Chamadas são serializadas; cancelamento/interrupção é verificado antes da carga e
entre blocos. Mats temporários são liberados em `finally`. OpenCV Java 4.12 não
fornece `close()` ou `release()` para `Net`: a única rede aceita é reutilizada em
cache e sua liberação nativa fica a cargo do finalizador do wrapper. Não há garantia
de liberação imediata. A interface clona a imagem, mantém a análise científica
intacta e descarta resultados se a sessão ativa mudar.

## Testes

Os testes sintéticos são portáveis e não precisam de imagens privadas. Os dois testes
adicionais com ONNX são opcionais, ativados pela variável de ambiente
`PELOTAVISION_EXPERIMENTAL_MODEL` apontando para o arquivo escolhido. Eles comparam
contagens e entropia com referências geradas em Python/PyTorch em imagens sintéticas,
incluindo uma redução BGR `INTER_AREA`. São comparações pontuais, não validação,
nem paridade completa pixel a pixel. A execução requer o JAR/DLL OpenCV do projeto:

```powershell
.\gradlew.bat -p desktop test --tests br.com.pelotavision.ExperimentalMultitaskTest '-PopencvJava=E:/PelotaVision/build/opencv-4.12.0/opencv/build/java'
```
