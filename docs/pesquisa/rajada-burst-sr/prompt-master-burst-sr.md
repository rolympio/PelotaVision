# PROMPT MASTER — Rajada + Super-resolução multiframe (Burst SR) no PelotaVision

Como usar: cole este arquivo inteiro numa sessão de agente com o repositório `rolympio/PelotaVision`
aberto (Android `0.16.0` / Windows `0.6.0`). Ele foi escrito para ser executado por fases, com
medição antes de promessa. Se alguma asserção sobre o código não bater com a versão atual, **pare e
corrija a asserção** antes de implementar.

---

## 0. Missão e regra de ouro

Objetivo: quando a câmera for ruim (sensor pequeno, luz baixa, lente fraca, montagem que treme),
extrair informação real de **N fotografias da mesma cena** para melhorar a medição granulométrica —
reduzindo ruído, melhorando a posição sub-pixel das bordas e, **só se as condições permitirem**,
dobrando a resolução efetiva.

Regra de ouro, não negociável: **primeiro meça, depois prometa.** A literatura e o experimento
interno (`burst_sr_demo.py`, `burst_sr_probe.py`, resultados em `burst_sr_sonda.json`) mostram que:

1. O ganho de **resolução** por rajada tem teto físico em **2×** quando a câmera é a mesma, porque o
   pixel integra luz (limite de Nyquist do sensor). Acima disso, sem priors de rede neural, é
   interpolação com nome novo.
2. O ganho de **ruído** é real e grande: SNR cresce com √N (10 frames ≈ 10 dB, 100 ≈ 20 dB,
   1000 ≈ 30 dB) — **mas só para o que está parado entre os frames**.
3. **Se a câmera não treme (tripé rígido, sem dither controlado), não há ganho de resolução
   nenhum.** Empilhar vira média e o que se ganha é SNR. O experimento mediu isso: com jitter ~0, a
   fusão drizzle 2× ficou **2,9 dB pior** que a média alinhada simples.
4. O gargalo prático não é a quantidade de frames e sim a **precisão do registro**. Com offsets
   estimados do próprio ruído, o erro de registro ficou entre **0,04 e 1,06 px** conforme a amplitude
   do tremor e o ruído; com offsets ideais ("oracle") o resultado é outro mundo. Registro errado não
   borra: cria imagem fantasma que engorda ou emagrece a pelota.
5. Nunca declare exatidão dimensional a partir de nitidez aparente. O README do projeto já diz:
   *"Super-resolução pode modificar a aparência sem melhorar a exatidão dimensional."*

Entregue três coisas separadas e não uma: **Burst Clear** (empilhar para reduzir ruído), **Burst SR 2×**
(dobra de resolução, condicionado à medição) e **evidência medida** de qual dos dois melhorou a
granulometria em fotos reais.

---

## 1. Contexto real do repositório (verifique antes de codificar)

- Núcleo comum sem Android: `core/src/main/java/br/com/pelotavision/` (Java + OpenCV 4.12).
  Aplicação Android em `app/` (CameraX com interop Camera2), aplicação Windows em `desktop/`
  (Swing + OpenCV oficial x64 + webcam via `CameraSource`).
- Já existe super-resolução de **imagem única**: `core/.../FsrcnnUpscaler.java` (FSRCNN x2,
  TensorFlow `.pb` por `Dnn.readNetFromTensorflow`, CPU, entrada **cinza**), com o modelo em
  `app/src/main/assets/FSRCNN_x2_features.pb`, procedência em `third_party/FSRCNN/`
  (Apache-2.0, pesos de Saafke treinados em T91/General100, **não é modelo de pelotas**) e
  reprodução por `tools/prepare_fsrcnn.py`.
- Uso atual dessa SR: `core/.../DetailedAnalysis.java` + `core/.../TilePlan.java`
  (`NORMAL_SIDE = 640`, `SR_SIDE = 320`, sobreposição de 65% do passo). A SR **só gera propostas**;
  a elipse final e as trincas são recalculadas nos **pixels originais** (`refineOriginal(..., .5)`),
  proposta sem correspondência vira `needsReview`, e o tratamento SR só vence quando supera o atual
  em **mais de 2 pontos**. Preserve essa política de contenção de dano.
