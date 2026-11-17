# Agente local (Ollama + opencode) no PelotaVision

Pasta do projeto: `D:\Projetos\PelotaVision`. Área privada: `D:\Projetos\PelotaVision-local`.

## Preparar (uma vez)

```bat
cd /d D:\Projetos\PelotaVision
git fetch origin
git checkout feature/rede-borda-roboflow-agente-local
PREPARAR_AGENTE_LOCAL.bat
```

O script pergunta antes de instalar qualquer coisa e:
1. lê GPU/VRAM e RAM e escolhe o modelo (troque com `-Modelo <tag>`):

   | Máquina | Modelo | Observação |
   |---|---|---|
   | ≥ 20 GB VRAM | `qwen3-coder:30b` | melhor agente local |
   | 14–19 GB VRAM | `gpt-oss:20b` | |
   | < 14 GB VRAM e ≥ 32 GB RAM | `qwen3-coder:30b` | MoE (~3 B ativos): parte na CPU, mais lento |
   | < 14 GB VRAM e 16–31 GB RAM | `gpt-oss:20b` | ~14 GB, divide GPU/CPU |
   | menos que isso | `qwen3:8b` | só tarefas pequenas |

2. cria a variante com **contexto de 32 mil tokens** (`pv-<modelo>-32k`). O padrão do Ollama é curto e,
   quando estoura, o opencode perde as chamadas de ferramenta sem avisar;
3. instala o opencode (`npm install -g opencode-ai`) se faltar e grava `%USERPROFILE%\.config\opencode\opencode.json`.
   Se já existir, não mexe: grava `opencode.pelotavision.json` ao lado para você copiar o bloco `provider.ollama`;
4. cria a venv `..\PelotaVision-local\envs\borda` (PyTorch CUDA se houver NVIDIA) e roda os testes sintéticos.

**Versão do opencode.** O arquivo global usa o formato `provider`/`model` da documentação V1, que é o
que os guias de Ollama usam. O `opencode.jsonc` do repositório usa `permissions` (lista), formato da V2.
Se o seu opencode recusar um dos dois, rode `opencode --version` e ajuste pelo formato da sua versão.

## Usar

```bat
cd /d D:\Projetos\PelotaVision
opencode
```
No opencode: `/models` → `ollama/pv-...-32k`. As skills `pelotavision-*` carregam com `@pelotavision-borda`,
`@pelotavision-roboflow`, `@pelotavision-ambiente`, `@pelotavision-dataset`.

## Como pedir (modelos locais erram menos com passos curtos)

Um pedido por vez, sempre dizendo o comando e o que conferir. Prompts prontos:

**1. Conferir o ambiente**
> @pelotavision-borda Rode `BORDA.bat testes` (com `set PV_NONINTERACTIVE=1` antes) e me diga quantos testes passaram. Não altere arquivos.

**2. Baixar a primeira fonte** (defina `ROBOFLOW_API_KEY` no terminal antes de abrir o opencode)
> @pelotavision-roboflow Rode `BORDA.bat baixar --ids rf_iron_ore_pellets_healer`. Depois leia o `proveniencia.json` gerado e me diga licença da API, número de imagens por divisão e classes. Não faça mais nada.

**3. Converter e auditar**
> @pelotavision-roboflow Rode `BORDA.bat converter rf_iron_ore_pellets_healer` e depois `BORDA.bat auditar rf_iron_ore_pellets_healer`. Mostre o bloco `resumo` do `auditoria.json` e diga, pela regra da skill, se a fonte pode ensinar borda.

**4. Dataset só de caixas (ex.: Rubber Pellets Detection)**
> @pelotavision-roboflow Rode `BORDA.bat baixar --ids rf_rubber_pellets_detection`, depois `BORDA.bat caixas rf_rubber_pellets_detection --max-imagens 200` e `BORDA.bat auditar rf_rubber_pellets_detection`. Mostre os resumos.

**5. Pré-treino**
> @pelotavision-borda Rode `BORDA.bat pretreino --iteracoes 6000`. No fim, leia `treino.json` e me mostre a última linha de `historico_validacao` (rede × gradiente).

**6. Avaliar e decidir**
> @pelotavision-borda Rode `BORDA.bat exportar` e `BORDA.bat avaliar <pasta>`. Mostre `total` e `criterio` do `avaliacao.json`. Se o critério não for aprovado, pare e explique; não mexa no app.

## Limites do agente local

- Ele roda comandos de verdade na sua máquina. Mantenha `git push`, apagar arquivos e instalações em "ask".
- Não cola a chave do Roboflow no chat do agente: ela só existe na variável do terminal.
- Treino longo: rode o `BORDA.bat` direto no terminal e peça ao agente só para ler os resultados.
