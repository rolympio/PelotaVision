# Rede neural StarDist — localização de pelotas (experimental)

## O que é

Terceiro método de localização do PelotaVision, ao lado de **Bordas** (gradientes/Hough + CLAHE) e **LoG**.

A rede é uma reimplementação em PyTorch do StarDist (Schmidt et al., MICCAI 2018; Weigert et al., WACV 2020). Para cada pixel, ela prevê duas coisas:

- a probabilidade de o pixel estar no interior de uma pelota;
- a distância até a borda dessa pelota em 32 direções.

Cada objeto vira um polígono estrelado. Os polígonos duplicados são removidos por supressão de não máximos.

O formato foi escolhido porque pelotas são convexas e frequentemente se tocam. Nesse caso, os outros dois métodos tendem a fundir ou partir objetos.

A rede só **propõe** objetos. A elipse final continua sendo ajustada às bordas observadas na foto (`EllipseFitter`), com os mesmos gates. A triagem de trincas, a calibração e o relatório não mudaram.

## Estado do modelo distribuído: demonstração

O modelo em `app/src/main/assets/pellet_stardist.onnx` foi treinado com as 7 cenas de `ml_training/data`. São 225 círculos **provisórios**, anotados pelo assistente. Uma das cenas é sintética e as demais têm origem não comprovada.

Esse modelo serve para provar que o encanamento funciona: treino, exportação, inferência no Android e no Windows, JSON e testes. **Não serve para medir pelotas da usina.**

Os números de `pellet_stardist.json` medem o seguinte:

- `training_fit`: o ajuste nas próprias cenas de treino. Esse valor é otimista por construção.
- `validation_holdout`: o desempenho em duas cenas inteiras deixadas fora do treino (`cluster` e `paired40`). É o único número honesto, e mesmo assim tem n muito pequeno e foi medido contra círculos provisórios.

A comparação entre os três métodos (Bordas, LoG e rede) roda no CI do Windows e aparece como anotação "Rede neural StarDist".

## Para virar ferramenta: dados reais

1. Junte de 40 a 80 fotos reais (estimativa inicial), cobrindo dias, iluminação, câmera, umidade e densidade diferentes.
2. Anote um contorno por pelota. O caminho rápido é pré-anotar com o SAM e revisar à mão. Pelotas cortadas pela borda da foto também devem ser anotadas, com `"partial": true`.
3. Use o formato de `ml_training/data/annotations.json`. Cada cena tem um `group` e um arquivo `<group>.png`. Cada objeto pode ter:
   - `x`, `y`, `radius` (círculo), ou
   - `"polygon": [[x, y], ...]`, que é preferível.
4. Separe a validação **por cena ou por dia**, nunca por pelota.
5. Valide contra paquímetro e peneiramento, não contra os algoritmos atuais.

## Treinar

**Pelo GitHub (sem instalar nada).** Abra Actions → "StarDist - treinar modelo de pelotas" → Run workflow, no branch de trabalho. O runner tem 2 núcleos de CPU, e 1500 iterações levam cerca de 1 hora para os dois treinos (validação e final).

Ao terminar, o workflow grava no próprio branch:

- o ONNX e o metadado nos assets do app;
- as métricas e o log em `ml_training/stardist/output`.

**Localmente (CPU ou GPU).** Use Python 3.12:

```
pip install torch==2.5.1 --index-url https://download.pytorch.org/whl/cpu   # ou o índice CUDA
pip install -r requirements.txt
python train.py --exclude cluster,paired40 --out output/holdout.onnx --metrics output/holdout_metrics.json
python train.py --out output/final.onnx --metrics output/final_metrics.json
python check_onnx.py --model output/final.onnx --final-metrics output/final_metrics.json \
  --holdout-metrics output/holdout_metrics.json --metadata output/pellet_stardist.json --reference output/reference_dense.json
```

Depois copie `final.onnx` para `app/src/main/assets/pellet_stardist.onnx` e `pellet_stardist.json` para a mesma pasta. O teste Java exige que `reference_dense.json` seja do mesmo modelo, conferido pelo SHA-256.

Com dados reais, troque `--exclude` pelos grupos de validação e aumente as iterações. Em GPU, 10 a 20 mil iterações é um ponto de partida razoável.

## Contrato Python ↔ Java

`common.py` é a referência de `core/.../NeuralPelletDetector.java`. Os dois implementam o mesmo pipeline:

- **Escala:** raio esperado de 16 px na entrada da rede, a partir do perfil (fração da largura). O perfil Auto usa três escalas.
- **Normalização:** percentis 1% e 99,8% do histograma de 8 bits da imagem reduzida.
- **Blocos:** 384 × 384 com 64 px de sobreposição. Cada bloco é dono da sua região até o ponto médio da sobreposição.
- **Candidatos:** máximos locais 3 × 3 acima do limiar `0,75 − 0,005 × sensibilidade` (entre 0,2 e 0,8).
- **NMS:** interseção sobre a menor área acima de 0,5, calculada com casca convexa (Andrew) e recorte Sutherland–Hodgman. Foram implementados à mão nos dois lados para a paridade ser exata.

Se mudar um lado, mude o outro. O teste `NeuralDetectorTest` compara os dois no OpenCV 4.12 nativo.

## Licenças

O método StarDist é publicado sob BSD-3. Aqui ele foi reimplementado, sem cópia de código. O PyTorch também é BSD. Os pesos foram treinados apenas com dados do próprio repositório.

Não há dependência AGPL, como a Ultralytics.
