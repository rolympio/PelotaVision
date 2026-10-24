# Correção do histograma — PelotaVision 0.2.1

## Alteração

A versão 0.2.0 rejeitava mais de 60 classes e mostrava o motivo somente em
Toast. Sem a calibração aplicada, permitia salvar o PNG apenas com círculos.
Isso explica um caminho possível para a ausência do gráfico. Os valores de
px/mm e largura usados no celular não foram informados; não foi confirmada
uma reprodução idêntica à tentativa do usuário.

A 0.2.1 aumenta automaticamente a largura para limitar o gráfico a 60 classes,
sem alterar os diâmetros individuais. A classe efetiva aparece no gráfico;
a solicitada e a efetiva constam do JSON. Erros de entrada permanecem visíveis
no diálogo. O botão abre a calibração quando necessário e somente grava o
resultado após aplicar uma escala válida.

O compositor usado no salvamento exige um histograma, inclusive quando todas
as regiões foram excluídas (nesse caso imprime n=0). O PNG de resultado é
`_resultado.png`, separado do `_original`. A confirmação oferece Abrir imagem
usando o URI do PNG de resultado.

## Verificado nesta entrega

- `assembleDebug lintDebug testDebugUnitTest`: concluído sem erros.
- Cinco testes em Robolectric 4.13 / Android API 33, com gráficos nativos:
  1. Aplicação da escala pelo diálogo real da atividade, aceitando vírgula
     decimal e mantendo o formulário aberto após valor inválido. Estado FOTO
     e resultados foram preparados pelo teste; a câmera não foi exercitada.
  2. PNG codificado e decodificado, contendo barras visíveis abaixo da foto,
     dimensões 380 × 516 e painel original 380 × 300.
  3. Distribuição vazia ainda produz rodapé explícito.
  4. Salvamento sem calibração é rejeitado antes de acessar MediaStore.
  5. Excesso de classes agrupa os valores, preservando diâmetros e contagem.
- Verificação matemática independente em JVM 17: escala original/reduzida,
  classes, percentuais, percentis, exclusões e agrupamento automático.
- Assinatura ARM64 igual à do APK 0.2.0; atualização sem desinstalação dessa
  versão. `zipalign -c -P 16 4` aprovado.

`photo-export-tests.xml` contém o resultado dos cinco testes.
`resultado_regressao.png` é uma imagem sintética produzida pelo compositor
real usado no app, com três diâmetros de teste. Não é uma análise da foto
anexada pelo usuário nem uma validação da medição física.

## Limites

Não houve teste de câmera ou gravação real em MediaStore em um celular físico.
O teste gráfico verifica o bitmap que ResultSaver codifica; não testa um
aplicativo de Galeria de fabricante. A precisão da segmentação e a calibração
metrológica não foram revalidadas. Testes antigos do detector são mantidos como
histórico e não foram reexecutados, pois o detector não mudou.

## Instalar e conferir

1. Instalar 0.2.1 sobre 0.2.0 (mesmo pacote e assinatura).
2. Fotografar ou abrir uma imagem e informar a escala em px/mm da foto original.
3. Aplicar. Conferir o gráfico e a classe efetiva na tela.
4. Salvar foto + gráfico e tocar em Abrir imagem.
5. Conferir o arquivo `_resultado.png`, que contém o rodapé do histograma.
   O `_original` preserva a foto sem as anotações.

Para repetir os testes: `./gradlew testDebugUnitTest` com JDK 17 e SDK 35.
