# Classes e calibração — PelotaVision 0.2.2

## Correção

O anexo do usuário mostra D50 de 0,70 mm, escala informada de 60 px/mm e classe
1 mm; com esses valores, um único intervalo 0–1 mm é esperado. A foto, por si
só, não fornece uma escala física verificável. Não foi presumido que cada
pelota mede 12 mm, nem inventada uma faixa de 8–16 mm para a foto.

O app distingue escala física e largura da classe, mantém a classe solicitada
e mostra limites em mm sobre a faixa observada, em vez de iniciar todas as
classes em zero. Gaps entre classes ocupadas são mantidos. O último intervalo
inclui o limite superior final. Média, mínimo e máximo aparecem no gráfico.
O mesmo renderer produz a tela e o rodapé do PNG salvo.

A calibração permite informar px/mm, calcular pixels/comprimento real de uma
referência, ou escolher explicitamente uma média conhecida externamente.
O último modo é identificado como estimativa na interface, no gráfico e no
JSON: a média informada define a escala e não é uma medição independente.
A prévia do diálogo mostra faixa, média, escala e número de classes antes de
aplicar. Um resultado que cai em uma classe orienta a conferir a calibração.

## Verificação e evidências

A verificação matemática em `GranulometryCheck.java` passou: faixa 8–16 mm,
média 12 mm, oito classes de 1 mm, percentis invariantes à largura, limites,
classes vazias, arredondamento decimal, exclusões, vazio, correção do tamanho
original/reduzido, referência, estimativa explícita da média e escala errada.
Mais de 5000 classes produz erro em vez de alterar a largura.

`histograma_8_a_16_mm.png` foi gerado pelo compositor real Android em um teste
com dados sintéticos conhecidos. Não é uma análise da foto anexada. Os testes
Robolectric usam gráficos nativos e codificam/decodificam PNG para verificar
barras no arquivo. O teste de diálogo prepara estado FOTO e detecções e aciona
os controles reais; não exercita a câmera.

Nove testes Robolectric passaram, sem erros ou falhas. A compilação ARM64 e
x86_64 e o lint passaram (0 erros; 29 avisos, principalmente strings de
interface). O certificado SHA-256 do APK coincide com o da 0.2.1, e a
verificação `zipalign -c -P 16 4` passou. A câmera física não foi exercitada.

O detector não foi alterado. Resultados históricos de detector e versões
anteriores foram preservados e não constituem testes novos.

## Limites

Não há teste de câmera ou gravação real em MediaStore em celular físico nesta
entrega. O teste gráfico verifica o bitmap que ResultSaver codifica; não testa
um aplicativo de Galeria de fabricante. A calibração metrológica precisa de
uma referência física no mesmo plano da amostra. Não houve nova validação de
precisão da segmentação, peneiramento ou desempenho em dispositivo.

## Conferir no celular

1. Instalar 0.2.2 sobre 0.2.0 ou 0.2.1, com a mesma assinatura.
2. Abrir/fotografar a amostra e tocar em Escala / mm.
3. Informar uma escala válida ou calcular por referência e manter classe 1 mm.
4. Conferir a prévia em mm e aplicar. O eixo é calculado dos diâmetros medidos.
5. Salvar foto + gráfico e abrir o arquivo `_resultado.png`.

Se a média de 12 mm for conhecida externamente para essa mesma amostra,
pode selecionar Estimar por média conhecida e digitar 12. O resultado fica
marcado como estimativa e os limites são derivados da dispersão detectada,
sem impor a faixa de 8–16 mm.

Repetir: `./gradlew assembleDebug lintDebug testDebugUnitTest`, JDK 17, SDK 35.
