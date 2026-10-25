# Trincas: aquisição de dados, treinamento e comparação (25/10/2026)

Issue #5 · branch `feature/external-pellet-datasets` · PR #6. Experimental: **nenhum modelo do app foi substituído**.

## Conclusão

1. **Nenhuma imagem externa foi aproveitada.** As quatro fontes foram auditadas e todas ficaram bloqueadas: sem licença, sem acesso (credencial e rede) ou fora do domínio. A única fonte com rótulos reais de trinca, o artigo ISIJ 2022, não publica os dados.
2. **O modelo embarcado (0.5.0) não discrimina trincas na única foto real disponível.** Em `crack_example.png`, com pelotas queimadas e rótulos provisórios, a AUC foi 0,49 (IC95 0,30–0,71). Com limiar 0,5, ele marcou 56 de 75 íntegras como trincadas. Na política do app (0,4/0,6), foram 48 íntegras como "possível trinca". Os bons números da 0.5.0 (AUC 0,96 no dev) vêm de cenas com aparência de imagem gerada e não se transferem.
3. **O candidato `rf_original_plus_synthetic` é o único sinal positivo.** Ele usa os mesmos 72 atributos e o formato PVF1, mais trincas sintéticas sobre doadoras reais. No holdout, a AUC foi 0,76 (IC95 0,52–0,92), com ΔAUC +0,02 a +0,49 sobre o embarcado. Isso **não é evidência suficiente**: há uma única foto, 7 trincas e rótulos provisórios do assistente. O bootstrap reamostra pelotas, não fotos, e o critério pré-registrado da bandeja exige ≥15 trincadas e ≥30 íntegras. Além disso, com limiar 0,5 o candidato gera ainda mais falsos positivos (66/75).
4. **Decisão: MANTER o classificador atual no Android e no Windows.** A integração está bloqueada até a validação em fotos reais independentes, conforme o protocolo `ml_training/crack_validation` da branch `feature/trincas-sintese`. O candidato e a paridade Java já estão prontos para esse teste.

## Fontes externas

| Fonte | Conteúdo verificado | Licença | Status |
|---|---|---|---|
| aryadeo/Pellet_Size_Distribution (git d224b55) | 150 imagens (119+31) 656×480 em cinza, pelotas **verdes** na descarga do disco, máscara binária pelota/fundo, **sem trincas** | **Nenhuma** (sem LICENSE) | Bloqueada: pedir permissão ao autor |
| Roboflow hamitouche/iron-pellets | Página sem descrição, contagens ou classes visíveis | "Public Domain" declarado | Bloqueada: exige conta/API key, e o domínio está bloqueado no ambiente |
| ISIJ Int. 62(8) 2022, Zhou et al. | 640 imagens 512×512 (320 trincadas/320 não), pelotas **verdes** | Artigo CC BY-NC-ND 4.0; dados não publicados | Bloqueada: solicitar aos autores |
| Akki-Maharaj/Manufacturing-Defect-Detection | Código MIT; dados de telhas magnéticas (abin24, 1344 imagens), **não são pelotas** | Dados sem LICENSE ("welcomed to use… cite") | Excluída por regra do projeto |

Detalhes, hashes e ações estão em `docs/datasets/fontes.json`. Imagens baixadas e aproveitadas para treino: **0**. Os dois repositórios GitHub foram clonados somente para auditoria e não foram versionados.

## Dados efetivamente usados (internos)

| Conjunto | Papel | Origem | Material | Trincadas | Íntegras | Incertas |
|---|---|---|---|---|---|---|
| 6 cenas 0.5.0 (brown40, cluster, dense, gray20, gray42, spaced) | dev (validação agrupada) | não verificada (aparência gerada) | desconhecido | 29 | 140 | 16 (parciais) |
| paired40 | só treino | sintética declarada | desconhecido | 8 | 32 | 0 |
| crack_example.png | **teste final** | foto real, procedência não registrada | queimada (visual) | 7 | 75 | 40 (parciais + 7 duvidosas) |
| Sintéticos procedurais (fotos 1000411645/46) | só treino | sintética | queimada (visual) | 462 | 708 | — |

