[CmdletBinding()]
param(
    [ValidateSet('Instalar','Atualizar','Verificar','Compilar','Sincronizar')]
    [string]$Action = 'Verificar',
    [string]$LocalRoot,
    [string]$DataSource,
    [string]$OpenCvJava,
    [ValidateSet('Windows','Android')][string]$Platform = 'Windows',
    [switch]$PrepareOnly,
    [switch]$CheckRemote,
    [switch]$CopyData,
    [switch]$Package,
    [switch]$DryRun
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
# Git escreve UTF-8; o PowerShell 5.1 pode decodificar stdout como pagina OEM.
# Ajuste limitado a este processo, necessario para caminhos com acentos.
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = $OutputEncoding
. "$PSScriptRoot\seguranca.ps1"
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$configFile = Join-Path $repo '.pelotavision.local.json'
if (Test-Path -LiteralPath $configFile) {
    $config = Get-Content -LiteralPath $configFile -Raw | ConvertFrom-Json
    foreach ($name in @('LocalRoot','DataSource','OpenCvJava')) {
        if (!$PSBoundParameters.ContainsKey($name) -and $config.PSObject.Properties[$name]) {
            Set-Variable -Name $name -Value ([string]$config.$name)
        }
    }
}
if (!$LocalRoot) { $LocalRoot = Join-Path (Split-Path $repo -Parent) 'PelotaVision-local' }
$LocalRoot = [IO.Path]::GetFullPath($LocalRoot)
if (!$DataSource) { $DataSource = "$env:USERPROFILE\OneDrive\Documentos\PelotaVision" }
$log = $null
$transcribing = $false
$env:GIT_TERMINAL_PROMPT = '0'

function Invoke-Git([string[]]$Arguments) {
    # Windows PowerShell 5.1 transforma stderr nativo em ErrorRecord mesmo com exit=0.
    $oldPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& git -c credential.interactive=never -C $repo @Arguments 2>&1)
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $oldPreference }
    if ($code -ne 0) { throw "Git falhou: $($output -join ' ')" }
    return @($output | ForEach-Object { "$_" })
}

function Assert-Repo {
    if (!(Get-Command git -ErrorAction SilentlyContinue)) { throw 'Instale Git for Windows.' }
    $top = [string](Invoke-Git @('rev-parse','--show-toplevel'))
    if ([IO.Path]::GetFullPath($top) -ne $repo) { throw 'A pasta nao e a raiz do repositorio esperado.' }
    Assert-OfficialRemote ([string](Invoke-Git @('remote','get-url','origin')))
    # Verifica tambem pushurl sem alterar credenciais nem configuracao global.
    foreach ($url in (Invoke-Git @('remote','get-url','--push','--all','origin'))) { Assert-OfficialRemote ([string]$url) }
    Assert-OutsideOneDrive $repo
    Assert-NoReparseAncestors $repo
}

function Find-OpenCv {
    $candidates = @($OpenCvJava, $env:OPENCV_JAVA_DIR, "$repo\desktop\opencv-java",
        'C:\opencv-4.12.0\opencv\build\java', 'C:\opencv\build\java')
    foreach ($dir in $candidates) {
        if ($dir -and (Test-Path -LiteralPath "$dir\opencv-4120.jar") -and
            (Test-Path -LiteralPath "$dir\x64\opencv_java4120.dll")) { return [IO.Path]::GetFullPath($dir) }
    }
    return $null
}

function Get-Jdk {
    if (!$env:JAVA_HOME -or !(Test-Path -LiteralPath "$env:JAVA_HOME\bin\javac.exe")) {
        throw 'Defina JAVA_HOME para um JDK 17 completo; nao apenas um JRE.'
    }
    $version = & "$env:JAVA_HOME\bin\javac.exe" -version 2>&1
    if ($LASTEXITCODE -ne 0 -or "$version" -notmatch 'javac 17\.') { throw 'Este projeto requer JDK 17.' }
    return "$version"
}

function Find-AndroidSdk {
    foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk")) {
        if ($sdk -and (Test-Path -LiteralPath "$sdk\platforms\android-35\android.jar") -and
            (Test-Path -LiteralPath "$sdk\build-tools\35.0.0\aapt2.exe")) { return $sdk }
    }
    return $null
}

function Invoke-Gradle([string[]]$Arguments) {
    Push-Location $repo
    try {
        & "$repo\gradlew.bat" @Arguments --no-daemon --stacktrace
        if ($LASTEXITCODE -ne 0) { throw "Gradle falhou (codigo $LASTEXITCODE). Consulte o log; nao desative TLS." }
    } finally { Pop-Location }
}

