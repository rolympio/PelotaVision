# Validação — PelotaVision Android 0.7.0

18/10/2026. Nova calibração de escala 2D por uma pelota ou esfera padrão escolhida explicitamente pelo usuário. Mantém o detector elíptico e o classificador RF; não houve treinamento de modelo nesta versão.

## Fluxo e cálculo

Toque na elipse → painel de revisão → Calibrar → escolha de eixo maior, eixo menor ou esfera padrão → medida externa em mm → prévia → Aplicar escala. A escolha do objeto é sempre humana. O aplicativo aceita vírgula/ponto, mostra a dimensão amarela no recorte e calcula px/mm em pixels da imagem original orientada. A esfera usa o diâmetro equivalente à área da elipse. A transformação inversa da covariância da elipse corrige escalas horizontal/vertical distintas antes de definir o eixo real medido.

A referência possui UUID, data, índice da imagem completa, número exibido ao selecioná-la, geometria e dimensões da foto. Não há busca automática de outro objeto. Nova foto limpa a referência. Reanálise conserva apenas índice/geometria/dimensões idênticos. A ROI conserva a escala e a marca REF mesmo fora da seleção. Remover a escala restaura o objeto às estatísticas, respeitada a ROI.

Esfera padrão é excluída por padrão; pelota medida permanece incluída. A opção é reversível e afeta conjuntamente contagem de pelotas, trincas, média b/a e histograma. Revisões manuais são conservadas na lista completa. O objeto excluído permanece destacado como REF e sua medida aparece no PNG e nos metadados JSON/CSV. A original permanece sem marcações.

## Testes e build

41 testes, zero falhas/erros: PhotoExport 9, CrackExport 5, Region 10, LearnedModel 3, EllipseWorkflow 5, ObjectCalibration 9. Os nove novos testes cobrem:

- Escala pelos três modos e diferença entre eixo medido e diâmetro equivalente; não força a média.
- Elipse rotacionada e transformação anisotrópica, comparadas com 20.000 pontos de contorno originais.
- Rejeição de zero, valores negativos/não finitos e contornos parciais/sem ajuste; referência nunca religada a outra detecção.
- Exclusão da referência e ROI com índices estáveis, metadados e contagens separadas.
- Toque no objeto pelo fluxo real de revisão, abertura do formulário, prévia, cancelamento, erro e aplicação com vírgula decimal.
- Esfera excluída editável, referência fora da ROI, conservação pelo painel Escala / mm, remoção e limpeza da foto.
- Troca de calibração por estimativa de média usando a população restaurada, incluindo o objeto antes excluído.
- Exportação pelo ResultSaver real com MediaStore simulado: PNG, original idêntica, JSON, 32 colunas CSV e REF visível.
- Renderização do formulário Android real e composição de demonstração com geometria simulada.

`assembleDebug testDebugUnitTest lintDebug`: concluídos. Lint: zero erros, 64 avisos (incluem internacionalização, acessibilidade, orientação e alocações em desenho); relatório completo em lint.txt. APK ARM64, Android 10+, versão 0.7.0, código 9. Assinatura v2 e mesmo certificado de desenvolvimento das versões anteriores, alinhamento ZIP 16 KiB aprovado. Não exige desinstalação para atualizar.

## Imagens e limites

Exemplo_Calibracao_0.7.0.png foi desenhado pelo PhotoComposer do aplicativo, em Robolectric com gráficos Android nativos. Usa oito formas geométricas simuladas, com um padrão informado como 12 mm (escala original 10 px/mm), excluído, e sete objetos na amostra. Classes de 1 mm, faixa observada 8,2–15,8 mm, eixo de 8–16 mm. O cabeçalho identifica a demonstração; não representa medição com paquímetro de amostras reais. Tela_Calibracao_0.7.0.png é a interface real renderizada em ambiente simulado com foto uniforme de teste; não é captura de telefone físico. scenes contém relatórios das sete cenas experimentais anteriores redesenhados pelo compositor atual, sem calibrar essas fotos com uma medida física inventada.

PNG: encode Android, checagem IEND, gravação atômica, leitura integral Pillow. As imagens de 0.6.0 foram preservadas do pacote anterior. Os testes atuais direcionam os novos relatórios para reference-0.7.0/scenes.

Sem ensaio em aparelho físico, câmera/Galeria reais, validação metrológica ou build iOS. Esta escala depende da seleção humana, da medida externa correta, do contorno detectado e de plano/profundidade comparáveis. Não corrige perspectiva nem distorção de lente. As limitações experimentais do classificador de trincas e da esfericidade aparente 2D permanecem; consulte ellipse-0.6.0/VALIDACAO.md e README.md.

Evidências: TEST-*.xml, build-full-suite.log, lint.txt, apk-signature.txt, apk-manifest.txt, apk-alignment.txt, artifact_hashes.json, png-integrity.json e runtime-results.json. O único ajuste posterior à suíte completa foi o diretório de saída do teste de cenas, sem alterar os dados, os cálculos ou as asserções; o teste foi repetido conforme registro separado.
