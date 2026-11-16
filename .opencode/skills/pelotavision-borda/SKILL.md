---
name: Rede de borda PelotaVision
description: Treinar, exportar e avaliar a rede de borda (interior/borda/separacao) que pode substituir o recorte edge_v1 do SAM 2, com criterio de implantacao fixo.
---

## Antes
1. Ler `../../../ml_training/borda/README.md` inteiro e `../../../docs/ia/PLANO_REDE_BORDA_20261116.md`.
2. Rodar `BORDA.bat testes` e so seguir com tudo verde.
3. Confirmar com `git status -sb` a branch de trabalho (nunca `main`).

## Procedimento
1. Pre-treino: `BORDA.bat pretreino --iteracoes 6000` (GPU) ou `--iteracoes 1500 --lote 8` (CPU).
   Ler `treino.json`: `historico_validacao` (rede x gradiente) e `fontes`.
2. Ajuste fino so com fotos proprias de contorno REVISADO por pessoa (`annotations.json` com `polygon`).
   Pseudo-rotulos entram so marcados (`contorno_confiavel=false`).
3. `BORDA.bat exportar` e `BORDA.bat avaliar <pasta holdout revisado>`.
4. Relatar `avaliacao.json` -> `criterio`. Se `aprovado_nestas_cenas` for falso, PARAR: nao integrar no app.
5. Se aprovado: abrir tarefa separada de integracao Java (`edge_v2` em `Sam2Refiner`), com teste de paridade
   Java x `borda.recortar_por_raios`, opcao desligada por padrao e AVALIAR_ESFERAS antes/depois.

## Nao fazer
- Escolher modelo, limiar ou iteracao olhando o holdout final.
- Treinar a borda com saida do proprio edge_v1 (circularidade).
- Afirmar ganho de medida sem o resultado das esferas de 11 mm.
