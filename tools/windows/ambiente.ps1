[CmdletBinding()]
param(
    [ValidateSet('Instalar','Atualizar','Verificar','Compilar','Sincronizar')]
    [string]$Action = 'Verificar',
    [string]$LocalRoot,
    [string]$DataSource,
    [string]$OpenCvJava,
    [ValidateSet('Windows','Android','Ambos')][string]$Platform = 'Windows',
    [switch]$PrepararSam2,
    [switch]$SemSam2,
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
        'C:\opencv-4.12.0\opencv\build\java', 'C:\opencv\build\java', 'C:\Projetos\tools\opencv-4.12.0\opencv\build\java')
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
    foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk", 'C:\Projetos\tools\android-sdk')) {
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

# SHA-256 por .NET: nao depende do Get-FileHash, que some quando o Windows PowerShell 5.1 e aberto
# a partir do PowerShell 7 (PSModulePath herdado aponta para o modulo Utility do 7).
function Get-Sha256([string]$Path) {
    $stream = [IO.File]::OpenRead((Resolve-Path -LiteralPath $Path).ProviderPath)
    try {
        $sha = [Security.Cryptography.SHA256]::Create()
        try { return ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '') }
        finally { $sha.Dispose() }
    } finally { $stream.Dispose() }
}

# ---------------------------------------------------------------- SAM 2.1 (0.17.0 / Windows 0.9.0)
$Sam2Files = @('sam2_encoder.onnx','sam2_decoder.onnx','sam2.json')
$Sam2CheckpointUrl = 'https://dl.fbaipublicfiles.com/segment_anything_2/092824/sam2.1_hiera_tiny.pt'
$Sam2CheckpointSha = '7402E0D864FA82708A20FBD15BC84245C2F26DFF0EB43A4B5B93452DEB34BE69'
$Sam2Commit = '2b90b9f5ceec907a1c18123530e92e794ad901a4'

function Test-Sam2Dir([string]$Dir) {
    foreach ($f in $Sam2Files) { if (!(Test-Path -LiteralPath (Join-Path $Dir $f))) { return $false } }
    $meta = Get-Content -LiteralPath (Join-Path $Dir 'sam2.json') -Raw | ConvertFrom-Json
    if ($meta.random_weights) { return $false }
    foreach ($pair in @(@('sam2_encoder.onnx', $meta.encoder_sha256), @('sam2_decoder.onnx', $meta.decoder_sha256))) {
        $h = Get-Sha256 (Join-Path $Dir $pair[0])
        if ($h -ne ([string]$pair[1]).ToUpperInvariant()) { throw "SAM 2: SHA-256 de $($pair[0]) em $Dir nao confere com sam2.json." }
    }
    return $true
}

function Find-Python {
    foreach ($v in @('3.12','3.11','3.13','3.10')) {
        $py = Get-Command py -ErrorAction SilentlyContinue
        if ($py) { $exe = & py "-$v" -c 'import sys;print(sys.executable)' 2>$null; if ($LASTEXITCODE -eq 0 -and $exe) { return [string]$exe } }
    }
    foreach ($name in @('python','python3')) {
        $cmd = Get-Command $name -ErrorAction SilentlyContinue
        if ($cmd) { $ok = & $cmd.Source -c 'import sys;print(sys.version_info[:2]>=(3,10))' 2>$null; if ("$ok" -eq 'True') { return $cmd.Source } }
    }
    return $null
}

function Invoke-Checked([string]$Exe, [string[]]$Arguments, [string]$What) {
    $old = $ErrorActionPreference
    try { $ErrorActionPreference = 'Continue'; & $Exe @Arguments 2>&1 | ForEach-Object { "$_" } | Write-Output; $code = $LASTEXITCODE }
    finally { $ErrorActionPreference = $old }
    if ($code -ne 0) { throw "$What falhou (codigo $code)." }
}

