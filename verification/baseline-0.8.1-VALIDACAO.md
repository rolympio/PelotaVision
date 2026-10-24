# Validação — PelotaVision Android 0.8.1

19/10/2026. Correção da sugestão inicial: 24 combinações de quatro perfis, três sensibilidades e dois métodos (bordas e LoG), mais o ajuste atual se distinto. LoG médio/75% entra logo depois do atual. O detector, o refinador de elipses e o classificador de trincas permanecem iguais aos da 0.8.0.

## Critérios e preservação das regras

Com evidência de imagem disponível, pontua cobertura da união de marcadores sobre o primeiro plano, cobertura por elipses válidas, qualidade ponderada por área, concordância e pureza. Sobreposições, limite de candidatos e objetos válidos abaixo do mínimo calibrado são penalizados. Duplicações não multiplicam a cobertura e o total de marcações não recebe um prêmio direto. Qualidade e concordância são calculadas na foto completa; cobertura e elegibilidade respeitam ROI e referência excluída. O plano de iluminação é compensado quando a tendência de fundo é forte. Contraste/polaridade insuficientes mantêm o escore geométrico anterior. Cobertura de pixels não é recall de objetos nem acurácia.

Rosin–Rammler/Weibull é um critério auxiliar por número (n≥12), não uma distribuição normal. Não força média, faixa ou diâmetros. O mínimo esperado de 5 mm só pontua com escala física externa independente, e não apaga marcações. Sem calibração, a comparação ocorre em pixels. ROI/calibração recalculam recomendação sem trocar os contornos; outra opção exige aplicação explícita e conserva as regras anteriores de invalidação da referência e revisões. Orçamento de 30 s é verificado entre operações, sem interromper JNI em andamento. A triagem de trincas só ocorre na opção aplicada. CSV mantém 32 colunas e o compositor PNG anterior permanece igual.

## Foto padrão enviada

No arquivo original de 1536×865 px: automático 0.8.0 = 68 propostas/52 elipses válidas; manual LoG médio/74% = 122/47; automático 0.8.1 = 125/47, LoG médio/90%. As 122 marcações de referência tiveram correspondência um a um (distância de centros ≤0,35×soma dos raios; razão de raios 0,7–1,43). As três propostas adicionais são inconclusivas, duas parciais na borda. Não há verdade-terreno validada. Os prints do celular mostram 80/124 e não são os mesmos resultados dessa reprodução. A imagem comparativa é uma visualização diagnóstica das geometrias executadas sobre o arquivo real, não um print do aplicativo em telefone.

Também se repetiu o ensaio com largura de 600 px: antigo automático 59, manual 123, novo 126. A biblioteca JNI usada no desktop é OpenCV 4.9 (OpenPnP); o app embarca Android OpenCV 4.12. Resolução, codificação e versão podem alterar resultados. Dados, referência e códigos estão em coverage-0.8.1.

## Testes e regressões

56 testes Robolectric, zero falhas/erros: os 50 anteriores e seis de cobertura, união sem duplicação, ROI/referência excluída, ordem/opções da busca, fallback e compensação de plano. Os testes de UI e exportação preservam o fluxo de calibração, histograma, classificação, região e forma. JNI Android não é executado por Robolectric.

Execução JNI nas sete cenas experimentais anteriores (incluem imagens sintéticas/de procedência não verificada). Não representam acurácia industrial. Orçamento ampliado para 120 s neste ensaio, garantindo 24 opções por cena; duas cenas ultrapassaram 30 s. Em produção a busca pode avaliar menos opções no limite de tempo.

| Cena | Propostas/válidas | Opção | Busca (s) |
|---|---:|---|---:|
| gray20 | 20/20 | clahe_p3_s55 | 30.91 |
| brown40 | 40/38 | clahe_p3_s90 | 23.93 |
| dense | 33/20 | log_p2_s90 | 24.75 |
| gray42 | 42/42 | clahe_p2_s75 | 30.92 |
| spaced | 19/18 | clahe_p3_s55 | 22.18 |
| cluster | 35/19 | log_p2_s90 | 18.53 |
| paired40 | 40/40 | clahe_p2_s90 | 13.67 |

Três cenas analíticas de 800×600 px com 20 elipses conhecidas cada, iluminação clara/escura e baixo contraste com gradiente: centros recuperados pelo ajuste atual 19/19/20 e pelo sugerido 20/19/20, sem propostas adicionais nesses casos. Correspondência um a um, tolerância de centro 15 px. O terceiro cenário detectou uma regressão durante o desenvolvimento; a compensação do plano de iluminação a resolveu antes do build final. São formas simples sem poros/sobreposição e não demonstram generalização industrial. Cancelamento, orçamento zero e falha explícita de todas as opções também passaram. Logs e CSVs finais anexos.

## Build e integridade

assembleDebug, testDebugUnitTest e lintDebug concluídos. Lint: zero erros, 70 avisos, disponíveis em coverage-0.8.1/lint.txt. APK Android 10+ ARM64, versão 0.8.1, código 11; certificado de desenvolvimento preservado, assinatura e alinhamento ZIP de 16 KiB aprovados. Fontes de produção conferidas byte a byte com o projeto compilado. Hash do classificador preservado. Evidências: artifact_hashes.json, apk-signature.txt, apk-manifest.txt, apk-alignment.txt, gradle.log e XMLs dos 56 testes.

Sem ensaio em telefone físico nesta versão, validação metrológica, validação industrial ou novo build iOS. Tempos de desktop não estimam a latência do celular. Evidências e validação da 0.8.0 permanecem em autotune-0.8.0; versões anteriores também foram preservadas.
