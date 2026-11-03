# Experimento Studio: instâncias, confiança e multitarefa

## Estado e isolamento

Pipeline **offline e executável**, iniciado em 03/11/2026 em `tools/studio_ml/`.
Não altera UI, Java, assets, builds, empacotamento, workflows ou modelos distribuídos.
Não instala bibliotecas automaticamente durante o processamento/treino. O utilitário
separado `bootstrap_env.py` é uma etapa de instalação explicitamente autorizada,
restrita a um venv privado. Não publica imagens, pesos ou releases.

Saídas obrigatoriamente em `../PelotaVision-local/experiments/studio-instances-20261103/`.
Cada execução cria uma pasta UTC + identificador aleatório, sem sobrescrever.
Imagens originais são somente lidas e seus hashes são conferidos antes/depois.
Manifestos, caminhos de fotos, hashes de dados, resultados e logs permanecem privados.

**Não existe validação científica neste experimento.** Houve ensaio em quatro
originais reais e testes sintéticos. A primeira tentativa de treino foi bloqueada
por ausência de PyTorch no interpretador com OpenCV. A rodada posterior autorizada
de instalação/treino é registrada separadamente ao final deste documento.

## Referências efetivamente consultadas

- `ml_training/stardist/README.md` e `common.py`: já existe StarDist experimental,
  rasterização de polígonos, probabilidade de interior e protocolo de paridade
  Python/Java. Este experimento não modifica esse contrato nem o substitui.
- `tools/cracks/build_manifest.py` e `requirements.txt`: aproveita a regra conceitual
  de não converter ausência de anotação de trinca em `intact` e de separar grupos.
- Referência local MicroscopiaAI: `studio/servidor.py`,
  `engine/python/ai_segmentation.py`, documentação/estrutura de
  `engine/v0.4/python/segmentacao.py` e `pipeline.py`.
  Inspiração: máscara explícita, classe desconhecida, confiança/revisão e
  rastreabilidade. A referência trabalha sobretudo com fases e máscaras de seção;
  seus rótulos/modelos não são transferíveis para pelotas. Não houve cópia de código.

## Contrato dos artefatos por imagem

| Arquivo | Significado |
|---|---|
| `instances_uint16.png` | IDs de instância watershed; zero é ausência de proposta |
| `instances_clahe_uint16.png` | Segunda variante, com CLAHE antes do limiar |
| `semantic_uint8.png` | 0 fundo provisório; 1 pelota indeterminada; 2 esfera candidata **2D**; 3 objeto desconhecido/fora da ROI |
| `boundary_uint8.png` | Bordas das identidades, inclusive contatos |
| `uncertainty_uint8.png` | 255 = revisar/sem supervisão; 0 = consenso heurístico, não certeza calibrada |
| `consensus_instances_uint16.png` | Interseções de propostas aceitas; não constitui verdade-terreno |
| `pseudo_weight_uint8.png` | Peso binário de supervisão exploratória; zero ignora pixel |
| `possible_cracks_uint8.png` | Sinal óptico black-hat separado; possível trinca não confirmada |
| `roi_uint8.png` | Domínio de processamento, incluindo ROI manual quando informada |
| `overlay_review.jpg` | Visualização de revisão, nunca fonte de rótulos |
| `training_dev.npz` | Cinza, instâncias, alvos interior/borda/separação e pesos |
| `record.json` | Hashes do original, escala, contornos, parâmetros, grupos e métricas |

As máscaras usam a resolução processada (lado máximo padrão 1024), não
necessariamente a resolução original. `record.json` registra ambas as dimensões e
a transformação XY. Para reamostrar IDs usar exclusivamente vizinho mais próximo.
Não interpretar IDs como ordem física persistente entre fotos.

`summary.json`, `environment.json` e `artifact_hashes.json` ficam na pasta da
execução. Os contornos são registrados por instância em coordenadas da máscara.
`structural_integrity` permanece sempre `nao_avaliada`.

## Métodos e limites

1. Cinza, suavização, Otsu em ROI, abertura/fechamento, distância euclidiana,
   máximos locais e watershed OpenCV. **Não depende de StarDist.**
2. Variante CLAHE pelo mesmo procedimento. Matching 1:1 guloso por IoU
   decrescente, com desempate por ID; limiar padrão 0,5.
3. IoU/Dice de pares aceitos, IoU/Dice de primeiro plano, F1 das bordas com
   tolerância de dois pixels e distância simétrica média entre contornos globais.
   Bordas globais incluem instâncias não pareadas. Médias dos pares omitem
   instâncias sem par; consultar sempre também as contagens não pareadas.
   Sem objetos/pares, métricas indefinidas são `null`, não números inventados.
4. Consenso exige IoU >= 0,7, circularidade/aspecto 2D e objeto não parcial.
   Fundo longe das propostas recebe peso apenas se ambas as máscaras de primeiro
   plano também concordarem. Primeiro plano descartado não vira fundo confiável.