- Amostragem: `Mats.MAX_ANALYSIS_SIDE = 3000` com `sampleFor()` em potência de dois (INTER_AREA).
  Ou seja, hoje o app **joga resolução fora** quando a foto passa de 3000 px — isso é uma perda
  maior do que qualquer ganho que a rajada daria depois.
- Captura: `desktop/.../CameraSource.java` já tenta travar foco e exposição e registra no JSON o que
  o **driver aceitou** (`lock_focus_requested`, `auto_exposure_off_accepted_by_driver`,
  `exposure_value_read`, `fourcc`, `fps_reported`). Reaproveite esses sinais: rajada sem AE/AF travado
  é inútil (a fusão mistura exposições diferentes).
- Ao vivo: `desktop/.../LiveStabilizer.java` + `LiveMotion.java` decidem "cena parada"
  (parado < 1,6 níveis de cinza por 4 medições; movendo > 3,5) e já capturam 3 quadros parados com
  180 ms de intervalo para a busca de ajuste. `LiveConsensus` confirma detecções em ≥ 60% dos últimos
  10 quadros. **Esse detector de estabilidade é a porta de entrada natural da rajada.**
- Luz baixa: `LowLightPreprocessor`/`LowLightRecovery` (Gama+CLAHE, Suavizar+CLAHE, Retinex) com a
  mesma regra: só propõe, mede no original. A rajada compete diretamente com esse recurso — compare.
- Fotos reais disponíveis para ensaio: `ml_training/data_belt/raw/*.jpg` (1280×720, mediana de
  28–30 px de diâmetro de pelota, p10 ≈ 10 px), `ml_training/data_bench_20261026/images`
  (1200×1600, celular), `ml_training/data_webcam_20261027/images/*_original.png` (1920×1080).
- Convenções do projeto que continuam valendo: CSV por pelota com as 32 colunas de `ReportTables`,
  JSON com as chaves de `ResultSaver`/`core/ReportJson`, original preservado byte a byte, um item por
  commit, mensagens em português, evidências em `verification/<tema>-<versão>/` e documentação em
  `docs/`. Build real de Android/Windows só no GitHub Actions (`android.yml`, `windows.yml`);
  resultados legíveis saem como anotações (`::notice`), e `tools/ci_status.sh` espera/lerá o run.

---

## 2. Arquitetura em quatro trilhas (entregue nesta ordem)

### Trilha 0 — Diagnóstico de rajada (obrigatória, vem antes de qualquer fusão)
Nova classe `core/.../BurstDiagnostics.java` + tela/botão **"Analisar rajada"** nas duas plataformas.
Ela recebe N imagens (ou N quadros do ao vivo) e devolve um laudo **sem produzir imagem final**:

- offsets globais por frame (parte inteira por correlação de fase; parte sub-pixel por refinamento
  DFT sobreamostrado — equivalentes Java: `Core.phaseCorrelate` com `Core.createHanningWindow`, e
  refinamento próprio por busca local ou `VideoTracking.findTransformECC` com `MOTION_TRANSLATION_EUCLID`);
- **campo de fases** real: histograma das frações `(dx mod 1, dy mod 1)` e a **cobertura da grade**
  (percentual de células de uma grade 2× tocadas por ≥ 2 frames distintos);
- amplitude do tremor: mediana e p90 de |offset| em px;
- deriva de brilho/temperatura entre frames (diferença média em blocos de fundo) e rejeição de frames;
- nitidez por frame (Laplaciano variance, already used pattern do projeto) e qual seria a base;
- Estimativa de ruído por frame (desvio em área lisa, ou transformada de Anscombe generalizada se
  houver RAW) e o ganho teórico de SNR (√N);
- Veredito: `NADA_A_FAZER` | `SOMENTE_MEDIANA` | `SR_2X_PLAUSIVEL`.

Critério de decisão (deixe os números nomeados como constantes, ajustáveis, mas comece assim):

```
SR_2X_PLAUSIVEL  somente se  N >= 8
                   e p90(|offset|) entre 0,15 e 2,0 px
                   e cobertura da grade 2x >= 60%
                   e erro de registro interno <= 0,15 px (medido em pirâmide, 2 metades do frame)
                   e deriva de brilho <= 1,5 nível de cinza
                   e cena classificada como PARADA pelo LiveMotion
caso contrário     -> SOMENTE_MEDIANA (com pesos robustos), e dizer por quê na tela
```

Publicar o laudo no JSON (`burst.diagnostics`) e na tela. **É proibido habilitar SR 2× por padrão.**

