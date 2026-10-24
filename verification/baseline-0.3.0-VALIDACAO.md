# PelotaVision 0.3.0 — trincas e relatório composto

## O que foi implementado

Triagem experimental de possíveis trincas por linhas escuras na região interior
de cada pelota detectada, com realce dos segmentos candidatos. Classifica por
pelota, nunca por número de segmentos: POSSIBLE, NO_INDICATION ou INCONCLUSIVE.
Não há modelo treinado nem confirmação automática de ausência/presença física
de trincas. A revisão por toque na pelota modifica a contagem e é exportada.
O botão Trincas ajusta sensibilidade; reanalisar descarta revisões.

O PNG contém original sem marcas no alto, imagem com círculos e possíveis
trincas logo abaixo, contagens/legenda e histograma ao final. Coordenadas
exportadas continuam relativas ao painel orientado; offsets dos painéis
constam do JSON. O original separado preserva os bytes da entrada.
Sem calibração, é possível salvar; histograma e dados ficam explicitamente
em pixels. Não foi atribuída uma escala física à foto anexada. Com uma escala
aplicada, preserva-se a granulometria em mm e a largura de classe escolhida.

## Foto fornecida pelo usuário

A imagem 1000414433.webp tem 1536 × 865 pixels. Foi processada com o detector
Java de pelotas, perfil médio e limiar 0,045, até 1024 pixels internos, e triagem
de trincas com sensibilidade 50. Em JVM/OpenCV desktop 4.9, resultou em:

- 122 regiões identificadas como pelotas;
- 44 pelotas com indicação de possível trinca;
- 56 sem indicação encontrada;
- 22 inconclusivas.

Esses números NÃO são verdade-terreno: não foram anotados/validados por um
especialista. A inspeção visual do exemplo confirma que diversas marcas
coincidem com trincas aparentes, mas também há indicações ambíguas e omissões.
Não há precisão/recall medida. No histograma, 89 regiões inteiras são incluídas
e 33 regiões cortadas são excluídas. Essa população é diferente da contagem
de trincas, que considera todas as detecções.

PelotaVision_Exemplo_Trincas.png foi produzido pelo compositor real Android
em Robolectric com gráficos nativos, usando a foto e os resultados executáveis
desktop como fixture. O detector OpenCV não é executado dentro do Robolectric;
a biblioteca Android é 4.12 e a biblioteca JNI desktop de verificação é 4.9.
O exemplo não é uma renderização por IA generativa nem marcação manual.

## Verificações

- Teste executável CrackDetectorCheck.java passou: uma trinca, várias trincas
  na mesma pelota contadas uma vez, poros arredondados, superfície uniforme,
  região parcial, coordenadas finitas e foto redimensionada.
- GranulometryCheck.java passou: referência, escala, redimensionamento, classes
  fixas, médias/percentis, exclusões, faixa 8–16 mm e largura 1 mm.
- Testes Android simulados cobrem PNG codificado/decodificado, preservação
  pixel a pixel da original no primeiro painel, marca vermelha no segundo,
  posição do histograma, contagem por pelota, revisão e restauração no JSON.
- ResultSaver.save é exercitado com um provedor de conteúdo de teste: cria os
  cinco arquivos, codifica o PNG, grava contagens/estados, preserva os bytes da
  original e exporta px (sem mm fictícios) na ausência de escala.
- A revisão por toque é exercitada na atividade real, com estado FOTO e
  resultados preparados para o teste; a câmera não foi utilizada nesse teste.

Os XMLs, resultados numéricos, relatório PNG e log de compilação são preservados
nesta pasta. Relatórios anteriores têm prefixo baseline e são históricos.

## Limites

Não houve teste de câmera, Galeria de fabricante ou MediaStore real em celular
físico. O provedor de teste exercita a lógica de gravação, mas não substitui a
verificação no dispositivo. A análise de trincas ocorre somente na foto.
Trincas escondidas, internas ou abaixo da resolução não podem ser descartadas;
textura e sombras podem produzir falsos positivos. O algoritmo de identificação
de pelotas não foi alterado e pode agrupar/omitir objetos. O relatório é uma
triagem experimental, não uma medição laboratorial validada.

## Compilação final

assembleDebug, lintDebug e testDebugUnitTest concluídos com sucesso.
14 testes passaram (9 de exportação/granulometria e 5 de trincas), sem falhas.
Lint: 0 erros e 38 avisos, registrados em lint-0.3.0.txt.
APK ARM64: versão 0.3.0, código 5, Android 10 ou superior.
Assinatura verificada e idêntica à versão 0.2.2; alinhamento ZIP para páginas
de 16 KB verificado. Nenhuma validação em celular físico foi realizada.
