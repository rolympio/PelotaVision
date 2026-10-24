# PelotaVision — Android 0.6.0

A versão 0.6.0 acrescenta elipses ajustadas ao contorno e **esfericidade aparente 2D (b/a)**. Uma foto isolada não mede esfericidade 3D. O classificador de trincas Random Forest da versão 0.5.0 permanece embarcado, sem novo treinamento.

## Usar

Instale `PelotaVision_0.6.0_Android.apk` sobre a versão anterior: pacote e certificado de desenvolvimento foram preservados. Android 10+ ARM64. Não é necessário desinstalar. Esta atualização não compila nem atualiza iOS.

Fotografe ou abra uma imagem. Os marcadores passam a usar elipses com eixos e orientação ajustados aos gradientes do contorno. A indicação **Esfericidade aparente 2D (b/a)** aparece na tela ao vivo e na foto; toque nela para ver a definição. Um ajuste não confiável fica tracejado e não entra na média, sem receber artificialmente um índice 1. As pelotas continuam disponíveis para contagem e revisão de trincas.

A média é aritmética por número, apenas entre elipses ajustadas, inteiras na imagem, sem alerta e dentro da área de interesse. A tela e o relatório mostram n e excluídas. Não há média ponderada por área ou massa. Caso nenhuma pelota seja válida, aparece “—” e o JSON registra null. A média representa somente esse subconjunto; exclusões podem enviesá-la.

**b/a = eixo menor / eixo maior**, com a e b os semieixos (ou diâmetros, pois a razão é igual). Varia entre 0 e 1; 1 representa uma projeção circular e não comprova uma esfera 3D. Exemplo: eixos de 12 e 10 mm produzem b/a=0,833. Esse indicador não depende da calibração px/mm. Perspectiva, foco, sombras, sobreposição e contornos incompletos podem alterar o ajuste.

## Granulometria e ROI

Para elipses válidas, área projetada estimada = πab, raio equivalente = √(ab), diâmetro equivalente = 2√(ab). O histograma conserva classes configuráveis, inclusive 1 mm, e usa o diâmetro equivalente à área. Eixos individuais são registrados em pixels e em mm quando houver calibração. O campo de px/mm continua se referindo à imagem original orientada e o redimensionamento interno é corrigido. Não se força média de 12 mm nem faixa de 8–16 mm.

A ROI retangular exige a elipse inteira dentro dos limites usando suas extensões rotacionadas exatas. A ROI circular calcula a maior distância da borda da elipse ao centro da ROI, em vez de substituir a elipse por um círculo de raio equivalente. A ROI não recorta/redimensiona a imagem nem altera px/mm. Mudar a seleção atualiza a média de forma, a contagem e o histograma; as revisões de trincas são preservadas para os objetos que voltarem à seleção.

Objetos sem ajuste confiável retêm a estimativa anterior de localização/área e ficam marcados para revisão. Podem entrar no histograma se o usuário permitir objetos de revisão; nunca entram na média b/a. A escolha “Localização alternativa · LoG” muda a geração de candidatos, mas os marcadores finais também passam pelo ajuste elíptico.

## Método e limites

O detector anterior localiza candidatos com gradientes/Hough e CLAHE, com alternativa LoG. Para cenas esparsas, contornos de objetos isolados ajudam quando pelotas alongadas não geram candidatos circulares. Ao redor de cada candidato, o ajuste busca gradientes de borda em 96 direções, seleciona observações na vizinhança esperada e usa ajuste robusto de elipse com amostragem determinística, refinamento e gates de cobertura, resíduo e coerência de polaridade. Não é segmentação neural, não corrige perspectiva e não garante contorno correto em pelotas sobrepostas. Propostas circulares internas são apenas sementes de localização; a elipse final é medida a partir de bordas observadas.

Os gates incluem cobertura ≥55% das direções, pelo menos sete dos oito setores, resíduo normalizado RMS ≤0,055 e ≥70% de coerência de polaridade entre bordas usadas. São parâmetros experimentais, não uma probabilidade de acerto. Elipses muito alongadas, fora da faixa inicial, ou sem borda suficiente podem ficar inconclusivas.

O modelo de trincas usa o mesmo recorte circular de referência da versão 0.5.0, mesmo que a elipse desloque o centro ou altere a área. Isso preserva seus 72 atributos e o escore; os traçados são limitados ao interior da elipse. Elipses com b/a<0,65 ficam inconclusivas na triagem de trincas porque diferem muito do domínio de treinamento. Essa restrição do classificador não exclui uma elipse geometricamente válida da média de forma. As limitações do treino sintético e dos rótulos provisórios continuam; não houve validação na usina.

## Saída e dados

O PNG conserva a sequência: original sem marcações → imagem com elipses/trincas/ROI → resumo com média b/a e n → histograma. O arquivo original separado permanece inalterado. CSV e JSON preservam os campos anteriores e acrescentam eixos maior/menor, orientação, b/a, validade do ajuste, inclusão na média, eixos em mm e indicadores de ajuste. Valores de forma sem ajuste são vazios/null, não 1.

O JSON `shape_analysis` registra fórmula, média, população, exclusões e `true_3d_sphericity:false`. O campo `radius_px` é o raio equivalente à área; não é o semieixo maior. `ellipse_major_axis_px` e `ellipse_minor_axis_px` são diâmetros completos. A orientação é a do semieixo maior em graus [0,180), a partir do eixo x da foto, no sentido horário em coordenadas com y crescente para baixo. A circularidade `roundness` de elipses ajustadas é derivada do perímetro aproximado da elipse, não do perímetro real segmentado.

## Compilar e verificar

JDK 17, SDK Platform 35, Build Tools 35.0.0, Gradle 8.9, AGP 8.7.3, OpenCV Android 4.12.0. `./gradlew assembleDebug testDebugUnitTest lintDebug`.

A chave pública de desenvolvimento em `signing/prototype.keystore` (alias androiddebugkey; senha android) mantém compatibilidade de atualização. Não é chave de publicação em produção. O projeto gera APKs ARM64 e x86_64, mas a entrega é ARM64.

Verificações atuais: `verification/ellipse-0.6.0`. Ensaios analíticos verificam eixos, razão e ângulo em 24 elipses de contraste/polaridade/orientação conhecidos; também testam região uniforme e objeto isolado alongado. Testes Android simulados cobrem ROI rotacionada/circular, média, exclusões, calibração de eixos, CSV/JSON e PNG sem alteração da original. Os relatórios PNG foram desenhados pelo compositor real do app em Robolectric com gráficos nativos; as detecções foram calculadas pelas mesmas classes Java em JNI desktop. Não são capturas da câmera de um telefone.

O código e os dados do treino da versão 0.5.0 continuam em `ml_training`; documentação e validações anteriores foram preservadas como histórico. Não houve execução em celular físico nem validação metrológica em amostras reais com verdade-terreno de forma 3D.
