# PelotaVision — Android 0.8.0

A versão 0.8.0 acrescenta a busca de um ajuste inicial de detecção, comparando configurações na mesma foto. Conserva a calibração por um objeto escolhido explicitamente na foto. Mantém as elipses ajustadas ao contorno e a **esfericidade aparente 2D (b/a)**. Uma foto isolada não mede esfericidade 3D. O classificador de trincas Random Forest da versão 0.5.0 permanece embarcado, sem novo treinamento.

## Usar

Instale `PelotaVision_0.8.0_Android.apk` sobre a versão anterior: pacote e certificado de desenvolvimento foram preservados. Android 10+ ARM64. Não é necessário desinstalar. Esta atualização não compila nem atualiza iOS.

Fotografe ou abra uma imagem. Os marcadores passam a usar elipses com eixos e orientação ajustados aos gradientes do contorno. A indicação **Esfericidade aparente 2D (b/a)** aparece na tela ao vivo e na foto; toque nela para ver a definição. Um ajuste não confiável fica tracejado e não entra na média, sem receber artificialmente um índice 1. As pelotas continuam disponíveis para contagem e revisão de trincas.

A média é aritmética por número, apenas entre elipses ajustadas, inteiras na imagem, sem alerta e dentro da área de interesse, retirando também a referência quando o usuário optar por excluí-la. A tela e o relatório mostram n e excluídas. Não há média ponderada por área ou massa. Caso nenhuma pelota seja válida, aparece “—” e o JSON registra null. A média representa somente esse subconjunto; exclusões podem enviesá-la.

**b/a = eixo menor / eixo maior**, com a e b os semieixos (ou diâmetros, pois a razão é igual). Varia entre 0 e 1; 1 representa uma projeção circular e não comprova uma esfera 3D. Exemplo: eixos de 12 e 10 mm produzem b/a=0,833. Esse indicador não depende da calibração px/mm. Perspectiva, foco, sombras, sobreposição e contornos incompletos podem alterar o ajuste.

## Sugestão inicial de detecção — 0.8.0

**Ao abrir ou fotografar**, o aplicativo testa os quatro perfis de tamanho aparente com sensibilidades de 55%, 75% e 90%. O ajuste atual é testado primeiro; se ele não coincidir com uma dessas 12 opções, é acrescentado, totalizando 13. A alternativa LoG participa se já for o ajuste atual; não é varrida em todos os perfis. São avaliados os resultados do detector com o ajuste elíptico real, na mesma resolução usada para análise de foto; não se escolhe pela aparência de um histograma gerado artificialmente.

A busca roda fora da interface e mostra opção/progresso. Há orçamento de 30 segundos, verificado entre opções: uma chamada nativa já em andamento não é interrompida, portanto não é um limite rígido de duração total. **Cancelar busca** termina após a opção em andamento e usa o ajuste atual, continuando a análise da foto. A análise de trincas é executada apenas no resultado escolhido. Na câmera ao vivo continua sendo usado um único ajuste; a comparação ocorre na foto parada.

O melhor escore entre as opções efetivamente testadas inicia a foto, com aviso se houver pouca evidência. **Sugestão aplicada · ver**, **Novo ajuste sugerido · ver** ou **Sugestão · revisar** abre a comparação. Também é possível entrar por **Ajustes → Ver sugestão**. A lista mostra ajuste, escore, elipses válidas/total, sobreposições, contagem abaixo do mínimo quando disponível e discrepância descritiva de Rosin–Rammler. É uma heurística experimental, não uma garantia de melhor contagem, probabilidade de acerto ou validação de todos os contornos.

**Critérios:** bordas apoiadas e resíduo baixo; proporção de elipses válidas; número relativo de elipses válidas entre as opções; concordância geométrica com outros ajustes; penalidades por sobreposição, borda e limite de candidatos. A concordância é calculada na imagem inteira, com correspondência um a um por posição e raio, sem usar rótulos de trincas. ROI e referência excluída afetam os demais critérios de contagem/tamanho. A região da referência explicitamente excluída é ignorada na pontuação; isso não identifica nem religa automaticamente uma referência em outro resultado.