#### Calibração medida (2026-11-20, em fotos reais do repo — use como alvo, não como fé)

Ensaio: recorte 512² de `ml_training/data_belt/raw/20170619_09-52-13.jpg`, N=24, sensor simulado 2× pior
que o real, ruído por quadro 35/255 e 10/255, tremor gaussiano σ ∈ {0; 0,5; 1,5; 2,5} px. Código:
`burst_sr_demo.py` e `burst_sr_probe.py`; dados: `burst_sr_results.json` e `burst_sr_sonda.json`;
relatório: `RELATORIO-super-resolucao-multiframe.md` (seções 5.1, 5.2 e 5.3).

| Fato medido | Valor | Consequência para este prompt |
|---|---|---|
| Mediana/média alinhada vs 1 quadro | **+11 dB** de PSNR (30,3 → 41,2) e erro de diâmetro **0,40 → 0,10 px** (4× menos) | A Trilha 1 é o produto. Alvo de aceite em bancada: reduzir ≥3× o desvio do diâmetro entre capturas repetidas da mesma amostra. |
| Curva de ganho vs N (σ=1,5 px) | achata entre **N=6 e N=12** (27,8 → 29,2 dB; diâmetro 0,88 → 0,41 px) | N≈10 basta. Não prometa "1000 imagens" para resolução; prometa para ruído. |
| Erro do estimador de offsets (correlação de fase, `upsample_factor=100`) | **≈ 0,33σ**: 0,006 / 0,149 / 0,487 / 0,823 px para σ = 0 / 0,5 / 1,5 / 2,5 px | O critério `erro de registro <= 0,15 px` **só se satisfaz sozinho com σ ≲ 0,5 px**, e nesse regime a cobertura de grade é pobre. Logo `SR_2X_PLAUSIVEL` vai raramente disparar por vibração natural — **isso é a resposta certa, não um bug a consertar**. |
| Custo de usar offsets estimados no lugar dos reais | **−6,1 dB** (σ=1,5, ruído alto), **−10,5 dB** (σ=2,5); no IBP 42,3 → 30,0 dB; diâmetro 0,04 → 0,11 px | A Trilha 2 jamais entra sem o erro de registro medido internamente. Pesos robustos e rejeição de frame não são opcionais. |
| Teto do 2× com offsets *oracle* | com ruído **10/255**: IBP **42,46 vs 41,65 dB** da média e diâmetro **0,03 px vs 0,05–0,09** | O 2× é real, mas exige **as duas** condições: ruído ≲ 10/255 (exposição/ISO/RAW — JPEG de celular não chega) **e** erro de offset < 0,15 px. Com ruído 35/255 ele perde da média até com alinhamento perfeito. |
| Iterações de IBP | 30–60 iterações degradaram (33,7 dB e 27,0 dB); 8 iterações com passo `1,2·N/λ` preservaram o ganho | A regra "6 a 12 iterações, nunca as 60" está confirmada por medida. |

**Leitura operacional:** o retorno confiável é **SNR + estabilidade do diâmetro** (Trilha 1, quase de
graça, sem tocar na escala px/mm). Resolução dobrada (Trilha 2) só se paga com deslocamento
**controlado** — parafuso micrométrico/atuador de ½ px, ou *pixel shift* do próprio sensor, ou a Trilha 4
(movimento da correia compensado por pelota). Se o laudo disser `SOMENTE_MEDIANA`, entregue isso como
bom resultado e explique o motivo medido na tela.

### Trilha 1 — Burst Clear (mediana robusta, sem mudar resolução)
`core/.../BurstAlign.java` (tradução sub-pixel, warp cúbico) + `core/.../BurstFuse.java`
(**mediana ponderada com peso de robustez por pixel**, não média simples).
- Pesos: Gaussiana do resíduo vs. consenso + confiança do registro por frame (frames com resposta de
  correlação de fase baixa perdem peso).
- Saída no **mesmo grid de pixels da imagem de base**, mesma escala px/mm, zero mudança de coordenadas.
- Efeito esperado: queda de ruído ≈ √N e, por consequência, bordas menos erráticas. É o produto de
  maior valor por menor risco: **não altera geometria, não inventa pixel.**
- Validar que reduz o desvio do diâmetro entre capturas repetidas da mesma amostra (ver §5).

