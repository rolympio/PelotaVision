# Verificação da atualização 0.2.0 — 10/10/2026

## Concluído nesta atualização

- `assembleDebug` e `lintDebug`: sem erros. Há avisos sobre orientação fixa,
  tradução das strings, alocação no desenho do gráfico e política de backup.
- APK Android 10+ ARM64: versão 0.2.0 / versionCode 2.
- Assinatura do APK conferida; alinhamento `zipalign -c -P 16 4` aprovado.
- Teste JVM 17 em `GranulometryCheck.java`: conversão original/redimensionada
  de px/mm, diâmetros em mm, limites das classes, exclusões de bordas/alertas,
  percentuais somando 100%, D10/D50/D90, distribuição vazia e entradas inválidas.
- Nova assinatura de protótipo preservada em `signing/prototype.keystore`.
  A assinatura da primeira entrega não estava no projeto recuperado.

## Limites de execução

A tela de calibração, o gráfico, a câmera e o salvamento em MediaStore não
foram executados em um celular físico ou emulador nesta atualização. Os testes
matemáticos não comprovam o funcionamento da UI nem a precisão metrológica.
O registro está em `runtime-results.json`.

A tentativa de emulador da versão 0.1.0 ficou incompleta no passo de instalação;
o registro original foi preservado em `baseline-0.1.0-runtime-results.json`.
Os testes do detector e suas contagens (129, 46 e 240) são da entrega anterior,
com JNI de desktop OpenCV 4.9. O detector não foi alterado nesta atualização;
esses resultados não foram reexecutados e não são contagens de referência.
O APK continua usando OpenCV Android 4.12.

## Conferência no celular

1. Se a versão anterior estiver instalada, desinstalá-la antes de instalar
   o novo APK devido à mudança de assinatura. Fotos não salvas e preferências
   do app serão perdidas. Arquivos já exportados ficam nas pastas públicas,
   salvo se o usuário também optar por removê-los.
2. Fotografar ou abrir uma imagem. Conferir os círculos e a orientação.
3. Tocar em **Escala / mm**. Informar px/mm da imagem ORIGINAL orientada,
   cujas dimensões aparecem na tela; não usar pixels da captura da tela.
   Exemplo: referência de 12 mm ocupa 192 px no original → 16 px/mm.
4. Escolher a largura da classe, por exemplo 1 mm. Conferir as opções de
   exclusão e o n incluído/excluído no gráfico.
5. Conferir a conversão com uma medida conhecida no mesmo plano da amostra.
   Alterar a detecção deve preservar a calibração na mesma foto; abrir outra
   foto deve remover a escala para exigir nova calibração.
6. Salvar foto + dados. Conferir original e PNG com gráfico em Galeria /
   PelotaVision e CSV/JSON em Downloads / PelotaVision. O CSV de granulometria
   é adicional quando existe calibração.
7. Confirmar que os círculos continuam no painel superior da foto; o gráfico
   ocupa um rodapé adicional do PNG. Coordenadas não incluem esse rodapé.
8. Voltar à câmera e confirmar funcionamento da prévia e captura.

Distribuição por NÚMERO, não por massa. Diâmetro equivalente à área projetada
visível. Perspectiva, profundidade e oclusão podem afetar os resultados mesmo
com px/mm correto. Não há comparação com peneiramento, métricas de precisão
ou validação em minério em movimento.

## Repetir o teste matemático

```bash
mkdir -p verification/classes
javac -encoding UTF-8 -d verification/classes \
  app/src/main/java/br/com/pelotavision/Granulometry.java \
  verification/GranulometryCheck.java
java -cp verification/classes GranulometryCheck
```

## Repetir o teste anterior do detector no desktop

Obter `org.openpnp:opencv:4.9.0-0` como `opencv-host.jar` em uma pasta externa.
O JAR contém JNI desktop e não integra o aplicativo Android.

```bash
javac -encoding UTF-8 -cp /caminho/opencv-host.jar -d verification/classes \
  app/src/main/java/br/com/pelotavision/PelletDetector.java \
  verification/DetectorCheck.java
java -cp /caminho/opencv-host.jar:verification/classes \
  DetectorCheck verification/fixtures/1000411645.jpg \
  verification/fixtures/1000411646.jpg verification/fixtures/1000411647.jpg
```

No Windows, trocar `:` por `;` no separador do classpath.