# Exporta o SAM 2.1 Tiny oficial para ONNX numa venv isolada em PelotaVision-local (nada global).
# Downloads: checkpoint ~156 MB (Meta, Apache-2.0), PyTorch CPU ~200 MB, codigo SAM 2 (GitHub) ~5 MB.
function Export-Sam2([string]$SamDir) {
    New-Item -ItemType Directory -Path $SamDir -Force | Out-Null
    $ckpt = Join-Path $SamDir 'sam2.1_hiera_tiny.pt'
    if (!(Test-Path -LiteralPath $ckpt)) {
        # Reaproveita um download manual (Downloads, pasta privada antiga ou worktrees), conferido pelo SHA-256.
        foreach ($dir in @($SamDir, "$env:USERPROFILE\Downloads", (Join-Path (Split-Path $repo -Parent) 'worktrees\PelotaVision-local\models\sam2'))) {
            $alt = Get-ChildItem -LiteralPath $dir -Filter 'sam2.1_hiera_tiny*.pt' -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($alt -and (Get-Sha256 $alt.FullName) -eq $Sam2CheckpointSha) {
                Copy-Item -LiteralPath $alt.FullName -Destination $ckpt; Write-Output "SAM 2: checkpoint reaproveitado de $($alt.FullName)"; break
            }
        }
    }
    if (!(Test-Path -LiteralPath $ckpt)) {
        Write-Output "SAM 2: baixando o checkpoint oficial (~156 MB) de $Sam2CheckpointUrl"
        $ProgressPreference = 'SilentlyContinue'
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri $Sam2CheckpointUrl -OutFile "$ckpt.part" -UseBasicParsing
        Move-Item -LiteralPath "$ckpt.part" -Destination $ckpt
    }
    $sha = Get-Sha256 $ckpt
    if ($sha -ne $Sam2CheckpointSha) { throw "SAM 2: SHA-256 do checkpoint nao confere ($sha). Apague o arquivo e repita." }
    $python = Find-Python
    if (!$python) { throw 'SAM 2: instale Python 3.10-3.13 (python.org) para exportar o modelo uma unica vez.' }
    $venv = Join-Path $LocalRoot 'envs\sam2-export'
    $vpy = Join-Path $venv 'Scripts\python.exe'
    if (!(Test-Path -LiteralPath $vpy)) { Invoke-Checked $python @('-m','venv',$venv) 'Criacao da venv SAM 2' }
    Invoke-Checked $vpy @('-m','pip','install','--disable-pip-version-check','-q','torch','torchvision','--index-url','https://download.pytorch.org/whl/cpu') 'Instalacao do PyTorch CPU'
    Invoke-Checked $vpy @('-m','pip','install','--disable-pip-version-check','-q','onnx','onnxruntime','hydra-core>=1.3.2','iopath>=0.1.10','numpy','pillow','tqdm') 'Instalacao das dependencias SAM 2'
    $src = Join-Path $LocalRoot 'envs\sam2-src'
    if (!(Test-Path -LiteralPath (Join-Path $src '.git'))) { Invoke-Checked 'git' @('clone','-q','https://github.com/facebookresearch/sam2',$src) 'Download do codigo SAM 2' }
    Invoke-Checked 'git' @('-C',$src,'-c','advice.detachedHead=false','checkout','-q',$Sam2Commit) 'Checkout da revisao SAM 2'
    $env:SAM2_BUILD_CUDA = '0'
    Invoke-Checked $vpy @('-m','pip','install','--disable-pip-version-check','-q','--no-deps','--no-build-isolation','-e',$src) 'Instalacao do pacote SAM 2'
    Invoke-Checked $vpy @("$repo\tools\sam2\exportar_sam2_onnx.py",'--checkpoint',$ckpt,'--saida',$SamDir) 'Exportacao ONNX do SAM 2'
    Invoke-Checked $vpy @("$repo\tools\sam2\verificar_sam2_onnx.py",'--modelo',$SamDir,'--checkpoint',$ckpt) 'Verificacao ONNX x PyTorch do SAM 2'
}

