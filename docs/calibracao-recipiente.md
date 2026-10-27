# Calibração pelo diâmetro interno do recipiente

Disponível no Android (Escala / mm → **Calibrar pelo diâmetro interno do recipiente**) e no Windows (painel Escala → **Calibrar pelo diametro do recipiente…**). O usuário informa o diâmetro interno real, medido por dentro, em mm.

## Detecção automática da borda

`ContainerRimDetector` (núcleo, Java puro, igual nas duas plataformas) roda numa cópia em cinza com lado maior de 640 px:

1. Votação de Hough pelo gradiente para centros de círculos grandes (raio de 15% a 53% do menor lado); as pelotas são pequenas demais para vencer.
2. Perfil radial em 360 raios: o raio cuja borda persiste em quase toda a circunferência (percentil 30 da derivada radial, janela de ±9%). Num aro dobrado, escolhe a borda interna.
3. Borda subpixel por raio e ajuste robusto `r(θ) = r0 + a1 cos θ + b1 sin θ + a2 cos 2θ + b2 sin 2θ`. Os termos de 1ª ordem corrigem o centro; os de 2ª ordem medem a **ovalização** `(rmax − rmin)/r`.
4. **Nível das pelotas:** em cada raio, a primeira transição parede → material indo para dentro; círculo ajustado à envoltória externa (pontos em vãos entre pelotas são descartados).

A sugestão nunca é aplicada sozinha: o círculo é editável (linha/alças = raio, cruz = mover) e a aplicação exige marcar "Conferi o círculo, o plano e o diâmetro interno real".

## Plano da medida — a limitação principal

A escala só vale no plano onde o círculo foi medido. Num recipiente fundo, a borda está mais perto da câmera que as pelotas, então a escala da borda **superestima px/mm no nível das pelotas e as pelotas saem menores**.

Na foto de bancada de 26/10/2026 (`verification/container/recipiente_pelotas_640.jpg`, peneira com pelotas, foto de cima):

| Medida | Resultado |
|---|---|
| Ø da borda na foto original (2212 px de altura) | 1554 px, variação < 0,2% entre 400 e 800 px de trabalho |
| Ovalização da borda | 1,9% |
| Nível das pelotas / borda | 0,843 |
| Escala na borda vs. nível das pelotas | **+18,6%** — calibrando na borda, as pelotas sairiam ~16% menores |

Por isso há dois planos:

- **Nível das pelotas (contato com a parede)** — proposto quando encontrado. Supõe parede cilíndrica (peneira, bandeja reta) e pelotas periféricas encostadas nela. Recipiente cônico ou monte afastado da parede invalidam a hipótese.
- **Borda superior interna** — medida mais nítida, mas no plano errado se o recipiente for fundo. O app mostra o viés estimado quando os dois círculos existem.

Mesmo no nível das pelotas, uma camada em monte deixa as pelotas do centro mais perto da câmera que as da parede. Recomendações de captura: câmera alta e paralela à boca (zoom óptico em vez de aproximar), camada nivelada e rasa, recipiente cilíndrico.

## Bloqueios e rastreabilidade

- Ovalização da borda acima de 6% bloqueia (foto oblíqua; não há correção de perspectiva). Acima de ~15% a detecção não converge e o círculo precisa ser posto à mão.
- Mais de 15% da circunferência fora da foto bloqueia.
- A referência é invalidada se a imagem analisada mudar de dimensão.
- JSON: `physical_scale.source = container_inner_diameter_user_confirmed` e `physical_scale.container_reference` (plano, diâmetro real, px na original, ovalização, razão borda/nível, centro e raio). O PDF mostra a linha "Recipiente".
- Após aplicar, o app propõe uma ROI circular no interior da borda (só vale após Aplicar no editor de ROI).

## Validação pendente

Os números acima medem **repetibilidade e coerência geométrica**, não exatidão. Falta comparar, em fotos reais, o diâmetro das pelotas medido com esta escala contra paquímetro ou peneiramento.
