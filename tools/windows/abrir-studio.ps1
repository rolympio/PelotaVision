$ErrorActionPreference = 'Stop'
try {
    $repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
    $root = Join-Path (Split-Path $repo -Parent) 'PelotaVision-local'
    $configFile = Join-Path $repo '.pelotavision.local.json'
    if (Test-Path $configFile) {
        $config = Get-Content $configFile -Raw | ConvertFrom-Json
        if ($config.LocalRoot) { $root = $config.LocalRoot }
    }
    $package = Get-Content (Join-Path $root 'latest-package.json') -Raw | ConvertFrom-Json
    if (!$package.packaged_gui_test -or !(Test-Path -LiteralPath $package.executable -PathType Leaf)) {
        throw 'Nao ha pacote com ensaio GUI aprovado. Execute COMPILAR_PELOTAVISION.bat -Package.'
    }
    Start-Process -FilePath $package.executable -WorkingDirectory (Split-Path $package.executable -Parent)
} catch { Write-Output $_.Exception.Message; exit 1 }