# Garante sam2_*.onnx + sam2.json em app\src\main\assets (fora do Git): Android embute; Windows copia para modelos\.
function Prepare-Sam2 {
    $assets = "$repo\app\src\main\assets"
    $samDir = Join-Path $LocalRoot 'models\sam2'
    if ($SemSam2) {
        New-Item -ItemType Directory -Path $samDir -Force | Out-Null
        foreach ($f in $Sam2Files) { $p = Join-Path $assets $f; if (Test-Path -LiteralPath $p) { Move-Item -LiteralPath $p -Destination (Join-Path $samDir $f) -Force } }
        Write-Output 'SAM 2: desativado (-SemSam2); pacote sem o modelo.'; return
    }
    if (!(Test-Sam2Dir $samDir)) {
        if (Test-Sam2Dir $assets) { Write-Output "SAM 2: usando o modelo ja presente em $assets."; return }
        if (!$PrepararSam2) {
            Write-Warning "SAM 2 ausente em $samDir. O pacote sai SEM o refino SAM 2. Rode com -PrepararSam2 (ou COMPILAR_PELOTAVISION_COMPLETO.bat) para baixar e exportar automaticamente."
            return
        }
        Export-Sam2 $samDir
        if (!(Test-Sam2Dir $samDir)) { throw 'SAM 2: exportacao nao gerou os tres arquivos.' }
    }
    foreach ($f in $Sam2Files) { Copy-Item -LiteralPath (Join-Path $samDir $f) -Destination $assets -Force }
    Write-Output "SAM 2: modelo copiado de $samDir para $assets (fora do Git)."
}

function Copy-AndroidArtifacts {
    $out = Join-Path $LocalRoot "results\android-$runId"
    New-Item -ItemType Directory -Path $out -Force | Out-Null
    $lines = @()
    foreach ($apk in @(Get-ChildItem "$repo\app\build\outputs\apk\debug\*.apk")) {
        Copy-Item -LiteralPath $apk.FullName -Destination $out
        $lines += "$(Get-Sha256 $apk.FullName)  $($apk.Name)"
    }
    $lines | Set-Content -Encoding ASCII (Join-Path $out 'SHA256.txt')
    Write-Output "APKs Android: $out (celular: app-arm64-v8a-debug.apk)"
}

function Invoke-Build {
    Write-Output (Get-Jdk)
    Prepare-Sam2
    if ($Platform -eq 'Ambos') {
        $script:Platform = 'Windows'; Invoke-Build-One
        $script:Platform = 'Android'; Invoke-Build-One
        $script:Platform = 'Ambos'
        return
    }
    Invoke-Build-One
}

