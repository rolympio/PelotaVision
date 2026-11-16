# Rede de borda e dados externos (Roboflow) — plano de 16/11/2026

## Onde estamos (base: `integracao/0.17.0-sam2`, commit 8721e45)

- Android 0.17.0 / Windows 0.9.0 compilados em 10–11/11 com SAM 2.1 Tiny: o detector conta, o SAM 2 dá a
  máscara e o `edge_v1` recorta pelo maior degrau de brilho em 72 raios (`Sam2Refiner.snapEdge`).
- Esferas de 11 mm, 3 esferas e k ajustado: viés +0,17 mm, erro 0,22 mm no SAM 2 (~2%). O que sobra é
  dispersão foto a foto.
- Foto de referência: 72 de 150 pelotas com IoU do SAM 2 < 0,8 (marcadas para revisão).
- "IA multitarefa" do Studio 0.8 (#30): rede interior/borda/separação com 3 imagens e 90 passos. Isolada,
  não mede nada. Este plano a substitui por um treino de verdade (`ml_training/borda`).

## A pergunta: dados do Roboflow melhoram a estimativa?

**Só a parte da segmentação, e só se o contorno anotado estiver na borda real.** Eles não mexem na escala nem
na perspectiva, que hoje respondem por parte do erro. E um contorno desenhado 1 px para dentro, numa pelota de
15 px de raio, dá −6,7% no diâmetro: um dataset assim ensinaria a rede a errar mais que o app erra hoje. Por
isso todo dataset externo passa por `auditar_borda_anotada.py` antes de ensinar borda.

O que dá para tirar deles com segurança:
1. **Pré-treino de interior/separação** (pelotas encostadas, fundos e luz variados): muitos exemplos, rótulo
   grosso serve.
2. **Pré-treino de borda**, só das fontes que passam na auditoria (|viés| < 1%, ≥ 80% dos objetos na tolerância).
3. **Datasets só de caixas** (ex.: Rubber Pellets Detection, pellets-models com 2.106 imagens): viram contorno pelo
   SAM 2 e entram como pseudo-rótulo marcado, também auditado.

O que não dá: validar tamanho. A validação continua sendo foto própria revisada + esferas de 11 mm.

## Fontes levantadas

Registro completo em `tools/externos/fontes_roboflow.json`. Destaques:

| Fonte | Tipo | Licença na página | Uso |
|---|---|---|---|
| healer-vt39f/iron-ore-pellets | segmentação | CC BY 4.0 | pré-treino (prioridade 1) |
| hamitouche/iron-pellets v1 | segmentação, 195 imagens | Public Domain | pré-treino (prioridade 1) |
| object-detection-wpdz3/rubber-pellets-detection v3 | caixas | CC BY 4.0 | caixas → SAM 2 |
| yiboli-9synx/pellets-models v2 | caixas, 2.106 imagens | a conferir | caixas → SAM 2 |
| eric-smallwood/plastic-instanceseg | segmentação (pellet/fragmento/fibra) | CC BY 4.0 | só classe pellet |
| evin-bu0tz/stones-segmentation | segmentação | CC BY 4.0 | separação entre vizinhos |

Fora do Roboflow, para comparação e não para distribuição:
- **Cellpose-SAM**: código BSD-3, mas modelos treinados com dados CC-BY-NC. Serve como comparador interno de P&D,
  não para embarcar.
- **StarDist em pelotas** (Solomko et al., 2025): mesma família do detector do app. Reforça a escolha, sem dados públicos.
- **U-Net leve para pelotas verdes** (Duan et al., 2019) e monitoramento on-line com U-Net leve (IEEE T-ASE, 2023, comparado com peneiramento): mapas de contorno de
  pelota com U-Net. É a mesma ideia da rede de borda.
- **ParticleSeg3D** (nnU-Net, representação borda-núcleo e normalização de tamanho): é CT 3D, mas a normalização
  pelo raio foi copiada aqui (`R_NOM`).
- **Pellet_Size_Distribution** (CSIR-IMMT): continua bloqueado, sem licença.

## O que foi entregue nesta branch (`feature/rede-borda-roboflow-agente-local`)

- `tools/externos/`: baixar do Roboflow (chave só na variável de ambiente, proveniência e SHA-256), converter COCO
  (outras classes viram região ignorada, aumentos agrupados), caixas → contorno SAM 2 (sem `edge_v1`, para evitar
  circularidade), auditoria de borda anotada (offset em px e viés de diâmetro, pranchas, correção opcional).
- `ml_training/borda/`: U-Net de ~0,48 M parâmetros, 3 saídas, alvos com pesos (contorno não confiável não ensina
  borda), pré-treino e ajuste fino com divisão por origem, ONNX 1×1×128×128 (confere com ONNX Runtime; carrega
  no OpenCV DNN), avaliação rede × `edge_v1` com o mesmo recorte por raios do app.
- `BORDA.bat`, `PREPARAR_AGENTE_LOCAL.bat`, skills `pelotavision-borda` e `pelotavision-roboflow`, `docs/agente-local.md`.
- Testes sintéticos: 8 passando. Em discos sintéticos, 60 iterações em CPU já levam a rede a ~0,7% de MAE no
  diâmetro, contra ~0,3% do gradiente. Nesse caso fácil o gradiente é melhor, como esperado: só prova o encanamento.

## Critério de implantação (fixado antes de qualquer número real)

Nas fotos próprias com contorno revisado, fora do treino:
- MAE de diâmetro da rede ≤ 0,8 × MAE do `edge_v1`;
- |viés| ≤ 0,5%;
- aceitação do recorte não cai mais que 5 pontos;
- e não piora nas esferas de 11 mm (`AVALIAR_ESFERAS.bat` com `edge_v2`).

## Pendências que dependem do Ricardo

1. Chave do Roboflow (conta gratuita) para baixar. Ela fica só na variável do terminal.
2. Um holdout próprio com contorno revisado (sugestão: 20–30 fotos de bancada e 10 de correia, máscaras do SAM 2
   corrigidas à mão). Sem isso, nenhum resultado decide implantação.
3. Conferir com Jurídico/PI se CC BY e Public Domain podem entrar em modelo embarcado em app interno da Vale.
