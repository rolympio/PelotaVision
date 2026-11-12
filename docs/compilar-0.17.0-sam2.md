# PelotaVision com SAM 2 — Android 0.17.0 / Windows 0.9.0

Branch `integracao/0.17.0-sam2` = `main` (0.16.0) + #27 + #28 + `a918c6e` + #30 + #31 + SAM 2.
Fora: #29 (só a ideia do SAM 2 foi reaproveitada, reescrita no app), #26, #11 e o Play (#18).

## O que o SAM 2 faz no app

1. O método escolhido (Bordas, LoG ou Rede neural StarDist) localiza as pelotas, como antes.
2. Cada pelota vira um prompt **caixa + ponto central** para o **SAM 2.1 Hiera Tiny** (Meta, Apache-2.0).
3. A máscara devolvida define o tamanho: elipse de **mesmos momentos e mesma área** da máscara.
   Tamanho médio, D10/D50/D90, histogramas, mapa granulométrico, Rosin–Rammler, PDF e JSON passam a usar essa área.
4. Máscara ruim (IoU previsto < 0,5, área fora de 0,35–2,8× a detecção, vazamento para fora da janela)
   → a pelota mantém a elipse do detector e entra na contagem "mantidas". IoU entre 0,5 e 0,8 → aceita, marcada para revisão.
5. Vale para a análise da foto (rápida, sugestão aplicada e detalhada). **Não roda no ao vivo** (alguns segundos por foto).

Rastreabilidade: `sam2_refinement` no JSON (modelo, SHA-256 do checkpoint e dos ONNX, blocos, pelotas refinadas/mantidas, tempos).

## Paridade Windows × Android

Mesmo código (`core/Sam2Refiner.java`), mesmo ONNX e mesmo motor (**ONNX Runtime 1.31.1, CPU**) nas duas plataformas.
A GPU (CUDA) é opcional e só existe no Windows, com a aceleração ligada. A exportação é conferida contra o
`SAM2ImagePredictor` oficial (`tools/sam2/verificar_sam2_onnx.py`: embeddings idênticos, IoU das máscaras = 1,0).
Isso garante o mesmo algoritmo, não resultados idênticos bit a bit: a decodificação JPEG e o redimensionamento
da foto antes da análise ainda diferem entre Android e Windows (já era assim na 0.16.0).

## Compilar tudo (Windows, uma vez por versão)

Pré-requisitos: Git, **JDK 17** (`JAVA_HOME`), OpenCV Java 4.12.0 (`C:\Projetos\tools\opencv-4.12.0` já é encontrado),
Android SDK 35 + build-tools 35.0.0 (`C:\Projetos\tools\android-sdk` já é encontrado) e **Python 3.10–3.12**
(só na primeira vez, para exportar o SAM 2).

```bat
cd C:\Projetos\PelotaVision
git fetch origin
git checkout integracao/0.17.0-sam2
COMPILAR_PELOTAVISION_COMPLETO.bat
```

Na primeira execução o script:
- baixa o checkpoint oficial `sam2.1_hiera_tiny.pt` (~156 MB) e confere o SHA-256
  `7402e0d8…be69`. Se o arquivo já estiver em `..\PelotaVision-local\models\sam2\` (local padrão), não baixa de novo;
  também reaproveita uma cópia em `Downloads` ou em `worktrees\PelotaVision-mascaras`;
- cria uma venv isolada em `..\PelotaVision-local\envs\sam2-export` (PyTorch CPU ~200 MB, nada global);
- exporta `sam2_encoder.onnx` (~109 MB), `sam2_decoder.onnx` (~16 MB) e `sam2.json` para `..\PelotaVision-local\models\sam2\`;
- confere os ONNX contra o PyTorch oficial e copia os três arquivos para `app\src\main\assets\` (ignorado pelo Git).

Depois: testes + pacote Windows (`jpackage`, autoteste exige `sam2_run=` no log) + testes, lint e APK Android.

Instalador: se o Inno Setup 6 estiver instalado (`winget install --id JRSoftware.InnoSetup -e`), a compilação também
gera `PelotaVision_Windows_<versão>_instalador.exe` a partir da mesma pasta que passou no autoteste. Para gerar só o
instalador do último pacote testado, sem recompilar: `.\GERAR_INSTALADOR.bat`.

Opções: `-SemSam2` (pacote sem o modelo), `-Platform Windows|Android|Ambos`, `-LocalRoot <pasta>`.

## Onde ficam os arquivos

| Item | Local |
|---|---|
| Executável Windows | `C:\Projetos\PelotaVision-local\results\windows-<data>\dist\PelotaVision\PelotaVision.exe` |
| Instalador Windows (por usuário, sem admin) | `C:\Projetos\PelotaVision-local\results\windows-<data>\PelotaVision_Windows_0.9.0_instalador.exe` — só se o Inno Setup 6 estiver instalado |
| Zip portátil + SHA-256 | `C:\Projetos\PelotaVision-local\results\windows-<data>\PelotaVision_Studio_Windows_0.9.0.zip` e `SHA256.txt` |
| APK celular | `C:\Projetos\PelotaVision-local\results\android-<data>\app-arm64-v8a-debug.apk` (+ `SHA256.txt`) |
| Modelo SAM 2 | `C:\Projetos\PelotaVision-local\models\sam2\` |
| Logs | `C:\Projetos\PelotaVision-local\logs\` |

O APK fica com ~125 MB a mais (modelo embutido). Alternativa: compile com `-SemSam2` e copie os três arquivos
por USB para `Android/data/br.com.pelotavision/files/modelos/`; o app procura ali quando o modelo não está no APK.
Para atualizar por cima da versão instalada, use a mesma chave `signing/prototype.keystore` de antes.

## Onde ligar e desligar

- Windows: caixa **"Mascaras SAM 2 (tamanho pela area)"** em Segmentação (ligada por padrão). Estado em "Estado…".
- Android: **Ajustes de detecção → Máscaras SAM 2**.

## Limites (não esconder)

- SAM 2.1 Tiny **sem ajuste fino em pelotas**: segmenta bem objetos com borda clara, mas pelotas encostadas e com
  baixo contraste podem fundir ou encolher. O detector continua decidindo **quantas** pelotas há.
- Medida 2D projetada: não é peneiramento nem esfericidade 3D.
- Sem validação metrológica independente. Compare com o detector (desligando a caixa) antes de usar em decisão.
- Memória: pico de ~1,1 GB no encoder (medido em PC x86 com ONNX Runtime CPU; no celular não foi medido).
  Tempo: ~3–5 s por bloco de 1024 px num PC de 2 núcleos; no celular, espere mais. Em aparelhos com pouca RAM, desligue o SAM 2.
