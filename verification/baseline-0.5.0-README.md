# PelotaVision — Android 0.5.0

Protótipo de identificação de pelotas, granulometria por imagem e triagem experimental de possíveis trincas na superfície visível. A versão 0.5.0 incorpora um classificador Random Forest treinado localmente com as imagens enviadas. Uma indicação não confirma uma trinca, e ausência de indicação não garante uma pelota sem trincas.

## Novidades da versão 0.5.0

- Modelo offline embarcado (aproximadamente 284 KiB): 209 pelotas inteiras, sete cenas, 37 rótulos positivos provisórios. Pontos vermelhos e cópias não entram como novos exemplos.
- Perfil **Automático**, com propostas circulares por gradientes na luminância original e com equalização local CLAHE. O novo perfil inicia no primeiro uso desta versão. Os três perfis de tamanho continuam disponíveis.
- **Ajustes → Método anterior** permite escolher LoG/área segmentada para imagens irregulares nas quais círculos por gradiente omitam objetos. A troca reanalisa a foto e descarta revisões, como os outros ajustes.
- Trincas: classificador por pelota, faixa inconclusiva, revisão por toque, escore visível na revisão e traçados candidatos realçados em vermelho. A análise de trincas ocorre na foto parada, não ao vivo.
- CSV/JSON incluem escore, identificador do modelo, método do detector e estado revisado. ROI, calibração e saída original → marcações → histograma foram preservados.

**Limitação comprovada:** na validação por cena, o classificador detectou 25 de 37 positivas com limiar 0,5, com 3 falsos positivos. Com a faixa padrão do app (0,4–0,6), houve 25 positivas indicadas, 0 falsos positivos, 3 inconclusivas e 12 positivas classificadas sem indicação. Isso considera círculos anotados, sem erros de detecção e sem gates de qualidade; não é validação do processo inteiro. Uma cena sintética teve 0/8 positivas recuperadas quando deixada fora do treino. São rótulos do assistente, não homologados. É necessário teste externo em fotos reais, sem marcações e com rótulos de especialista.

Nas cenas usadas para desenvolver o detector, foram localizadas 203/209 pelotas inteiras originais. Variantes simuladas de iluminação resultaram em 193–206/209. Os círculos são aproximados: ainda há omissões, deslocamentos e falsas marcações. O modo de gradientes usa o diâmetro do círculo ajustado, área=pi*r² e circularidade 1 imposta; essa circularidade não é uma medição da forma. LoG mantém a área visível segmentada. Nenhum modo converte pixels em mm sem a calibração da foto.

Treinamento, imagens limpas, círculos revisáveis e pesos estão em `ml_training`. Evidências atuais: `verification/ml-0.5.0`; verificações de versões anteriores continuam como histórico. Não houve execução em celular físico nem compilação iOS nesta atualização.

## Área de interesse por toque — nova na versão 0.4.0

Após fotografar ou abrir uma imagem, toque em **Área**. O editor ocupa a tela
para facilitar os gestos. Escolha Retângulo ou Círculo, arraste o interior para
mover, use as alças brancas para ajustar ou faça pinça com dois dedos.
O retângulo possui oito alças (quatro lados e quatro cantos); o círculo possui
quatro alças e continua circular ao ajustar por qualquer lado. A seleção não
sai da imagem. Restaurar retorna à seleção inicial; Imagem inteira remove a
restrição; Cancelar não modifica a área aplicada. Aplicar atualiza a análise.

Apenas pelotas cuja **circunferência inteira** cabe na seleção são incluídas.
Objetos fora ou que cruzam a borda da ROI são excluídos da contagem de pelotas,
trincas e histograma. A tela informa a quantidade excluída. A seleção de área
é feita na foto parada, não na prévia ao vivo. Uma área sem pelotas também pode
ser salva, com contagem zero e histograma vazio.

Não há recorte, zoom ou redimensionamento por essa operação: o detector usa o
contexto da foto completa e a ROI filtra suas detecções. As coordenadas e a
relação px/mm permanecem na mesma imagem orientada. Revisões de trincas ficam
associadas às detecções completas e reaparecem ao expandir ou remover a ROI.
Reanalisar mantém a área e a calibração, mas refaz as detecções/revisões.
Uma foto nova começa com a imagem inteira e requer sua própria escala.

No relatório, somente o painel anotado mostra o limite ciano e o exterior
escurecido; a original superior e o arquivo original separado permanecem sem
marcações. O JSON inclui forma, limites em pixels da foto analisada, regra de
inclusão e quantidade fora/cruzando o limite. CSV e histograma usam a seleção.

