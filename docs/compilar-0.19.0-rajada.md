# PelotaVision 0.19.0 (Android) / 0.11.0 (Windows) — rajada (burst) e SR 2× multiframe

Branch: `feature/rajada-burst-0.19.0` (base: `versao/0.18.0-relatorio-roi-auto`, PR #34).
Detalhes técnicos e números: [`docs/rajada-burst.md`](rajada-burst.md). Evidências:
`verification/burst-0.19.0/`.

## O que muda
- Núcleo (`core/`): `BurstAlign`, `BurstDiagnostics`, `BurstFuse`, `BurstSr` e `BurstStack`, iguais
  no Windows e no Android.
- Windows:
  - botão **Rajada (várias fotos)…** no painel de aquisição;
  - `BurstCli` + `RAJADA.bat` para rajada em lote;
  - exportação com `_rajada_empilhada.png`, `_rajada_quadro_XX.*`, `_rajada_sr2x.png` (se houver) e o
    bloco `burst` no JSON (`source.type = burst_median`).
- Android:
  - **Abrir foto** aceita seleção múltipla: com 2+ fotos, abre a rajada (laudo + empilhada);
  - SR 2× desligada nesta versão;
  - o JSON ganha `burst` e `source_type = burst_median`.
- Nada muda para quem abre ou fotografa uma foto só.

## Compilar localmente (PowerShell)

```powershell
cd D:\Projetos\PelotaVision
git fetch origin
git checkout feature/rajada-burst-0.19.0
git pull
.\COMPILAR_PELOTAVISION.bat                     # testes (inclui BurstDiagnosticsTest, BurstFuseTest, BurstSrTest) + JAR Windows 0.11.0
.\COMPILAR_PELOTAVISION.bat -Platform Android   # testes, lint e APK debug 0.19.0
```

Não usar `tools\windows\compilar_tudo.bat`.

**Atenção:** o código desta branch foi testado com OpenCV 4.9 (Linux) e com checagem de tipos contra o
framework Android 15 e o OpenCV Android 4.12. A compilação Gradle completa (Android + Windows com
OpenCV 4.12) **não** rodou neste ambiente e é o primeiro passo local.

## O que conferir na primeira execução
1. Windows → **Rajada** → **Escolher fotos e analisar…** com as 12 fotos de uma rajada simulada:
   ```powershell
   python tools\burst_validate\simular_rajada.py --foto ml_training\data_bench_20261026\images\IMG_20261026_134344.jpg --saida $env:TEMP\sim --n 12 --tremor 0.03 --ruido 3 --grade
   ```
   - Deve aparecer `SR_2X_PLAUSIVEL`.
   - Com "Tentar SR 2×" marcado, deve aparecer "SR 2× aceita".
   - "Abrir empilhada" segmenta a imagem como uma foto comum.
2. Com a webcam, câmera parada, foco e exposição travados: **Capturar da câmera e analisar** com N = 8.
   Espera-se `SOMENTE_MEDIANA` (sem tremor não há fases novas) e ruído medido caindo ~3×.
3. Salvar e conferir na pasta:
   - os 8 `_rajada_quadro_XX.png`;
   - `_rajada_empilhada.png`;
   - no JSON: `burst.verdict`, `burst.frames`, `burst.used_for_measurement = true` e
     `source.type = burst_median`.
4. Android: **Abrir foto** → selecionar 4 a 8 fotos da galeria tiradas em sequência da mesma cena. O
   diálogo do laudo aparece e a empilhada é analisada. Salvar grava os quadros.
5. `RAJADA.bat --saida ..\PelotaVision-local\results\rajada-teste --sr <pasta com fotos>` gera
   `laudo.txt` e `rajada.json`.
