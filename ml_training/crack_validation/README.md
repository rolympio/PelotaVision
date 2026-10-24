# Validação na bandeja: modelo atual × candidato sintético

É este teste que decide se `training_synth/crack_forest_candidate.pvrf` substitui o modelo de produção (`app/src/main/assets/crack_forest.pvrf`, `random_forest_20261018_v1`). Sem ele, o candidato não entra no app.

## 1. Fotografar (bancada, não correia)

- **Amostra:** 50 a 100 pelotas reais, com **pelo menos 15 trincadas** e 30 íntegras. Separe-as antes, à mão ou com lupa.
- **Separação física:** faça uma foto só com as trincadas e outra só com as íntegras, ou divida a bandeja em duas metades marcadas com fita: trincadas à esquerda, íntegras à direita. Assim o rótulo sai da arrumação, e não do olho na tela. Para medir a confusão real, junte também 1 ou 2 fotos com as duas misturadas e anote num papel quais estão trincadas.
- **Fundo:** liso, fosco e de contraste médio (papel cinza ou bege). Não use preto brilhante nem branco estourado. As pelotas não devem se tocar.
- **Distância:** cada pelota precisa ter **raio ≥ 30 px** na foto analisada (o app torna inconclusiva qualquer pelota abaixo de 20 px). Com um celular de 12 MP, uma bandeja de cerca de 25 × 20 cm enquadrada resolve bem. Use a câmera principal, sem zoom digital.
- **Iluminação:** luz difusa (janela ou luminária com difusor), sem flash direto. Se der, repita uma foto com luz lateral rasante, que realça as trincas, e informe qual é qual.
- **Fotos:** 3 ou 4, em foco e sem tremido, em JPEG normal do celular. Inclua as trincas finas: elas são o caso difícil.

## 2. Analisar e rotular

1. Abra cada foto no PelotaVision (Android ou Windows) no modo Bancada e salve. O app gera `<base>.json`, `<base>_original.jpg` e o relatório com as pelotas numeradas.
2. Monte `rotulos.csv` olhando o relatório numerado:

```
foto;pelota;trinca
PelotaVision_20261101_101500;1;0
PelotaVision_20261101_101500;2;1
PelotaVision_20261101_101500;3;?
```

`foto` é o nome do JSON sem extensão, `pelota` é o número do relatório e `trinca` vale 1 (trincada), 0 (íntegra) ou `?` (não sei; fica fora). Na foto com metades separadas, todas as pelotas de um lado levam o mesmo rótulo.

## 3. Rodar

```
cd ml_training/crack_validation
python avaliar_bandeja.py --rotulos rotulos.csv --fotos CAMINHO/Pictures/PelotaVision CAMINHO/Download/PelotaVision/*.json --saida resultado_bandeja
```

O script recorta de novo cada pelota a partir da foto original e dos campos `crack_crop_*_px` do JSON. Em seguida extrai os mesmos 72 atributos do app e pontua com as **duas** florestas. Saída: `decisao.json` e `escores_por_pelota.csv`. Ele também confere se o escore gravado pelo app bate com o modelo atual.

## 4. Critério de troca (fixado antes dos dados)

Ficam fora as pelotas parciais, as com revisão, as com raio < 20 px e as barradas pelo gate de contraste, porque o app já as declara inconclusivas.

0. Mínimo de 15 trincadas e 30 íntegras avaliáveis; abaixo disso o resultado é **INSUFICIENTE**.
1. ΔAUC (candidato − atual): o limite inferior do IC 95% precisa ser maior que 0. O IC vem de bootstrap estratificado com 5000 reamostragens, reamostrando fotos e pelotas.
2. Sensibilidade com especificidade fixa de 90%: o candidato precisa ser ≥ atual, e o limite inferior do IC 95% da diferença precisa ser maior que −0,05.
3. Na política do app (sensibilidade 50), a fração de íntegras marcadas como "possível trinca" pelo candidato não pode passar a do atual em mais de 5 pontos percentuais.

**TROCAR** só se 1, 2 e 3 passarem; caso contrário, **MANTER** o atual. Se o candidato vencer, os limiares 0,4/0,6 do app precisam ser recalibrados com **outra** bandeja. O script sugere limiares só como ponto de partida, porque estes dados já foram usados para decidir. A troca em si é `install_model.py` apontando para o candidato, seguida da atualização de `CrackModel.ID` e dos testes de paridade.

Não altere o critério depois de ver os resultados. Se a bandeja for pequena demais, fotografe mais pelotas.

## Teste do script

`crack_example_labels_provisorios.json` traz rótulos provisórios do assistente para `verification/fixtures/crack_example.png`, uma foto externa com apenas 7 trincas visíveis. Com eles, o script responde corretamente INSUFICIENTE. Esses rótulos servem só para teste de software e relato, não para decidir a troca.
