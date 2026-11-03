# Correções identificadas pelo CI real

PR #27, base inicial `098d654`:

- Run `41693606074`: os sete cenários de integração passaram, mas o wrapper do
  Actions propagou o último `LASTEXITCODE=1`, esperado no cenário de remoto inválido.
  Corrigido com `exit 0` somente após todas as asserções. Erros reais continuam falhando.
- Run `41693606041`: o RF atual `random_forest_20261027_v2` estava sendo comparado
  com `ml_training/training/reference_vectors.npz`, do RF anterior. O metadata já
  apontava para o CSV congelado `verification/ml-0.15.5/reference_vectors.csv`.
  O teste atual agora usa essa referência existente e um teste separado preserva
  a regressão do RF antigo com seu NPZ. Nenhum peso ou escore de referência foi alterado.

Verificações locais executadas: sete cenários de integração aprovados; 209 vetores
do RF atual com diferença máxima **0**, RF antigo com diferença máxima
**5,5511e-17**. Essas são verificações de implementação/versão, não acurácia científica.
A suíte pytest completa requer dependências próprias; o CI deve confirmá-la após o push.
