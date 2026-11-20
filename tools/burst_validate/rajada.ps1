# Rajada em lote (BurstCli) com o pacote Windows aprovado ou com o build de desenvolvimento.
# Uso: RAJADA.bat --saida PASTA [--sr] [--roi x,y,w,h] [--parada] FOTOS... | PASTA_DE_FOTOS
# Nao altera as fotos de entrada. Saida: laudo.txt, rajada.json, rajada_empilhada.png, rajada_sr2x.png (se houver).
$ErrorActionPreference = 'Stop'
try {
    $repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
    $root = Join-Path (Split-Path $repo -Parent) 'PelotaVision-local'
    $configFile = Join-Path $repo '.pelotavision.local.json'
    if (Test-Path $configFile) {
        $config = Get-Content $configFile -Raw | ConvertFrom-Json
        if ($config.LocalRoot) { $root = $config.LocalRoot }
    }
    $java = $null; $cp = $null; $lib = $null
    $latest = Join-Path $root 'latest-package.json'
    if (Test-Path $latest) {
        $package = Get-Content $latest -Raw | ConvertFrom-Json
        $image = Split-Path $package.executable -Parent
        if ((Test-Path "$image\runtime\bin\java.exe") -and (Test-Path "$image\app\pelotavision-desktop.jar")) {
            $java = "$image\runtime\bin\java.exe"; $cp = "$image\app\*"; $lib = "$image\app"
        }
    }
    if (-not $java) {
        # Build de desenvolvimento: COMPILAR_PELOTAVISION.bat gera o jar e runtime-libs.
        $jar = "$repo\desktop\build\libs\pelotavision-desktop.jar"
        $libs = "$repo\desktop\build\runtime-libs"
        if (!(Test-Path $jar)) { throw 'Nenhum pacote nem build encontrado. Execute COMPILAR_PELOTAVISION.bat.' }
        $ocv = if ($env:OPENCV_JAVA_DIR) { $env:OPENCV_JAVA_DIR } else { "$repo\desktop\opencv-java" }
        $java = 'java'; $cp = "$jar;$libs\*;$ocv\opencv-4120.jar"; $lib = "$ocv\x64"
    }
    & $java -Xmx3g "-Djava.library.path=$lib" -cp $cp br.com.pelotavision.BurstCli @args
    exit $LASTEXITCODE
} catch { Write-Output $_.Exception.Message; exit 1 }
