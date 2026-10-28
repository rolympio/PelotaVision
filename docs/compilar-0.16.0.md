# Compilar a 0.16.0 integrada (Android 0.16.0 · Windows 0.6.0)

Branch **`integracao/0.16.0`**. Ela reúne tudo o que ainda não estava na `main`:

| Origem | O que traz |
|---|---|
| `feature/0.16-webcam-trincas-camera-aovivo` (PR #24) | trincas v3, StarDist de bancada real, câmera USB/OTG, ao vivo completo, busca rápida |
| `feature/aceleracao-hardware` | aceleração por hardware: ONNX Runtime (CUDA/CPU) no Windows, LiteRT (GPU) no Android |
| `copilot/windows-installer-20261028` | desempenho do Windows (junção de blocos com índice espacial, consenso temporal) |

Os `.tflite` do Android foram **reconvertidos dos modelos novos** (o app recusa um `.tflite` convertido de outro ONNX). Resultado em `verification/litert-0.16.0/`.

## Baixar
```bat
git clone --depth 1 -b integracao/0.16.0 https://github.com/Rolympio/pelotavision pelotavision-0.16
cd pelotavision-0.16
```
Se a conexão cair no meio, repita; o pacote tem ~270 MB.

## Android
```bat
echo sdk.dir=C\:\\Users\\rolym\\AppData\\Local\\Android\\Sdk> local.properties
gradlew.bat assembleDebug testDebugUnitTest lintDebug --stacktrace
```
- Precisa de `platforms;android-35` e `build-tools;35.0.0` (já instalados na sua máquina).
- APK: `app\build\outputs\apk\debug\app-arm64-v8a-debug.apk`.
- **Aceleração:** Ajustes → "Aceleração por hardware". O texto abaixo da caixa mostra o motor de cada rede e o tempo da sonda.
- **Câmera:** toque no rótulo abaixo da imagem; "Diagnóstico USB" com a câmera OTG conectada.

## Windows
1. OpenCV 4.12.0 para Windows extraído em `C:\opencv-4.12.0`, depois:
   ```bat
   mkdir desktop\opencv-java\x64
   copy C:\opencv-4.12.0\opencv\build\java\opencv-4120.jar desktop\opencv-java\
   copy C:\opencv-4.12.0\opencv\build\java\x64\opencv_java4120.dll desktop\opencv-java\x64\
   ```
2. Compilar e testar (JDK 17):
   ```bat
   gradlew.bat --stop
   gradlew.bat -p desktop test jar runtimeLibs --no-daemon --stacktrace
   ```
   O primeiro build baixa o ONNX Runtime GPU (~250 MB).
3. **Aceleração:** barra "Modelos de analise" → **Aceleracao GPU**; **Estado…** mostra o motor de cada rede. Com GPU NVIDIA, precisa de CUDA e cuDNN instalados no Windows (ver `docs/aceleracao-hardware.md`); sem eles, roda no ONNX Runtime em CPU.
4. Empacotar: seção 5 do prompt de compilação, com `--app-version 0.6.0` e `/DAppVersion=0.6.0`.

## Testes que só rodam aí
- `HardwareAccelerationTest` (Windows): precisa do ONNX Runtime real; aqui foi compilado contra interfaces mínimas.
- Testes Android (Robolectric) e lint.
- Os demais testes do desktop passaram neste ambiente com OpenCV 4.12 nativo.