### Trilha 2 — Burst SR 2× (condicionada ao veredito da Trilha 0)
Reconstrução explícita, em duas etapas determinísticas e auditáveis:

1. **Drizzle / convolução normalizada** na grade 2×: cada pixel LR é projetado com o footprint do
   pixel (caixa de lado 2 em unidades HR) deslocado pela fase sub-pixel estimada; acumula
   `num/den`. Pesos de área, sem IA, sem interpolação "mágica".
2. **Refinamento por back-projection iterativa** (IBP) com o operador direto explícito
   `A(x) = decimação( integração 2×2( translação(x, offset) ) )` e sua adjunta por scatter com os
   **mesmos pesos**. Passo por iteração de potência em `AᵀA` (relaxação inicial 1,2; 6 a 12 iterações,
   **nunca** as 60 do experimento, que amplificam ruído). Inclusão de máscara de robustez (rejeita
   pixels inconsistentes → deghosting) e **regularização de suavidade nas áreas planas**, mantendo
   a aresta.

Regras de integridade numérica que a Trilha 2 precisa demonstrar em teste unitário:
- `<A x, r> == <x, Aᵀ r>` dentro de 1e-9 relativo (o experimento em Python faz isso e dá 7,2e-16);
- reconstruir uma cena sintética de bandas conhecidas e medir MTF na frequência de Nyquist da grade
  LR e em 0,5·Nyquist da HR; a SR **tem que subir a MTF** acima da média alinhada, senão desliga;
- reconstruir campo plano sem artefato e sem desvio de nível (bias ≤ 0,5 nível de cinza);
- com offsets trocados de propósito (injetando erro de 0,3 px), o teste deve **rejeitar** a SR e
  cair na mediana (nunca entregar reconstrução geometria-inconsistente).

Se a rede neural entrar depois (Trilha 3), ela **refina** a saída da IBP; nunca substitui o registro,
e continua restrita a proposta, com a elipse e as trincas medidas nos pixels originais — como o FSRCNN
de hoje.

### Trilha 3 — Rede neural (opcional, só depois de medir)
- Continuar com FSRCNN x2 (CPU) como baseline e comparar `ESPCN x2` e um modelo leve (RLFN-classe)
  convertido para o runtime já usado (`Dnn`/ONNX Runtime no Windows, LiteRT no Android, com a sonda
  de paridade existente em `HardwareAcceleration.java`).
- burst-SR深度学习 (BSRN/Deep Burst SR, Burstormer) exige **RAW Bayer** do sensor, PyTorch/GPU e
  pipeline de demosaicing próprio: **fora do escopo** enquanto o app consome JPEG/sRGB. Documentar a
  decisão, não "deixar em aberto".
- Auto-supervisão (SReNet/DUSRM) e treinar no domínio: só se houver pelo menos 200 fotos reais com
  anotação de referência. Sem isso, não comece.

### Trilha 4 — Rajada em correia em movimento (pesquisa, depois das anteriores)
Se a amostra chega pela correia, o movimento **é** a fonte ideal de fases sub-pixel diferentes — mas
exige compensação de movimento por pelota, não por frame. Investigar como experimento offline em
`tools/`: rastrear cada pelota por K quadros, remapear sua trajetória para a grade HR e reconstruir
**uma imagem por pelota** (super-resolução ao nível do objeto), que alimenta diâmetro e forma.
Documentar como experimental e medir contra paquímetro/peneira antes de qualquer UI.

---

## 3. Matematicamente o que o implementador deve respeitar

- Modelo direto por frame: `y_k = D_k B S_k(x) + n_k` — translação sub-pixel `S`, integração do pixel
  `B`, decimação `D`, ruído `n`. Toda a Trilha 2 é a inversão regularizada disso. Nada de "aumentar a
  imagem e afiar".
- Limite de informação: com PSF quadrada e traslações puras, mais imagens **não** criam informação
  acima do Nyquist do sensor (Baker & Kanade, CVPR 2002). O que a rajada traz de fato é (a) amostras
  em fases distintas, se houver tremor; (b) SNR; (c) no caso Bayer, informação de cor por posição.
- Teto prático publicado para rajada de câmera comum: **2×** (Wronski et al., SIGGRAPH 2019 — o
  Super-Res Zoom do Pixel 3; reimplementação aberta em Lafenetre/Facciolo/Eboli, IPOL 2023, e no
  repo MIT `Jamy-L/Handheld-Multi-Frame-Super-Resolution`, que exige **RAW .dng/.ARW/.CR2**).
