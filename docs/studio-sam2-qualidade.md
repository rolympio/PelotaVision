# SAM 2.1 — qualidade exploratória e revisão

Etapa posterior ao piloto inicial, sem reinstalação do SAM. Não é validação
científica nem segmentação pronta para granulometria automática.

## Fluxo reproduzível

```powershell
# Mesmo Python isolado documentado em studio-sam2.md
python -m tools.studio_ml.quality_review roi <watershed/image-id> --source-map <mapa.json>
python -m tools.studio_ml.sam2_quality_pilot --previous <sam-anterior> `
  --rois <roi-specs.json> --source-map <mapa.json> --checkpoint <checkpoint-oficial>
python -m tools.studio_ml.quality_review queue <novo-run/image-id/stardist_centers> --rank 0 --revisor <nome>
python -m tools.studio_ml.sam2_quality_report --run <novo-run> --source-map <mapa.json>
```

O editor ROI propõe um polígono por limiarização fotométrica, fechamento e casco
convexo do componente dominante. **A sugestão pode incluir fundo e precisa ser
editada**: clique os vértices, aplique o polígono de inclusão ou adicione polígonos
de exclusão; ajuste margem em pixels. Não ajusta nem exige círculo/elipse. Salva
uma nova versão privada, vinculada ao hash do original e à grade. `--initial`
abre um JSON de ROI individual. Para o piloto, `roi-specs.json` é um objeto
`image-id -> ROI individual`. O operador pode reunir as versões editadas ali.

Formato de ROI individual:

```json
{
  "source_sha256": "HASH_DO_ORIGINAL",
  "shape_hw": [576, 1024],
  "include_xy": [[200, 30], [750, 30], [750, 530], [200, 530]],
  "exclude_xy": [[[200, 30], [240, 30], [240, 100], [200, 100]]],
  "border_exclusion_px": 3,
  "status": "pendente_revisao"
}
```

Essas coordenadas são apenas exemplo de formato, não uma ROI universal.
No ensaio, polígonos conservadores foram inspecionados pelo agente nas oito
imagens, usando três enquadramentos semelhantes. Não houve aprovação humana.
Essa ROI elimina borda/recipiente exterior, mas pode excluir pelotas marginais;
o custo precisa ser revisado e não pode ser confundido com melhoria do detector.

## Geometria e escala

Cada variante calcula sua própria estimativa de área; não usa áreas StarDist
para filtrar SAM-grade. Um voto por prompt, entre candidatos com score >= 0,7,
uma componente, circularidade >= 0,55, solidez >= 0,8 e aspecto >= 0,55, quase
inteiramente dentro da ROI. Exige oito votos. Intervalo baseado na mediana/MAD,
com limites de 0,3 a 2,8 vezes a mediana e piso de dispersão para MAD zero.
É um prior exploratório em pixels², sujeito a viés de seleção, não pixels/mm.
Se faltarem votos, todas as propostas vão à revisão.

Triagem final: área nesse intervalo, circularidade >= 0,45, solidez >= 0,75,
aspecto >= 0,45, uma componente, score >= 0,7, no máximo 2% fora da ROI.
Contato com a borda, referência branca possível, formas irregulares, baixa
confiança e conflitos são preservados como pendências. Motivos são múltiplos:
**a soma dos motivos pode exceder o tamanho da fila**. Score SAM não é confiança
calibrada de pelota; circularidade não é esfericidade.

## Sobreposição

- IoU >= 0,65 ou contenção >= 0,9: duplicação **ou** aninhamento ambíguo.
- Interseção <= 8% da menor máscara: só divide pixels quando toda a interseção
  está a até 3 px das duas bordas e o gradiente médio supera o percentil 60 do
  gradiente local (piso 10). Distância interior atribui pixels e preserva a união;
  ambas as regiões precisam continuar conexas.
- Sem essas evidências: mantém a proposta integral no arquivo bruto e na fila.
- Contenção pode significar objeto composto: não é prova de duplicação.

Os testes incluem comparação com rejeição por qualquer pixel. A auditoria pode
reprocessar o **mesmo cache bruto**, com ROI/geometria fixas, sob políticas
`any_pixel`, `iou_only` e `edge`. Isso separa o efeito da sobreposição dos efeitos
da ROI e da geometria, sem repetir inferência.

## Fila e correções

`raw_masks.npz` preserva **todas** as máscaras produzidas, compactadas em bits;
`decisions.json` registra índice, score, prompt, geometria, motivos e conflitos.
`review_queue.json` prioriza compostos/conflitos e parciais antes de duplicações.
Índices brutos são base zero; IDs uint16 aceitos começam em um.

Abrir a fila cria um cartão privado de uma proposta bruta. O editor existente
permite pintar, apagar, separar, criar/unir, excluir/classificar e exportar uma
nova revisão. Para revisar relações entre instâncias aceitas, abrir diretamente
a pasta da variante em `tools.studio_ml.review`. Nenhuma edição altera originais
ou máscaras do piloto. Cartões não são reconciliados automaticamente em um mapa
global: decisões conflitantes entre cartões precisam de revisão humana.

A exportação preserva a proveniência da fila e marca referências 11 mm, objetos
ignorados e excluídos em `excluir_granulometria_ids`. Classificar uma esfera não
produz escala. A fila não foi revisada por especialista; interface gráfica requer
ensaio humano, além dos testes do carregador/exportador.

## Integração de referências de 11 mm

`--calibration` aceita `image-id -> registro`. Exige `status: human_verified`,
`source_sha256`, `shape_hw`, `operator`, `measured_at`, `method`, `evidence` e
`references`. Cada referência exige `diameter_mm: 11`, `confirmed_standard: true`,
`measured_diameter_px`, `uncertainty_px` e `exclude_polygon_xy`. Mediana de
diâmetro_px/11 fornece escala; discordância >10% entre referências é recusada.
Regiões confirmadas ficam excluídas dos candidatos granulométricos. A escala
deve corresponder à grade processada; perspectiva pode invalidar escala única.
O ensaio não forneceu tal registro e não calculou medidas físicas. O validador
confere estrutura/coerência, não autentica a verdade da declaração humana.

## Resultados e recomendação

Todos os resultados, exemplos com IDs/coordenadas, versões, hashes, tempos e
imagens antes/depois ficam em `../PelotaVision-local/experiments/`. Watershed e
StarDist permanecem referências separadas e inalteradas; SAM-centros continua
dependente dos prompts StarDist. Pares não correspondentes são discordâncias,
não contagens comprovadas de omissões. Nenhuma concordância é acurácia.

Pronto para integração **experimental no Studio**: proveniência, persistência de
candidatos, ROI editável, fila e exclusão explícita de referências na revisão.
Permanecem experimentais: ROI sugerida, limiares de forma/área, divisão por borda,
classificação de esferas e qualquer recomendação de desempenho científico.

```powershell
python -m unittest tools.studio_ml.test_sam2_quality -v
```