Regras aplicadas: uma pelota por recorte. Pelota parcial ou sem anotação recebe `uncertain`, nunca `intact` implícito. No holdout, `intact` só veio da inspeção exaustiva registrada antes dos escores. A foto doadora **1000411647.jpg** foi excluída por ter marca d'água "Jindal Steel & Power" (imagem de terceiro). Não há nenhuma pelota **verde** rotulada: a avaliação estratificada verde × queimada está bloqueada por falta de dados.

## Reprodução do baseline

- Atributos: `features.py` com `cv4compat.py` (OpenCV 5.0 emulando o warpAffine 4.x) reproduz os 209 vetores de referência com **diferença 0**. Os recortes por pelota também dão diferença 0, inclusive nas 6 variantes de iluminação.
- O asset embarcado tem SHA-256 `736bf0bb…` (igual a metadata.json) e reproduz `score_final` com erro de 6e-17 (Python) e 0 no `CrackModel.java` compilado.
- Retreino com scikit-learn 1.9.1 (o 1.8.0 original não pôde ser instalado, PyPI bloqueado): a floresta não é idêntica (SHA `45ef6da9…`). Nos 7 grupos originais, foi LOSO 25/37 trincas, 2 FP e AUC 0,883, contra 25/37, 3 FP e AUC 0,912 no original.

## Comparação (mesmos grupos, hiperparâmetros fixos a priori, limiar 0,5)

Dev = LeaveOneGroupOut nas 6 cenas (169 pelotas, 29 trincadas). Holdout = crack_example (82 pelotas, 7 trincadas). Matriz `[[VN, FP], [FN, VP]]`.

| Modelo | Dev P / R / F1 | Dev FN | Dev AUC | Holdout matriz | Holdout P / R / F1 | Holdout AUC (IC95) | App 0,4/0,6 no holdout: possível (trinc./ínteg.) |
|---|---|---|---|---|---|---|---|
| embedded_0.5.0 | 0,89 / 0,86 / 0,88 | 4 | 0,962 | [[19,56],[1,6]] | 0,10 / 0,86 / 0,17 | 0,49 (0,30–0,71) | 4 / 48 |
| rf_original (retreino) | 0,93 / 0,86 / 0,89 | 4 | 0,961 | [[16,59],[1,6]] | 0,09 / 0,86 / 0,17 | 0,50 (0,31–0,70) | 5 / 49 |
| rf_original_plus_synthetic | 0,93 / 0,86 / 0,89 | 4 | 0,963 | [[9,66],[1,6]] | 0,08 / 0,86 / 0,15 | **0,76 (0,52–0,92)** | 6 / 46 |
| rf_hoglbp (kit) | 0,00 / 0,00 / 0,00 | 29 | 0,803 | [[75,0],[7,0]] | — / 0,00 / — | 0,56 (0,36–0,76) | 0 / 0 |
| rf_hoglbp_plus_synthetic | 0,67 / 0,07 / 0,13 | 27 | 0,844 | [[75,0],[7,0]] | — / 0,00 / — | 0,67 (0,47–0,84) | 0 / 0 |
| mlp_pixels_plus_synthetic | 0,25 / 0,48 / 0,33 | 15 | 0,680 | [[47,28],[4,3]] | 0,10 / 0,43 / 0,16 | 0,51 (0,29–0,73) | 2 / 21 |

Leitura:
- **Dev (cenas geradas) não diferencia os RFs de 72 atributos.** Todos ficam com AUC ≈ 0,96 e 4 FN. Esse conjunto não serve para escolher modelo.
- **HOG+LBP:** a ordenação é razoável no dev (AUC 0,80–0,84), mas os escores ficam quase sempre abaixo de 0,5, com recall 0. Também não roda no app (1776 atributos, sem port Java). Foi descartado.
- **Rede neural leve:** a CNN não pôde ser treinada (PyTorch/TensorFlow indisponíveis offline e só 29+7 trincas reais). A MLP de pixels ficou pior que os RFs em todos os critérios.
- **Escores não calibrados:** no holdout, o limiar fixo 0,5 gera muitos falsos positivos em todos os RFs de 72 atributos. A foto tem iluminação e textura muito diferentes das cenas de treino. Recalibrar limiar exige outra foto (não pode ser este holdout).