Uma calibração estimada pela média agora fica fixa depois de aplicada: mudar
a seleção não reimpõe a média assumida nem muda px/mm. Para estimá-la de novo,
abra Escala/mm e aplique a média novamente. Segue sendo uma estimativa sem
referência física independente.

## Instalar

Abrir `PelotaVision_0.5.0_Android.apk` em um celular ARM64 com Android 10 ou
superior. Autorizar a instalação pelo aplicativo que abriu o APK e permitir
a câmera. O pacote e a assinatura de desenvolvimento são preservados em
relação a 0.2.0, 0.2.1, 0.2.2 e 0.3.0, permitindo atualização sobre essas versões.
A chave da primeira entrega 0.1.0 era diferente. Não há publicação na Play Store.

## Analisar e salvar

1. Fotografar ou abrir um JPEG, PNG ou WebP, até 100 MB. A câmera ao vivo
   continua identificando pelotas; a análise de trincas ocorre na foto.
2. Conferir círculos, linhas vermelhas e as contagens. Vermelho indica possível
   trinca; verde, nenhuma indicação encontrada; amarelo, análise inconclusiva.
   Linhas vermelhas realçam os pixels candidatos. IDs identificam as pelotas.
3. Tocar em uma pelota para abrir sua imagem ampliada. Classificar como
   Trinca visível, Sem trinca visível ou Inconclusiva, ou restaurar o automático.
   A revisão muda o estado e a contagem; não inventa o traçado de uma trinca
   que o detector não localizou. Ao revisar para sem trinca, suas linhas são
   ocultadas no relatório. Estados automático e revisado constam do JSON.
4. O botão Trincas ajusta a sensibilidade e reanalisa. Maior sensibilidade pode
   marcar mais textura, poros e sombras. Reanalisar, inclusive em Ajustes,
   descarta revisões de trincas; mantém a calibração da mesma foto.
5. Salvar relatório. O PNG `_resultado.png` contém, nesta ordem:
   - imagem original orientada, sem marcações;
   - imagem anotada com circunferências, IDs e possíveis trincas;
   - contagens e legenda;
   - histograma granulométrico por número.

Cada pelota é contada uma vez, mesmo com vários segmentos de trinca.
Inconclusivas incluem regiões parciais, regiões com alerta geométrico e
objetos pequenos/escuros para os quais nenhuma indicação foi localizada.
Uma trinca candidata visível pode ser contada mesmo numa pelota parcialmente
cortada. As contagens de trincas usam todas as detecções; o histograma pode
excluir regiões cortadas e, opcionalmente, regiões com alerta de geometria.
Assim, o n do histograma pode ser menor que a contagem de pelotas.

O PNG usa a foto orientada e, quando necessário, reduzida a até 2400 pixels
no maior lado. A primeira imagem da composição não tem marcas nem recorte
adicional. O arquivo `_original` separado preserva os bytes e a resolução da
entrada. O relatório é uma composição de duas imagens mais o histograma;
não possui as mesmas dimensões da entrada.

Fotos ficam em Pictures/PelotaVision (Galeria), dados em Download/PelotaVision.
Após salvar, Abrir imagem abre o PNG do relatório. São cinco arquivos:
original, relatório PNG, CSV por pelota, CSV de histograma e JSON.
A entrada não é sobrescrita. O app só pede permissão de câmera; seleção pelo
seletor Android e gravação pelo MediaStore. Análise funciona sem internet.

## Escala e histograma

A análise de trincas e o salvamento não exigem px/mm. Sem escala física,
o histograma é explicitamente em pixels do painel orientado da foto, com
classes de 10 px. O CSV usa lower_px/upper_px; JSON histogram.unit é px e
physical_scale é null. Campos diameter_mm e area_mm2 ficam vazios/null.

Para granulometria em mm, tocar em Escala / mm:

- informar px/mm da foto ORIGINAL orientada;
- calcular por comprimento de referência: pixels / comprimento real em mm;
- ou escolher explicitamente uma média conhecida externamente da mesma
  amostra. Nesse último modo, a escala é marcada ESTIMADA, e a média passa a
  definir a escala em vez de constituir uma medição independente.

Ex.: referência de 240 px / 12 mm = 20 px/mm. Usar pixels do arquivo original,
não da tela ou de uma miniatura. Uma referência deve estar no mesmo plano
aproximado da amostra. O app corrige seu próprio redimensionamento.
O tamanho conhecido da amostra não é preenchido automaticamente.

