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

## Modelo de correia (pseudo-rótulos) — `pellet_stardist_belt.onnx`

Segundo modelo, **separado** do de bancada. É usado **somente** com cena Correia + método Rede neural. A bancada continua com `pellet_stardist.onnx`, sem mudança.

**Dados.** São os pseudo-rótulos de consenso de `ml_training/data_belt/pseudo`, gerados por `ml_training/data_belt/pseudo_label.py`. O processo, a divisão por câmera e as limitações estão em `ml_training/data_belt/README.md`. Não há verdade-terreno humana.

**Treino (`train.py --conjunto correia`).**

- Lê `annotations.json` e usa só os recortes `split: "treino"`.
- Cada recorte é reescalado pelo raio mediano dos seus rótulos inteiros: duas escalas sorteadas em `RNET` (raio de 10,4 a 24 px na rede). Nos negativos, usa raio de 12 px.
- A loss é a mesma da bancada, ponderada pela máscara (`loss_fn_weighted`). **Peso 0 ignora o pixel** na BCE, no termo de distância e na regularização. Pelotas cortadas pela janela de amostragem também recebem peso 0.
- Não há moldura artificial: se a janela é menor que 128 px, o resto entra com peso 0.
- Sem argumentos, `train.py` treina a bancada exatamente como antes. O caminho de bancada não foi alterado.

**Avaliação (`evaluate_belt.py`).** Mede nos quadros de validação (Disco 11, Disco 12 e 4P10F, nunca vistos no treino) a concordância com o **consenso**, que não é verdade-terreno. Compara o modelo de correia, o de demonstração e os próprios rotuladores. Também conta detecções em quadros de correia vazia do Disco 03 que não entraram no treino.

**Paridade e metadado (`check_onnx.py --conjunto correia`).** Compara ONNX Runtime × OpenCV 4.12 num quadro do Disco 11. Gera `output/reference_belt.json`, usado pelo `BeltSceneTest` Java no Windows, e `pellet_stardist_belt.json`, no mesmo esquema do de bancada, com `scene: "belt"`, `label_origin: "pseudo_consensus_v1"`, contagens, divisão e métricas.

**Pelo GitHub.** Actions → "StarDist - treinar modelo de pelotas" → `conjunto = correia`. O workflow grava `app/src/main/assets/pellet_stardist_belt.{onnx,json}` e `ml_training/stardist/output/belt_*.json`, `reference_belt.json` e `training_log_belt.txt` no branch. `modo = avaliar` reavalia o ONNX de correia já gravado.

**Localmente.**

```
python ../data_belt/pseudo_label.py            # na raiz: python ml_training/data_belt/pseudo_label.py
python train.py --conjunto correia --iterations 6000 --out output/belt.onnx --metrics output/belt_metrics.json
python evaluate_belt.py --belt output/belt.onnx --out output/belt_validation.json
python check_onnx.py --conjunto correia --model output/belt.onnx --final-metrics output/belt_metrics.json \
  --holdout-metrics output/belt_validation.json --metadata output/pellet_stardist_belt.json --reference output/reference_belt.json
```

**Sem o modelo de correia**, correia + rede cai para o modelo de bancada. O status avisa ("Correia usa o modelo de bancada"), e o JSON marca `belt_model_missing_fallback_to_bench: true`.

<!-- RESULTADOS_CORREIA -->

## Bancada real (0.16.0) — `--conjunto bancada_real`

Modelo de bancada distribuído desde a 0.16.0 (`stardist_bench_real_20261028_v2`). Ajuste fino do modelo de demonstração (BN dobrada lida do ONNX e congelada) com:
- as 7 cenas antigas (peso 1);
- 5 análises reais de webcam 1920×1080 sobre panela (27/10/2026), com pseudo-rótulos do próprio app (`ml_training/data_pan_20261027/make_pseudo.py`): só pelotas com elipse confiável; duvidosas, faixa junto à ROI e regiões claras não cobertas com peso 0; frestas escuras e fundo fora da panela com peso 1;
- 53 fotos de bancada com uma pelota (26/10/2026).

Validação: análises 13:27 e 16:09 fora do treino, `evaluate_pan.py` contra os pseudo-rótulos (não é verdade-terreno). Critério fixado antes: `verification/stardist-real-20261028/gate_stardist.json` (aprovado).

```
python ../data_pan_20261027/make_pseudo.py --exports <pasta com as análises exportadas>
python train.py --conjunto bancada_real --init-onnx ../../app/src/main/assets/pellet_stardist.onnx --iterations 2000 --out output/real.onnx --metrics output/real_metrics.json
python evaluate_pan.py --model output/real.onnx --out output/pan_validation.json
```

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
