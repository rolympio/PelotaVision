# Treinamento reproduzível — experimental

Python 3.12.14 foi usado. Crie um ambiente virtual, instale requirements.txt e execute `python train.py` nesta pasta. Depois, `python install_model.py` copia os pesos e metadados para os assets Android. Recompile e repita as verificações. Não há chamada de servidor na inferência.

`data/annotations.json` é a fonte dos círculos e rótulos PROVISÓRIOS anotados pelo assistente. As imagens `*_labels.jpg` permitem revisar cada objeto: vermelho=trinca candidata, verde=sem indicação visível. Não são rótulos homologados. Ajuste label, x, y, radius e partial antes de treinar novamente. Os valores estão nos pixels das imagens PNG aqui incluídas, e cada group identifica uma cena. O manifesto conserva os nomes de origem e recorte do painel sintético. Não recrie grupos para cópias ou versões marcadas da mesma cena.

O conjunto possui sete cenas limpas: 225 círculos aproximados, 209 pelotas inteiras usadas no classificador, 37 positivas e 172 negativas. Há uma cena explicitamente identificada como sintética e outras de origem não comprovada. Não há teste independente em fotos reais da usina.

Seis variantes por pelota são usadas SOMENTE no treino de cada fold: original, escurecimento gamma, clareamento gamma, redução de contraste, gradiente de iluminação e desfoque leve. São 1254 amostras derivadas, não 1254 pelotas independentes. A validação deixa uma cena inteira fora por vez (LeaveOneGroupOut), testa a imagem original dessa cena e mantém todas as suas variantes fora do treino. O ajuste final usa todas as sete cenas depois da validação.

RandomForest: 160 árvores, profundidade máxima 7, mínimo 2 amostras por folha, sqrt atributos, pesos de classe balanceados e semente 20261018. Os 72 atributos morfológicos são calculados sobre um recorte normalizado de 96x96 pixels, no interior de 78% do raio. Os pontos vermelhos não entram nos pixels de treinamento. Três imagens anotadas sem par limpo ficaram fora do treino; pares limpos/marcados e duplicatas não viraram novas cenas.

`training/oof_predictions.csv` contém escores fora do treino por objeto. `validation.json` registra as métricas para limiar 0,5. O app usa, em sensibilidade 50, >=0,6 para POSSIBLE, <=0,4 para NO_INDICATION e intervalo intermediário para INCONCLUSIVE. Gates de qualidade, borda, raio e revisão podem acrescentar inconclusivas. O escore não é probabilidade calibrada. A localização da linha é uma heurística separada, não uma segmentação treinada.

O arquivo PVF1 é big-endian: magic int32 0x50564631, versão int32 1, atributos int32 72, número de árvores int32. Cada árvore: número de nós int32; cada nó: atributo, filho esquerdo, filho direito (int32), limiar float64, escore da folha float64. Folhas usam atributo -2 e filhos -1. A entrada é float32, como no sklearn. O Java valida o cabeçalho e os índices; scores de referência permitem testar paridade. Não use o escore em decisões de qualidade sem validação externa.

Para repetir a comparação nativa, obtenha OpenPnP OpenCV 4.9.0-0 (somente desktop), compile as classes Java de visão e os checks em verification/ml-0.5.0. DatasetCheck espera model.pvrf, pasta de imagens *_light0.png a *_light5.png e pasta de saída. As variantes são produzidas por features.lighting. O benchmark usa círculos provisórios e mede desenvolvimento, não ensaio metrológico. Os XML de teste e resultados detalhados estão em verification/ml-0.5.0.

## Modelo candidato com trincas sintéticas procedurais (24/10/2026)

**O modelo de produção não mudou.** O app continua usando `training/crack_forest.pvrf` (`random_forest_20261018_v1`). O candidato está em `training_synth/crack_forest_candidate.pvrf` e só substitui o atual se passar na validação da bandeja real (`crack_validation/README.md`), com critério fixado antes.

- `crack_synthesis/`: gerador procedural de trincas sobre 131 pelotas reais sem trinca, recortadas de 3 fotos de bancada. Inclui máscara exata, negativos difíceis, pares limpos e folhas de contato. Ver o README da pasta, incluindo o que ainda não convence.
- `train_synth.py`: mesmos 72 atributos e mesmo PVF1. Treina com 3632 amostras sintéticas + as 7 cenas antigas (6 variantes de iluminação). Os hiperparâmetros foram escolhidos só pela validação sintética por foto-fonte (`max_depth=12`, `min_samples_leaf=3`, 160 árvores).
- `cv4compat.py`: **correção de reprodutibilidade.** Com OpenCV 5 em Python, `cv2.warpAffine` deixou de quantizar as coordenadas em 1/32 px. Por isso os atributos divergiam em até 0,6 dos do app, que usa OpenCV 4.12. `features.py` passa a usar uma emulação em numpy do 4.x, com erro 0 nos 209 recortes de referência. Com OpenCV 4.x instalado, a chamada original é mantida.

Resultados (`training_synth/validation.json`; política do app = sensibilidade 50):

| Conjunto | Modelo | AUC | Sens. @0,5 | Espec. @0,5 | Sens. @espec. 90% |
|---|---|---|---|---|---|
| (a) sintéticos fora da foto-fonte (3632; 1423 pos.) | atual | 0,615 | 0,477 | 0,683 | 0,200 |
| | candidato | 0,698 | 0,629 | 0,641 | 0,353 |
| (b) 7 cenas antigas, deixa uma de fora (209; 37 pos.) — rótulos provisórios | atual (OOF original) | 0,912 | 0,676 | 0,983 | 0,757 |
| | candidato | 0,941 | 0,811 | 0,983 | 0,838 |
| (c) `crack_example.png`, foto real externa (82; 7 pos.) — rótulos provisórios | atual | 0,491 | 0,857 | 0,253 | 0,143 |
| | candidato | 0,798 | 0,857 | 0,240 | 0,429 |

- Em (b), o IC 95% da diferença de AUC é [−0,008; +0,071]. A diferença não é significativa, e 6 das 7 cenas parecem geradas.
- Em (c), o IC 95% é [+0,05; +0,54], mas com só 7 positivas e rótulos do assistente. Nos dois modelos, cerca de metade das íntegras empilhadas de (c) cai em "possível trinca" ou "inconclusiva": esse domínio (pelotas sobrepostas com sombras) continua difícil.
- Em (a), o candidato foi avaliado fora do treino. O atual nunca viu sintéticos.
- Paridade: a floresta PVF1 × sklearn tem erro ≤ 2e-16. O `CrackModel.java` executado localmente com os 291 vetores de referência tem erro 0 para o atual e para o candidato. A extração Java (OpenCV 4.12 nativo) é testada no CI Windows por `CrackCandidateParityTest`.
