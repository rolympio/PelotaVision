# Validação — PelotaVision Android 0.5.0

18/10/2026. Versão experimental. Classificador de trincas Random Forest, sem rede neural e sem servidor.

## Dados e escopo

16 arquivos enviados, com 3 cópias idênticas, 3 versões marcadas que possuem pares limpos e 3 imagens marcadas sem par limpo excluídas dos pixels de treinamento. Restaram 7 cenas limpas, incluindo o painel sem marcação de uma composição identificada como sintética. As demais cenas têm procedência não verificada. Há 225 círculos aproximados, 209 pelotas inteiras usadas no classificador, 37 rótulos positivos e 172 negativos. Os rótulos foram anotados pelo assistente e apoiados nas marcações do usuário onde havia par limpo; não foram homologados por especialista. Ausência de marca vermelha não foi tratada como certificação de ausência de trinca. Os negativos significam apenas ausência de indicação visual na inspeção provisória.

A validação do classificador deixa uma cena inteira fora por vez. Variações de brilho/contraste entram apenas no treino da respectiva rodada. Duplicatas, pares marcados e a mesma cena não são divididos entre treino e avaliação. O ajuste final usa todas as sete cenas depois de registrar as predições fora do treino. A detecção dos círculos foi desenvolvida usando essas mesmas cenas; seus números são de desenvolvimento, não um teste independente.

## Classificador — círculos de referência

- Limiar fixo 0,5: 25 verdadeiros positivos, 12 falsos negativos, 3 falsos positivos, 169 verdadeiros negativos. Recall de trincas 67,6%, precisão positiva 89,3%. São rótulos provisórios e a amostra é pequena.
- Política padrão do app (escore >=0,6 / <=0,4): 25 positivas indicadas (todas positivas rotuladas), 181 sem indicação (169 negativas e 12 positivas), 3 inconclusivas (negativas). Os gates de qualidade do app podem acrescentar inconclusivas. Esses resultados não incluem erros de detecção de círculos.
- A cena paired40, explicitamente sintética, teve 0/8 positivas detectadas quando ficou fora do treino: generalização entre domínios ainda insuficiente. Não se deve interpretar o bom resultado em outras cenas como validação na usina.
- As fotos usadas para renderizar exemplos também fazem parte do treino final: as imagens ilustram a saída, não medem generalização.

## Detector de pelotas e iluminação

Propostas Hough por gradientes em bandas de raio, luminância original e CLAHE, ranking por alinhamento de gradientes e supressão de duplicatas. Não pressupõe que a pelota seja mais clara que o fundo. O perfil automático pesquisa uma faixa ampla; os perfis manuais e o método anterior LoG continuam disponíveis.

Na imagem original, 203/209 pelotas inteiras anotadas foram correspondidas; contando bordas, 214/225, com 218 círculos propostos e 4 sem correspondência. A tolerância de correspondência é distância de centro <0,5 raio anotado e erro relativo de raio <0,5, em pareamento um a um. É uma tolerância larga sobre contornos aproximados, não precisão dimensional. Ainda existem círculos deslocados e superdimensionados.

Variantes por cena: original 203/209, escurecimento gamma 193/209, clareamento gamma 204/209, redução de contraste 201/209, gradiente lateral 202/209, desfoque leve 206/209 pelotas inteiras. São simulações; não representam todos os efeitos de iluminação, reflexo ou desfoque de um celular.

No modo gradientes, diâmetro=2*r do círculo ajustado, área=pi*r², circularidade=1 imposta. No modo anterior LoG, usa-se área segmentada. Não há autocalibração física nova nem imposição de média de 12 mm. Os exemplos não têm referência física comprovada e usam histograma em pixels. A escala px/mm e as classes de 1 mm continuam configuráveis na foto original, com correção do redimensionamento interno.

## Integração e verificações