- Fator > 2× em cena real só foi demonstrado com **deslocamentos mecanicamente controlados e alvo
  imóvel** (ICCP 2025, 64 imagens, fator 8, câmera em mesa de posicionamento fino) — não é o nosso caso.
- Medição independente de pixel shift (Kasson, análise de MTF): 16 disparos com deslocamento de ½ px
  **não** aumentam a MTF em ciclos/mm no caso ideal; reduzem aliasing e melhoram o demosaicing. Ou
  seja, "mais disparos" não é "mais resolução" — é mais amostragem de fase, e só se a fase existir.
- Custo real medido na reimplementação aberta: 12 MP × 20 frames → 48 MP em **< 4 s** numa RTX 3090,
  e ~100 ms por frame de 12 MP no celular do artigo do Google (com GPU/ISP dedicados). Assuma que no
  nosso runtime (CPU, Java/OpenCV, e Android ARM64) será **5 a 20× mais lento**: daí o teto de
  frames e o uso de ROI.
- Ferramenta aberta sem IA que faz exatamente o que o usuário pediu (`skittleson/burst-superres`,
  aceita JPEG/PNG, recomenda 4–8 fotos, entrega 2× por dimensão) reporta **+1,07/+1,25 dB de PSNR**
  em testes simulados e descreve o ganho real como modesto. Use esse número como régua de
  honestidade: se a sua promessa for muito maior que isso em cena real, desconfie da sua medição.

---

## 4. Interface e fluxo de produto

Windows (`desktop/`):
- Botão **"Rajada"** ao lado de Capturar. Diálogo: `N (4, 8, 16, 24)`, intervalo, travar
  exposição/foco (marcado como aceito/recusado pelo driver), modo (`Analisar rajada`,
  `Empilhar (mediana)`, `Empilhar + SR 2×` — este último desabilitado se o veredito não for
  `SR_2X_PLAUSIVEL`), e uma caixa de texto com o laudo.
- Ao vivo: quando `LiveMotion` declara PARADA por ≥ 0,6 s, opção "Empilhar quadros parados"
  (reaproveitando a captura já feita por `LiveStabilizer`, hoje limitada a 3 quadros de 180 ms).

Android (`app/`):
- CameraX `ImageCapture` com `Camera2CaptureRequestOptions`: travar AE (`AE_LOCK`), AWB e foco antes
  do disparo; solicitar `JPEG` na maior resolução aceita e, se o dispositivo expuser
  `DNG_PROFILE_ID`, permitir RAW (deixar claro que RAW é caminho futuro para o método Bayer-aware).
- Se o aparelho cai no modo "foto = quadro ao vivo" (1280×720, já tratado em
  `docs/camera-externa-e-ao-vivo.md`), a rajada de quadros do preview passa a ser a única fonte —
  nesse caso o ganho de SNR é o produto, não a SR.

Ambos: a Rajada **nunca** sobrescreve o original. Salve os N frames como arquivos próprios
(`SaveNaming` já dá o padrão), a imagem reconstruída como arquivo derivado nomeado e rastreável, e o
JSON com o bloco abaixo.

```jsonc
"burst": {
  "enabled": true,
  "mode": "median | median_sr2x | rejected",
  "verdict": "SR_2X_PLAUSIVEL | SOMENTE_MEDIANA | NADA_A_FAZER",
  "frames_requested": 24, "frames_used": 21, "frames_rejected": [{"index": 4, "reason": "deriva_brilho"}],
  "base_frame": 0,
  "jitter_px": {"median": 0.61, "p90": 1.32},
  "grid2x_coverage": 0.71,
  "registration_rmse_px": 0.09,
  "noise_estimate": 6.2, "snr_gain_db_expected": 6.6, "snr_gain_db_measured": 5.9,
  "ibp": {"iterations": 10, "relaxation": 1.2, "step": 2.9, "adjoint_error": 7.2e-16},
  "output": {"path": "..._burst.png", "scale_px_per_mm": 0.0, "grid": "original | 2x"},
  "measurement_source": "original_pixels",  // sempre: elipse e trincas
  "used_for_measurement": false
}
```

Regra de escala: se (e somente se) a reconstrução estiver na grade 2×, toda conversão px↔mm usa
`scale/2` e isso **tem que aparecer no JSON** e na tela. Coordenadas de resultado continuam na foto
inteira original, como em `PhotoSession`/`AnalysisWindow`.

