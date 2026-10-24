# Validação técnica — PelotaVision 0.9.0

Build Android concluído: assembleDebug, testDebugUnitTest e lintDebug. Java 17, Gradle 8.9, SDK 35. Pacote br.com.pelotavision, versionCode 12. APK ARM64 assinado com o mesmo certificado de desenvolvimento do projeto anterior.

67 testes Robolectric, zero falhas e erros. São 56 testes anteriores, dez de geometria/gestos/calibração/gravação da correia e um de renderização da foto enviada. A UI de escala passa a oferecer cinco modalidades; os dois testes que verificavam a antiga lista de quatro opções foram atualizados para verificar também a nova opção inicialmente desabilitada. Lint: zero erros e 87 avisos. Evidências XML em belt-0.9.0/tests.

Os testes verificam a distância perpendicular entre bordas inclinadas no plano da imagem, correção de redimensionamento anisotrópico, rejeição de referência degenerada ou cruzada, limite de variação das larguras marcadas, proposta em bordas conhecidas e ausência de proposta em fundo uniforme, deslocamento de alças/linhas, pinça sem alterar coordenadas físicas, confirmação obrigatória, máscaras retangular/circular e gravação real via MediaStore simulado. O ensaio integrado compara o marcador no PNG recortado com sua transformação no JSON, preserva os bytes da original e confere 12 mm no CSV e no histograma de uma geometria conhecida.

A foto real 1000420704.jpg foi usada para exercitar o algoritmo de sugestão e renderizar as classes reais da interface e do compositor em Robolectric com gráficos nativos. As telas são renderizações do aplicativo, não capturas de um telefone. A largura física dessa correia não foi informada; o exemplo de relatório permanece em pixels. As detecções de pelotas/trincas do exemplo foram calculadas pelas classes Java com JNI desktop OpenPnP OpenCV 4.9; o APK usa OpenCV Android 4.12, e pode produzir resultados diferentes. A fotografia não é um conjunto de marcações de referência para medir acurácia.

A sugestão de bordas requer conferência humana. Não se corrige perspectiva, distorção óptica, abaulamento ou altura do leito. O limite de 8% é uma regra geométrica de aceitação, não uma incerteza metrológica validada. O classificador RF permanece experimental e inalterado. Não houve teste em celular físico nem validação industrial desta versão.

O relatório final opcional usa a ROI inteira selecionada. A pinça no visualizador serve à inspeção; a ROI e sua seleção estatística são ajustadas no editor de área. Os dados continuam nas coordenadas da foto inteira, e a ampliação é registrada como transformação de apresentação. A original de entrada permanece salva separadamente.

As verificações históricas foram restauradas dos bytes do ZIP 0.8.1 após os testes de regressão, para não substituir os registros antigos por renderizações desta versão. A evidência atual está em belt-0.9.0.