27 testes Android simulados passaram: 9 PhotoExport, 5 CrackExport, 10 Region e 3 LearnedModel. Verificaram paridade do arquivo embarcado, preservação do escore após ROI/revisão, CSV/JSON com modelo e método, imagem original inalterada acima do painel marcado e histograma dentro do PNG. Os sete relatórios foram desenhados pelo PhotoComposer real do app usando gráficos Android nativos em Robolectric; as detecções e trincas foram calculadas antes com as mesmas classes Java em JNI desktop OpenCV 4.9. Isso não é uma captura de câmera física.

A comparação Python OpenCV 4.12 / Java JNI OpenCV 4.9 em 209 recortes teve erro máximo de atributos 0, diferença de escores <3e-16 e nenhuma mudança de classificação. Modelo inválido foi rejeitado, superfície uniforme ficou inconclusiva, traços em quatro trincas visíveis de referência foram preservados, revisão não perdeu o escore. Checks legados de círculos, poros, borda e redimensionamento também passaram. Referências de versões anteriores permanecem como histórico.

Build assembleDebug, testDebugUnitTest e lintDebug concluíram. Lint: 0 erros, 46 avisos de orientação fixa, backup, desenho, acessibilidade e internacionalização; permanecem no relatório. APK ARM64 Android 10+, versão 0.5.0/código 7, assinatura v2 verificada com o mesmo certificado do APK 0.4.0 e alinhamento de 16 KiB aprovado. A chave é de desenvolvimento, não de publicação. Não houve execução em aparelho físico, teste de Galeria real, validação de velocidade da câmera nem atualização/compilação iOS.

## Uso e próxima validação

Instalar o APK de atualização, abrir uma foto limpa e conferir Ajustes → Automático, com método anterior desmarcado. Na foto parada, tocar em Trincas para sensibilidade e tocar nas pelotas para revisão. Verde=sem indicação visível, vermelho=possível trinca, amarelo=inconclusiva. Linhas vermelhas são apenas os trechos localizados pela heurística; nem toda pelota positiva tem traçado. Salvar gera original, relatório composto, CSV/JSON e histograma por número. A ROI filtra contagens e mantém a escala.

Para validar de fato: reunir fotos reais sem marcações, de sessões/lotes distintos, em iluminação e celulares representativos; obter rótulos de especialista por pelota (e traçado/máscara se for necessário medir/localizar a trinca); separar sessões e lotes entre treino e teste antes de ajustar limiares. Medir falsos negativos, falsos positivos, inconclusivas, omissões de pelotas, erro dimensional com referência física e latência em aparelho.

## Reprodutibilidade

`ml_training/` inclui imagens limpas, círculos/rótulos revisáveis, scripts, requisitos fixos, pesos PVF1, metadados e predições fora do treino. `verification/ml-0.5.0/` inclui XML de testes, relatórios Android PNG, detections.csv, benchmark de iluminação, paridade, hash e assinatura. A floresta é experimental e o escore não é probabilidade calibrada. A localização dos traços é uma heurística independente, não uma segmentação treinada.

Referências técnicas primárias: [OpenCV HoughCircles](https://docs.opencv.org/4.12.0/d4/d70/tutorial_hough_circle.html), [CLAHE](https://docs.opencv.org/4.12.0/d6/db6/classcv_1_1CLAHE.html), [RandomForestClassifier](https://scikit-learn.org/stable/modules/generated/sklearn.ensemble.RandomForestClassifier.html), [validação por grupos](https://scikit-learn.org/stable/modules/cross_validation.html#cross-validation-iterators-for-grouped-data). A metodologia e os resultados deste projeto são próprios; esses links documentam as APIs utilizadas.

SHA-256 APK: `f61e027278e2bc7467dafbc15b3e7ad35751c8da027d68dc6552e9a0deb779f0`

SHA-256 modelo: `736bf0bbaf881cfc0465ea2de362285430820fbc3381cd9753f309cd5e09f8ce`
