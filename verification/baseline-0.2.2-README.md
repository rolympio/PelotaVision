# PelotaVision — Android 0.2.2

Aplicativo Android nativo para analisar pelotas visíveis na câmera, desenhar
círculos e fotografar. Protótipo experimental: as contagens não são validadas
como referência de laboratório ou granulometria física.

## Instalar

1. Transferir `PelotaVision_0.2.2_Android.apk` para o celular e abrir o arquivo.
2. Quando o Android solicitar, permitir que o aplicativo usado para abrir o
   arquivo instale esse APK.
3. Abrir PelotaVision e permitir acesso à câmera.

Compatibilidade do APK entregue: Android 10 ou superior, processador ARM64.
A compilação é de desenvolvimento, assinada para instalação direta.
A versão 0.2.2 mantém a assinatura da 0.2.0 e pode ser instalada como
atualização, sem desinstalar a 0.2.0 ou 0.2.1. Para a 0.1.0, a chave da primeira
entrega não estava disponível: pode haver conflito de assinatura e necessidade
de desinstalar essa versão antiga. Não há
publicação na Play Store. O projeto também gera um APK x86_64 para emuladores.

## Usar

- **Ao vivo:** apontar a câmera traseira para as pelotas e aguardar os círculos.
- **Foco:** tocar sobre a imagem. Manter o celular parado enquanto os círculos
  são atualizados; existe latência de processamento.
- **Fotografar:** capturar uma imagem nova e analisar a foto separadamente com
  maior resolução de processamento. A tela passa ao modo FOTO.
- **Calibrar e salvar / Salvar foto + gráfico:** exige uma escala aplicada.
  Se ela ainda não existir, abre a calibração e salva após Aplicar. Grava
  original, PNG `_resultado.png` com círculos + histograma, CSV de regiões,
  CSV de granulometria e JSON. Depois oferece **Abrir imagem** diretamente
  no PNG com gráfico. O `_original` não tem círculos nem gráfico. Nenhum
  arquivo de entrada é sobrescrito.
- **Câmera:** voltar à análise ao vivo. Uma foto não salva é descartada do cache.
- **Abrir foto:** selecionar um JPEG, PNG ou WebP existente; tamanho máximo 100 MB.
- **Ajustes:** escolher a faixa de tamanho aparente e a sensibilidade. Aplicar
  ajustes no modo FOTO reanalisa a mesma imagem.
- **Escala / mm:** no modo FOTO, informar pixels por milímetro da foto original
  orientada e a largura da classe do histograma. Aceita ponto ou vírgula decimal.
  O app ajusta a escala ao redimensionamento interno. Pode informar px/mm,
  calcular por comprimento de referência (pixels / mm) ou estimar pela média
  conhecida externamente. A prévia mostra média e faixa antes de aplicar.
- **Histograma:** aparece abaixo da foto e é incluído como rodapé no PNG salvo.
  Mostra percentuais por número e D10/D50/D90 por número. Por padrão exclui
  regiões cortadas na borda; a exclusão de alertas geométricos é opcional.
- **Luz:** ligar/desligar a lanterna quando a câmera oferecer esse recurso.
- **Números:** mostrar/ocultar IDs na tela. Os exports continuam numerados.

Fotos são gravadas em `Pictures/PelotaVision`, visíveis na Galeria. Os dados
ficam em `Download/PelotaVision`, acessíveis pelo aplicativo Arquivos.
O app usa somente a permissão de câmera; o salvamento usa MediaStore e a
seleção de fotos usa o seletor do Android. Não requer internet para analisar.

## Cores

| Cor | Interpretação |
|---|---|
| Verde | Região sem alerta geométrico específico; ainda é automática |
| Amarelo | Tamanho, forma ou deslocamento do centro sugerem revisão |
| Laranja | A região segmentada toca uma borda da imagem analisada |

As cores são heurísticas, não probabilidades de acerto. Pelotas encostadas ou
parcialmente cobertas podem ser agrupadas, omitidas ou receber círculos
inadequados. Verde não confirma que uma pelota foi identificada corretamente.
A revisão nesta versão é visual; não há editor manual para remover/adicionar
círculos no Android. O editor Python anterior continua disponível separadamente.

## Ajustes de tamanho

| Perfil | Diâmetro aparente como porcentagem da largura analisada |
|---|---|
| Pequenas | 2,6–7,6% |
| Médias (padrão) | 4,2–12% |
| Grandes | 8–21% |

Aproximar a câmera muda essas porcentagens. Se faltarem círculos, verificar o
perfil, o foco e as sombras antes de aumentar a sensibilidade. Aumentar a
sensibilidade também pode marcar textura ou espaços como objetos.
Não há zoom digital, seleção de lentes nem rastreamento individual nesta versão.

## Método e relação com o Python

A implementação porta a abordagem anterior para Java/OpenCV:

1. Copiar a luminância da imagem da câmera respeitando cropRect, rowStride,
   pixelStride e rotação. Na foto, aplicar orientação EXIF.