function Invoke-Build-One {
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
        Copy-AndroidArtifacts
    }
    $files = @()
    if ($Platform -eq 'Windows') { $files = @(Get-Item "$repo\desktop\build\libs\pelotavision-desktop.jar") }
    else { $files = @(Get-ChildItem "$repo\app\build\outputs\apk\debug\*.apk") }
    if ($files.Count -eq 0) { throw 'Nenhum artefato gerado.' }
    $record = [ordered]@{ commit = [string](Invoke-Git @('rev-parse','HEAD')); platform = $Platform
        created_utc = [DateTime]::UtcNow.ToString('o'); dirty = (@(Invoke-Git @('status','--porcelain')).Count -gt 0)
        artifacts = @($files | ForEach-Object { @{ name=$_.Name; sha256=(Get-Sha256 $_.FullName) } }) }
    $record | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 (Join-Path $LocalRoot "logs\build-$Platform-$runId.json")
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
    if ((Test-Path "$inputDir\modelos\sam2_encoder.onnx") -and
        !(Select-String -LiteralPath "$out\autoteste\autoteste.txt" -Pattern '^sam2_run=' -Quiet)) {
        throw 'SAM 2 empacotado, mas o autoteste nao o executou (veja sam2= no autoteste.txt); pacote nao aprovado.'
    }
    # Janela real e exportacao completa SEM Python/JDK no PATH e com cwd independente.
    $oldPath = $env:PATH; $oldJava = $env:JAVA_HOME; $oldPython = $env:PYTHONHOME
    $aiPreviewTested = $false
    New-Item -ItemType Directory -Path "$out\cwd-vazio" | Out-Null
    try {
        $env:PATH = "$env:SystemRoot\System32;$env:SystemRoot"
        $env:JAVA_HOME = ''; $env:PYTHONHOME = ''
        $gui = Start-Process -FilePath $exe -WorkingDirectory "$out\cwd-vazio" `
            -ArgumentList @('--studio-smoke', "`"$repo\verification\fixtures\1000411645.jpg`"", "`"$out\studio-smoke`"") -PassThru
        $handle = $gui.Handle
        if (!$gui.WaitForExit(360000)) { $gui.Kill(); throw 'Timeout do ensaio GUI do pacote.' }
        $gui.Refresh()
        $smokePath = "$out\studio-smoke\studio-smoke.json"
        if ($gui.ExitCode -ne 0 -or !(Test-Path $smokePath)) { throw 'Ensaio GUI do executavel falhou.' }
        $smoke = Get-Content $smokePath -Raw | ConvertFrom-Json
        if ($smoke.exit -ne 0 -or $smoke.stages_rendered -ne 6 -or $smoke.pdf_pages -lt 1) {
            throw 'Ensaio GUI incompleto: pacote nao aprovado.'
        }
        if ($env:PELOTAVISION_EXPERIMENTAL_MODEL) {
            $modelPath = $env:PELOTAVISION_EXPERIMENTAL_MODEL
            if (!(Test-Path -LiteralPath $modelPath -PathType Leaf)) { throw 'ONNX experimental solicitado para teste nao existe.' }
            $ai = Start-Process -FilePath $exe -WorkingDirectory "$out\cwd-vazio" `
                -ArgumentList @('--studio-ai-smoke', "`"$repo\verification\fixtures\1000411645.jpg`"", "`"$out\studio-ai-smoke`"", "`"$modelPath`"") -PassThru
            $handle = $ai.Handle
            if (!$ai.WaitForExit(480000)) { $ai.Kill(); throw 'Timeout do ensaio GUI da IA.' }
            $ai.Refresh()
            $aiPath = "$out\studio-ai-smoke\studio-smoke.json"
            if ($ai.ExitCode -ne 0 -or !(Test-Path $aiPath)) { throw 'Ensaio GUI da IA falhou.' }
            $aiResult = Get-Content $aiPath -Raw | ConvertFrom-Json
            if ($aiResult.exit -ne 0 -or $aiResult.experimental_ai -ne 'preview_cpu_sha_verificado_sem_alteracao_de_medicoes') {
                throw 'Previa IA nao concluiu sem alterar as medicoes.'
            }
            $aiPreviewTested = $true
        }
    } finally { $env:PATH = $oldPath; $env:JAVA_HOME = $oldJava; $env:PYTHONHOME = $oldPython }
    $metadata = [ordered]@{ version=$version; commit=[string](Invoke-Git @('rev-parse','HEAD'))
        created_utc=[DateTime]::UtcNow.ToString('o'); executable=$exe; packaged_gui_test=$true
        ai_preview_tested=$aiPreviewTested; clean_vm_tested=$false; python_required=$false; experimental=$true }
    $metadata | ConvertTo-Json | Set-Content -Encoding UTF8 "$out\dist\PelotaVision\VERSION.json"
    @'
@echo off
rem Atualizacoes sao distribuidas apenas apos aprovacao. Nunca sobrescreve esta pasta.
start "" "https://github.com/rolympio/PelotaVision/releases"
'@ | Set-Content -Encoding ASCII "$out\dist\PelotaVision\VERIFICAR_ATUALIZACOES.bat"
    $zip = "$out\PelotaVision_Studio_Windows_$version.zip"
    # .NET em vez de Compress-Archive (mesmo motivo do Get-Sha256); inclui a pasta PelotaVision no zip.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::CreateFromDirectory("$out\dist\PelotaVision", $zip, [IO.Compression.CompressionLevel]::Optimal, $true)
    $hash = [pscustomobject]@{ Hash = (Get-Sha256 $zip) }
    "$($hash.Hash)  $([IO.Path]::GetFileName($zip))" | Set-Content -Encoding ASCII "$out\SHA256.txt"
    $metadata | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $LocalRoot 'latest-package.json')
    $desktop = [Environment]::GetFolderPath('Desktop')
    if ($desktop -and (Test-Path -LiteralPath $desktop)) {
        $shortcutPath = Join-Path $desktop 'PelotaVision Studio.lnk'
        if (Test-Path $shortcutPath) { $shortcutPath = Join-Path $desktop "PelotaVision Studio-$runId.lnk" }
        $shell = New-Object -ComObject WScript.Shell
        $shortcut = $shell.CreateShortcut($shortcutPath)
        $shortcut.TargetPath = $exe; $shortcut.WorkingDirectory = Split-Path $exe -Parent
        $shortcut.IconLocation = "$exe,0"
        $shortcut.Save()
        Write-Output "Atalho criado: $shortcutPath"
    }
    Write-Output "Distribuicao: $zip | SHA256=$($hash.Hash)"
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
