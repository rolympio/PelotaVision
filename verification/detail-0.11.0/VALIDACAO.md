# Validação técnica — PelotaVision 0.11.0

## Compilação e testes

assembleDebug, testDebugUnitTest e lintDebug concluídos. 93 testes Robolectric, zero falhas/erros; lint com zero erros e 103 avisos. Pacote br.com.pelotavision, versionCode 14, Android 10+, ARM64. Mesmo certificado de desenvolvimento, verificado por apksigner. Não houve execução desta versão em telefone físico, compilação iPhone ou validação industrial de acurácia.

Os 78 testes anteriores permanecem. Foram acrescentados 14 testes de geometria, escala, extração regional, canais de SR, interface, exportação e recuperação após erro, e um de renderização real das telas. As mudanças em arquivos de regressão anteriores à 0.10.0 já pertenciam à versão anterior; esta entrega mantém suas verificações. A exportação confere 12 mm em histograma/CSV de uma geometria conhecida, entrada original preservada e dimensões nativas distintas da prévia no JSON.

## Geometria e extração

O plano de blocos cobre todos os centros de pixel uma única vez, inclusive em dimensões irregulares e nos últimos blocos ancorados ao limite. A supressão adicional elimina candidatos quase coincidentes e preserva duas pelotas vizinhas encostadas no ensaio geométrico. O mapeamento afim preserva área, eixos físicos e referência de escala, inclusive com redução anisotrópica. O contexto de pontuação converte coordenadas de bloco à prévia antes de aplicar a ROI e a exclusão espacial da referência; a escala nativa em px/mm é derivada da mesma calibração.

Uma fonte de 4000×2400 gera prévia reduzida, mas a extração de uma ROI menor recupera seu pixel isolado e sua resolução original. A extração inteira registra explicitamente amostragem 2 para cumprir o orçamento de 8 MP. Há comparação de oito orientações JPEG EXIF contra o resultado orientado, além de uma verificação independente das matrizes de cada espelhamento/rotação. Voltar à ROI da análise anterior preserva também a metainformação da resolução nativa; a alteração de área bloqueia salvar até reanalisar. Um erro de leitura mantém fonte, foto, resultado e escala anteriores.

**Limitação do ambiente:** o BitmapRegionDecoder simulado padrão do Robolectric 4.13 retorna imagens vazias e não aplica inSampleSize. Os testes regionais usam um shadow restrito ao contrato dessa API, com BitmapFactory nativo/crop/amostragem. As matrizes EXIF, cálculo dos limites de leitura, configuração da amostragem, orientação e transformação de escala executam o código de produção. Isso não equivale a testar o decodificador regional Android em um telefone. A produção usa BitmapRegionDecoder da plataforma, sem esse shadow.

## Modelo e ensaio nativo

O FSRCNN x2 de Saafke/OpenCV foi importado com licença Apache-2.0. Não houve treinamento nas pelotas. O grafo derivado preserva todos os pesos, termina em conv8 e usa em Java o mesmo DepthToSpace 2× e bias escalar b8 do grafo original. Há teste independente da ordem dos canais e bias, reprodução byte a byte do asset a partir do grafo de origem e execução da rede real. Um campo uniforme de entrada 120 produziu média interna 119,75, com dimensões exatamente duplicadas. Isso verifica implementação/normalização, não fidelidade de detalhes industriais.

DetailNative.java executou classes de produção com JNI desktop OpenPnP OpenCV 4.9. As 24 pelotas sintéticas conhecidas foram identificadas uma vez cada em 12 blocos, e os bytes da entrada permaneceram iguais. Cancelar antes da análise lançou cancelamento, sem devolver um resultado parcial. A busca também executou sem erros as 24 configurações em um bloco nativo de 640×640, com pontuação na escala física e seleção da ROI mapeadas para a prévia. A busca detalhada escolhe uma configuração nessa resolução nativa, não pela resolução de exibição. O APK usa OpenCV Android 4.12; os números nativos não são uma previsão exata do resultado/tempo no celular.

O ensaio real usa explicitamente a configuração log_p1_s90. A busca de 24 configurações foi ensaiada separadamente no caso sintético; a seleção automática no celular pode escolher outra opção. Na foto real, a ROI 440,340–860,940 (420×600 px) foi analisada em 6 blocos com SR disponível. A contagem sem tratamento foi 648, e a combinação escolhida teve 648 marcações. Tratamentos escolhidos [original, CLAHE, bilateral, SR] = [6, 0, 0, 0]. Nesse exemplo a heurística preferiu a original em todos os blocos, portanto o ensaio não demonstrou ganho com SR. O tempo desktop foi 12687 ms. Não há contagem verdadeira nem largura física confirmada para essa foto; o exemplo permanece em pixels e inclui finos/poros e muitas marcações inconclusivas. Maior contagem não prova melhor precisão.

## Interface e rastreabilidade

As telas e o relatório em detail-0.11.0 foram renderizados por classes reais do aplicativo em Robolectric com gráficos nativos, usando os resultados nativos como fixture. Não são capturas de telefone. O diálogo permite rápida/detalhada e exige habilitação explícita de SR. A comparação mostra contagens no mesmo plano de blocos e tratamentos escolhidos. Na SR o plano usa 320 px para evitar que o detector reduza a imagem ampliada; contagens entre planos diferentes não isolam o efeito da rede.

Final fits e trincas usam pixels originais; tratamentos e SR apenas propõem contornos. Propostas extras de SR recebem alerta. A ampliação não muda a calibração: resultados e traços são convertidos à prévia e as medidas usam a foto original. Referência por objeto interna com contorno alterado continua exigindo nova escolha/calibração; correia e escala manual permanecem. O JSON registra resolução real, amostragem, mapa afim, blocos, tratamentos, modelo SR/hash e ausência de validação de acurácia. CSV mantém 32 colunas. Cancelamento/erro não publica resultados incompletos, inclusive na checagem antes do commit da interface.

O RF de trincas é o mesmo modelo da 0.5.0, sem novo treinamento. A forma é aparente 2D; não é esfericidade 3D ou ensaio de peneiramento. Perspectiva, altura do leito, movimento e desfoque continuam limitações. Os registros anteriores foram restaurados do ZIP 0.10.0 para conservar as evidências históricas.
