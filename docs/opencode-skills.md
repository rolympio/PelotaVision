# Skills do OpenCode neste projeto

Preparado conforme a documentação **V2**, consultada em 03/11/2026:
- https://opencode.ai/v2/docs/skills
- https://opencode.ai/v2/docs/config

## Adicionar uma skill

Criar `.opencode/skills/<id-unico>/SKILL.md`:

```markdown
---
name: Nome legível
description: Descreva precisamente quando esta skill deve ser usada.
---

## Procedimento
1. Ler as instruções relevantes do projeto.
2. Executar verificações seguras e registrar evidências.
```

Usar ID lowercase kebab-case e não repetir IDs de outras skills. Arquivos de apoio
podem ficar em `references/`, `scripts/` e `templates/` dentro do diretório. Caminhos
no corpo são relativos ao diretório da skill. Frontmatter `name` é nome de exibição;
o ID vem do caminho. Não são necessários npm, plugin ou servidor MCP.

OpenCode descobre `.opencode/skills/` automaticamente. `opencode.jsonc` não altera
modelo/provedor ou configuração global. Skills `pelotavision-*` são permitidas;
outras requerem confirmação. Configurações globais/gerenciadas podem restringir
mais. As mudanças são recarregadas ao salvar; se necessário usar `/reload`.

## Skills incluídas

- `pelotavision-ambiente`: verificação, compilação e atualização segura Windows.
- `pelotavision-dataset`: inventário privado, hashes e prevenção de vazamento.
- `pelotavision-roboflow`: baixar, converter e auditar datasets externos (Roboflow) com licença e proveniência.
- `pelotavision-borda`: treinar, exportar e avaliar a rede de borda com critério de implantação fixo.

Invocar no prompt como `@pelotavision-<id>`. Uso com modelo local (Ollama): `docs/agente-local.md`.
Não afirmar que uma skill executou algo só porque foi descoberta/carregada.

## Receber skills externas

1. Confirmar origem, licença e versão/commit; ler **todos** os arquivos antes de executar.
2. Copiar apenas a skill selecionada para uma branch, nunca substituir `.opencode/`
   inteira nem `AGENTS.md`. Revisar colisões de ID e scripts de rede/escrita.
3. Não incluir tokens, fotos ou manifestos privados; não instalar catálogos remotos
   ou plugins de execução automaticamente.
4. Conferir se a descrição aparece e carregar explicitamente pelo ID.
5. Testar primeiro em dados sintéticos/pastas temporárias; registrar evidências e abrir PR.

Skills são instruções reutilizáveis, não executáveis confiáveis por definição;
não ampliam autorização para publicar dados ou apagar arquivos.
