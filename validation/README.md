# Validação externa — trincas e granulometria

**Status: protocolo e analisadores preparados; nenhum resultado industrial homologado.**
Não confundir testes automatizados de software com qualificação metrológica.

## 1. Comparação cega de Random Forest e MobileNetV3

1. Registrar antes do teste os identificadores de imagens, câmera, dia, lote, operador, iluminação, cenário (bancada/correia), versão de software, SHA-256 dos dois modelos e limiar fixo.
2. Reservar imagens **reais**, nunca utilizadas em treino, seleção de hiperparâmetros, ajuste de limiar, pseudo-rotulagem ou geração sintética. Isolar por origem/campanha/dia, e verificar duplicatas e quase-duplicatas. Uma imagem aumentada herda o grupo de sua origem.
3. Dois revisores humanos classificam independentemente cada pelota em trinca visível / sem trinca visível / inconclusiva; divergências passam por adjudicação. Guardar a imagem original e contorno/ID de cada pelota. Excluir inconclusivas das métricas binárias, mas divulgar quantidade e motivos.
4. **Comparação pareada**: executar RF e MobileNetV3 exatamente nos mesmos recortes originais, com mesmo detector/ROI e pré-processamento documentado. Congelar limiares antes de abrir o teste; escores não são probabilidades calibradas.
5. Exportar truth.csv (pellet_id,scene_id,label,split,source_id; split=test), predictions.csv (pellet_id,model,score) com nomes de modelo **RandomForest** e **MobileNetV3**.
6. Executar: `python validation/compare_classifiers.py --truth truth.csv --predictions predictions.csv --output validation_results/classifiers.json`.
7. Relatar TP, FP, FN, TN, precisão, recall, F1, especificidade e métricas por cena; adicionar intervalos de confiança via bootstrap **por cena/dia**, nunca tratar pelotas da mesma imagem como independentes. Documentar latência e memória em Android e Windows.
8. **Separar avaliação da localização**: pelotas reais não detectadas não entram na comparação pura do classificador; devem aparecer em uma avaliação ponta a ponta independente. A seleção de recortes pelo próprio algoritmo pode introduzir viés.
9. Registrar também erros em correia vazia, pelotas parcialmente visíveis e baixa resolução; comparar taxas de inconclusivas e não apenas F1.

## 2. Validação granulométrica

1. Definir mensurando: diâmetro projetado por elipse, Feret ou diâmetro equivalente, e **não** tratá-lo automaticamente como diâmetro de peneiramento.
2. Para referência individual, medir as mesmas pelotas com paquímetro ou outro método calibrado, documentando orientação, resolução, incerteza e repetição. Para referência de lote, usar peneiramento rastreável e registrar distribuição **em massa**, distinguindo-a do histograma **por número** do PelotaVision.
3. Fotografar referência dimensional coplanar às pelotas; guardar largura real da correia e a região de interesse. Repetir a calibração em diferentes posições do campo.
4. Registrar pareamento de pellet_id com a imagem; distinguir objetos parcialmente ocultos, agrupados e em bordas. Não imputar diâmetro verdadeiro de objetos encobertos.
5. Registrar `reference_mm`, `measured_mm`, `calibration_id`, `occluded`, `perspective_corrected`, `segmentation_reviewed` e `scene_id` por pelota.
6. Executar: `python validation/granulometry_reference.py --input measurements.csv --output validation_results/granulometry.json`.
7. Relatar viés, MAE, RMSE, MAPE e percentil 95 do erro absoluto por cenário e estratos. Testar repetibilidade com novas capturas, sem contar medidas repetidas como amostras independentes.
8. Isolar fontes de erro por experimentos controlados: (a) **calibração**: mesmo contorno, escalas distintas e referência coplanar; (b) **segmentação**: escala fixa, contorno manual versus automático; (c) **oclusão**: separar pelotas inteiras, parcialmente encobertas e bordas; (d) **perspectiva**: posições no campo, câmera perpendicular versus inclinada e correção geométrica.
9. Para comparar curvas, declarar explicitamente base numérica ou mássica, faixas granulométricas, método de conversão (se houver), diferenças em pontos percentuais por classe e D10/D50/D90 quando defensáveis.

## 3. Critério de decisão

Não publicar ganhos de precisão nem promover modelo experimental a padrão sem teste independente. Definir previamente limites aceitáveis com processo/qualidade e metrologia. Comparar desempenho por cenário, erros críticos, latência, memória e reprodutibilidade; preferir RF quando MobileNetV3 não demonstrar ganho robusto. O resultado deve incluir a origem e SHA-256 de cada artefato, casos excluídos e exemplos de falhas.

Os scripts calculam métricas **a partir de CSVs fornecidos**. Não geram rótulos, não inventam referências físicas e não executam inferência dos modelos. Esses dados e as medições precisam ser produzidos e revisados antes de qualquer conclusão.