---

## 5. Protocolo de validação (é aqui que o recurso nasce ou morre)

Sem isto pronto, o recurso não entra na release. Crie `tools/burst_validate/` (Python, fora do app;
o projeto já tem precedentes em `tools/cracks/` e `tools/litert/`) com três ensaios:

**A — Repetibilidade em cena parada (o teste decisivo para granulometria).**
Monte a câmera como no uso real (mesmo suporte, mesma luz), fotografe a **mesma amostra** 20 vezes, e
compute, para N = 1, 4, 10, 20 (e para cada modo: sem tratamento, mediana, mediana+SR):
- contagem de pelotas e **CV da contagem** entre rajadas independentes;
- D10/D50/D90 e desvio de D50;
- erro absoluto médio de diâmetro por pelota **contra medição física** (paquímetro em ≥ 30 pelotas
  marcadas, ou peneira de referência) — este é o número que importa;
- tempo de CPU por frame adicional e pico de memória.
Aceite: o modo só é habilitado se reduzir ≥ 20% o erro de diâmetro **ou** ≥ 30% o CV de D50, sem
aumentar a contagem de "inconclusivas". Caso contrário, entrega a mediana e desliga a SR, e escreve
isso no `docs/`.

**B — Sensibilidade a jitter.** Repita A forçando fases: (i) tripé rígido sem tremor; (ii) tremor
natural da mão; (iii) deslocamento deliberado da base em passos de 0,33 px (com um calço/parafuso
micrométrico) — o caso (iii) é o "pixel shift feito em casa" e funciona melhor que depender da sorte,
porque garante cobertura de fases. Se (iii) for viável mecanicamente na sua montagem, ele passa a ser
a recomendação de produto, não a rajada aleatória.

**C — Custo do erro de registro.** Reproduza o experimento do Python (`burst_sr_probe.py`, que roda
com offsets "oracle" e offsets estimados) sobre as fotos reais: se o ganho some quando o registro é
estimado, o problema a resolver é registro, não fusão, e a decisão correta é **não** habilitar SR.

Para o CI: um teste de software puro (`BurstDiagnosticsTest`, `BurstFuseTest`, `BurstSrTest`) com
cenas sintéticas (discos de diâmetro conhecido sobre fundo texturizado + ruído + offsets controlados),
sem precisar de câmera nem GPU. Os testes do projeto já rodam no runner Windows; mantenha o
padrão (Java, JUnit, sem dependência de hardware).

---

## 6. Orçamento de desempenho (molesto antes que o usuário sofra)

- Padrão de N: 8 (mínimo útil) / teto 24 (acima disso o ganho é ~√N no ruído e nada na resolução;
  1000 frames não cabem no orçamento de 3 min por bloco nem na memória do celular).
- Memória: 24 frames de 12 MP em 8 bits ≈ 830 MB. **Não** mantenha todos em `Mat`. Trabalhe em
  blocos/ROI com o plano de `TilePlan` (SR já usa 320), acumulando `num/den` incrementalmente e
  liberando cada frame após o warp (padrão `try/finally` + `release()` já usado em `PhotoSession`).
- Fluxo recomendado: (1) ROI ativa; (2) decodificação por região como `OriginalRoi` faz, respeitando
  as 8 orientações EXIF; (3) registro em pirâmide (metade, depois 1:1); (4) acumulação; (5) refinamento
  só nos blocos escolhidos pela heurística de cobertura (> 2 pontos de vantagem, como hoje).
- Meça e publique no `docs/`: tempo por frame no Android (modelo de referência) e no Windows, e o
  número de frames em que o ganho mensurável satura. Não publique ganho sem foto de verificação em
  `verification/burst-<versão>/`.

---

## 7. O que NÃO fazer

1. Não prometa "resolução muito superior" a partir de 10/20/1000 fotos: prometa **mediana com
   ganho de SNR** e **2× condicionado a diagnóstico**.
2. Não use pixels reconstruídos para medir trincas, e não mude a política atual de que elipse final é
   reajustada no original — a menos que o ensaio A prove o contrário com medição física.
3. Não encadeie FSRCNN depois de drizzle para "chegar a 4×": dois interpoladores não são dois
   sensores.
