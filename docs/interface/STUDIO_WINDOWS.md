# PelotaVision Studio Windows — implementação incremental

## Decisão arquitetural

O código atual é Java 17/Swing, com OpenCV e modelos ONNX embarcados e núcleo
compartilhado com Android. A referência MicroscopiaAI usa pywebview/WebView2 com
interface web escura em três colunas. Inspecionamos seu `studio/desktop.py` e
`studio/web/index.html`. Reutilizamos a organização, paleta e fluxo, não o servidor
Python nem o código de análise de microscopia. A introdução de Qt/PySide6 exigiria
uma ponte e novas dependências/licenças sem benefício demonstrado para a entrega.

## Implementado

- Tema Studio, cabeçalho e navegação em seis etapas, visor central preservado.
- Painéis contextuais roláveis, indicadores recolhíveis e divisórias redimensionáveis.
- Todos os controles anteriores continuam acessíveis: câmera, resolução, bloqueios,
  ao vivo, ROI, busca rápida/genética, detalhada, luz, escala, revisão de trincas,
  comparação, forma, redes, aceleração, histogramas e Rosin–Rammler.
- Camadas de instâncias/classes **como projeções das elipses existentes** e traços
  possíveis de trinca. Não apresentadas como máscaras neurais independentes ou
  reconstrução de partes ocultas. Transparência ajustável, identificação e zoom/pan.
- Histórico local das últimas 15 exportações, configurações por usuário e logs
  rotativos em `%LOCALAPPDATA%\PelotaVision\logs`.
- `--studio-smoke imagem pasta-nova` abre a janela real, processa imagem, aplica
  escala explicitamente de teste, percorre seis etapas, captura telas e exporta PDF.
  Falha em etapa crítica não é aceita como sucesso parcial.

## Ainda não concluído

Editor livre de contornos/split/merge com atualização metrológica, máscara neural
real incorporada à janela, múltiplas referências automáticas e incerteza física
propagada ainda precisam de integração/testes. Não remover ou substituir métodos
existentes com base apenas em pseudo-rótulos. Android permanece preservado.

## Branches e aprovação

PR #10 contém proposta guiada antiga, mas sem revisões aprovadas; aplicá-lo inteiro
regrediria versões e não incluiria recursos recentes. A organização Studio foi
adaptada sobre a main 0.16.0, preservando os recursos atuais. PR #26 é draft/WIP de
calibração, sem revisão aprovada, e não foi integrado automaticamente.
Nenhum resultado experimental de trincas sintéticas foi promovido a produção.

Distribuição: `COMPILAR_PELOTAVISION.bat -Package`. O pacote inclui JRE/DLL/modelos,
autoteste nativo, ensaio GUI sem Python/JDK no PATH, ZIP e SHA-256. O ensaio local
não equivale a uma VM Windows limpa; essa etapa deve ter evidência separada no CI.
O atalho `ABRIR_PELOTAVISION_STUDIO.bat` abre o último pacote local testado.
Atualizações distribuídas só após aprovação, pelo link oficial de Releases;
não há sobrescrita silenciosa nem autoatualização de modelos experimentais.

## Ícone

O desenho próprio de pelotas está em `tools/icon/pelotavision_512.png`. O `.ico`
multirresolução correspondente é `desktop/installer/PelotaVision.ico`: ele é
embutido por `jpackage` no executável e configurado explicitamente no atalho.
Os ícones da janela vêm de `desktop/src/main/resources/icon/`. Para atualizar o
ícone mostrado de uma instalação antiga, é preciso abrir o novo pacote/atalho;
não substituímos nem fechamos a instalação anterior automaticamente. A imagem
de microscopia enviada como referência pertence a outro aplicativo, cujo ícone
próprio é distinto do ícone de pelotas do PelotaVision.
