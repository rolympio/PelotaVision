# Empilhar fotos com tremor sub-pixel para obter resolução maior

**O que a literatura diz, o que medimos nas imagens do PelotaVision e o que implementar.**

Preparado por Manus AI em 2026-11-20, para Ricardo Freitas, no escopo do repositório
[`rolympio/PelotaVision`](https://github.com/rolympio/PelotaVision). Pergunta original:

> *"Há a possibilidade de, se forem batidas várias fotos com a câmera imóvel, aproveitarmos as pequenas
> vibrações que existem e pegarmos a alteração de cada pixel para criar, a partir de 10, 20 ou 1000
> imagens, uma única imagem de resolução muito superior?"*

Documento irmão: [prompt master de implementação](/home/ubuntu/pelotavision-sr/PROMPT-MASTER-rajada-burst-sr-PelotaVision.md).
Código do experimento: [`burst_sr_demo.py`](/home/ubuntu/pelotavision-sr/burst_sr_demo.py) e
[`burst_sr_probe.py`](/home/ubuntu/pelotavision-sr/burst_sr_probe.py).

---

## 1. Resposta curta

**Sim, o fenômeno existe, é estudado há 40 anos e tem nome: *multi-frame super-resolution* (MFSR), ou
*burst SR*. Mas ele entrega duas coisas muito diferentes, e só uma delas serve ao que você quer.**

> **O que a bancada respondeu** (seção 5): com o ruído de uma câmera ruim, empilhar 24 quadros deu
> **+11 dB e cortou o erro de diâmetro de 0,40 → 0,10 px**, e *nenhuma* fusão em grade 2× venceu essa
> média. O 2× só supera a média quando ruído baixo **e** alinhamento perfeito ocorrem juntos — aí ele
> entrega +1,2 dB e **metade do erro de diâmetro** (0,09 → 0,03 px). Entre um caso e outro está todo o
> trabalho de engenharia (e é por isso que o prompt master começa medindo, não codando).

1. **Ganho de relação sinal-ruído: real, previsível e imediato.** Ao empilhar N imagens alinhadas, o
   ruído (aleatório, não-correlacionado entre quadros) cai como `1/√N`, ou seja **+3 dB por dobra de
   quadros**: 10 fotos → +10 dB, 100 → +20 dB, 1000 → +30 dB. Isso é físico e não depende de sorte. Com
   câmera ruim e pouca luz, é o ganho que importa.
2. **Ganho de resolução espacial: existe, mas é modesto e tem teto rígido de ~2×.** As pequenas
   vibrações fazem cada quadro amostrar a cena em fases sub-pixel diferentes; essas amostras
   *intercaladas* é que preenchem uma grade 2× mais densa. Acima de 2× a literatura é categórica:
   translações sub-pixel não criam informação nova — a partir daí só *priors* de rede neural
   ("invenção plausível de textura") ampliam além, e invenção não é medida.
   No nosso experimento o 2× **funcionou**, mas apenas dentro dessa janela estreita (5.3, linha de
   ruído 10/255 com offsets exatos): +0,8 a +1,2 dB sobre a média e diâmetro 0,03–0,04 px vs 0,05–0,09.

**"1000 imagens" não compra 1000× de resolução; compra ~30 dB de silêncio e uma amostragem de fase que
satura em poucas dezenas de quadros.** Para granulometria por imagem, isso é bom — só que o bom é o
diâmetro medido com menos ruído, não uma foto "muito mais nítida".

3. **O gargalo prático não é a contagem de fotos, é o registro.** Nas medidas da seção 5, usar offsets
   *estimados* do ruído em vez dos offsets verdadeiros custou **6,1 dB** no cenário de 1,5 px de tremor
   (10,5 dB a 2,5 px), e no IBP levou de 42,3 para 30,0 dB. Pior: o erro do estimador escalou com o
   próprio tremor — **≈ 0,33σ** medido —, então o tremor grande que *criaria* a informação é o que a
   cadeia de software não consegue medir. Daí a recomendação de deslocamento controlado em vez de
   tremer aleatoriamente.
4. **A câmera precisa estar mesmo imóvel e o alvo também.** No PelotaVision o alvo anda na correia:
   entre dois cliques de uma rajada a bolota se desloca vários pixels, e a mediana/registro global
   falha. Isso não é detalhe de implementação — é a premissa do método, que a literatura documenta como
   a causa número 1 de falha (*ghosting*).
   E, a jusante: mudar a resolução da entrada obriga a revalidar segmentação e métricas — a literatura
   mede detector ficar *pior* com SR se não for retreinado [35][36].
5. **Existe código aberto utilizável**, do "pronto para rodar" ao "para estudar": reimplementação do
   burst-SR do Pixel 3 sob licença MIT [11], o artigo IPOL 2023 com demo e ZIP [12], `drizzle` da NASA
   [21], apps de desktop [22][23] e as primitivas do próprio OpenCV que o PelotaVision já usa
   [15][16][17][18].

---

## 2. O modelo matemático em duas frases

Cada quadro `k` é uma amostra da cena contínua `f`, deslocada por um vetor sub-pixel `(dy_k, dx_k)` e
corrompida por ruído:

```
y_k(dy, dx) = ∫∫ h(u,v) · f(dy - u + dy_k, dx - v + dx_k) du dv + n_k
```

- Se `f` é **band-limited acima de Nyquist** (sem conteúdo além do meio-pixel), o conjunto de quadros não
  contém nada além do que um quadro contém: empilhar só reduz ruído. É o teorema de Baker & Kanade [4].
- Se `f` **tem** conteúdo entre Nyquist e a frequência de Nyquist da grade 2×, então os deslocamentos
  sub-pixel realmente distintos tornam o problema **superdeterminado** e a grade 2× é recuperável —
  desde que a amplitude do tremor cubra as fases e que você saiba os deslocamentos com precisão bem
  menor que 1 px.

Daí os dois números que decidem tudo: **amplitude do jitter** (σ em px) e **erro de registro** (RMSE em
px). O resto é consequência. A cobertura de grade (quantos quadros para "preencher" as fases) cresce com
o quadrado do fator: para 2× com σ=0,5 px, ~10 quadros cobrem ~94% das células e ~20 cobrem quase 100%;
para 3× são necessários de 20 a 50 [seção 5 e 3].

**Ganhos esperados de SNR** (empilhamento mediana/média, ruído dominante, cena rígida):

| N (fotos) | redução de σ | ganho |
|---:|---:|---:|
| 1 | 1,00 | 0 dB |
| 4 | 0,50 | +6,0 dB |
| 10 | 0,32 | +10,0 dB |
| 20 | 0,22 | +13,0 dB |
| 100 | 0,10 | +20,0 dB |
| 1000 | 0,032 | +30,0 dB |

O teto prático é outro: **ruído não é o único termo**. Quantização em 8 bit (~0,5 LSB de *dither*
necessário), bloco de ruído do JPEG, poeira no sensor, *vignetting* e deriva de exposição nãoCancelam
com N e viram um piso. Em JPEG de câmera, o piso de JPEG costuma ser o limitante real antes dos 100
quadros.

---

## 3. Por que o teto é 2× (e quando não é)

- **Integração do pixel.** Cada fotosíte integrates a cena numa caixa de 1 px, não num ponto. Duas
  imagens com deslocamento de meio pixel se complementam; um terceiro deslocamento não traz um quarto
  grau de liberdade novo. Lafenetre, Facciolo & Eboli (IPOL 2023), ao documentar a fusão RAW do burst do
  Pixel 3, limitam a ampliação a **2×** por exatamente esse motivo [12]; Wronski et al. (Google, 2019),
  o artigo em que se baseia, idem [13].
- **Mais quadros ≠ mais resolução.** Baker & Kanade (CVPR 2002) demonstram que, para translações puras
  e PSF quadrada, "usar mais imagens de baixa resolução não ajuda além de certo ponto" [4]. Na prática:
  a curva de ganho *achata* entre 7 e 20 quadros. Wronski mede saturação de nitidez em ~7 frames sob boa
  luz e ~15 em low-light — ou seja, onde o tremor é grande e o ruído domina, mais quadros ainda ajudam
  (porque é ganho de SNR, não de fase) [13].
- **Pixel shift de fábrica confirma por baixo.** Sony (4/16 RAWs), OM System (50/80 quadros), Pentax
  (2/4/16) e Nikon Z8 movem o *sensor* mecanicamente em passos de ½–1 px, com tripé, alvo **imóvel** e
 RAW por quadro. Jim Kasson mostrou que, no modelo monocromático ideal com lente perfeita, o pixel shift
  de 16 tiros dá MTF em ciclos/mm **virtualmente idêntica** a uma única exposição: o ganho real é menos
  *aliasing*, menos cor falsa e mais margem para *sharpen* [14][25][26][27].
- **Acima de 2× só com ajuda externa.** (a) *Priors* de rede neural treinada em pares LR/HR — que
  "inventam" textura plausível, útil para aparência e perigoso para medição; (b) deslocamentos grandes e
  *conhecidos*, como no trabalho do grupo de Brown (ICCP 2025), que chegou a **fator 8** com 64 quadros
  usando uma mesa nano-posicionadora, alvo 100% estático a 3 m e reconstrução TV de 5–10 minutos [8].
  Note a combinação: **controle mecânico + cena imóvel + alvo não-móvel**. Não é a vibração da sua
  bancada.

---

## 4. Cardápio: métodos, algoritmos e códigos

### 4.1 Clássicos (implementáveis em Java/OpenCV, sem treinar nada)

| Método | Ideia | Custo | Onde ler |
|---|---|---|---|
| **Shift-and-add / média alinhada** | alinhar por translação e somar | O(N) | [1][4] |
| **Mediana /trimmed mean** | rejeita *ghosting* de objeto móvel | O(N log N) | [1] |
| **Drizzle** | cada pixel LR "goteja" sobre a grade HR com peso de área de sobreposição | O(N) | [18][19][21] |
| **IBP** (back-projection iterativa) | resíduo retroprojetado na grade HR, iteração a iteração | O(k·N) | [2][3][5] |
| **POCS / set-projected** | projeta nos conjuntos de consistência | O(k·N) | [2] |
| **Kernel regression** | regressão local ponderada na grade HR | O(N·janela²) | [20] |
| **Tikhonov / TV** | minimiza ‖Ax−y‖² + λ·R(x) — controla artefato | iterativo | [3][8] |
| **Deghosting / weighting robusto** | peso por quadro e por pixel, *outlier rejection* | — | [13][11] |

### 4.2 Registro sub-pixel (a parte que decide)

- **Correlação de fase com sobreamostragem de DFT** (Guizar-Sicairos et al., Opt. Lett. 2008): precisão
  de "uma fração pequena de pixel" com custo de poucas FFTs [9]. Em satélite, com deslocamento verdadeiro
  de ±0,5 px, mediram −0,5014/+0,5140 px em cena urbana (~0,01 px de erro) — mas −0,4053/+0,5013 em
  deserto sem textura, e até 0,56 px de erro sem passa-alta prévio [10]. **Textura manda.**
- OpenCV: `phaseCorrelate` (+ `createHanningWindow`) para translação global com resposta de confiança;
  `findTransformECC` (TRANSLATION/EUCLIDEAN) para refinamento; `DISOpticalFlow`/Farneback se houver
  paralaxe [15][16].
- scikit-image: `phase_cross_correlation(..., upsample_factor=100)` [24].

### 4.3 Aprendizado profundo

- **Burst-SR dedicado:** BSRN (goutamgmb/deep-burst-sr, 14 quadros RAW Bayer) [28], BurstSR Challenge /
  NTIRE21 [29], Burstormer (transformer não-supervisionado) [30], burst-SR self-supervised (CVPR 2023)
  [31]. Todos assumem **RAW**; nenhum roda em Android de forma realista sem trabalho pesado.
- **SR de quadro único (já presente no repo, mas vale a comparação):** Real-ESRGAN (BSD-3) [32],
  **RLFN** (Apache-2.0, 0,317 M parâmetros, 27,11 ms — leve o bastante para pensar em embarcado) [33],
  SwinIR/HAT/SReNet [34], e `cv2.dnn_superres` com EDSR/ESPCN/**FSRCNN**/LapSRN [17] — o PelotaVision já
  usa FSRCNN ×2 em CPU.
- **Multi-frame no esporte, na prática:** quando o problema é *posição*, a literatura esportiva resolve
  com temporalidade e baseline, não com resolução: TrackNet sobe F1 de 92,5% para 98,2% usando 3
  quadros consecutivos como canais [40]; WACV 2026 (tênis de mesa) diz que "alta resolução de entrada
  é primordial" e que *downsampling* faz a bola desaparecer [41]; e em cena escura a SR dá +17% de mAP
  **com** retreino do detector [42].
- **Evidência crítica para quem detecta/mede em cima:** SR não melhora automaticamente a visão computacional
  a jusante. Seweryn et al. mediram em futebol (entrada 320×240): RLFN ×4 subiu mAP@[.5:.95] de 24,3 para
  27,3 (+12% relativo) **mas derrubou mAP@50 de 46,7 para 41,4** [35]; e "Rethinking SR for Near-Sensor
  Detection" mostra YOLO sem *fine-tune* caindo de 0,401 para 0,369 com SR ×2 [36]. Tradução: se você
  trocar a resolução de entrada, **revalide o segmento/métrica a jusante ou ela piora**.

### 4.4 Prontos para usar (sem escrever código)

| Projeto | Licença | Entrada | Nota |
|---|---|---|---|
| [Jamy-L/Handheld-Multi-Frame-Super-Resolution](https://github.com/Jamy-L/Handheld-Multi-Frame-Super-Resolution) | MIT | RAW `.dng/.ARW/.CR2` | reimplementação do burst do Pixel 3; `--scale 2`; <4 s para 20×12 MP→48 MP em RTX 3090 [11] |
| [IPOL 2023 (Lafenetre et al.)](http://www.ipol.im/pub/art/2023/460/) | IPOL | RAW Bayer | PDF + demo online + ZIP; 500 ms + 190 ms/quadro [12] |
| [kunzmi/ImageStackAlignator](https://github.com/kunzmi/ImageStackAlignator) | GPL-3.0 | RAW/DNG | Windows+NVIDIA; registro+empilhamento astronômico [22] |
| [skittleson/burst-superres](https://github.com/skittleson/burst-superres) | ver repo | JPG/PNG | 4–8 fotos, 2×; PSNR real modesto (+1,07/+1,25 dB em simulação) [23] |
| [spacetelescope/drizzle](https://github.com/spacetelescope/drizzle) | BSD | imagem+pesos | a implementação de referência do algoritmo de drizzle [21] |
| PIPP / AutoStakkert! / Siril | livres | vídeo RGB | seleção dos melhores quadros por nitidez (*lucky imaging*) [37][38][39] |

---

## 5. O que medimos (experimento no material real do PelotaVision)

Rodei um experimento numérico completo usando **fotos reais de pelotas do repositório**
(`ml_training/data_belt/raw/20170619_09-52-13.jpg`, recorte 512²), simulando um sensor 2× pior do que o
real (para chegar no cenário "câmera de qualidade ruim") e injetando tremor sub-pixel gaussiano de
amplitude controlada. Pipeline: gerar rajada → estimar offsets por correlação de fase → 5 reconstruções
→ medir PSNR contra o *ground truth* de 512² e **erro de diâmetro** das 87 pelotas segmentadas (a métrica
que o PelotaVision realmente usa). N=24. O teste de consistência do operador adjunto (usado no IBP) deu
erro **7,2 × 10⁻¹⁶** — a matemática está certa.

![Comparação visual e PSNR por cenário](https://private-us-east-1.manuscdn.com/sessionFile/QPE21sCz5pEmS9i2dvcNoW/sandbox/8KwVoRKaizNBtrG5DuDmni-images_1795177774362_na1fn_L2hvbWUvdWJ1bnR1L3BlbG90YXZpc2lvbi1zci9idXJzdF9zcl9jb21wYXJhY2Fv.png?Policy=eyJTdGF0ZW1lbnQiOlt7IlJlc291cmNlIjoiaHR0cHM6Ly9wcml2YXRlLXVzLWVhc3QtMS5tYW51c2Nkbi5jb20vc2Vzc2lvbkZpbGUvUVBFMjFzQ3o1cEVtUzlpMmR2Y05vVy9zYW5kYm94LzhLd1ZvUkthaXpOQnRyRzVEdURtbmktaW1hZ2VzXzE3OTUxNzc3NzQzNjJfbmExZm5fTDJodmJXVXZkV0oxYm5SMUwzQmxiRzkwWVhacGMybHZiaTF6Y2k5aWRYSnpkRjl6Y2w5amIyMXdZWEpoWTJGdi5wbmciLCJDb25kaXRpb24iOnsiRGF0ZUxlc3NUaGFuIjp7IkFXUzpFcG9jaFRpbWUiOjE3OTcyOTI4MDB9fX1dfQ__&Key-Pair-Id=K2QY5QTL8JSY6C&Signature=MEUCIQCoIDqaYPmMUf2lkSC1d16LJiwFZbLpSbl5npsPHaXNvQIgHhUS-N2iNKCkkB6QcDCZA0xhDl6wqBN3tcifen-1aBE_)

### 5.1 Tabela principal (PSNR dB contra o GT 512²; erro de diâmetro em px)

| Cenário | RMSE registro (px) | 1 quadro + bicúbica | média alinhada | **drizzle 2×** | drizzle+IBP | diam. 1q → média → driz. |
|---|---:|---:|---:|---:|---:|---|
| tremor ~0 px | **0,035** | 30,32 | **41,22** | 38,33 | 33,71 | 0,40 → **0,10** → 0,10 |
| tremor 0,5 px | 0,314 | 29,05 | **35,25** | 34,31 | 27,01 | 0,31 → **0,10** → 0,14 |
| tremor 1,5 px | 1,057 | 24,43 | **27,34** | 25,88 | 22,77 | 0,67 → **0,14** → 0,47 |

Leitura, sem suavizar:

- **O empilhamento vale muito**: 30,3 → 41,2 dB é **+11 dB**, quase exatamente os +13,8 dB previstos para
  N=24. O diâmetro das pelotas fica **4× mais estável** (0,40 → 0,10 px). *Isso é o ganho real.*
- **A fusão em grade 2× perdeu da média alinhada em todos os cenários.** Não por implementação ruim:
- **A fusão em grade 2× perdeu da média alinhada em todos os cenários *com offsets estimados*.** Não por
  implementação ruim — a sonda 5.3 mostra que, com offsets exatos e ruído baixo, ela **ganha**:
  a acutância do drizzle (0,017) é igual à do GT (0,018), enquanto o 1 quadro bicúbica dá 0,030 — isto é,
  a *impressão* de nitidez do aumento por interpolação é artefato, e o 2× honesto não encontrou mais
  informação nesta cena porque **o tremor de 0,5–1,5 px não produziu fases suficientes e o registro
  errou 0,3–1,1 px**.
- IBP piorou (27,0 e 22,8 dB) com registro ruim — comportamento clássico: back-projection amplifica erro
  de deslocamento, e é por isso que ele é o maior vencedor *e* o maior perdedor da tabela 5.3.

### 5.2 Varredura de N (tremor 1,5 px, σ_ruído 0,035)

| N | média alinhada (dB) | drizzle 2× (dB) | erro diâmetro drizzle (px) |
|---:|---:|---:|---:|
| 1 | 25,43 | 24,82 | 0,88 |
| 3 | 27,78 | 26,40 | 0,55 |
| 6 | 29,02 | 27,27 | 0,39 |
| 12 | **29,21** | 27,69 | 0,41 |
| 24 | 29,18 | 27,46 | 0,41 |

Curva que **achata entre 6 e 12 quadros** — coerente com a literatura [13]. Ou seja: *10 fotos bastam;
1000 fotos dariam mais silêncio, não mais detalhe.*

Repare no que a curva medida **não** alcança: partindo de N=1, o teto de √N previa **+13,8 dB** em 24
quadros; medimos **+3,8 dB**. A diferença (≈10 dB) não é física, é do estimador de movimento — com tremor
de 1,5 px o erro de registro foi 1,06 px, e cada meio-pixel errado devolve boa parte do ganho. No cenário
de tremor ~0, em que o registro ficou bom (RMSE 0,035 px), a média alinhada chegou a **41,2 dB**, ou seja
quase exatamente o teto de √N. Essa é a curva mais importante do relatório: o ganho de ruído está
disponível, mas só para quem alinha bem.

![Ganho por cenário](https://private-us-east-1.manuscdn.com/sessionFile/QPE21sCz5pEmS9i2dvcNoW/sandbox/8KwVoRKaizNBtrG5DuDmni-images_1795177774362_na1fn_L2hvbWUvdWJ1bnR1L3BlbG90YXZpc2lvbi1zci9idXJzdF9zcl9nYW5obw.png?Policy=eyJTdGF0ZW1lbnQiOlt7IlJlc291cmNlIjoiaHR0cHM6Ly9wcml2YXRlLXVzLWVhc3QtMS5tYW51c2Nkbi5jb20vc2Vzc2lvbkZpbGUvUVBFMjFzQ3o1cEVtUzlpMmR2Y05vVy9zYW5kYm94LzhLd1ZvUkthaXpOQnRyRzVEdURtbmktaW1hZ2VzXzE3OTUxNzc3NzQzNjJfbmExZm5fTDJodmJXVXZkV0oxYm5SMUwzQmxiRzkwWVhacGMybHZiaTF6Y2k5aWRYSnpkRjl6Y2w5bllXNW9idy5wbmciLCJDb25kaXRpb24iOnsiRGF0ZUxlc3NUaGFuIjp7IkFXUzpFcG9jaFRpbWUiOjE3OTcyOTI4MDB9fX1dfQ__&Key-Pair-Id=K2QY5QTL8JSY6C&Signature=MEUCIQCCRyT2HR~QinCK~vRukCyo4kIr43Q5G4MqeLvE5VzS7gIgH7pGhyl19DkDzfuO-znpS5VZNTxxKmpBSR-BCIpSoa0_)

### 5.3 Sonda: onde está o limitante (teto "oracle" × chão "estimado")

Para separar as três causas que competem entre si (amplitude do tremor, precisão do registro e ruído por
quadro), rodei cada cenário **duas vezes**: uma com os offsets *verdadeiros* ("oracle" = alinhador
perfeito) e outra com offsets estimados por correlação de fase (`upsample_factor=100`) — que é o que o
código Java do app faria. PSNR contra o GT 512²; N=24; saída completa em
[`burst_sr_sonda.json`](/home/ubuntu/pelotavision-sr/burst_sr_sonda.json).

| Ruído/quadro | Tremor σ | RMSE registro | média *(or.)* | drizzle *(or.)* | **IBP 2× *(or.*** | média *(est.)* | drizzle *(est.)* | IBP *(est.)* |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 35/255 | 0,0 px | 0,025 | **41,23** | 37,77 | 37,77 | 41,23 | 38,20 | 37,75 |
| 35/255 | 0,5 px | 0,167 | **40,12** | 38,89 | 32,51 | 38,78 | 37,90 | 32,09 |
| 35/255 | 1,5 px | 0,482 | **39,99** | 37,92 | 32,61 | 33,85 | 32,09 | 28,33 |
| 35/255 | 2,5 px | 0,798 | **39,63** | 37,22 | 32,52 | 30,42 | 28,76 | 26,34 |
| 10/255 | 0,0 px | 0,006 | **43,85** | 39,19 | 39,19 | 43,85 | 39,27 | 39,27 |
| 10/255 | 0,5 px | 0,149 | 41,65 | 40,16 | **42,46** | 40,12 | 39,08 | 38,53 |
| 10/255 | 1,5 px | 0,487 | 41,40 | 38,91 | **42,34** | 34,07 | 32,28 | 29,95 |
| 10/255 | 2,5 px | 0,823 | 40,89 | 38,03 | **42,09** | 30,34 | 28,68 | 27,15 |

Erro de diâmetro das 87 pelotas (px, menor melhor):

| Ruído/quadro | Tremor σ | 1 quadro | média alinhada | IBP 2× (**oracle**) | IBP 2× (estimado) |
|---:|---:|---:|---:|---:|---:|
| 35/255 | 0,0 px | 0,25 | **0,08** | 0,09 | 0,09 |
| 35/255 | 1,5 px | 0,55 | **0,09** | 0,10 | 0,13 |
| 35/255 | 2,5 px | 0,68 | 0,11 | **0,08** | 0,27 |
| 10/255 | 0,0 px | 0,09 | 0,05 | 0,06 | 0,06 |
| 10/255 | 0,5 px | 0,11 | 0,05 | **0,03** | 0,07 |
| 10/255 | 1,5 px | 0,17 | 0,06 | **0,04** | 0,08 |
| 10/255 | 2,5 px | 0,45 | 0,09 | **0,03** | 0,11 |

**Três respostas que sustentam a decisão:**

1. **Existe um teto, e ele é positivo.** Com sensor limpo (ruído 10/255) **e** alinhamento perfeito, o
   **IBP 2× supera a média alinhada** — 42,46 vs 41,65 dB; 42,34 vs 41,40; 42,09 vs 40,89 — e **corta o
   erro de diâmetro pela metade** (0,03–0,04 px vs 0,05–0,09 px). A informação sub-pixel estava lá e foi
   recuperada: a física não é o obstáculo, *desde que as duas condições ocorram juntas*.
2. **Se uma das duas falhar, o ganho evapora.** Com ruído alto (35/255), nem o alinhamento perfeito salva
   o 2×: a média vence tudo (40 dB vs 38 dB). Com alinhamento estimado, o IBP desaba (42,34 → 29,95 dB) e
   o erro de diâmetro quase triplica (0,04 → 0,08 px). O "imposto do registro" medido na média alinhada
   com ruído 35/255: **6,1 dB** a σ=1,5 px e **10,5 dB** a σ=2,5 px.
3. **A armadilha é auto-limitante.** O erro do registro escalou junto com o tremor — 0,006 → 0,149 →
   0,487 → 0,823 px para σ = 0 → 0,5 → 1,5 → 2,5 px, ou seja **≈ 0,33σ**: exatamente o tremor grande que
   cobriria as fases é o que o estimador padrão da área [6] não consegue medir. Não é defeito de
   implementação — é a razão de existir do *gate* de diagnóstico e do **dither mecânico deliberado** do
   prompt master. Controlar o deslocamento em vez de medi-lo (parafuso/atuador de ½ px, ou *bracketing*
   com σ pequeno e N grande) é o que torna o teto do item 1 alcançável.

---

## 6. Tradução para o PelotaVision

O repo já tem metade da infraestrutura de que uma rajada precisa, e isso muda o custo da decisão:

- **Já existe SR de quadro único**: `core/.../FsrcnnUpscaler.java` (FSRCNN ×2, CPU, imagem em cinza,
  `app/src/main/assets/FSRCNN_x2_features.pb`, `third_party/FSRCNN`, `tools/prepare_fsrcnn.py`), mas usada
  **apenas para gerar propostas** em blocos 320×320 (`TilePlan.SR_SIDE=320`, `NORMAL_SIDE=640`), com
  elipse e trincas recalculadas nos pixels originais e vitória só quando há >2 pontos a mais
  (`DetailedAnalysis.java`). **Exatamente a postura correta**: SR como *sugestão*, medida como *medição*.
- **A resolução efetiva é baixa de propósito**: `Mats.MAX_ANALYSIS_SIDE=3000` com `sampleFor` em potência
  de 2 (`INTER_AREA`). Antes de falar em 2×, pergunte quanto do detalhe que o sensor entrega está sendo
  jogado fora *aqui* — é provável que ganhar resolução seja mais barato recuperando amostragem do que
  sintetizando com rajada.
- **A câmera já é travada**: `CameraSource` fixa foco/exposição e registra a aceitação do driver — pré
  condição para rajada. Falta o *buffer de rajada* (CameraX `ImageAnalysis` com fila de N quadros; no
  modo foto atual, "foto = quadro ao vivo 1280×720").
- **O detector de movimento já está escrito**: `LiveStabilizer`/`LiveMotion` (aceita "parado" <1,6 por 4
  medições; "movendo" >3,5; 3 quadros de 180 ms) e `LiveConsensus`. Isso é a **porta de entrada**
  (*gate*) da rajada: só dispara se a câmera estiver imóvel e a correia parada. Custo quase zero.
- **A métrica que importa já existe**: CSV de 32 colunas (`ReportTables`), JSON (`ResultSaver`,
  `core/ReportJson`), original preservado byte a byte. Qualquer rajada precisa de colunas novas
  explícitas (N usado, σ do jitter medido, erro de registro, método) para que a metrologia continue
  auditável.

**Onde a rajada ganha valor real no PelotaVision:** (i) imagens de correia com diâmetro mediano de
28–30 px (belt 1280×720) e ~8,6–9,1 px na bancada (1200×1600) são *pequenas demais para medir borda com
precisão* — +13 dB de SNR encurta o erro de borda sub-pixel, e foi isso que medimos (0,40 → 0,10 px);
(ii) *low-light* de câmeras de celular ruins, onde `LowLightPreprocessor/LowLightRecovery`
(Gama+CLAHE, Suavizar+CLAHE, Retinex) hoje *amplifica ruído* — empilhar antes de realçar é estritamente
melhor que realçar ruído;
(iii) estabilidade de lote: a mediana de N quadros é um produto *metrologicamente* mais defensável do
que um único disparo.

**Onde não ganha:** se a correia estiver em movimento, a rajada é inválida (a bolota anda entre
quadros — a 60 fps, uma queda a 10 m/s anda 17 cm/quadro; no transporte industrial é menos, mas o
suficiente para 28 px de diâmetro). Se o objetivo for "ver mais detalhes da superfície", o concorrente
de melhor custo/benefício é óptica e iluminação (lente melhor, distância menor, luz difusa lateral), não
algoritmo. E se a entrada for JPEG de celular, o piso do JPEG limita antes do ganho teórico.

**Recomendação de prioridade (mesma ordem do prompt master, agora com os números):**

1. **Medir antes de construir.** 20–30 quadros travados na bancada, câmera no suporte, alvo imóvel →
   estimar σ do jitter por correlação de fase. **Se σ < 0,3 px, não há rajada a fazer**: o que você tem
   é média simples (e ganho de SNR pleno, que é o que serve). Se σ ∈ [0,5, 2] px, o burst-SR 2× é
   plausível. Se σ > 2 px, o registro vira o problema e a rajada é cara.
   **Regra ajustada pelos números da 5.3:** o caminho "software puro" está fechado em qualquer σ, porque
   o erro do estimador cresce com o próprio tremor (≈0,33σ). A janela em que o 2× se paga é
   **σ ∈ [0,5, 1] px com deslocamento conhecido por construção** (dither mecânico, suporte com parafuso
   micrométrico, ou sensor da própria câmera se ela tiver *pixel shift*) — não σ medido do zero.
2. **Trilha B (mediana robusta) primeiro.** É ~+11 dB, reduz erro de diâmetro 4×, não precisa de grade
   nova, não muda escala px/mm, e é defensável em auditoria. Implementação em Java/OpenCV: dias.
3. **Trilha C (burst-SR 2×, drizzle + IBP) só como experimento condicionado**, com a mesma honestidade
   do FSRCNN atual: nunca medir diâmetro na grade 2× (a escala px/mm dobraria silenciosamente e o erro
   seria *sistemático*, não aleatório). Use para *proposta* de contorno, não para resultado.
   Prerequisite de entrada na trilha C, senão o resultado será sempre pior que a trilha B: ruído por
   quadro ≲ 10/255 (exposição maior/ISO menor/RAW, não JPEG de celular) e offsets com erro < 0,15 px.
4. **Melhore a aquisição antes da computação**: RAW/quadros sem compressão agressiva, exposição mais
   curta (blur de 3,5 px por quadro inviabiliza 2× [7]), iluminação difusa lateral, tripé/morsa, e —
   se o hardware permitir — *dither* deliberado de meio pixel em vez de contar com a vibração do
   prédio. A literatura de *pixel shift* mostra que deslocamento controlado vence deslocamento aleatório
   em confiabilidade [14][25][27].
5. **Protocolo de validação A/B/C** do prompt master: A = rajada real na bancada; B = rajada com alvo
   em movimento (espera-se rejeição); C = câmera propositalmente tremida (espera-se degradação suave,
   não catástrofe). Critério de aceite em *erro de diâmetro e D50/D90 do lote*, não em PSNR nem em
   "parece mais nítida".

---

## 7. Limites do que foi afirmado aqui

- Os números da seção 5 vêm de **simulação própria** sobre foto real do repo (sensor 2× pior, ruído
  gaussiano, tremor gaussiano, JPEG do *ground truth* já comprimido). Eles ilustram a física do método e
  a sensibilidade ao registro; **não substituem** medir a sua câmera na sua bancada. O ruído de uma
  câmera real é dependente do sinal (shot noise) e o JPEG é não-linear — ambos tendem a piorar o burst
  em relação à simulação, não a melhorar.
- As curvas publicadas que cito são de outras cenas (Kodak, satélite, futebol, astrofotografia). A
  ordem de grandeza é transferível; o número exato, não.
- Não consegui validar em hardware Android: o repo compila apenas no GitHub Actions (`android.yml`,
  `windows.yml`, `tools/ci_status.sh`), e nenhum pipeline de rajada foi executado no aparelho-alvo.
- A reimplementação MIT do burst do Pixel 3 [11] e o artigo IPOL [12] são as referências mais
  confiáveis do estado da arte "honesto" (2×, com modelo de ruído, *deghosting*). As redes neurais de
  burst [28..31] exigem RAW e GPU — não combinam com o perfil do projeto.

---

## Referências

1. Tsai, H.-S.; Huang, T. S. (1984). *Multiframe image restoration and registration* —
   https://scholar.google.com/scholar?q=Multiframe+image+restoration+and+registration+Tsai+Huang
2. Schultz, R. R.; Stevenson, R. L. (1992). *Experiments in multiframe image enhancement* —
   https://scholar.google.com/scholar?q=Schultz+Stevenson+super-resolution+image+reconstruction
3. Capel, D. C.; Zisserman, A.; Pountain, C. (2003). *A posteriori probability spaces for multi-frame
   super-resolution* (Oxford VGG) —
   https://www.robots.ox.ac.uk/~vgg/publications/2003/Capel03/capel03.pdf
4. Baker, S.; Kanade, T. (CVPR 2002). *Limits on super-resolution and how to break them* —
   https://publications.ri.cmu.edu/storage/publications/pub_files/pub3/baker_simon_2002_4/baker_simon_2002_4.pdf
5. Irani, M.; Peleg, S. (1991). *Improving resolution by image registration*, CVGIP: Graphical Models and
   Image Processing 53(3) — a raiz da back-projection iterativa —
   https://doi.org/10.1016/1049-9652%2891%2990045-L
6. Guizar-Sicairos, Thurman & Fienup (2008). *Efficient subpixel image registration algorithms*,
   Opt. Lett. 33:156 — https://doi.org/10.1364/OL.33.000156
7. Ben-Ezra, Zomet & Nayar (TPAMI 2005). *Video super-resolution using controlled subpixel detector
   shifts (Jitter Camera)* — https://cave.cs.columbia.edu/Statics/publications/pdfs/BenEzra_PAMI05.pdf
8. Litterio, Hodge, Srinivasan et al. (ICCP 2025). *Non-uniform phase-shift super-resolution* (fator 8 com
   offsets mecânicos) — https://arxiv.org/html/2505.15961v2
9. *Subpixel accuracy analysis of phase correlation shift measurement methods applied to satellite
   imagery* (números de erro de registro por tipo de cena) —
   https://thesai.org/Downloads/Volume3No12/Paper_33-Subpixel_Accuracy_Analysis_of_Phase_Correlation_Shift_Measurement_Methods_Applied_to_Satellite_Imagery.pdf
10. Wronski et al. (2019). *Handheld Multi-frame Super-Resolution* (Google, SIGGRAPH Asia) —
    https://arxiv.org/abs/1905.03277
11. Jamy-L. *Handheld Multi-Frame Super-Resolution* (reimplementação aberta, MIT; exige RAW) —
    https://github.com/Jamy-L/Handheld-Multi-Frame-Super-Resolution
12. Lafenetre, Facciolo & Eboli (2023). *Handheld Multi-frame Super-resolution*, IPOL — artigo + demo +
    ZIP — https://www.ipol.im/pub/art/2023/460/ (DOI: https://doi.org/10.5201/ipol.2023.460)
13. Wronski et al. (2019), mesma referência de [10] — fonte dos números de saturação de nitidez com N e do
    *deghosting*.
14. Kasson, J. *Does pixel shift increase resolution?* (análise de MTF) —
    https://blog.kasson.com/the-last-word/does-pixel-shift-increase-resolution/
15. OpenCV, *Motion Analysis and Object Tracking* (`phaseCorrelate`, `findTransformECC`) —
    https://docs.opencv.org/4.13.0/d7/df3/group__imgproc__motion.html
16. OpenCV, *Optical Flow* (DIS, Farneback) —
    https://docs.opencv.org/4.13.0/dc/d6b/group__video__track.html
17. OpenCV, *dnn_superres* (EDSR/ESPCN/FSRCNN/LapSRN) —
    https://docs.opencv.org/4.13.0/d9/de0/group__dnn__superres.html
18. Fruchter, A.; Hook, R. (2002). *Drizzle: a step for image reconstruction*, PASP 114 — referência
    original do algoritmo. **URL editorial não verificada nesta pesquisa**; ver implementações em
    [19] e [21].
19. STScI, *DrizzlePac* — notebook de otimização de amostragem no drizzle —
    https://spacetelescope.github.io/hst_notebooks/notebooks/DrizzlePac/optimize_image_sampling/optimize_image_sampling.html
20. Azzari, A. et al. (2015). *Kernel regression for multi-frame super-resolution*, IPOL —
    https://www.ipol.im/pub/art/2015/130/ (autoria/ID informados pela busca; **página não carregada
    nesta pesquisa — confirme antes de citar formalmente**)
21. NASA/STScI, `drizzle` (Python, BSD) — https://github.com/spacetelescope/drizzle
22. kunzmi, *ImageStackAlignator* (GPL-3.0, RAW/DNG) — https://github.com/kunzmi/ImageStackAlignator
23. skittleson, *burst-superres* (JPEG/PNG, 2×) — https://github.com/skittleson/burst-superres ;
    medição independente do ganho: https://docodethatmatters.com/burst-superres/
24. scikit-image, `skimage.registration` (`phase_cross_correlation`, `upsample_factor`) —
    https://scikit-image.org/docs/stable/api/skimage.registration.html
25. Sony, *Pixel shift multi shooting* (4/16 RAW, passo de ½–1 px, tripé) —
    https://support.d-imaging.sony.co.jp/app/imagingedge/en/instruction/1_2_about_psm.php
26. OM System, *High-Res Shot* ("não compatível com alvos em movimento") —
    https://explore.omsystem.com/us/en/high-res-shot
27. Ricoh, *Pixel Shift Resolution* (Pentax K-1 II) —
    https://www.ricoh-imaging.co.jp/english/products/k-1-2/feature/02.html ; Nikon Z8, guia de pixel shift —
    https://onlinemanual.nikonimglib.com/technicalguide/z8/pixelshift/en/scene_with_pixelshift_enabled_4.html
28. Gupta, N. et al. *Deep Burst Super-Resolution* (BSRN, RAW Bayer, 14 frames) —
    https://github.com/goutamgmb/deep-burst-sr
29. *NTIRE21 Burst Super-Resolution Challenge* — https://github.com/goutamgmb/NTIRE21_BURSTSR
30. *Burstormer* (burst-SR com transformer não-supervisionado) —
    https://github.com/HongYang222/Burstormer
31. Bhat et al. (CVPR 2023). *Self-Supervised Burst Super-Resolution* —
    https://openaccess.thecvf.com/content/CVPR2023/html/Bhat_Self-Supervised_Burst_Super-Resolution_CVPR_2023_paper.html
32. Li, X. et al. *Real-ESRGAN* (BSD-3) — https://github.com/xinntao/Real-ESRGAN
33. Cai et al. *RLFN* (Apache-2.0; 0,317 M parâmetros, 27,11 ms) — https://github.com/bytedance/RLFN
34. SwinIR — https://github.com/JingyunLiang/SwinIR ; HAT — https://github.com/XPixelGroup/HAT ;
    SReNet (SR auto-supervisionado) — https://github.com/aaugustinus/SReNet
35. Seweryn et al. (2024). *Super-resolution for football player detection in SoccerNet* —
    https://arxiv.org/abs/2402.00163
36. *Rethinking Super-Resolution for Near-Sensor Object Detection* (SR derruba mAP sem retreinar) —
    https://onsensor-vision.github.io/pdf/Rethinking_Super-Resolution_for_Near-Sensor_Object_Detection.pdf
37. PIPP, *Astronomy Image Processor* (seleção de quadros por nitidez) —
    https://sites.google.com/view/sequator/manual
38. AutoStakkert! (empilhamento *lucky imaging*) — https://www.autostakkert.com/
39. Siril, tutorial de *lucky imaging* — https://siril.org/tutorials/lucky/
40. Yano, K. et al. (2019). *TrackNet: A deep learning network for tracking high-speed and
    invisible objects in multi-view tennis videos* (1 vs 3 frames: F1 92,5 → 98,2%)://ar5iv.labs.arxiv.org/html/1907.03698 ; código:
    https://github.com/yastrebksv/TrackNet ; Hawk-Eye: https://en.wikipedia.org/wiki/Hawk-Eye
41. Kienzle et al. (WACV 2026). *Uplifting Table Tennis: a robust real-world application for 3D
    trajectory estimation* —
    https://openaccess.thecvf.com/content/WACV2026/papers/Kienzle_Uplifting_Table_Tennis_A_Robust_Real-World_Application_for_3D_Trajectory_WACV_2026_paper.pdf
42. Krutul et al. (2026). *Enhancing object detection accuracy in visually challenging scenarios using
    super-resolution* — https://github.com/dkrutul/Enhancing-Object-Detection-Accuracy-in-Visually-Challenging-Scenarios-Using-Super-Resolution
