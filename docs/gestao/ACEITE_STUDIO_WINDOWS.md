# Aceite de distribuição — PelotaVision Studio

Um JAR compilado ou teste unitário aprovado não constitui aceite de distribuição.

## Portão de execução

1. Preservar estado local e registrar commit, versão Java/OpenCV e artefatos.
2. Executar testes científicos Windows sobre cópias das fixtures e registrar XML.
3. Gerar app-image com `.exe`, runtime Java, DLL OpenCV, dependências e modelos.
4. Executar `--autoteste` pelo `.exe`, exigindo `exit=0` e PDF exportado.
5. Executar `--studio-smoke` pelo `.exe`, em diretório de trabalho vazio, removendo
   Python/JDK do PATH. Exigir seis etapas renderizadas, análise com objetos e PDF legível.
6. Inspecionar telas produzidas e registrar falhas de layout/fluxo, sem tratar a
   ausência de falhas Java como avaliação humana de qualidade visual.
7. Testar em runner Windows novo no Actions; teste local com PATH reduzido não
   equivale a uma VM sem ferramentas previamente instaladas.
8. Empacotar ZIP e/ou instalador, calcular SHA-256, subir artefatos e registrar URL/run.
9. Criar atalho sem sobrescrever instalação anterior. Atualizações apenas por
   origem oficial, sem autoaplicar modelos experimentais ou perder dados locais.
10. Solicitar aprovação do responsável antes de publicar em Releases ou integrar
    mudanças científicas/metrológicas ainda experimentais à main.

## Escopo de validade

O ensaio de software pode usar uma escala fornecida explicitamente ao teste.
Isso não valida o diâmetro físico das pelotas nem a identificação de uma esfera.
Captura por webcam real, oclusão, múltiplas alturas, distorção e perspectivas
continuam exigindo ensaios específicos. Treino em pseudo-rótulos mede aprendizado
do alvo provisório, não acurácia contra especialista.

## Não considerar como entrega integral

Editor de máscaras ainda ausente, artefato que não abre, PDF não verificado,
workflow vermelho ou upload indisponível devem permanecer como pendência explícita.
Não usar Release automática para contornar falta de espaço/cota de artefatos.
