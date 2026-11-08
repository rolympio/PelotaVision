# PelotaVision — Studio de Revisão Visual
**Branch:** experimento/studio-mascaras-multitarefa  
**Data:** 2026-11-07

## Limitação deste ambiente
O sandbox de execução não possui display gráfico e não tem acesso à árvore privada `../PelotaVision-local/` onde estão as oito imagens reais do piloto. A interface tkinter existente (`tools/studio_ml/review.py`) e a nova interface web não podem ser abertas aqui com as imagens reais.

## Interface existente
`tools/studio_ml/review.py` — editor tkinter com zoom, pincel, separar, unir, metadados e exportação rastreável. Testes: 28 passaram (review + pipeline).

## Interface web (nova)
`tools/studio_ml/web_review.py` — servidor HTTP local que reutiliza a classe `Review`. Sem display nativo. Serve overlay PNG e fila de candidatos.

### Execução no Windows
```powershell
cd C:\caminho\PelotaVision
python -m tools.studio_ml.web_review --folder <pasta-candidata-privada> --revisor <seu-nome> --port 8765
# Abrir http://127.0.0.1:8765
```

A pasta candidata deve conter `record.json`, `instances_uint16.png` e `training_dev.npz` (saída de `pipeline.py` ou `sam2_candidates.py`). As oito imagens reais estão em `../PelotaVision-local/experiments/`.

## Decisões
Aceitar, Excluir, Esfera 11 mm, Exportar. Exportação cria nova pasta com hash e histórico; não sobrescreve originais. Revisão leiga não é ground truth.

## Pendências
- Abrir a interface com as oito imagens reais no Windows do operador.
- Revisão humana das filas SAM 2 / StarDist / watershed.
- Calibração rastreável para medidas físicas.