4. Não faça rajada sem travar exposição, foco e balanço de branco; e não aceite frame com cena em
   movimento na fusão (deghosting descarta, não "suaviza").
5. Não confunda o caso da correia em movimento com o caso da câmera tremida: se as pelotas andam,
   o que você tem é vídeo com objeto móvel, e a fusão ingênua **engorda** a pelota (elongamento no
   sentido do movimento). Nesse caso use Trilha 4 ou nada.
6. Não reduza a imagem a 3000 px e depois façam SR: primeiro pare de jogar resolução fora
   (`Mats.MAX_ANALYSIS_SIDE`/`sampleFor`), senão a SR só conserta o recorte que o app causou.
7. Não ative nada por padrão; cada opção nova entra desmarcada, com o laudo visível, como FSRCNN hoje.
8. Não escreva "validado" sem ensaio físico. Estados possíveis e obrigatórios no texto final:
   implementado → testado em software → demonstrado em imagem de verificação → validado com
   amostra física.

---

## 8. Sequência de execução proposta (PRs pequenos, um item por commit)

1. `BurstDiagnostics` + testes sintéticos + botão "Analisar rajada" no Windows (sem fusão).
2. Ensaios A e B com 3 montagens de câmera diferentes (tripé, mão apoiada, suporte na correia) e
   relatório em `verification/burst-0.17.0/diagnostico.md`.
3. `BurstAlign` + `BurstFuse` (mediana ponderada) no `core/`, com JSON e rastreabilidade; ensaio A.
4. `BurstSr` (drizzle + IBP) atrás do veredito, com as 4 garantias numéricas; ensaio A e C.
5. Portar para Android com CameraX/Camera2 travando AE/AWB/foco; medir no aparelho real.
6. Documentação `docs/rajada-burst.md` + nota no README ("Novidades da 0.17.0") dizendo o que foi
   medido, em qual câmera, e o que **não** foi validado.

---

## 9. Referências que o implementador deve ler antes de codificar

- Wronski et al., *Handheld Multi-Frame Super-Resolution*, SIGGRAPH 2019 —
  https://arxiv.org/abs/1905.03277 (método do Super-Res Zoom do Pixel 3; teto de 2×; ~100 ms/frame 12 MP).
- Lafenetre, Facciolo, Eboli, *Implementing Handheld Burst Super-resolution*, IPOL 2023 —
  https://www.ipol.im/pub/art/2023/460/ (detalhe de implementação; código; "até 2× é o limite dado pela
  integração do pixel").
- Código aberto correspondente (MIT, exige RAW): https://github.com/Jamy-L/Handheld-Multi-Frame-Super-Resolution
- Ben-Ezra, Zomet, Nayar, *Video Super-Resolution Using Controlled Subpixel Detector Shifts*,
  IEEE TPAMI 2005 — https://cave.cs.columbia.edu/Statics/publications/pdfs/BenEzra_PAMI05.pdf
  (o blur de 3,5 px já inviabiliza SR 2×; deslocamento controlado do detector é o que funciona).
- Baker & Kanade, *Limits on Super-Resolution and How to Break Them*, CVPR 2002 (por que mais frames
  não bastam).
- Litterio et al., *Super-Resolution with Structured Motion*, ICCP 2025 — https://arxiv.org/abs/2505.15961
  (fator 8 real, mas com offsets mecanicamente controlados e alvo imóvel).
- Guizar-Sicairos et al., *Efficient Subpixel Image Registration Algorithms*, Opt. Lett. 2008,
  e a implementação de referência em `skimage.registration.phase_cross_correlation`
  (`upsample_factor=100`).
- Jim Kasson, *Does pixel-shift increase resolution?* — https://blog.kasson.com/the-last-word/does-pixel-shift-increase-resolution/
- Empilhamento HDR+-style (RAW, GPL-3.0): https://github.com/kunzmi/ImageStackAlignator
- Ferramenta sem IA para JPEG/PNG com medição honesta: https://github.com/skittleson/burst-superres
- Drizzle (reconstrução por footprint com fases sub-pixel): https://github.com/spacetelescope/drizzle
- Deep burst SR (RAW Bayer, PyTorch — fora do escopo atual): https://github.com/goutamgmb/deep-burst-sr
- Relatório completo deste estudo (com o experimento em fotos reais do repo):
  `RELATORIO-super-resolucao-multiframe.md` e os dados em `burst_sr_results.json` / `burst_sr_sonda.json`.
