# Pesquisa: super-resolução por múltiplas imagens (20/11/2026)

Material de origem da rajada da 0.19.0, preservado como recebido (pacote "Pesquisar super-resolução por
múltiplas imagens e criar prompt para o PelotaVision"). Os caminhos `/home/ubuntu/...` citados nos
textos são do ambiente onde a pesquisa rodou.

- `relatorio-super-resolucao-multiframe.md`: relatório completo, com o experimento em fotos do
  repositório.
- `prompt-master-burst-sr.md`: prompt de implementação.
- `A-classicos.md`, `B-deep.md`, `C-ferramentas.md`, `referencias.md`: notas de literatura e
  ferramentas.
- Código e dados do experimento: `tools/burst_validate/pesquisa/`.

## Onde a implementação diverge do prompt (e por quê)

| Prompt | Implementado | Motivo |
|---|---|---|
| Base Android 0.16.0 / Windows 0.6.0 | Base 0.18.0 / 0.10.0 (branch `versao/0.18.0-relatorio-roi-auto`) | É a versão mais recente do repositório |
| Builds só no GitHub Actions | Compilação e testes locais (`COMPILAR_PELOTAVISION*.bat`) | Actions sem cota; testes JUnit rodados aqui com OpenCV 4.9 |
| IBP parte do drizzle | Parte da empilhada ampliada; o drizzle fica como reserva | O drizzle soma a pegada de 2 px HR ao pixel. Com só 6 a 12 iterações, a SR ficava 4 dB abaixo da empilhada ampliada |
| "Regularização de suavidade nas áreas planas" (sem número) | `μ = 0,5·N/4`, peso tirado da estimativa da fase 1 | Varredura: μ = 0,05 → 34,2 dB, μ = 0,3–1 → 38,8 dB (foto de correia, ruído 10) |
| Critério SR sem ruído | Ruído (luma) ≤ 10 níveis adicionado ao veredito | Tabela de calibração do próprio prompt: com 35/255 a SR perde da média; confirmado em `verification/burst-0.19.0/` |
| Teste "SR tem que subir a MTF" | Teste unitário no Nyquist LR, mais gate de χ² em toda execução | Sem a verdade não dá para medir MTF em produção; o χ² pega registro errado e cena que mexeu |
| SR no Android | Desligada no Android nesta versão | Custo em ARM não medido; a empilhada já roda |