2. Redimensionar para uma resolução de trabalho limitada.
3. Aplicar GaussianBlur e Laplacian normalizado em várias escalas.
4. Procurar máximos espaciais e suprimir candidatos sobrepostos.
5. Criar uma máscara por limiar de Otsu e preencher buracos internos.
6. Fazer watershed por marcadores com uma fila de prioridade de 256 níveis
   sobre a luminosidade suavizada e conectividade de quatro vizinhos.
7. Aparar extensões distantes dos centros, filtrar fragmentos, medir regiões e
   desenhar círculos equivalentes à área projetada.

É a mesma família de métodos do protótipo, mas não uma execução do Python no
Android e não uma reprodução numérica exata. As aproximações do LoG, da
supressão e da circularidade podem alterar os resultados. O aplicativo não
contém rede neural treinada para reconhecer especificamente minério de ferro.
As regiões parecidas com pelotas também podem ser marcadas.

O processamento da prévia usa no máximo 640 pixels no maior lado e oito
escalas; a foto usa até 1024 pixels e doze escalas. O painel da foto anotada pode ter
até 2400 pixels no maior lado; o rodapé do histograma aumenta a altura total
do PNG. O original é mantido no tamanho capturado.
Na câmera, o app analisa somente o quadro mais recente, com intervalo mínimo
220 ms entre inícios, descartando intermediários. A prévia pode continuar
fluida sem que todos os seus quadros sejam analisados. Não há promessa de
30 análises/s ou de taxa mínima em qualquer celular.

A atividade é orientada a celulares em retrato. CameraX agrupa Preview,
ImageAnalysis e ImageCapture com ViewPort compartilhado. Luminância e fotos são
orientadas antes da análise; os overlays são centrados com escala FIT_CENTER.
Validar o alinhamento no dispositivo é parte do teste de campo, especialmente
em aparelhos com configurações de câmera diferentes.

## Dados exportados

CSV/JSON incluem ID, centro x/y, raio e diâmetro equivalente em pixels, área
projetada, circularidade aproximada, região parcial na borda e alerta de revisão.
Os pixels se referem ao painel da foto, no canto superior esquerdo do PNG
anotado já orientado. O rodapé do histograma aumenta a altura do PNG;
`photo_panel_width/height` delimitam a área da foto no JSON. Não se referem necessariamente ao JPEG original de maior resolução.
Os IDs são reordenados por posição em cada análise e não acompanham o mesmo
objeto ao longo do vídeo.

O JSON inclui parâmetros, tempo de análise, método e indicação de resultados
não revisados. A foto original mantém o EXIF; a imagem anotada está orientada e
redimensionada explicitamente. Com escala informada, CSV/JSON incluem diâmetro equivalente em mm, área em mm²
e indicação de inclusão no histograma. Um CSV adicional `_granulometria.csv`
contém limites, contagens e percentuais por classe. Sem escala, o histograma físico não aparece e o app solicita a calibração
antes de salvar, evitando uma imagem de resultado sem gráfico. A escala é resetada
a cada foto nova, pois distância, zoom e recorte podem mudar a relação px/mm.
Ajustar a detecção na mesma foto preserva a calibração.

A distribuição é POR NÚMERO, não por massa. É uma estimativa dos diâmetros
equivalentes à área projetada visível; não substitui granulometria por
peneiramento. Perspectiva, profundidade, sombras e oclusão podem causar viés.
Usar uma referência no mesmo plano da amostra; por exemplo, 192 px / 12 mm
correspondem a 16 px/mm NA FOTO ORIGINAL usada para calibrar. A medida deve
ser em pixels do arquivo original orientado, não pixels físicos da tela.

A largura da classe NÃO é a escala física. Com escala correta, uma amostra
com diâmetros entre 8 e 16 mm e classe de 1 mm gera intervalos 8–9, 9–10,
10–11, …, 15–16 mm. O eixo horizontal mostra os limites em mm; o vertical
mostra o percentual por número. A média e a faixa observada aparecem acima.
O gráfico cobre da primeira à última classe ocupada e mantém as classes vazias
entre elas. Limites são múltiplos da largura escolhida. Classes seguem [a,b),
com a última classe incluindo também o limite superior [a,b]. Um valor exato
no limite interno pertence à classe à direita; um valor no limite superior
final pertence à última classe.

A largura solicitada é preservada, sem aumento automático. Até 5000 classes
são permitidas; acima disso, a calibração informa um erro e mantém o formulário
aberto, solicitando uma largura maior. Na tela e no PNG, somente alguns rótulos
do eixo são mostrados quando há muitas classes, mas as barras, contagens e
limites do CSV permanecem na largura escolhida.

**Calibração:** 60 px/mm significa que 60 pixels do arquivo original representam
1 mm. Se a média medida ficar em 0,7 mm para uma amostra conhecida como 12 mm,
alterar a largura da barra não corrige o problema: é necessário conferir a
referência e a escala. O app não reutiliza escala de outra foto.

