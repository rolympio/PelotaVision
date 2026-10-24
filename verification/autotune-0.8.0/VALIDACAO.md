# Validação — PelotaVision Android 0.8.0

19/10/2026. Nova comparação de configurações para sugerir ajuste inicial na foto parada. Não houve novo treinamento do classificador nem alteração do ajuste elíptico. Não é calibração automática de escala: o objeto de referência e sua medida física continuam escolhidos pelo usuário.

## Critérios e escopo

Quatro perfis, sensibilidades 55/75/90%, 12 combinações; ajuste atual primeiro e incluído se diferente, totalizando até 13. LoG entra somente se for a configuração atual. Cada opção executa o mesmo detector/refinador real da foto. Busca em trabalhador, progresso e cancelamento; orçamento de 30 s verificado entre opções, sem interromper uma chamada JNI em andamento. Trincas são calculadas somente para a opção aplicada.

Escore heurístico: 30×qualidade da borda + 20×fração de elipses válidas + 25×n válido/n válido máximo entre opções + 15×concordância geométrica + até 5 por aderência RR − 20×fração sobreposta − 8×fração parcial − 35×fração válida abaixo do mínimo − 15 se limite de candidatos, limitado a [0,100]. Qualidade combina suporte e RMS do ajuste já existente. Concordância um a um usa centros/raios e as duas melhores concordâncias com outras opções; é calculada uma vez na foto completa. ROI e região da referência excluída afetam contagem/tamanho/forma na pontuação. Nenhum escore é probabilidade de acerto.

Weibull de dois parâmetros (Rosin–Rammler, não normal), máxima verossimilhança numérica, forma limitada a 0,15–100. Usa diâmetros equivalentes observados por número, com n≥12 e variação, independentemente das classes do histograma. KS descritivo do ajuste, sem p-valor. Seu peso é secundário. O mínimo esperado de 5 mm é configurável/zero desativa, só ativa com escala externa independente; média assumida não ativa. Só elipses válidas entram na contagem abaixo do mínimo. Os objetos não são apagados e os diâmetros não são modificados para satisfazer o mínimo ou a curva.

Mudança de escala/ROI recalcula a recomendação sem trocar contornos. A aplicação de outra opção é explícita, reanalisa trincas, descarta revisões e invalida uma referência de escala se índice/contorno mudarem. Sem busca, o ajuste manual continua disponível; a sugestão de uma foto não redefine os parâmetros globais da câmera. JSON registra aplicada/recomendada, opções, medidas de qualidade, hipóteses, duração e interrupções. CSV permanece com 32 colunas e a composição PNG anterior é preservada.

## Testes Android simulados

50 testes, zero falhas/erros: PhotoExport 9, CrackExport 5, Region 10, LearnedModel 3, EllipseWorkflow 5, ObjectCalibration 9 e DetectionSuggestion 9. Os nove novos verificam ajuste Weibull em quantis conhecidos e invariância de escala; mínimo inativo sem referência externa e com média assumida/bloqueada; mudança de recomendação após calibrar sem mudar diâmetros/resultados; ROI e referência excluída; geometria ruim/ausência de evidência; concordância um a um e cobertura das opções; rastreabilidade JSON; validação e persistência do formulário; comparação informativa sem mudar a foto/escala e limpeza ao sair da foto.

São testes Robolectric; as telas são operadas por seus controles reais, mas não são ensaios em aparelho físico. A biblioteca OpenCV Android não é executada nessa suíte; a execução do detector usa JNI desktop nos testes abaixo.

## Execução nativa nas cenas existentes

OpenCV desktop 4.9 (OpenPnP), mesmas classes Java do detector, 2 threads. O app embarca OpenCV Android 4.12. Essas sete cenas são do conjunto experimental anterior, incluindo imagens sintéticas/de procedência não verificada. Os valores não medem acurácia industrial e elipse geometricamente aceita não garante que seja uma pelota real. Todas as 12 opções foram comparadas em cada cena. Os ensaios usaram orçamento ampliado de 120 s para garantir comparação completa; durações ficaram abaixo dos 30 s de produção neste ambiente.

| Cena | Atual: detecções/válidas | Sugerido: detecções/válidas | Opção escolhida | Busca (s) |
|---|---:|---:|---|---:|
| brown40 | 40/38 | 39/38 | clahe_p1_s55 | 4.30 |
| cluster | 32/18 | 32/20 | clahe_p3_s55 | 3.24 |
| dense | 25/19 | 26/20 | clahe_p2_s55 | 7.81 |
| gray20 | 20/20 | 20/20 | clahe_p3_s55 | 8.62 |
| gray42 | 42/42 | 42/42 | clahe_p2_s90 | 5.85 |
| paired40 | 40/40 | 40/40 | clahe_p3_s75 | 3.56 |
| spaced | 19/17 | 19/18 | clahe_p3_s55 | 5.22 |

A cena gray20 teve KS acima de 0,25 nas opções válidas: a busca manteve as 20 elipses apoiadas pelas bordas, sem tentar forçar uma curva Weibull. O aumento do número de elipses válidas em algumas cenas é resultado do critério interno e não comprova maior recall ou precisão.

## Geometria com verdade-terreno simples

Três cenas artificiais de 800×600 px, cada uma com 20 elipses conhecidas, gradiente de iluminação e contraste claro/escuro/baixo. Correspondência por centro, raio de tolerância 15 px, um a um. Atual e sugerido recuperaram respectivamente 19, 19 e 20 centros, sem falsos candidatos adicionais nesses três casos. As imagens e analytic-lighting.csv estão anexas. Figuras simples sem poros ou sobreposição não representam usina; não provam generalização.

Ensaios JNI adicionais: entrada uniforme sem pelotas e com aviso de pouca evidência; cancelamento parando após a opção atual; orçamento zero parando entre opções; falhas de todas as opções explícitas, sem fabricar resultado. Arquivos native-results.log e limits-results.log contêm resultados; códigos de reprodução em native_check.java (classe NativeTuneCheck) e NativeSearchLimits.java. Para reproduzir, compile as classes Java sem Android de app/src/main/java e esses arquivos com OpenPnP opencv-4.9.0-0.jar; execute NativeTuneCheck com a raiz do projeto e NativeSearchLimits com o diretório de evidências como argumento.

## Build e integridade

assembleDebug, testDebugUnitTest, lintDebug concluídos. Lint: zero erros, 70 avisos (internacionalização, acessibilidade, orientação, alocações em desenho e outros já presentes); detalhes em lint.txt. APK ARM64, Android 10+, versão 0.8.0, código 10. Mesmo certificado de desenvolvimento da 0.7.0, assinatura e alinhamento ZIP de 16 KiB aprovados. O classificador de trincas mantém o hash anterior. artifact_hashes.json, apk-signature.txt, apk-manifest.txt e apk-alignment.txt registram integridade.

Sem teste em telefone físico, desempenho da câmera/Galeria reais, ensaio metrológico, validação de acurácia em usina ou build iOS. Durações de desktop não estimam a latência do celular. Consultar README para operação e limitações; evidências anteriores preservadas em reference-0.7.0 e ellipse-0.6.0.

Referência conceitual primária: NIST, Particle Size Characterization, SP 960-1, https://nvlpubs.nist.gov/nistpubs/Legacy/SP/nistspecialpublication960-1.pdf. O uso fraco do modelo de distribuição e os pesos acima são escolhas experimentais deste projeto, não recomendações dessa publicação.