function Invoke-Build {
    Write-Output (Get-Jdk)
    if ($Platform -eq 'Windows') {
        $ocv = Find-OpenCv
        if (!$ocv) { throw 'Falta OpenCV Java 4.12.0 (jar + DLL x64). Use -OpenCvJava ou OPENCV_JAVA_DIR. Consulte docs/ambiente-windows.md.' }
        Invoke-Gradle @('-p', 'desktop', '--init-script', "$repo\tools\windows\isolar-testes.gradle", "-PopencvJava=$ocv", 'test', 'jar', 'runtimeLibs')
        if ($Package) { New-WindowsPackage $ocv }
    } else {
        $sdk = Find-AndroidSdk
        if (!$sdk) {
            throw 'Android requer SDK platform 35 e build-tools 35.0.0. SDK/licencas nao serao instalados silenciosamente.'
        }
        $env:ANDROID_HOME = $sdk
        Invoke-Gradle @('--init-script', "$repo\tools\windows\isolar-testes.gradle", ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug')
    }
    $files = @()
    if ($Platform -eq 'Windows') { $files = @(Get-Item "$repo\desktop\build\libs\pelotavision-desktop.jar") }
    else { $files = @(Get-ChildItem "$repo\app\build\outputs\apk\debug\*.apk") }
    if ($files.Count -eq 0) { throw 'Nenhum artefato gerado.' }
    $record = [ordered]@{ commit = [string](Invoke-Git @('rev-parse','HEAD')); platform = $Platform
        created_utc = [DateTime]::UtcNow.ToString('o'); dirty = (@(Invoke-Git @('status','--porcelain')).Count -gt 0)
        artifacts = @($files | ForEach-Object { @{ name=$_.Name; sha256=(Get-FileHash $_.FullName -Algorithm SHA256).Hash } }) }
    $record | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 (Join-Path $LocalRoot "logs\build-$runId.json")
}

function New-WindowsPackage([string]$Ocv) {
    if (!(Test-Path -LiteralPath "$env:JAVA_HOME\bin\jpackage.exe")) { throw 'JDK sem jpackage.' }
    $out = Join-Path $LocalRoot "results\windows-$runId"
    $inputDir = Join-Path $out 'input'
    New-Item -ItemType Directory -Path "$inputDir\modelos" -ErrorAction Stop | Out-Null
    Copy-Item "$repo\desktop\build\libs\pelotavision-desktop.jar" $inputDir
    Copy-Item "$repo\desktop\build\runtime-libs\*.jar" $inputDir
    Copy-Item "$Ocv\x64\opencv_java4120.dll" $inputDir
    foreach ($dll in @('vcruntime140.dll','vcruntime140_1.dll','msvcp140.dll')) {
        if (Test-Path "$env:JAVA_HOME\bin\$dll") { Copy-Item "$env:JAVA_HOME\bin\$dll" $inputDir }
    }
    Copy-Item "$repo\app\src\main\assets\*" "$inputDir\modelos" -Recurse
    Copy-Item "$repo\third_party" "$inputDir\third_party" -Recurse
    $versionLine = Select-String -LiteralPath "$repo\desktop\build.gradle" -Pattern "^version = '([^']+)'"
    if (!$versionLine) { throw 'Versao Windows nao encontrada.' }
    $version = $versionLine.Matches[0].Groups[1].Value
    & "$env:JAVA_HOME\bin\jpackage.exe" --type app-image --name PelotaVision --app-version $version `
        --input $inputDir --main-jar pelotavision-desktop.jar --main-class br.com.pelotavision.DesktopApp `
        --icon "$repo\desktop\installer\PelotaVision.ico" --java-options '-Xmx3g' --dest "$out\dist"
    if ($LASTEXITCODE -ne 0) { throw 'jpackage falhou; pacote nao aprovado.' }
    Copy-Item "$repo\desktop\LEIA-ME.txt" "$out\dist\PelotaVision"
    $exe = "$out\dist\PelotaVision\PelotaVision.exe"
    $process = Start-Process -FilePath $exe -ArgumentList @('--autoteste', "`"$repo\verification\fixtures\1000411645.jpg`"", "`"$out\autoteste`"") -Wait -PassThru
    if ($process.ExitCode -ne 0 -or !(Test-Path "$out\autoteste\autoteste.txt") -or
        !(Select-String -LiteralPath "$out\autoteste\autoteste.txt" -Pattern '^exit=0$' -Quiet)) {
        throw 'Autoteste do executavel falhou; pacote nao aprovado.'
    }
    Write-Output "Pacote Windows testado: $exe"
}

try {
    Assert-Repo
    Assert-OutsideOneDrive $LocalRoot
    Assert-NoReparseAncestors $LocalRoot
    if ((Test-WithinPath $LocalRoot $repo) -or (Test-WithinPath $repo $LocalRoot) -or
        (Test-WithinPath $LocalRoot $DataSource) -or (Test-WithinPath $DataSource $LocalRoot)) {
        throw 'Codigo, dados fonte e area local precisam de arvores separadas.'
    }
    $branch = [string](Invoke-Git @('branch','--show-current'))
    $commit = [string](Invoke-Git @('rev-parse','HEAD'))
    if ($DryRun) {
        if ($Action -eq 'Atualizar') {
            Assert-CleanStatus @(Invoke-Git @('status','--porcelain','--untracked-files=all'))
            Assert-UpdateBranch $branch ([string](Invoke-Git @('rev-parse','--abbrev-ref','@{upstream}'))) 0
        }
        Write-Output "SIMULACAO: $Action; branch=$branch; commit=$commit; area=$LocalRoot; nenhuma escrita ou download."
        exit 0
    }
    $runId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8)
    foreach ($dir in @('logs','backups','datasets','models','results')) {
        New-Item -ItemType Directory -Path (Join-Path $LocalRoot $dir) -Force | Out-Null
    }
    $log = Join-Path $LocalRoot "logs\$Action-$runId.log"
    Start-Transcript -Path $log -NoClobber | Out-Null
    $transcribing = $true
    Write-Output "PelotaVision: $Action | branch=$branch | commit=$commit"
    switch ($Action) {
        'Verificar' {
            Write-Output (Get-Jdk)
            Write-Output "OpenCV Java: $(Find-OpenCv)"
            Write-Output "Fonte de dados existe: $(Test-Path -LiteralPath $DataSource)"
            Write-Output "SDK Android 35/build-tools 35.0.0: $(Find-AndroidSdk)"
            Write-Output "Alteracoes locais: $(@(Invoke-Git @('status','--porcelain')).Count)"
            if (!(Find-OpenCv)) { Write-Warning 'Windows: configure OpenCV antes de compilar.' }
            if ($CheckRemote) { Invoke-Git @('ls-remote','--exit-code','origin','HEAD') | Write-Output }
        }
        'Instalar' {
            Write-Output (Get-Jdk)
            if (!$PrepareOnly) { Invoke-Build }
            else { Write-Output 'Estrutura preparada. Compilacao nao executada (-PrepareOnly).' }
        }
        'Atualizar' {
            Assert-CleanStatus @(Invoke-Git @('status','--porcelain','--untracked-files=all'))
            Assert-UpdateBranch $branch ([string](Invoke-Git @('rev-parse','--abbrev-ref','@{upstream}'))) 0
            Invoke-Git @('fetch','origin') | Write-Output
            $ahead = [int]([string](Invoke-Git @('rev-list','--count','origin/main..HEAD')))
            Assert-UpdateBranch $branch 'origin/main' $ahead
            $incoming = @(Invoke-Git @('-c','core.quotepath=false','diff','--name-only','--no-renames','--diff-filter=A','HEAD','origin/main'))
            Assert-NoIncomingCollision $repo $incoming
            $backup = Join-Path $LocalRoot "backups\antes-atualizar-$runId.bundle"
            Invoke-Git @('bundle','create',$backup,'--all') | Write-Output
            Invoke-Git @('bundle','verify',$backup) | Write-Output
            # Revalida a arvore apos rede/backup; nunca usa reset, clean, stash ou force.
            Assert-CleanStatus @(Invoke-Git @('status','--porcelain','--untracked-files=all'))
            Assert-NoIncomingCollision $repo $incoming
            Invoke-Git @('merge','--ff-only','origin/main') | Write-Output
            Invoke-Build
        }
        'Compilar' { Invoke-Build }
        'Sincronizar' {
            $python = Get-Command python3 -ErrorAction SilentlyContinue
            if (!$python) { $python = Get-Command python -ErrorAction Stop }
            $out = Join-Path $LocalRoot "datasets\inventario-$runId"
            $argsInventory = @("$repo\tools\dataset\inventariar.py", '--source', $DataSource, '--output', $out)
            if ($CopyData) { $argsInventory += '--copy-originals' }
            & $python.Source @argsInventory
            if ($LASTEXITCODE -ne 0) { throw 'Inventario falhou. Consulte log/manifesto; nenhum dado sera enviado ao GitHub.' }
        }
    }
    Write-Output "CONCLUIDO: $Action | HEAD=$([string](Invoke-Git @('rev-parse','HEAD'))) | Log=$log"
    exit 0
} catch {
    Write-Output "ERRO: $($_.Exception.Message)"
    if ($log) { Write-Output "Log: $log" }
    exit 1
} finally {
    if ($transcribing) { Stop-Transcript | Out-Null }
}
