# Auditoria preliminar e plano de integração — 2026-11-08

## Base
- Repositório: rolympio/PelotaVision (privado).
- Base: main, commit 983ac9d478a22478279344c3ae255d99cd2cf19f (merge 0.16.0 / Windows 0.6.0).
- Branch de preparação: integracao/candidata-exe-apk-20261108.
- Nenhum merge, release, build ou teste executado nesta auditoria. Nenhum binário validado.

## Comparações verificadas (main...branch)
| Branch | Ahead | Behind | Tratamento |
| --- | ---: | ---: | --- |
| feature/calibracao-exclusao-20261030 | 12 | 1 | Parcial: revisão, UI de confirmação e testes físicos antes de integrar |
| studio/windows-executavel | 6 | 0 | Revisar workflow, dependências e testes |
| studio/0.8-ia-interface | 5 | 0 | Revisar UX e dependências; evitar integração duplicada |
| experimento/studio-mascaras-multitarefa | 7 | 0 | Experimental; não misturar pseudo-rótulos com validação |
| infra/ambiente-windows-skills | 2 | 0 | Revisar scripts seguros e testes |
| feature/calibracao-recipiente | 0 | 33 | Já ancestral de main |

## Segurança e rastreabilidade
1. Antes de alterações, ler AGENTS.md, docs/ambiente-windows.md e os scripts relevantes.
2. Verificar origin, branch, SHA, status local; nunca usar reset/clean/checkout forçado ou sobrescrever alterações locais.
3. Não executar tools/windows/compilar_tudo.bat como atualização; AGENTS.md o classifica como potencialmente destrutivo.
4. Não modificar imagens originais; dados privados fora do Git; conferir SHA-256.
5. Verificar licenças de SAM 2 e checkpoints antes de baixar; registrar URL, versão, licença e hash. Nenhuma instalação sem informar comando e tamanho.
6. Nunca publicar release automaticamente. Não publicar binários sem logs de testes, hashes e confirmação funcional.

## Critérios de integração
- Calibração: referência 11 mm confirmada visualmente; exclusão de todas as esferas de contagem, histogramas, trincas e exportações; comparações de escala recipiente/esfera sem correção universal presumida.
- Oclusão: rejeições justificadas por evidência independente, sem otimização oportunista de Rosin–Rammler.
- Windows e Android: mesma imagem, ROI e escala; comparar contagem, D10/D50/D90 e PDF/CSV/JSON.
- Studio: importar imagem, revisar máscaras, salvar resultados sem tocar nos originais, tratamento de erro e caminhos Unicode.
- IA: distinguir baseline/pseudo-rótulo de ground truth; registrar métricas em holdout real, regressões e paridade.
- CI: executar testes Java/Android/Windows e builds reproduzíveis; obter logs brutos, nomes, versões e SHA-256 dos artefatos.
- APK: distinguir debug de release e assinatura; EXE: testar inicialização em Windows limpo e empacotamento das DLLs OpenCV.

## Próxima execução
1. Inspecionar PRs #26 a #31, diffs e dependências, sem merge automático.
2. Criar commits seletivos apenas após revisão e testes; usar PR de integração.
3. Executar CI Windows e Android em runners adequados. Se não houver runner, executar localmente em ambiente autorizado.
4. Só disponibilizar EXE/APK quando os artefatos forem efetivamente gerados, testados e rastreáveis.

**Estado atual:** auditoria preliminar concluída; integração de código, testes e compilação pendentes.
