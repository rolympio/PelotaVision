# Auditoria — branch `feature/granulometric-color-map` (25/10/2026)

## 1. Integridade antes da correção

Base: `origin/main` = `7242b93`. Comparação `git diff --stat origin/main origin/feature/granulometric-color-map`:

| Arquivo | Situação | Veredito |
|---|---|---|
| `app/src/main/java/br/com/pelotavision/GranulometricColorMap.java` | novo, 187 linhas (`3c041f5`) | íntegro; todos os campos do núcleo que usa existem |
| `app/src/main/java/br/com/pelotavision/MainActivity.java` | **215 linhas, contra 844 na main** (−656/+28) | **corrompido** |
| demais arquivos | idênticos à main | sem alteração indevida |

Histórico do dano no `MainActivity.java`:

| Commit | Mensagem | Linhas do arquivo |
|---|---|---|
| `3c041f5` | add GranulometricColorMap | 844 (intacto) |
| `f4ff0f2` | integrate … with toggle button | **0**: o arquivo foi esvaziado (−844 +1) |
| `a1ba8d4` | "restore full MainActivity" | 0, apesar da mensagem |
| `1d7538b` | full MainActivity integration | 128 |
| `6c637d8` | integrate … (full file) | 215, com comentários no lugar do código real ("The full patched source is available locally…") |

A branch não compilaria. `buildInterface`, `saveResult`, a análise, a ROI, a calibração e as trincas tinham desaparecido da Activity.

## 2. Correções

1. **`c01929b`**: `MainActivity.java` restaurado byte a byte de `origin/main` (`git checkout origin/main -- …`). Depois disso, a árvore difere da main apenas em `GranulometricColorMap.java`.
2. **`62a4376`**: integração pedida, a partir do arquivo `MainActivity_patched.java` enviado. Ele foi conferido por diff: é exatamente a main mais os ganchos, com +22/−1 linhas:
   - campos `colorMap` e `colorMapVisible`;
   - view adicionada logo após o `overlay` em `buildInterface()`;
   - botão **Mapa** depois do checkbox **Números**;
   - atualização nos dois ramos de `refreshDistribution()` (sem escala, em px; com escala, em mm);
   - `toggleColorMap()`.

   Os textos dos toasts seguem o arquivo enviado, que são um pouco mais longos que os do prompt.
3. **`74c38c0`**: correção de estado de visibilidade, em commit separado e reversível. Na main, nada volta o `overlay` para `VISIBLE`, porque ele é visível por padrão. Com o patch puro, ao ativar o Mapa:
   - voltar para a câmera deixava o overlay oculto e o mapa da foto anterior desenhado sobre a imagem ao vivo;
   - mudar a ROI deixava o mapa velho na tela até a nova segmentação;
   - tocar em Mapa sem foto analisada deixava o estado "ligado" com a mensagem "Visualização original".

   O helper `hideColorMap()` resolve os três casos sem tocar em medição, calibração, ROI ou trincas.

## 3. Verificação

- **Sintaxe:** os 71 arquivos Java (`app`, `core`, `desktop`) foram analisados pelo parser do javac (OpenJDK 21), com 0 erros.
- **Símbolos dos ganchos conferidos manualmente:**
  - em `MainActivity`: `button(String,boolean)`, `dp(float)`, `toast(String)`, `overlay`, `displayWindow` (`AnalysisWindow`), `photoResult` (`PelletDetector.Result`) e `distribution` (`Granulometry.Distribution`);
  - usados por `GranulometricColorMap`: `Detection.semiMajor/semiMinor/angleDeg/partial`, `Distribution.minMm/maxMm/included/diameterMm()/settings.unit()` e `AnalysisWindow.left/top/width/height`.
- **Comportamento preservado:** os ganchos não alteram `PelletDetector`, `Granulometry`, a calibração, a ROI, as trincas nem a exportação. O PNG, o CSV e o JSON não incluem o mapa.
- **Compilação local: não foi possível neste ambiente.**
  - `./gradlew assembleDebug`: o wrapper não consegue baixar o Gradle 8.9 (`services.gradle.org` bloqueado). Também faltam o Android SDK 35 e o OpenCV Android 4.12.
  - `gradle -p desktop compileJava` (Gradle 8.14.3 do sistema): `Could not resolve org.json:json:20240303 … 403 Forbidden` (Maven Central bloqueado). Também falta o `opencv-4120.jar`.
  - Os builds reais ficam a cargo dos workflows `android.yml` (APK) e `windows.yml` (pacote portátil), que rodam no Pull Request.

## 4. Limitações do mapa (não corrigidas; decisão de produto)

- Com o mapa ativo, o overlay fica oculto. Tocar numa pelota não abre a revisão de trinca nem a calibração; é preciso desligar o Mapa antes.
- `isIncluded()` usa só raio válido e "não parcial". A granulometria pode excluir também objetos em revisão (`needsReview`), conforme a configuração da escala. Assim, objetos fora do histograma podem receber cor; a escala de cores é limitada ao mínimo e máximo da distribuição.
- A conversão para mm é consistente com o histograma: ambos usam `d.radius*2` no espaço de `photoResult` e o mesmo `analyzedPixelsPerMm` (conferido em `measurementItems()` e `Granulometry.compute`).
- O mapa não entra no relatório exportado.
