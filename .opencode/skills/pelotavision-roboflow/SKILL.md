---
name: Dados externos (Roboflow) PelotaVision
description: Baixar, converter e auditar datasets publicos do Roboflow Universe (pelotas, graos, pedras) para pre-treino da segmentacao de borda, com licenca e proveniencia registradas.
---

## Antes
1. Ler `../../../tools/externos/fontes_roboflow.json` e `../../../ml_training/borda/README.md` (regras).
2. A chave fica SO na variavel `ROBOFLOW_API_KEY` da sessao. Nunca gravar a chave em arquivo, log, commit ou prompt.
3. Tudo grava em `..\PelotaVision-local\datasets\externos\` (fora do Git). Nao apagar nem sobrescrever nada.

## Procedimento (um passo por vez; conferir a saida antes do proximo)
1. `BORDA.bat baixar --listar` e depois `BORDA.bat baixar --ids <id>` (ou `--prioridade 2`).
2. Ler `proveniencia.json`: licenca da API, contagens, se ha classes inesperadas. Licenca diferente do registro = parar e avisar.
3. Se a fonte tem poligonos: `BORDA.bat converter <id>`. Se so tem caixas (rota `caixas_para_contorno_sam2`): `BORDA.bat caixas <id>`.
4. `BORDA.bat auditar <id>` e ler `auditoria.json`:
   - |vies_diametro_rel_mediana| < 0,01 e frac_dentro_tolerancia >= 0,8 -> fonte pode ensinar borda.
   - Caso contrario, so interior (o `--aplicar` ja marca `contorno_confiavel=false`). Relatar os numeros.
5. Abrir 3-4 pranchas (`prancha_*.jpg`) e descrever o que se ve; nao afirmar qualidade sem olhar.
6. Atualizar o `status` da fonte no registro so com evidencia (numeros + pranchas), em branch e PR.

## Nao fazer
- Usar rotulo externo como referencia de tamanho ou na validacao final.
- Misturar copias aumentadas da mesma foto entre treino e validacao (o conversor ja agrupa por `grupo_origem`).
- Distribuir modelo com fonte de licenca nao conferida.
