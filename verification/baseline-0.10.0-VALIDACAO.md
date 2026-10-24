# Validação técnica — PelotaVision 0.10.0

Compilação Android: assembleDebug, testDebugUnitTest e lintDebug concluídos. Java 17, Gradle 8.9, SDK 35. Pacote br.com.pelotavision, versionCode 13. APK ARM64 verificado com apksigner e mesmo certificado da 0.9.0. Android 10+. 78 testes Robolectric, zero falhas/erros, lint com zero erros e 90 avisos. Não houve execução em telefone físico, compilação iPhone ou validação industrial.

## Comportamento verificado

O botão Aplicar do editor altera a imagem principal para a ROI, preserva o bitmap e o arquivo originais e bloqueia salvar enquanto a área não foi analisada. Há comandos explícitos para segmentar com o ajuste atual e buscar configuração na ROI. A troca de seleção invalida o cache de sugestões. Selecionar a mesma região ou alterar somente a escala mantém a análise correspondente. Voltar à imagem inteira recupera a foto e exige reanálise se o último resultado foi calculado só no recorte.

Os testes verificam máscaras retangulares/circulares, seleção fracionária, coordenadas FIT_CENTER e letterbox, revisão por toque na elipse recortada, tradução das trincas mantendo revisão manual, invariância de eixos/área/b/a e escala, referências de correia preservadas fora do recorte, identidade da referência de objeto arquivada e edição sem vincular o antigo índice a uma nova pelota. Uma referência interna com contorno alterado invalida a escala. Recortes quadrados do RF que cruzam uma ROI circular ficam inconclusivos. Uma falha de reanálise mantém foto, fonte, ROI e escala para nova tentativa.

O teste de exportação usa MediaStore simulado e confere os bytes da original, diâmetro conhecido de 12 mm e classes no histograma, localização do traço no PNG, transformação no JSON e dimensões reais da entrada. O relatório mantém original da ROI, marcações da ROI e histograma. A original de entrada inteira é preservada separadamente. O JSON registra processamento em coordenadas locais e saída em coordenadas da foto inteira. O botão de salvar foi verificado como integralmente visível nas renderizações de 800×1640.

## Ensaio nativo de segmentação

RoiNative.java executa as classes de produção com JNI desktop OpenPnP OpenCV 4.9. Foram criadas duas imagens com os mesmos pixels dentro da ROI e exterior preto/branco. As 24 combinações produziram as mesmas contagens e pontuações e a mesma opção vencedora. A máscara circular e sua mediana também permaneceram idênticas ao mudar todos os pixels originais externos. Ver native-check.log. O APK usa OpenCV Android 4.12; isso não substitui um teste no telefone.

A foto real da correia foi recortada em 440,340–860,940 (420×600 px). Foram avaliadas 24 opções, com 413 marcações na opção log_p1_s90. Esse número é uma saída experimental, não contagem verdadeira nem prova de acurácia. A imagem contém finos e muitas marcações inconclusivas; requer revisão. Não foi informada a largura física da correia: o exemplo permanece em pixels. A regra de 5 mm não é ativada sem calibração física externa independente.

## Evidência visual e limites

Tela_ROI_aplicada.png e Tela_ROI_segmentada.png são renderizações das classes reais do aplicativo em Robolectric com gráficos nativos, usando a foto enviada e resultados da análise nativa. Não são capturas de um telefone. Relatorio_ROI_ativo.png foi gerado pelo compositor real do aplicativo. O teste injeta os resultados nativos para exercitar o fluxo da interface sem tentar carregar JNI Android no Robolectric.

O classificador RF permanece inalterado. Não houve novo treinamento, validação metrológica ou de acurácia. Não se corrige perspectiva, lente, abaulamento ou altura do leito. A esfericidade é aparente 2D b/a. A granulometria é por número e projeção de imagem, não ensaio de peneiramento. As evidências anteriores foram restauradas da 0.9.0 para evitar substituir seus registros por novas renderizações de regressão.