**Rosin–Rammler é Weibull, não normal.** O app ajusta Weibull de dois parâmetros aos diâmetros equivalentes observados, por **número**, não por massa. O ajuste só é usado com pelo menos 12 elipses válidas e variação suficiente. Emprega máxima verossimilhança numérica, com forma limitada ao intervalo 0,15–100, e distância KS descritiva da distribuição ajustada. Não é teste KS com p-valor calibrado: os parâmetros são estimados da própria amostra. A aderência vale no máximo 5 pontos e não corrige nem altera os diâmetros, nem transforma uma população irregular em uma curva esperada. A ausência de ajuste disponível usa bônus neutro de 2,5 pontos. Uma distribuição real pode ser diferente de Rosin–Rammler.

**Mínimo esperado de 5 mm:** aparece em Ajustes e pode ser alterado (0 desativa). Só elipses válidas entram na contagem abaixo desse mínimo. Só elipses válidas entram na contagem abaixo desse mínimo. Só penaliza opções quando houver escala física externa: px/mm digitado, referência de comprimento medida ou objeto escolhido e medido. Sem calibração, ou com escala estimada pela média conhecida, o critério fica inativo. Converter uma suposição de média em mm e depois usar esses mesmos mm para justificar a detecção seria circular. O mínimo é uma hipótese de processo informada pelo usuário, não uma verdade descoberta pelo app. Objetos menores **não são apagados, excluídos nem têm seus diâmetros ajustados** por essa regra; continuam visíveis, revisáveis e elegíveis ao histograma conforme as exclusões usuais.

**Depois de calibrar ou mudar a ROI**, o app recalcula a recomendação entre resultados já testados, usando os novos critérios. Não troca contornos ou referências automaticamente. Na comparação, **Manter atual** conserva a análise; **Usar selecionado** aplica a opção escolhida. Trocar contornos reanalisa trincas, descarta revisões manuais e pode remover a calibração por objeto se o índice/geometria da referência tiver mudado, exigindo seleção humana novamente. A escala por objeto nunca é transferida para uma pelota supostamente parecida. **Testar novamente** refaz a busca na foto atual.

**Ajuste manual:** em Ajustes, desmarque **Sugerir ajuste ao abrir ou fotografar** e use **Aplicar manual**. Perfil e sensibilidade continuam disponíveis. A seleção automática de uma foto não muda silenciosamente o ajuste global da câmera ao vivo ou das próximas fotos; cada nova foto com sugestão habilitada recebe sua própria comparação.

**Rastreabilidade:** o JSON de saída acrescenta `detection_suggestion`, com parâmetros das opções, escores/componentes, quantidades, limite esperado e sua ativação, modelo Weibull, duração e interrupções, opção aplicada e recomendada. Se não houve busca, o bloco é null. O CSV mantém suas 32 colunas; formato PNG, elipses, trincas, calibração e histograma seguem o fluxo anterior.

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

Verificações da sugestão: `verification/autotune-0.8.0`. Verificações da calibração: `verification/reference-0.7.0`; histórico geométrico: `verification/ellipse-0.6.0`. Ensaios analíticos verificam eixos, razão e ângulo em 24 elipses de contraste/polaridade/orientação conhecidos; também testam região uniforme e objeto isolado alongado. Testes Android simulados cobrem ROI rotacionada/circular, média, exclusões, calibração de eixos, CSV/JSON e PNG sem alteração da original. Os relatórios PNG foram desenhados pelo compositor real do app em Robolectric com gráficos nativos; as detecções foram calculadas pelas mesmas classes Java em JNI desktop. Não são capturas da câmera de um telefone.

O código e os dados do treino da versão 0.5.0 continuam em `ml_training`; documentação e validações anteriores foram preservadas como histórico. Não houve execução em celular físico nem validação metrológica em amostras reais com verdade-terreno de forma 3D.