5. Circularidade **não demonstra material nem esfericidade 3D**. As duas variantes
   compartilham fortes erros: concordância não é independência nem acurácia.
   Fundo por limiar pode conter pelotas escuras omitidas. A inspeção real mostrou
   omissões em sombras e propostas de fundo/recipiente quando não há ROI.
6. Black-hat pode responder a textura, sombra, risco e contato. Nem sinal positivo
   confirma trinca, nem sinal negativo confirma integridade estrutural.

Sem ROI manual o domínio é a imagem inteira; isso não remove automaticamente a
panela ou objetos estranhos. A ROI elíptica é hipótese manual normalizada e deve
ser revisada em cada cena. Não foi ajustada para maximizar métrica contra teste.
Os parâmetros são pixels da grade processada e não medidas físicas calibradas.

## Grupos, splits e treino opcional

Nome de pasta registra apenas `acquisition_candidate`. A identidade de
aquisição/amostra independente é **não confirmada**. Todas as imagens ficam em
`dev`; não há divisão automática por foto/pelota nem alegação de independência.
Duplicatas exatas são detectadas por SHA-256 dentro da seleção; fotos diferentes
da mesma amostra continuam potencialmente dependentes.

`train_multitask.py` implementa uma U-Net pequena original, sem pesos externos,
com três cabeças sigmoid: interior, borda e separação entre IDs. Treina BCE
ponderada apenas nos pixels de instâncias em consenso, ignora padding e registra semente,
entradas/versões/hashes, perdas por cabeça, tempo e `state_dict`. Só aceita
manifestos explicitamente `dev`. Não seleciona checkpoint por teste; salva apenas
o último ajuste. Inferência `--infer-dev` é ajuste no próprio desenvolvimento,
**nunca validação**. `--demo-run` exclui as imagens demonstrativas da otimização
por hash, sem presumir independência da amostra. O programa recarrega o checkpoint
com `weights_only=True` antes dessa inferência.

**Fundo por limiar não revisado recebe peso zero no treino**, mesmo quando o
pipeline antigo havia atribuído peso positivo. Assim, um pixel incerto/desconhecido
não é reinterpretado como exemplo negativo. Negativos de interior vêm apenas das
bordas das próprias instâncias aceitas; negativos de borda/separação vêm apenas dos
pixels aceitos dentro das instâncias. Isso restringe fortemente o aprendizado de
fundo: a rede pode prever interior fora dos objetos. É uma limitação explícita,
não motivo para inventar negativos. Cabeças sem positivos confiáveis são inativadas.

O carregador verifica hash, `dev`, identidade da origem, tipos `uint8`/`uint16`,
grade HxW, três alvos binários finitos e consistência peso/incerteza. Normalização
é cinza `float32/255`, fixa e igual no treino/inferência; não muda conforme a foto.
Inferência usa blocos com contexto/reflexão, mantendo a grade original do NPZ.
`model_contract.json` registra canais, normalização, logits, sigmoid e pós-processamento.
Esse contrato é experimental e não autoriza integração no executável.

Para evolução científica: revisar contornos por instância, definir o que é pelota
e objeto estranho, confirmar amostra/campanha/câmera e repetições, reservar grupos
inteiros antes de ajustar modelos/limiares e só então produzir treino/validação/
teste. Não reutilizar os atuais `dev` como teste cego. Anotações revisadas e
avaliação independente são necessárias antes de qualquer uso metrológico.

## Ambiente e licenças

Foi usado o ambiente existente `%USERPROFILE%\.conda\envs\opencv_cuda`:
Python 3.11.5, NumPy 1.26.0 e `opencv-python` 4.8.1.78 (API OpenCV 4.8.1).
Apesar do nome do ambiente, o build consultado não oferece CUDA. A execução é CPU.
Os Python 3.12/3.15 padrões e os ambientes MicroscopiaAI consultados não fornecem
a combinação OpenCV/PyTorch. Nenhuma dependência foi instalada.

- NumPy: BSD-3-Clause, texto de licença consultado nos metadados instalados.
- OpenCV / distribuição `opencv-python` usada: Apache-2.0 nos metadados; componentes
  transitivos têm avisos próprios. Nenhum algoritmo non-free habilitado no build.
- Python: PSF; o interpretador usado é empacotado por Anaconda, sem redistribuição.
- PyTorch opcional: licença publicada BSD-3-Clause; pacote não instalado neste
  ambiente. Antes de instalar, verificar licença da distribuição concreta, wheel
  Windows/Python e, se GPU, arquitetura Pascal `sm_61`/GTX1050 e runtime suportado.
  O driver disponível não comprova compatibilidade de runtime ou wheel. CPU basta
  para este baseline; não se deve instalar CUDA por suposição.
- Não foi adicionada dependência StarDist, SAM, Ultralytics/AGPL ou código externo.
  Fotos permanecem privadas, sem licença de redistribuição estabelecida.

