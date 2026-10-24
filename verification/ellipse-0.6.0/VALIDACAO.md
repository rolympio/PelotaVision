# Validação — PelotaVision Android 0.6.0

18/10/2026. Alteração: contornos elípticos e índice médio de forma projetada. Não mede esfericidade tridimensional. Os valores de b/a apresentados nas fotos são estimativas experimentais, sem verdade-terreno dimensional.

## Métrica implementada

Esfericidade aparente 2D = eixo menor / eixo maior da elipse, entre 0 e 1. Média aritmética por número somente de elipses ajustadas, inteiras, sem alerta e dentro da ROI. Ajustes desconhecidos permanecem como localização tracejada, não recebem índice 1 e são excluídos. Sem objetos elegíveis, média=NaN no Java, “—” na interface e null no JSON. A média pode ser enviesada quando muitos contornos são excluídos; n e exclusões são mostrados na tela e no relatório.

A área é πab e o diâmetro equivalente é 2√(ab), com semieixos a e b. A calibração continua em pixels da imagem original e é corrigida pelo redimensionamento. Os dois eixos e o ângulo são exportados; a razão b/a independe de px/mm. Isso não é circularidade de perímetro real nem esfericidade 3D.

## Geometria e detecção

Os candidatos de localização existentes são refinados por bordas observadas em 96 direções. Ajuste robusto de elipse, refinamento, gates de cobertura/resíduo/polaridade e mapeamento da elipse ao sistema da foto original, incluindo o pequeno redimensionamento anisotrópico por arredondamento. Uma alternativa por contornos auxilia cenas esparsas com objetos alongados. Não é uma segmentação treinada, nem todos os objetos são encontrados e nem todo ajuste aceito é correto.

Foram testadas 24 elipses analíticas: razões 1, 0,8, 0,6 e 0,5; orientações 0°, 35° e 80°; objetos claros e escuros, em imagem de 400x280 pixels e semieixo maior de 70 pixels. Os centros iniciais foram deliberadamente deslocados em (3,-2) pixels. Erro máximo absoluto de b/a = 0,01521634; erro máximo dos semieixos = 0,939984 pixel. O teste confirmou centros, orientação, área e raio equivalente. A região uniforme ficou sem forma mensurável; uma pelota isolada alongada também foi detectada. São figuras geométricas simples, não uma prova de precisão em pelotas de usina.

A ROI retangular usa as extensões reais da elipse rotacionada. A ROI circular otimiza a maior distância entre a borda da elipse e o centro da seleção. O teste comparou essa distância com uma amostragem densa de 8192 pontos em 40 configurações determinísticas, além de verificar limites/tangência e seleção. A máscara circular da ROI continua circular, como na versão anterior; somente os marcadores das pelotas mudam para elipses.

## Imagens de exemplo

São as sete cenas do conjunto experimental anterior, que inclui conteúdo sintético e imagens de procedência não verificada. Não há eixos verdadeiros de referência nessas fotografias e os números abaixo não são acurácia. A população inclui somente os candidatos já localizados e os ajustes aceitos. Contornos sobrepostos, sombras e falsos candidatos ainda ocorrem.

| Cena | Média b/a | Incluídas | Excluídas da forma |
|---|---:|---:|---:|
| brown40 | 0.883678 | 38 | 2 |
| cluster | 0.928388 | 18 | 14 |
| dense | 0.926264 | 19 | 6 |
| gray20 | 0.958220 | 20 | 0 |
| gray42 | 0.919705 | 42 | 0 |
| paired40 | 0.944592 | 40 | 0 |
| spaced | 0.941848 | 17 | 2 |

As sete imagens de relatório foram renderizadas com o PhotoComposer real do app e gráficos Android nativos em Robolectric. Detecções e trincas foram calculadas pelas mesmas classes Java via JNI OpenCV 4.9 desktop; o app inclui OpenCV Android 4.12. Não são fotos da câmera do telefone. Foi verificado IEND, leitura integral com Pillow e preservação dos pixels originais. O teste de renderização também verifica concordância entre a média nativa e os objetos reconstruídos no compositor Android.

## Trincas e compatibilidade

A floresta e os 72 atributos da versão 0.5.0 não foram retreinados. O recorte de referência original é mantido separadamente dos eixos medidos; o maior erro de escore antes/depois do ajuste de elipse foi 0 nas sete cenas. O aplicativo limita os traçados à elipse. Elipses com b/a<0,65 ficam inconclusivas para trincas pelo desvio de forma em relação ao treino; uma elipse alongada geometricamente válida pode continuar incluída na média b/a.

Continuam válidas as limitações do classificador: treino com rótulos provisórios, conteúdo sintético, falsos positivos e falsos negativos. Sem validação em fotos reais da usina e sem confirmação de trincas internas. A classificação de trincas ocorre na foto parada. O modelo não comprova ausência de trinca.

## Verificações do aplicativo

32 testes do projeto passaram: 9 PhotoExport, 5 CrackExport, 10 Region, 3 LearnedModel e 5 EllipseWorkflow. A nova classe cobre média por número, exclusões, média indefinida, ROI geométrica, elipse rotacionada desenhada fora do antigo círculo equivalente, exportação real via MediaStore de teste, escala com redução de imagem, eixos em mm, diâmetro equivalente, CSV/JSON e relatórios das sete cenas. Os cinco testes de elipse foram repetidos após reforçar a gravação atômica e a checagem de integridade dos PNGs de exemplo; também passaram. O código de produção não foi alterado nessa repetição.

Build assembleDebug, testDebugUnitTest e lintDebug concluídos. Lint: 0 erros, 51 avisos, mantidos em lint.txt; abrangem orientação fixa, backup, alocações de desenho, acessibilidade e internacionalização. Não houve teste em aparelho físico, velocidade da câmera, Galeria real ou validação metrológica. Não foi feita compilação iOS.

APK ARM64, Android 10+, versão 0.6.0, código 8. Assinatura APK v2 verificada com o mesmo certificado das versões 0.5.0/0.4.0; pode ser instalada como atualização. Alinhamento ZIP de 16 KiB aprovado. A chave continua de desenvolvimento, não de publicação em loja.

## Evidências e reprodução

- EllipseCheck.java e native-results.txt: ensaio geométrico e inferência nas cenas.
- *_analysis.json, shape-results.json e *_report.png: geometria, média, traçados e imagens.
- TEST-*.xml e build-full-suite.log: suíte completa; targeted-EllipseWorkflowTest.xml e png-integrity-rerun.log: repetição dos cinco testes.
- apk-signature.txt, apk-manifest.txt e artifact_hashes.json: identidade e integridade do APK.
- Código Android em app/src; pesos e treino anterior preservados em ml_training.

Referências primárias para os conceitos e APIs: [OpenCV fitEllipse](https://docs.opencv.org/4.13.0/d3/dc0/group__imgproc__shape.html) e [NIST — comparação de medidas de forma 2D e 3D](https://www.nist.gov/publications/three-dimensional-shape-analysis-jsc-1a-simulated-lunar-regolith-particles). As métricas e testes específicos acima são resultados deste projeto, não conclusões dessas referências.

SHA-256 APK: `26be0cc6b62d731fa11953d3f712a60c5e2124c63655bb1dca344ff2d3e29b85`

SHA-256 modelo preservado: `736bf0bbaf881cfc0465ea2de362285430820fbc3381cd9753f309cd5e09f8ce`
