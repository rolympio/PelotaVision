# Auditoria de bordas — 4 de novembro de 2026

## Estado e limites

Na última consulta autenticada, a branch `studio/0.8-ia-interface` estava no
mesmo commit que sua contraparte `origin`; os PRs #27–#30 permaneciam abertos,
e os PRs #28–#30 eram rascunhos. A `main` ainda não incorporava o Studio 0.8.
A branch local `auditoria/bordas-preview` contém alterações não commitadas;
o executável deste ensaio inclui essas alterações além do commit base `c237e70`.

A prévia multitarefa adiciona visualizações separadas para borda e separação e
recorta a camada pela ROI circular. Não altera os pesos nem os cálculos científicos.
O modelo local foi treinado por 90 passos em CPU com três imagens e pseudo-rótulos;
não existe validação independente ou fundo revisado suficiente. Suas saídas não
devem ser usadas como referência, diagnóstico ou medida.

## Alternativas pesquisadas

- [StarDist](https://github.com/stardist/stardist): instâncias por polígonos
  radiais; já existe no projeto. O experimento privado em oito imagens compara
  máscaras com watershed, mas usa pós-processamento separado: não é paridade com
  o fluxo completo do aplicativo nem teste de acurácia.
- [Cellpose](https://github.com/MouseLand/cellpose): segmentação de instâncias;
  conferir licença dos pesos e dos dados específicos antes de uso industrial. O
  projeto informa código BSD-3 e dados de treinamento sob CC-BY-NC.
- [SAM 2](https://github.com/facebookresearch/sam2): segmentação interativa;
  possível auxílio à anotação, com custo e licença de cada peso a verificar.
- [Ultralytics](https://github.com/ultralytics/ultralytics): modelos de segmentação;
  verificar as opções AGPL-3.0/comercial e as licenças dos pesos e dados.
- [Detectron2](https://github.com/facebookresearch/detectron2): framework de
  segmentação; avaliar pesos, dados, custo de execução e implantação separadamente.

Nenhuma dessas redes foi demonstrada superior ou mais eficiente neste conjunto.
Ainda não foi treinada nem implantada uma nova rede alternativa validada. As
comparações carecem de anotações independentes e grupos de aquisição confirmados.

## Ensaio Windows

Compilação e testes Windows passaram em 04/11/2026. O primeiro empacotamento
falhou por falta de espaço em C:; uma segunda execução em pasta separada na
unidade E: gerou um aplicativo e executou autoteste, GUI, PDF de seis páginas e
prévia CPU com o modelo local. O modelo não está incluído no pacote. A captura
gráfica ainda requer revisão visual; não houve teste em máquina limpa nem Android.
O ZIP tem SHA-256
`E93C3F8985937D57A292F792402929CE3D024EE6D943803F73F5D7D4C3867B5A`.
O pacote é experimental, não uma release ou validação científica.

## Interface do Studio (versão de teste seguinte)

- Painel lateral com cartões padronizados (Escala, Resultado, Histograma,
  Rosin–Rammler, Legenda) e altura ajustada ao conteúdo atual.
- Histograma e curva acumulada com as mesmas dimensões e paleta escura do
  aplicativo **somente na tela**. As cores das séries são mantidas; figuras
  exportadas, PDF e JSON permanecem inalterados.
- Etapa Relatório: o PDF salvo abre na área central, com navegação por páginas;
  o painel de gráficos é recolhido. O histórico mostra o nome da pasta, e o
  caminho completo aparece na dica.
- O ensaio do pacote verifica a prévia do PDF (`pdf_preview_pages`) e grava uma
  captura da etapa. Nenhum cálculo científico foi alterado.

Pacote local de 04/11/2026: compilação, testes Windows, autoteste, ensaio GUI
e prévia de IA concluídos. ZIP SHA-256
`80B8D06592CC326A9BD36C88F2047E609B66DA26AB7FC05B6275CA6D06E04CE8`.
O arquivo de hash e o registro do pacote foram finalizados manualmente após uma
interrupção do ambiente; a conferência do ZIP encontrou 395 arquivos, iguais à
pasta do aplicativo em quantidade e bytes. A captura ainda requer revisão
humana; não houve teste em máquina limpa.
