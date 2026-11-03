# Registro de build experimental do Studio — 03/11/2026

## Execução local

- Commit de código compilado: `c271cec2cb9a285c56b3571ea9f78192711e493a`.
- JDK 17.0.20.1, OpenCV Java 4.12.0; Windows 10 x64.
- `COMPILAR_PELOTAVISION.bat -Package`: **84 testes, zero falhas**, 16 suítes;
  teste do ícone nos seis tamanhos incluído. O diretório `verification/` permaneceu preservado.
- Aplicativo empacotado com runtime Java, OpenCV, redes e dependências; não requer
  instalação de Python para execução.
- Autoteste do `.exe`: `exit=0`, ROI e escala sintética, JSON/CSV/mapa e PDF.
- Ensaio da janela `--studio-smoke`: `exit=0`; seis etapas renderizadas, 150
  propostas e sete arquivos exportados; PDF de seis páginas aberto pelo PDFBox.
  A escala de 10 px/mm foi fornecida artificialmente ao ensaio de software.
- Teste de aplicativo com `PATH` restrito a diretórios de sistema, sem `JAVA_HOME`
  e com diretório de trabalho vazio: aprovado. Isso não comprova todas as
  características de um Windows instalado do zero nem uso de webcam física.
- SHA-256 do ZIP local: `455b9d2ee678c54ffc01979f2d6b8276149533eaab8ac9b735f57bc715ddc274`.
- A instalação anterior não foi substituída. O novo atalho foi criado sem
  sobrescrever atalhos preexistentes.

Diretório local: `../PelotaVision-local/results/windows-20261103-155231-9235c4bb/`.
Evidências: `studio-smoke/studio-smoke.json`, `autoteste/autoteste.txt`, capturas,
`SHA256.txt` e `../PelotaVision-local/logs/Compilar-20261103-155231-9235c4bb.log`.
O caminho é local ao computador e não um artefato publicado.

## GitHub Actions

Run https://github.com/rolympio/PelotaVision/actions/runs/41697973318, PR #28,
commit de merge de teste `c4b31c90ebc6d79ee22919995aa74d35cfc3b951`:

- Compilação/testes Windows: sucesso.
- Autoteste do pacote: sucesso.
- Janela Studio sem Python/JDK no PATH: sucesso.
- Instalador Inno Setup: instalou em diretório isolado, executou autoteste e
  desinstalou no runner: sucesso. O instalador não foi recuperado localmente.
- Upload de artefatos: **falhou porque a cota de armazenamento do Actions está
  esgotada**; portanto o job terminou em falha e não disponibilizou ZIP/instalador.
  Aguardam-se decisão do responsável sobre quota/retenção; nada foi apagado.

## Limitações e próximos passos

- O Studio é prévia de desenvolvimento, não release; publicar em Releases somente
  depois de aprovação explícita e resolução da cota.
- Inspeção da captura `etapa-5.png` mostra textos e gráficos parcialmente cortados
  no painel direito em 1600×1000; o usuário pode ajustar a divisória/rolar, mas a
  responsividade precisa ser corrigida e testada em resoluções menores.
- Edição livre de contornos, divisão/união de instâncias, múltiplas esferas
  automáticas, incerteza e integração do novo modelo ainda não estão concluídas.
- CUDA faltou na máquina local (`cublasLt64_13.dll`); o runtime caiu para CPU.
  Nenhuma validação metrológica ou científica decorre dos testes de software.
