# PelotaVision 0.4.0 — área de interesse por gestos

18/10/2026. A implementação adiciona ROI retangular e circular na foto parada,
com arraste do interior, oito alças no retângulo, quatro no círculo e pinça.
O círculo preserva a forma circular. Os gestos usam coordenadas da foto
orientada e compensam as margens FIT_CENTER. Não ocorre recorte nem zoom.

A regra de inclusão é a circunferência inteira dentro da ROI. Contagem,
trincas, CSV e histograma recebem a mesma população selecionada. O detector
continua operando com o contexto da foto inteira, antes da filtragem; o
algoritmo de detecção e de triagem de trincas não foi alterado nesta versão.
Esse critério pode excluir objetos cuja forma real caberia na área, mas cuja
circunferência aproximada cruza o limite. Objetos cortados no limite não são
contados parcialmente. A quantidade excluída é apresentada separadamente.

Calibração direta, por referência e estimada permanece fixa ao mudar a ROI.
A estimativa por média passa a usar source=assumed_mean_locked após aplicada,
sem reimpor a média a cada população. A revisão de trincas é mantida na lista
completa e recuperada ao remover/expandir a seleção. Reanalisar descarta as
revisões, preservando área e escala; outra foto começa sem ROI e sem escala.

O relatório preserva original limpa e painel anotado com contorno ciano e
exterior escurecido, seguido de contagens e histograma. O original separado
preserva os bytes da entrada. JSON inclui region_of_interest: shape,
left/top/right/bottom_px, coordenadas, regra e excluded_outside_or_crossing.
Dimensões e coordenadas continuam da foto completa, mesmo com ROI ativa.

## Verificação Android

Testes de regressão de granulometria/exportação e trincas preservados.
Dez testes de ROI cobrem: inclusão da circunferência; oito alças retangulares;
círculo pelos quatro lados e pinça geométrica; arraste de dedo com margens
FIT_CENTER via eventos MotionEvent reais em View; associação de revisões;
congelamento da escala estimada; atualização da atividade/histograma e remoção;
gravação real com provedor de teste e preservação de pixels da original;
composição nativa dos exemplos retangular/circular usando a foto fornecida.

Asserções de gesto não substituem o teste em tela física. Não houve teste
no celular de Ricardo nem em outro aparelho físico. Testes de pinça verificam
os limites geométricos; a sequência multitouch real deve ser conferida no
aparelho. A triagem de trincas segue experimental, sem precisão/recall medida.

## Exemplos da foto fornecida

Usada a mesma fixture da versão anterior (122 detecções automáticas).
ROI retangular inicial: 57 incluídas, 65 fora/cruzando limite, 20 possíveis
trincas. ROI circular inicial: 21 incluídas, 101 fora/cruzando limite, 10
possíveis trincas. Não são contagens de trincas confirmadas por especialista.
Não há escala física conhecida; os exemplos usam histograma em pixels.

Os dois PNGs foram gerados pelo compositor Android em Robolectric com
renderização nativa, depois inspecionados visualmente. Não são imagens
produzidas por IA generativa. O primeiro painel permanece sem marcações.

## Histórico e iOS

baseline-0.3.0-* contém evidências históricas da versão anterior.
O projeto iOS 0.4.0 foi atualizado em separado. Não há compilação, assinatura
ou execução iOS neste ambiente Linux; essa limitação permanece explícita.

## Compilação final

Versão 0.4.0, código 6, ARM64, Android 10+. Gradle assembleDebug, lintDebug e
testDebugUnitTest concluídos: 24 testes, zero falhas/erros. Lint: zero
erros, 44 avisos. Assinatura igual à 0.3.0 e alinhamento ZIP 16 KB verificados.
Os PNGs dos exemplos passaram pela checagem de IEND e decodificação completa.

Foi acrescentada uma comparação de pixels que garante o interior da ROI sem
escurecimento em ambos os formatos. A máscara usa clipOutRect/clipOutPath.