**Por referência:** informar o comprimento da referência em pixels da foto
original e seu comprimento real em mm. Ex.: 240 px / 12 mm = 20 px/mm.
A referência precisa estar no mesmo plano das pelotas; não medir em pixels da
tela ou de uma miniatura. Este modo registra ambos os comprimentos no JSON.

**Estimar por média conhecida:** informar a média aritmética em mm da MESMA
amostra, obtida externamente. O app divide a média dos diâmetros incluídos em
pixels pela média informada, corrigindo o redimensionamento. Não preenche 12 mm
por padrão e não força limites 8–16 mm. O gráfico, a interface e o JSON
identificam a escala como ESTIMADA; a média deixa de ser uma medição independente.
Reanalisar a mesma foto recalcula essa estimativa com os novos diâmetros
incluídos. Para medir uma média desconhecida, usar uma referência física ou
uma escala px/mm já determinada. As exclusões escolhidas definem a população
usada para estimar a média.

Percentuais usam somente as regiões incluídas. D10/D50/D90 usam interpolação
linear dos diâmetros ordenados em (n−1)q e também são por número. O contador
principal continua mostrando todas as detecções, enquanto o gráfico mostra
o n incluído e o total excluído. Os círculos verdes continuam automáticos.

Os cinco arquivos são criados pendentes e publicados após gravar os dados.
Se o salvamento falhar durante a operação, o app tenta remover os arquivos
parciais. Interrupções abruptas do processo podem deixar entradas pendentes,
que o sistema gerencia; a gravação não é uma transação atômica entre coleções.

## Compilar no Android Studio

1. Abrir esta pasta como projeto.
2. Usar JDK 17 e instalar SDK Platform 35 / Build Tools 35.0.0 pelo SDK Manager.
3. Permitir a sincronização Gradle das dependências.
4. Executar o módulo app em um dispositivo ou gerar o APK debug.

Pela linha de comando:

```bash
./gradlew assembleDebug lintDebug testDebugUnitTest
```

No Windows:

```bat
gradlew.bat assembleDebug lintDebug testDebugUnitTest
```

Dependências fixadas:

- Gradle 8.9 e Android Gradle Plugin 8.7.3
- CameraX 1.4.2
- AndroidX Activity 1.9.3
- ExifInterface 1.3.7
- OpenCV Android 4.12.0 (Maven Central)
- Robolectric 4.13, somente para testes; não integra o APK

Os APKs são gerados em `app/build/outputs/apk/debug/`. A chave de protótipo `signing/prototype.keystore` está incluída para manter
a assinatura desta versão em compilações futuras. Alias `androiddebugkey`,
senha `android`. É uma chave de desenvolvimento, não uma identidade segura
para publicação. Não usar essa chave em uma versão de produção. Para distribuição
institucional, configurar uma chave de assinatura própria e um processo de
atualização. Não foi feita publicação externa.

## Verificação

- Compilação Android, análise estática lint e verificação da assinatura do APK.
- Testes Robolectric com gráficos nativos: PNG codificado/decodificado com
  barras e rodapé, caso vazio, bloqueio de exportação sem escala e preservação
  de classes fixas e dos modos de calibração. Ver detalhes em `verification/VALIDACAO.md`.
- Bibliotecas ARM64 e alinhamento do APK verificados para páginas de 16 KB.
- Detector Java exercitado com JNI OpenCV em desktop: imagem vazia, quatro
  discos encostados e três fotos reais, verificando contagem sintética,
  posições/raios sintéticos e coordenadas finitas dentro dos limites.
- `verification/detector-results.txt` contém os resultados; tempos de desktop
  não são medições de desempenho em celular. JNI de desktop usa OpenCV 4.9,
  enquanto a compilação Android usa 4.12.
- O relatório complementar `verification/VALIDACAO.md` descreve o teste de
  execução e quaisquer limites observados na entrega.
- Não há métricas de precisão/recall, validação metrológica, teste em minério
  em movimento ou teste físico no celular do usuário.

A verificação dos cálculos de calibração, classes, percentuais e percentis
está em `verification/GranulometryCheck.java`, sem dependência Android/OpenCV:

```bash
mkdir -p verification/classes
javac -d verification/classes app/src/main/java/br/com/pelotavision/Granulometry.java verification/GranulometryCheck.java
java -cp verification/classes GranulometryCheck
```

Para repetir a verificação independente do detector no desktop, obter o JAR
`org.openpnp:opencv:4.9.0-0`, que contém JNI de desktop, e compilar/executar
`verification/DetectorCheck.java` junto com `PelletDetector.java`. Esse JAR é
somente ferramenta de teste; não integra o APK Android.

## Referências oficiais

- CameraX análise: https://developer.android.com/media/camera/camerax/analyze
- CameraX captura: https://developer.android.com/media/camera/camerax/take-photo
- CameraX ViewPort: https://developer.android.com/media/camera/camerax/configuration
- Transformações: https://developer.android.com/media/camera/camerax/transform-output
- OpenCV Android: https://docs.opencv.org/4.12.0/d5/df8/tutorial_dev_with_OCV_on_Android.html
