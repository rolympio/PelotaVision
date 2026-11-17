# Rede de borda (interior / borda / separação) — protótipo

Estado em 16/11/2026: **encanamento pronto e testado em dados sintéticos; não treinada em dados reais;
não ligada ao app.** Substitui o ensaio "IA multitarefa" do Studio 0.8 (3 imagens, 90 passos, pseudo-rótulos),
que segue isolado e não deve ser usado para medir.

## Para que serve (e para que não serve)

- O tamanho no app vem da máscara do SAM 2 recortada pela **borda real** (`edge_v1`: maior degrau de brilho
  em 72 raios, `Sam2Refiner.snapEdge`). Em pelotas encostadas, com sombra, halo ou pouco contraste, o maior
  degrau de brilho nem sempre é a borda.
- A rede aprende **onde está a borda** (mapa de probabilidade com pico no contorno). A proposta `edge_v2`
  troca só o critério de cada raio: em vez do maior `dI/dr`, o máximo da probabilidade de borda. Janela
  0,80–1,15, mediana móvel, limites de área e fallback continuam iguais.
- Não decide **quantas** pelotas há (isso continua com Bordas/LoG/StarDist) e não corrige escala nem
  perspectiva (isso é a calibração por esferas).

## Fluxo (Windows, pasta `D:\Projetos\PelotaVision`)

Tudo grava em `D:\Projetos\PelotaVision-local\` (fora do Git). Venv: `..\PelotaVision-local\envs\borda`.

```bat
REM 0. uma vez: ambiente, venv e agente local
PREPARAR_AGENTE_LOCAL.bat

REM 1. baixar externos (precisa da sua chave Roboflow só nesta sessão do terminal)
set ROBOFLOW_API_KEY=sua_chave
BORDA.bat baixar --prioridade 2

REM 2. converter (polígonos) ou gerar contorno pelo SAM 2 (datasets só de caixas)
BORDA.bat converter rf_iron_ore_pellets_healer
BORDA.bat caixas rf_rubber_pellets_detection

REM 3. auditar se o contorno anotado está na borda real (decide se a fonte pode ensinar borda)
BORDA.bat auditar rf_iron_ore_pellets_healer

REM 4. pré-treino (deixa uma fonte inteira de fora), ajuste fino nas suas fotos, exportar e avaliar
BORDA.bat pretreino
BORDA.bat ajuste ..\PelotaVision-local\datasets\bancada_revisada
BORDA.bat exportar
BORDA.bat avaliar ..\PelotaVision-local\datasets\holdout_revisado
```

**Fotos próprias (X-AnyLabeling):** `BORDA.bat pre <análise>.json` grava a pré-anotação ao lado da
`_filtrada.png` (elipses do app como `auto_pelota`). Renomeie para `pelota` só o que conferir; o que ficar
`auto_*` é ignorado no treino. Depois, `BORDA.bat revisada <análise>.json <anotação>.json` converte para a
foto original e acumula em `..\PelotaVision-local\datasets\bancada_revisada`.

Cada passo também roda direto em Python (ver `--help` de cada script).

## Arquivos

| Arquivo | Papel |
|---|---|
| `borda.py` | dados, alvos, U-Net (~0,48 M parâmetros), inferência em blocos, recorte por raios (espelho do app), métricas |
| `treinar.py` | fases `pretreino` e `ajuste`; divisão por grupo de origem; escolhe o ponto pelo MAE de diâmetro |
| `exportar.py` | ONNX 1×1×128×128 → 1×3×128×128 (opset 13), confere ONNX Runtime × PyTorch, grava SHA-256 e fontes/licenças |
| `avaliar.py` | rede × gradiente (`edge_v1`) no recorte por raios, com critério de implantação |
| `test_borda.py` | testes sintéticos (incluem treino curto em CPU) |
| `../../tools/externos/` | baixar do Roboflow, converter COCO, caixas→SAM 2, auditar borda anotada |

## Regras que não mudam

1. Rótulo do Roboflow, do SAM 2 ou de qualquer algoritmo **não é verdade-terreno de tamanho**. Entra no
   pré-treino; a avaliação é em fotos próprias com contorno revisado e nas esferas de 11 mm.
2. Fonte só ensina **borda** se `auditar_borda_anotada.py` der |viés de diâmetro| < 1% e ≥ 80% dos objetos
   dentro da tolerância. Fora disso, só o interior entra (a faixa de borda fica com peso 0).
3. Validação por **fonte inteira** (pré-treino) ou por **foto/sessão** (ajuste), nunca por pelota.
4. Critério de implantação, fixado antes de ver os números: MAE da rede ≤ 0,8 × MAE do gradiente,
   |viés| ≤ 0,5%, aceitação não cai mais que 5 pontos, e não piorar nas esferas de 11 mm.
5. Licenças: CC BY exige atribuição. `pellet_borda.json` carrega a lista de fontes para a tela "Sobre"
   e o relatório. Fonte sem licença conferida não entra no modelo distribuído.

## Integração no app (só depois do critério 4)

1. `core/Sam2Refiner.java`: novo `EdgeNet` (OpenCV DNN, mesma convenção do `NeuralPelletDetector`):
   reescala a janela da pelota para raio `R_NOM` = 16 px, normaliza por percentis, roda em blocos de 128,
   e `snapEdge` usa `P(borda)` com limiar 0,30 no lugar de `dI/dr` (`edge_v2`). Fallback automático
   para `edge_v1` sem modelo.
2. JSON: `sam2_refinement.edge_method = edge_v1|edge_v2`, SHA-256 do modelo, nº de recortes recusados.
3. Paridade: teste Java × `borda.recortar_por_raios` nas mesmas imagens (raio por pelota, tolerância 0,05 px).
4. Opção na interface (Windows e Android), desligada por padrão até a validação nas esferas.
