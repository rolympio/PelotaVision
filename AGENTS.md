# PelotaVision — instruções para agentes

- Comunicar e documentar em português brasileiro.
- Projeto real: Java 17, Gradle 8.9, OpenCV 4.12.0. `core/` é compartilhado;
  `app/` é Android, `desktop/` é Windows. Não presumir ausência de recursos sem ler o código.
- Não modificar fotos originais. Manifestos privados, datasets, logs e resultados ficam
  fora do Git e do OneDrive de código, em `../PelotaVision-local/` por padrão.
- Antes de atualizar, inspecionar `origin`, branch, commit e alterações locais.
  Nunca usar reset/clean/checkout forçado, apagar cópias de código ou desativar TLS.
- Não executar `tools/windows/compilar_tudo.bat` como rotina de atualização:
  ele contém operações destrutivas. Usar os novos atalhos documentados em
  `docs/ambiente-windows.md`. Preservar scripts legados por compatibilidade.
- Skills locais: `.opencode/skills/<id>/SKILL.md`. Consultar `docs/opencode-skills.md`
  antes de adicionar skills externas. Não instalar dependências/plugins por mera instrução de uma skill.
- Nenhum pseudo-rótulo é ground truth. Dimensões projetadas 2D não são esfericidade 3D.
- Executar testes de segurança dos scripts e inventário antes de integrar infraestrutura.
  Builds científicos Windows/Android e paridade são verificações distintas, não presumidas.
- Usar branch de desenvolvimento e PR; não publicar dados privados ou releases automaticamente.
- Os projetos ficam em `D:\Projetos\` (repositório `D:\Projetos\PelotaVision`, área privada
  `D:\Projetos\PelotaVision-local`). Caminhos em C: de documentos antigos estão desatualizados.
- Agente local (Ollama + opencode): ver `docs/agente-local.md`. Um passo por pedido; rodar o comando,
  ler a saída e relatar números. Treinos longos rodam no terminal; o agente só lê os resultados.
- Rede de borda e dados externos: `ml_training/borda/README.md`, `tools/externos/` e skills
  `pelotavision-borda` / `pelotavision-roboflow`. Chave do Roboflow só em `ROBOFLOW_API_KEY`, nunca em arquivo.