A classe agrupa diâmetros; ela não calibra a imagem. Classes de 1 mm numa faixa
8–16 mm produzem 8–9, 9–10, …, 15–16 mm. A largura escolhida é preservada.
A faixa exibida cobre as classes ocupadas, mantendo gaps entre elas. Limites
são múltiplos da largura, intervalos [a,b), com último limite superior inclusivo.
Até 5000 classes: acima disso, um erro mantém o formulário aberto e solicita
classe maior, sem aumento automático. D10/D50/D90 usam interpolação em (n−1)q.

Distribuição por NÚMERO, não por massa ou peneiramento. Diâmetro equivalente
à área projetada visível: 2 sqrt(área/pi). Perspectiva, alturas diferentes e
oclusão podem causar erro. Alterar zoom, distância, recorte e lente muda escala;
ela é removida a cada foto nova. Não foi implementada autocalibração ArUco.

## Método experimental de trincas

1. Detectar cada pelota e recortar sua luminância para 96x96 pixels.
2. Usar o disco interior de 78% do raio; normalizar pelos percentis locais 5 e 95 para reduzir diferenças de brilho e contraste.
3. Calcular 72 atributos de black-hat, componentes, comprimento, alongamento e área em três escalas e três limiares.
4. Aplicar as 160 árvores embarcadas e obter um escore não calibrado.
5. Em sensibilidade 50, escore >=0,6 indica possível trinca; <=0,4 indica sem indicação; entre os dois é inconclusivo. A sensibilidade desloca os limites. Pelotas parciais, de revisão, raio <20 px, baixo contraste ou luminância extrema ficam inconclusivas.
6. Nas positivas, uma heurística separada localiza linhas escuras fortes conectadas e transforma os pixels em traçados vermelhos. A floresta conta pelotas; ela não segmenta trincas. Uma positiva pode não ter traçado localizado. Revisão manual não inventa um traçado.

Se os pesos estiverem indisponíveis ou incompatíveis, o app informa e usa a heurística anterior como reserva; o JSON identifica o método efetivamente usado. Trincas curtas, finas, periféricas ou ocultas podem faltar. Textura, poros alongados e sombras podem gerar indicações. Iluminação difusa, foco, câmera parada e camada única ajudam a preservar detalhes. IDs não acompanham objetos entre quadros ou reanálises.

## Exportações

As coordenadas x/y, raios e traçados de trincas usam o painel da foto orientada,
antes da composição. Para converter y para o painel anotado do PNG, somar
annotated_panel_top. JSON também informa original_panel_top, histogram_top,
photo_panel_width e photo_panel_height. Não confundir com coordenadas do
arquivo original de alta resolução.

CSV por pelota inclui crack_status (POSSIBLE, NO_INDICATION, INCONCLUSIVE),
crack_manually_reviewed, crack_score, crack_method, centro, raio, área, alertas e medidas em mm quando
calibradas. JSON cracks registra o método, contagens, sensibilidade, estados
automático/revisado e marked_runs_x1_y1_x2_y2_px. Esses runs são destaques dos
pixels candidatos, não uma largura de trinca validada metrologicamente.

Os cinco arquivos são criados pendentes e publicados depois de todos gravados.
Falhas tentam remover arquivos parciais. Interrupções abruptas podem deixar
entradas pendentes; não há transação atômica entre coleções do MediaStore.

## Compilar e verificar

JDK 17, Android SDK Platform 35, Build Tools 35.0.0, Gradle 8.9, AGP 8.7.3.
CameraX 1.4.2, Activity 1.9.3, ExifInterface 1.3.7, OpenCV Android 4.12.0.
Robolectric 4.13 e JUnit 4.13.2 são somente dependências de teste.

```bash
./gradlew assembleDebug lintDebug testDebugUnitTest
```

O projeto gera APKs ARM64 e x86_64 em app/build/outputs/apk/debug.
A chave signing/prototype.keystore preserva as atualizações: alias
androiddebugkey, senha android. É uma chave pública de desenvolvimento,
inadequada para uma distribuição de produção.

verification/ml-0.5.0/VALIDACAO.md descreve os testes atuais, exemplos e limitações. A foto de
exemplo é analisada automaticamente; não é verdade-terreno de um especialista.
CrackDetectorCheck.java exercita o algoritmo em JVM com JNI OpenCV 4.9 desktop,
incluindo trincas, poros, superfície uniforme e redimensionamento. O compositor
é testado em Android simulado com gráficos nativos. O salvamento real do código
é exercitado com um provedor MediaStore de teste, não com Galeria física.
Não houve teste físico no celular do usuário nem validação de precisão.

Uma ROI muito pequena pode enviesar a distribuição por excluir proporcionalmente
mais pelotas grandes que cruzam o limite. Para comparar granulometrias, use uma
área suficientemente ampla e mantenha o mesmo critério de seleção.
