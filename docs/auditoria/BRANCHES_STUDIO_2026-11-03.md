# Reconciliação de branches para a entrega Studio

Inspeção real: `git fetch`, refs remotas, diferenças contra `origin/main`, metadados
de PRs abertos/mesclados e código de interface/calibração relacionado. Base
`983ac9d` (PR #25 mesclado). Não é uma afirmação de auditoria linha a linha de todos
os experimentos históricos.

| Frente/PR | Evidência | Decisão nesta entrega |
|---|---|---|
| Integração #25/#24, Android 0.16.0 / Windows 0.6.0 | Já na main; core compartilhado, MobileNetV3 v3, StarDist real, ao vivo e aceleração | Preservar no pacote Studio |
| PDF, RR, mapa, ROI, câmera e luz (#14–22) | Ancestralidade/merges e código operacional | Preservar; não reintroduzir implementações antigas |
| Interface guiada #10 | Aberto, sem revisão aprovada; proposta de diálogo em código anterior à main | Reaproveitar ideia de fluxo, implementar shell contextual sobre código atual, sem downgrade |
| Esferas/exclusões #26 | Draft/WIP, sem aprovação; assistente mediana/MAD e mudanças de amostragem/metrologia | Não promover automaticamente a produção; exige paridade Android/Windows e validação física/manual |
| Auditoria de esfera 11 mm | Branch documental `feature/esferas-11mm-auditoria-20261030` | Incorporar alertas ao planejamento; não tratar documentação como algoritmo entregue |
| Validação #11 | Aberto, scripts adicionais em `validation/`, sem aprovação | Manter separado até revisão e dados de referência independentes |
| Play #18 | Aberto, publicação AAB e segredos, sem aprovação | Não habilitar distribuição Android ou mexer em assinatura |
| Trincas sintéticas #2/#4 | Experimentos abertos, um draft | Não trocar classificador embarcado com base em rótulos sintéticos |
| Redes/mapa #9 | PR ainda aberto contra integração antiga; branch já ancestral da main | Não duplicar nem confundir estado do PR com código ausente |
| Probe aceleração | Workflow de inspeção de dependências | Não necessário ao aplicativo distribuído |
| Infra #27 | Trabalho local preservado em `098d654`; correções CI em `1e7f305` | Publicado por PR, sem fotos privadas |
| Studio #28 | Nova interface e ensaio do executável, dependente de #27 | Draft até testes do pacote/CI; Release depende de aprovação do responsável |

Nenhum dos PRs abertos inspecionados apresentava revisão **APPROVED**. Não foram
feitos merges automáticos na main nem reescrita de branches de terceiros.

## Riscos confirmados e não inferidos

- Os XMLs antigos realmente contêm 81 testes sem falha, mas pertencem à base com
  árvore modificada e não comprovam execução de um pacote novo.
- CI real revelou referência RF antiga comparada ao RF retreinado: corrigido por
  vínculo ao CSV congelado já versionado, preservando teste do RF legado.
- Teste PowerShell encerrava com LASTEXITCODE de uma falha intencional: corrigido
  sem ignorar asserções ou ocultar falhas reais.
- Diferença de D50 ou escala entre fotografias não prova isoladamente defeito de
  calibração. Plano, altura, perspectiva e identificação das esferas precisam ser verificados.
- Ainda não se declara validadas a detecção de múltiplas referências, máscaras
  reais de borda ou a precisão dimensional de partes ocultas.
