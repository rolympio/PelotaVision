# Pelotas sobre correia — fotos reais, SEM rótulos

Este conjunto tem 371 JPGs enviados por Ricardo em 24/10/2026 (`img582.zip`). As fotos são de câmeras fixas sobre as correias de saída dos discos de pelotamento.

**Ainda não há anotação, então nada aqui entra no treino.** O conjunto serve para análise e para escolher os quadros que serão rotulados.

## Conteúdo (`manifest.json`)

O manifesto traz, para cada arquivo:

- SHA-256, largura e altura;
- câmera e horário;
- uma estimativa de escala e indicadores de contraste.

A câmera e o horário vêm do OCR da faixa impressa no topo da imagem. Nas fotos `2017*.jpg`, o horário vem do nome do arquivo. Em 118 imagens a faixa ficou ilegível, e o campo `camera` está nulo.

| Origem | Imagens |
|---|---|
| Discos 03 a 12, 02/10/2017 entre 14 h e 16 h (1280×720 e 800×450) | 244 |
| Câmera 4P10F, 27/12/2017 | 9 |
| Câmera não identificada pelo OCR (inclui as séries de 19/06/2017) | 118 |

## Medidas preliminares

As medidas abaixo são automáticas e não foram validadas.

- **Escala.** `radius_fraction_estimate` é o espaçamento entre pelotas, medido pela autocorrelação de um recorte central e dividido por 2 × largura. A mediana é cerca de 0,008 da largura. **93% das fotos ficam abaixo de 0,012**, que é o menor raio previsto pelos perfis atuais do app (Pequenas). Os três métodos estão fora da faixa de projeto para essas fotos.
- **Quantidade.** Num quadro denso há mais de 600 pelotas. É o limite de objetos do Bordas, do LoG e da rede neural, então a contagem fica truncada.
- **Quadros vazios.** Há cerca de 36 quadros com correia quase vazia ou baixo contraste (`periodicity` < 0,05 ou `gray_std` < 8). Servem como exemplos negativos.
- **Domínio.** As fotos são acinzentadas, com pelotas em camadas, aglomerados e pelotas deformadas. É muito diferente das 7 cenas de demonstração.

## Como rotular (proposta)

1. Escolha de 40 a 60 quadros distribuídos por disco e horário. **Não use quadros consecutivos do mesmo disco**, porque a correia mostra pelotas quase iguais.
2. Para economizar trabalho, rotule recortes de cerca de 400 × 400 px em vez do quadro inteiro.
3. Anote os contornos no formato de `ml_training/data/annotations.json`, com o campo `polygon`.
4. Pelotas cobertas por outras devem ser anotadas apenas na parte visível. Pelotas cortadas pela borda levam `"partial": true`.
5. Separe a validação **por disco**. Por exemplo: treino nos Discos 03 a 10 e validação nos Discos 11 e 12 e na 4P10F.
