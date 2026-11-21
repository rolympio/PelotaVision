# Entrega: ambiente local, scripts Windows e skills

## Resultado real

- Clone completo criado fora do OneDrive: `%USERPROFILE%\source\repos\PelotaVision`.
- Origin oficial confirmado; leitura autenticada funcionou (`ls-remote` e clone).
  Não foi necessário pedir novo acesso à conta já autenticada. Permissão de push
  não foi testada. Nenhuma alteração foi enviada ao GitHub nesta execução.
- Base `main`: `983ac9d478a22478279344c3ae255d99cd2cf19f`.
- Desenvolvimento: `infra/ambiente-windows-skills`.
- Área privada em `../PelotaVision-local/`, com logs, backups, datasets, modelos e resultados.
- Configuração pessoal ignorada pelo Git; OpenCV existente foi reutilizado por
  caminho, sem modificar a instalação antiga.
- Cinco atalhos criados, com guardas de remoto, branch, alterações locais, commits
  divergentes e colisão de arquivos ignorados; atualização `--ff-only` com bundle.
- Duas skills descobertas automaticamente pelo OpenCode; `pelotavision-ambiente`
  foi carregada efetivamente. Sem instalação de plugins ou dependências novas.

## Evidências executadas

| Verificação | Resultado |
|---|---|
| `VERIFICAR_AMBIENTE.bat -CheckRemote` | Sucesso; Git, JDK 17 e acesso remoto confirmados |
| `INSTALAR_PELOTAVISION.bat -PrepareOnly` | Estrutura criada; não significa instalação de release |
| `SINCRONIZAR_DADOS.bat` | 113 imagens, zero erros; 31 candidatos `_original`, 24 hashes únicos de originais |
| Inventário completo | 18 grupos de duplicação exata, 20 cópias excedentes; nenhuma removida |
| `test_seguranca.ps1` | 16 verificações passaram |
| `test_integracao.ps1` | 7 cenários passaram em repositórios sintéticos locais, inclusive caminho com espaço/acento, backup/recuperação, erro de build e arquivo ignorado preservado |
| `test_inventariar.py` | 7 testes passaram, sem dependências externas |
| Parser PowerShell e `git diff --check` | Sem erros de sintaxe/espaçamento |
| Compilação Windows + suíte existente | **81 testes, 15 suítes, zero falhas/erros/ignorados**; JAR e runtimeLibs gerados |
| Regressão do isolamento das fixtures | **11 testes** selecionados passaram com cópias; referências versionadas preservadas |
| Android | SDK platform 35/build-tools 35.0.0 encontrado. Tentativas completas chegaram aos testes, mas excederam os timeouts (20 s e 120 s); build/APK não declarado concluído. `BeltDemoTest` passou separadamente com fixtures isoladas (56 s) |
| Workflow CI novo | Criado, ainda não executado pelo GitHub |
| Executável/instalador novo | Não gerado nesta entrega; `-Package` foi implementado, mas não testado de ponta a ponta |

Detalhes privados ficam em `../PelotaVision-local/logs/` e `datasets/`, não no Git.
O XML completo dos 81 testes foi preservado em
`logs/evidencia-windows-20261103/test-results/`, antes da execução dos testes selecionados.
Log do build completo: `Compilar-20261103-133934-dfd3cc11.log`.
Manifesto: `datasets/inventario-20261103-133934-08ec1824/manifest.json`.

## Achados e preservação

O diagnóstico anterior, produzido antes de testar a autenticação Git, continha
conclusões indevidas. Não é verdade que o projeto não possua Android, testes,
modelos, CI ou fotos originais exportadas. Isso foi retificado no diagnóstico
anterior, que não foi copiado integralmente para este clone.

A instalação anterior em `E:\PelotaVision\build\pelotavision` estava na branch
`integracao/0.16.0`, commit `84b33a1`, com alterações locais. Ela não foi atualizada,
movida ou descartada. O executável já existente em `E:\PelotaVision\PelotaVision.exe`
também não foi substituído. O clone novo não equivale à instalação de um executável.

Alguns testes históricos regravam exemplos em `verification/`. A primeira execução
confirmou isso. Os sete exemplos gerados foram guardados em
`backups/exemplos-gerados-primeiro-build-20261103/`; somente as alterações geradas
por esta execução foram restauradas ao conteúdo versionado. Os novos atalhos
executam testes sobre cópias por meio de `isolar-testes.gradle`, também no Android.
As tentativas Android iniciais também geraram exemplos nas referências: esses
resultados foram preservados em `backups/exemplos-gerados-android-20261103/`,
incluindo diretórios PDF novos; somente as alterações geradas nesta execução foram
restauradas ao conteúdo versionado. Não houve alteração de fotos da fonte privada.

CUDA não estava operacional: faltou `cublasLt64_13.dll`; os testes utilizaram CPU/fallback.
Isso não demonstra ausência de GPU física nem ganho de desempenho em GPU.
Android avisou sobre ferramentas SDK XML de versões diferentes e chave debug local;
não foi modificada assinatura nem prometida atualização de instalações existentes.

## Pendências e limites

1. Concluir build Android sem timeout e testar APK em dispositivo; paridade entre
   plataformas não foi demonstrada apenas pelo build Windows.
2. Testar `-Package` e autoteste do novo executável antes de distribuir.
3. Revisar e integrar a branch via PR; o atalho de atualização recusa corretamente
   atualizar esta branch de desenvolvimento. Push e release não são automáticos.
4. Inventário não atribuiu classes científicas, qualidade óptica ou grupos físicos
   por suposição. Quase duplicatas, confirmação de amostras e anotação continuam pendentes.
5. Atualização/recuperação foi testada em repositórios locais sintéticos; não houve
   merge de atualizações na main oficial nem alteração do clone antigo.

Guias: [ambiente Windows](../ambiente-windows.md),
[inventário](../ia/PLANO_INVENTARIO_LOCAL.md),
[skills](../opencode-skills.md).