OpenCV Python 4.8.1 **não** é o OpenCV 4.12 do executável. Não houve build
Windows/Android, teste de paridade, exportação ONNX nem integração com o app.

## Comandos reproduzíveis (PowerShell, raiz do repositório)

```powershell
$python = "$env:USERPROFILE\.conda\envs\opencv_cuda\python.exe"
$source = "$env:USERPROFILE\OneDrive\Documentos\PelotaVision"
$private = Join-Path (Split-Path (Get-Location) -Parent) 'PelotaVision-local\experiments\studio-instances-20261103'
$env:PYTHONDONTWRITEBYTECODE = '1'

& $python -m unittest tools.studio_ml.test_pipeline -v
& $python -m tools.studio_ml.pipeline --source $source --limit 3 --max-side 1024 --seed-radius 12

# Copiar a pasta PRIVATE_RUN impressa pelo comando anterior.
$run = Join-Path $private '<PRIVATE_RUN>'
& $python -m tools.studio_ml.train_multitask --run $run --steps 100 --patch 128 --device cpu
# Sem PyTorch: registra blocked.json, não cria pesos e retorna código 2.

# Matching também aceita máscaras de outros métodos, na mesma grade.
& $python -m tools.studio_ml.compare '<mapa_a_uint16.png>' '<mapa_b_uint16.png>' --iou 0.5 --tolerance 2
& $python -m tools.studio_ml.audit --run $run --run-tests

# Opcional: arquivo individual e ROI manual, após revisar a cena.
# & $python -m tools.studio_ml.pipeline --source '<original.png>' --limit 1 --roi-ellipse CX CY RX RY
```

## Evidências locais desta rodada

Na raiz privada indicada acima, as execuções finais consultáveis são:

- `watershed-20261103T182838Z-c1890335ea`: descoberta de candidatos e ensaio em
  três originais, sem ROI; contagens/métricas reais em `summary.json`.
- `watershed-20261103T182837Z-79d9908925`: quarto original, com ROI manual
  normalizada `(0.46, 0.50, 0.31, 0.49)`; não é comparação independente de amostras.
- `comparison-20261103T182901Z-757a72054c`: execução real do comparador.
- `multitask-20261103T182901Z-52e9ba7326`: tentativa real de treino, bloqueio
  registrado em `blocked.json`, sem pesos e sem instalação.
- `audit-20261103T182941Z-f38ec558e9`: hashes dos quatro originais e dos artefatos
  conferidos, com log dos **12 testes sintéticos aprovados**.

Execuções preliminares foram preservadas em outras pastas únicas. Não misturar
seus resultados/pesos de confiança com os das execuções finais. Métricas completas,
identidades de fotos e hashes de dados não são incluídos na documentação versionada.

## Rodada posterior de treino e verificação — 03/11/2026

Em ambiente privado Python 3.12 isolado, instalado a partir de wheels com TLS,
foram confirmados `torch 2.10.0+cpu`, `numpy 2.2.6`, OpenCV 4.12.0 e `onnx 1.18.0`;
`pip check` passou. A instalação inicial excedeu o tempo limite; a verificação posterior
conferiu binários selecionados contra RECORD e importações, não todos os arquivos.

Execução `multitask-20261103T184116Z-71782b5b42`:

- 90 passos em CPU, três imagens reais de desenvolvimento com pseudo-rótulos de
  consenso dependente e 12,516 s de ajuste. Sem supervisão de fundo não revisado.
- Três saídas: interior, borda e separação. Pesos PyTorch, logs, contrato e ONNX
  foram efetivamente gravados fora do Git. O script de treino corresponde ao SHA-256
  `a698faaf2b2ea376c7b78f91a6fd0adadae1af83ffe80482c67cc72c99b797de`.
- ONNX SHA-256 `eaf79162c6693b3e502613254c47a0ea016faae8212604a36625faf0f9488c31`;
  comparação em um bloco 128×128 com OpenCV 4.12 DNN teve diferença máxima de
  logits `1,1921e-6` em relação ao PyTorch. É paridade de uma entrada, não benchmark.
- Uma quarta imagem foi excluída do ajuste apenas para demonstração; a amostra física
  pode estar relacionada. Produziu oito propostas, fração de primeiro plano 54,5%,
  sem pares IoU aceitos com as propostas clássicas. **Não é teste independente nem
  evidência de qualidade.** Sem fundo revisado a predição é inadequada para produção.
- Os 16 testes sintéticos, incluindo duas verificações PyTorch CPU, passaram no venv
  isolado. Não houve treino GPU, seleção comparativa, validação ou integração ao app.

O arquivo `installation.json` registra timeout inicial e não deve ser interpretado
isoladamente como instalação integral validada; relatórios posteriores ficam em
`environment-verification-20261103T184622Z-7a77fd7e17`. Sem autorização e sem
avaliação independente, não distribuir esses pesos como modelo aprovado.
