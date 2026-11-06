# SAM 2.1 Hiera Tiny no Studio de Máscaras

Integração experimental CPU em `tools/studio_ml/sam2_candidates.py`. As propostas
não são ground truth. Concordância com watershed/StarDist não mede acurácia.

## Origem e ambiente

- Código oficial: https://github.com/facebookresearch/sam2
- Revisão instalada: `2b90b9f5ceec907a1c18123530e92e794ad901a4`.
- Código e pesos: Apache-2.0, conforme LICENSE e seção License do README oficial.
- Configuração: `configs/sam2.1/sam2.1_hiera_t.yaml`.
- Checkpoint: https://dl.fbaipublicfiles.com/segment_anything_2/092824/sam2.1_hiera_tiny.pt
- Tamanho: 156.008.466 bytes.
- SHA-256: `7402e0d864fa82708a20fbd15bc84245c2f26dff0eb43a4b5b93452deb34be69`.
- Referência independente do download: metadados LFS de
  `https://huggingface.co/api/models/facebook/sam2.1-hiera-tiny/tree/main`.

O checkpoint é conferido antes de carregar. Instalação autorizada no ambiente
isolado `../PelotaVision-local/envs/studio-ml`, com `SAM2_BUILD_CUDA=0`.
O pacote não instala nem baixa pesos durante a inferência. Os registros de
instalação, licença e proveniência ficam em `../PelotaVision-local/experiments/`.

Ambiente utilizado: Python 3.12.10, SAM-2 1.0 (a versão do pacote não é o nome
do checkpoint 2.1), PyTorch 2.10.0+cpu, TorchVision 0.25.0, NumPy 2.2.6,
OpenCV headless 4.12.0.88, psutil 7.2.2, hydra-core 1.3.8 e iopath 0.1.10.
O relatório pip privado registra URLs e hashes das distribuições instaladas;
`environment.json` registra a lista completa de versões por execução.

## Execução

```powershell
& '..\PelotaVision-local\envs\studio-ml\Scripts\python.exe' -m tools.studio_ml.sam2_candidates `
  --run <run-watershed> --stardist-run <run-stardist> `
  --checkpoint <sam2.1_hiera_tiny.pt> --source-map <mapa-privado.json> `
  --limit 8 --spacing 64 --min-area-px 60 --max-area-px 1000000 --threads 4
```

O mapa opcional associa SHA-256 ao caminho atual do original, para datasets
relocados. O hash precisa corresponder ao registro histórico. Não altera o run
anterior. Máscaras de entrada são verificadas contra os manifestos históricos.

Cada imagem produz duas pastas distintas: `grid` e `stardist_centers`. Cada prompt
positivo gera até três máscaras; a filtragem ordena pelo score SAM, rejeita área
fora dos limites, excesso fora da ROI e qualquer conflito com máscara já aceita.
O score SAM não é probabilidade calibrada de pelota. Os centros StarDist tornam
essa variante dependente do StarDist e podem perpetuar suas omissões.

Sem escala rastreável, o filtro é exclusivamente em pixels². Os limites padrão
são exploratórios; a área máxima ampla pode admitir recipiente/fundo como objeto.
Não há conversão para mm. Uso físico exige `--px-per-mm` na **grade processada** e
`--calibration-record`: documento com referência medida, método, incerteza,
resolução e imagens às quais se aplica. A existência do documento não certifica
sua validade científica; cabe ao operador verificar a rastreabilidade.

## Revisão e auditoria

```powershell
python -m tools.studio_ml.review <run-sam>/<image-id>/grid --revisor <identificador-local>
python -m tools.studio_ml.review <run-sam>/<image-id>/stardist_centers --revisor <identificador-local>
```

Cada pasta tem `record.json`, máscara uint16, `instances.json`, overlay e
`training_dev.npz` contendo **somente gray**, por compatibilidade com o editor.
Nenhum alvo/peso de treino é criado. Pixels sem instância são desconhecidos.
`comparison.jpg` mostra watershed, StarDist, SAM-grade e SAM-centros, nessa ordem.

`summary.json` registra contagens, rejeições por motivo, comparações, tempos,
parâmetros e hashes dos originais antes/depois. O encoder é compartilhado entre
variantes e seu tempo é separado; o tempo de cada variante inclui predição,
filtragem e exportação, mas não a comparação entre métodos. O tempo da imagem
inclui essas comparações. O carregamento do modelo é separado.

Memória: RSS do processo amostrado após cada prompt (não um pico contínuo), RSS
final e pico cumulativo Windows `peak_wset`. Não representa uso exclusivo do
modelo nem pico isolado por imagem. Tempo histórico StarDist é reportado como
histórico; o run watershed anterior não mediu tempo. Não presumir equivalência
entre esses escopos. CUDA opcional não foi compilado; inferência é CPU.

## Testes

```powershell
python -m unittest tools.studio_ml.test_sam2_candidates tools.studio_ml.test_pipeline tools.studio_ml.test_review tools.studio_ml.test_stardist_baseline -v
```

Os quatro testes fornecidos foram preservados. Foram adicionadas verificações
de escala sem registro, limites em pixels, score inválido, grade incompatível,
escape de diretório e adulteração dos artefatos históricos.

Para conferir todos os artefatos, os originais e as duas variantes pelo carregador
do editor, além de medir novamente o watershed raw e exigir igualdade pixel a
pixel com o histórico:

```powershell
python -m tools.studio_ml.sam2_report --run <run-sam> --source-map <mapa-privado.json>
```

O relatório Markdown e a auditoria JSON são gravados em novo run privado.
Memória histórica e motivos de rejeição não registrados pelos baselines são
marcados como indisponíveis, nunca como zero.

## Verificação realizada em 06/11/2026

Integração, instalação CPU, inferência real com ambos os promptings e piloto
de oito imagens concluídos. Relatórios, comparações visuais, contagens, tempos,
memória e hashes permanecem exclusivamente na árvore privada de experimentos.
O carregador do editor aceitou as 16 pastas do piloto, preservando o método de
prompting. O watershed raw foi reexecutado e reproduziu as máscaras históricas
pixel a pixel. Isso verifica reprodutibilidade, não qualidade científica.

Testes distintos executados: 46 aprovados e um ignorado (criação de link
simbólico indisponível no Windows), distribuídos entre SAM (11), editor (10),
pipeline (17), StarDist (2) e inventário (7). Também passaram 16 verificações
de segurança dos scripts. A rodada inicial após integração teve 37 testes;
após as alterações adicionais, foram executados os 28 casos de SAM, editor e
inventário, sem repetir os testes inalterados de pipeline/StarDist.

Ocorrências resolvidas: caminhos antigos dos originais foram remapeados por
SHA-256; a política PowerShell exigiu execução dos testes de segurança com
permissão apenas no processo; o serializador auxiliar do resumo de testes
falhou ao receber um objeto unittest e o resumo foi recuperado do log aprovado.
O primeiro run incompleto e os logs foram preservados para auditoria.

Permanecem necessários: revisão humana das propostas, calibração rastreável
para qualquer medida física e dados independentes para validação. O teste de
carregamento não substitui uma sessão de interação gráfica. Métricas históricas
de memória e de rejeição dos baselines continuam indisponíveis.
