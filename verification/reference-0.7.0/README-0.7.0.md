# PelotaVision — Android 0.7.0

A versão 0.7.0 acrescenta calibração por um objeto escolhido explicitamente na foto. Mantém as elipses ajustadas ao contorno e a **esfericidade aparente 2D (b/a)**. Uma foto isolada não mede esfericidade 3D. O classificador de trincas Random Forest da versão 0.5.0 permanece embarcado, sem novo treinamento.

## Usar

Instale `PelotaVision_0.7.0_Android.apk` sobre a versão anterior: pacote e certificado de desenvolvimento foram preservados. Android 10+ ARM64. Não é necessário desinstalar. Esta atualização não compila nem atualiza iOS.

Fotografe ou abra uma imagem. Os marcadores passam a usar elipses com eixos e orientação ajustados aos gradientes do contorno. A indicação **Esfericidade aparente 2D (b/a)** aparece na tela ao vivo e na foto; toque nela para ver a definição. Um ajuste não confiável fica tracejado e não entra na média, sem receber artificialmente um índice 1. As pelotas continuam disponíveis para contagem e revisão de trincas.

A média é aritmética por número, apenas entre elipses ajustadas, inteiras na imagem, sem alerta e dentro da área de interesse, retirando também a referência quando o usuário optar por excluí-la. A tela e o relatório mostram n e excluídas. Não há média ponderada por área ou massa. Caso nenhuma pelota seja válida, aparece “—” e o JSON registra null. A média representa somente esse subconjunto; exclusões podem enviesá-la.

**b/a = eixo menor / eixo maior**, com a e b os semieixos (ou diâmetros, pois a razão é igual). Varia entre 0 e 1; 1 representa uma projeção circular e não comprova uma esfera 3D. Exemplo: eixos de 12 e 10 mm produzem b/a=0,833. Esse indicador não depende da calibração px/mm. Perspectiva, foco, sombras, sobreposição e contornos incompletos podem alterar o ajuste.

## Calibrar tocando na referência

1. Fotografe ou abra a imagem e espere a identificação das pelotas. Se usar uma esfera padrão, coloque-a no mesmo plano e distância das pelotas, com boa visibilidade do contorno.
2. Toque na elipse da pelota que você consegue localizar fisicamente. No mesmo painel usado para revisar trincas, toque em **Calibrar**. Se o classificador estiver indisponível, o toque abre a calibração diretamente. O aplicativo nunca escolhe sozinho qual objeto é o padrão.
3. Na imagem ampliada, escolha **Pelota · eixo maior**, **Pelota · eixo menor** ou **Esfera padrão · diâmetro**. A linha amarela indica a dimensão. Meça com paquímetro o mesmo eixo e informe o valor em **mm** (aceita vírgula ou ponto). Na esfera, informe o diâmetro conhecido.
4. Confira a prévia de px/mm, eixos em mm, média e número de classes. Escolha a largura de classe (por exemplo, 1 mm) e toque em **Aplicar escala**. A escala de toda a foto e o histograma são recalculados imediatamente.
5. A referência recebe a marca **REF**. Toque nela para editar a medida. Em uma pelota incluída na amostra, o toque abre o painel de revisão, com o botão Calibrar; em uma referência excluída ou fora da ROI, abre diretamente a calibração.

A esfera padrão fica **excluída das estatísticas por padrão**. A pelota medida com paquímetro permanece incluída por padrão. O campo **Excluir referência das estatísticas** permite alterar essa escolha. A exclusão remove o objeto da contagem de pelotas, contagem de trincas, média de forma e histograma; o contorno REF continua visível. As revisões manuais ficam preservadas na lista completa e reaparecem se o objeto for reincluído. Não confunda um número renumerado na ROI com a identidade original da referência.

Só é possível calibrar por uma elipse inteira, ajustada e sem alerta. Contorno errado gera escala errada: confira a sobreposição antes de aplicar. O formulário amplia a referência, mas não oferece ajuste manual de sua borda nesta versão. **Cancelar** ou informar medida inválida mantém a escala anterior. A escolha da dimensão importa: para eixos de 12 e 10 mm, informar 12 mm como eixo maior não torna o diâmetro equivalente igual a 12 mm.

**Cálculo:** px/mm = dimensão da elipse na imagem original orientada / dimensão real informada. Para a esfera padrão, usa-se o diâmetro equivalente à área, √(eixo maior × eixo menor); os dois eixos são mostrados para conferência. Uma projeção alongada exibe uma observação. Para os eixos maior e menor, a elipse é mapeada de volta à imagem original antes da medição, inclusive se os fatores de redimensionamento horizontal e vertical diferirem. O histograma continua usando diâmetro equivalente à área e classes definidas em mm, sem forçar uma média ou faixa.

**Vínculo à foto:** a referência e sua escala não passam para outra imagem. Alterar a ROI conserva a escala e a marca REF, inclusive fora da área; os objetos da amostra são recalculados. Reanalisar a mesma foto conserva a calibração apenas quando o mesmo índice de objeto, dimensões e geometria permanecem idênticos; se o contorno mudar, a referência é removida e deve ser escolhida novamente. Não há busca automática de uma referência semelhante. Outra distância, zoom ou plano exige nova referência.

**Escala / mm** mantém as opções manuais e acrescenta **Usar objeto selecionado na foto** para mudar as classes/exclusões sem perder a referência. **Remover escala** elimina também a referência e sua exclusão, retornando ao histograma em pixels; as regras da ROI continuam valendo.

**Registro:** o PNG mantém original → imagem com marcações/REF → resumo da medida e escala → histograma. O JSON `physical_scale.selected_object` registra seleção pelo usuário, identificador único, data, índice da detecção na imagem completa, número exibido quando escolhida, tipo de medida, valor em mm, comprimento em pixels originais, px/mm, elipse original/analisada e exclusão. O CSV acrescenta seis colunas de calibração, totalizando 32; as 26 anteriores mantêm posição. Quando a referência estiver excluída, ela fica no bloco de calibração do JSON, sem aparecer como uma pelota da amostra.

Esta é uma **calibração de escala 2D por uma referência**, sem correção de perspectiva, distorção de lente ou diferenças de profundidade. A medida digitada não é verificada pelo aplicativo. Uma referência bem medida não corrige contornos detectados incorretamente nem garante equivalência com granulometria por peneiramento.

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

Verificações da calibração: `verification/reference-0.7.0`; histórico geométrico: `verification/ellipse-0.6.0`. Ensaios analíticos verificam eixos, razão e ângulo em 24 elipses de contraste/polaridade/orientação conhecidos; também testam região uniforme e objeto isolado alongado. Testes Android simulados cobrem ROI rotacionada/circular, média, exclusões, calibração de eixos, CSV/JSON e PNG sem alteração da original. Os relatórios PNG foram desenhados pelo compositor real do app em Robolectric com gráficos nativos; as detecções foram calculadas pelas mesmas classes Java em JNI desktop. Não são capturas da câmera de um telefone.

O código e os dados do treino da versão 0.5.0 continuam em `ml_training`; documentação e validações anteriores foram preservadas como histórico. Não houve execução em celular físico nem validação metrológica em amostras reais com verdade-terreno de forma 3D.