## Desempenho computacional (CPU deste ambiente, não o celular)

| Item | Valor |
|---|---|
| 72 atributos (Python, mediana por pelota) | 9,2 ms |
| HOG+LBP (Python) | 5,7 ms |
| Inferência CrackModel.java (com parse de CSV) | 0,19–0,28 ms/pelota |
| PVF1 embarcado / candidato | 287 kB / 505 kB (160 árvores, prof. 7) |
| Paridade Python × Java (400 vetores) | 0 (embarcado, rf_original, candidato) |
| Joblib HOG+LBP+sint. / MLP | 4,0 MB / 2,2 MB (só Python) |

O tempo "atributos+inferência" de ~120–175 ms/pelota em `metrics.json` é dominado pela sobrecarga de `predict_proba` do scikit-learn com `n_jobs=-1` numa amostra por vez. Não representa o app. Não houve medição em celular ou EXE.

## Arquivos

- `metrics.json`, `predictions.csv`, `bootstrap_holdout.json`: resultados completos por modelo, grupo e material, com a política do app e o desempenho.
- `manifest_labels.csv`, `manifest_summary.json`, `synthetic_summary.json`: manifesto (os recortes são regenerados pelos comandos abaixo).
- `rf_original_plus_synthetic_candidate.pvrf` (SHA-256 `86ac0744…`): candidato para o teste da bandeja. **Não instalado.**
- `PvrfParity.java`, `java_parity.jsonl`: paridade com o runtime do app.
- `pytest.txt`: 24 testes aprovados.

## Reprodução

```sh
# 1) recortes sintéticos (gerador da branch feature/trincas-sintese, sem misturar branches)
mkdir -p /tmp/trincas && git archive origin/feature/trincas-sintese ml_training | tar -x -C /tmp/trincas
python tools/cracks/render_synthetic.py --synth-dir /tmp/trincas/ml_training/crack_synthesis --out data/synthetic --per-donor 12 --extra-neg 6
# 2) recortes + manifesto
python tools/cracks/build_manifest.py --scenes ml_training/data/annotations.json \
  --holdout tools/cracks/labels/holdout_crack_example.json --synthetic-manifest data/synthetic/synthetic.csv --out data/pv_cracks
# 3) treino e comparação (~4 min em CPU)
python tools/train_cracks.py --manifest data/pv_cracks/labels.csv --output artifacts/crack_benchmark --allow-synthetic-training
python tools/cracks/bootstrap_auc.py --predictions artifacts/crack_benchmark/predictions.csv
# 4) testes e paridade Java
python -m pytest tools/tests -q
javac -d build/pv core/src/main/java/br/com/pelotavision/CrackModel.java verification/cracks-benchmark-20261025/PvrfParity.java
```

Versões: Python 3.13.17, numpy 2.5.3, OpenCV 5.0.0, scikit-learn 1.9.1, scikit-image 0.26.0, OpenJDK 21. Sementes: 20261018 (modelos) e 20261024 (sintéticos).

## Bloqueios explícitos

| Etapa | Bloqueio | O que destrava |
|---|---|---|
| Download Roboflow | Credencial (API key) + domínio bloqueado | Exportar COCO com conta própria → `convert_annotations.py coco` |
| Pellet_Size_Distribution | Sem licença | Autorização escrita do autor |
| ISIJ 2022 | Dados não publicados | Pedido aos autores (Hunan University) |
| Avaliação verde × queimada | 0 pelotas verdes rotuladas | Fotos de pelotas verdes com trincas rotuladas |
| Teste em fotos reais independentes | Só 1 foto, 7 trincas, rótulos provisórios | Bandeja: ≥15 trincadas + ≥30 íntegras, separadas fisicamente |
| CNN leve | Sem PyTorch/TF offline; dados insuficientes | Ambiente com framework + centenas de trincas reais |
| Build APK/EXE e Gradle | Android SDK e Maven Central bloqueados aqui | CI do PR (android.yml / windows.yml) |
| Integração Android/Windows | Nenhuma melhoria comprovada | Aprovação na bandeja; aí `install_model.py` + `CrackModel.ID` + paridade |
