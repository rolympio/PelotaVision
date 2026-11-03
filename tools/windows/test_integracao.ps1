# Repositorios sinteticos locais: nunca acessa/publica no GitHub.
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$base = Join-Path $env:TEMP ('pelotavision-integracao-' + [guid]::NewGuid().ToString('N'))
$repo = Join-Path $base ('codigo com ' + [char]0x00e3 + ' e espacos')
$remote = Join-Path $base 'remoto.git'
$local = Join-Path $base 'privado'
$ocv = Join-Path $base 'opencv'
$shim = Join-Path $base 'git-local-transporte'
$env:PV_NONINTERACTIVE = '1'
$env:GIT_TERMINAL_PROMPT = '0'
$env:GIT_ALLOW_PROTOCOL = 'file'
function Git([string[]]$Arguments) {
    $old = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $result = @(& git.exe @Arguments 2>&1)
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $old }
    if ($code -ne 0) { throw "Git teste falhou: $result" }
    return $result
}
function Run-Update([int]$Expected) {
    & "$repo\ATUALIZAR_PELOTAVISION.bat" -LocalRoot $local -OpenCvJava $ocv
    if ($LASTEXITCODE -ne $Expected) { throw "Atualizacao retornou $LASTEXITCODE, esperado $Expected" }
}
New-Item -ItemType Directory -Path "$repo\tools\windows", "$repo\desktop\build\libs", "$ocv\x64", $shim -Force | Out-Null
$gitExe = (Get-Command git.exe).Source
# O shim redireciona SOMENTE fetch ao bare local. A validacao de URL nao e simulada.
@"
@echo off
echo %*>>"$base\git-invocacoes.log"
if "%~5"=="fetch" goto localfetch
if "%~6"=="fetch" goto localfetch
"$gitExe" %*
exit /b %errorlevel%
:localfetch
"$gitExe" -c "url.$($remote.Replace('\','/')).insteadOf=https://github.com/rolympio/PelotaVision.git" %*
exit /b %errorlevel%
"@ | Set-Content -Encoding ASCII "$shim\git.cmd"
$env:PATH = "$shim;$env:PATH"
Copy-Item "$root\tools\windows\ambiente.ps1", "$root\tools\windows\seguranca.ps1" "$repo\tools\windows"
Copy-Item "$root\ATUALIZAR_PELOTAVISION.bat" $repo
# Gradle sintetico: comprova propagacao de erro, nao compilacao cientifica.
@'
@echo off
if defined PV_TEST_GRADLE_FAIL exit /b 9
echo sintetico> desktop\build\libs\pelotavision-desktop.jar
exit /b 0
'@ | Set-Content -Encoding ASCII "$repo\gradlew.bat"
'sintetico' | Set-Content "$ocv\opencv-4120.jar"
'sintetico' | Set-Content "$ocv\x64\opencv_java4120.dll"
@('desktop/build/', 'local-only.txt') | Set-Content "$repo\.gitignore"
Git @('init','--initial-branch=main',$repo) | Out-Null
Git @('-C',$repo,'config','user.name','Teste local') | Out-Null
Git @('-C',$repo,'config','user.email','teste@example.invalid') | Out-Null
Git @('-C',$repo,'add','.') | Out-Null
Git @('-C',$repo,'commit','-m','base sintetica') | Out-Null
Git @('clone','--bare',$repo,$remote) | Out-Null
Git @('-C',$repo,'remote','add','origin','https://github.com/rolympio/PelotaVision.git') | Out-Null
Git @('-C',$repo,'fetch',$remote,'refs/heads/main:refs/remotes/origin/main') | Out-Null
Git @('-C',$repo,'branch','--set-upstream-to=origin/main','main') | Out-Null

# Caso 1: atualizacao limpa + backup + testes sinteticos.
Run-Update 0
if (@(Get-ChildItem "$local\backups\*.bundle").Count -ne 1) { throw 'Bundle nao criado' }

# Caso 2: untracked bloqueia, sem mudar HEAD ou apagar o arquivo.
$head = [string](Git @('-C',$repo,'rev-parse','HEAD'))
'preservar' | Set-Content "$repo\nao-rastreado.txt"
Run-Update 1
if ([string](Git @('-C',$repo,'rev-parse','HEAD')) -ne $head) { throw 'HEAD alterado indevidamente' }
if ((Get-Content "$repo\nao-rastreado.txt") -ne 'preservar') { throw 'Arquivo local nao preservado' }
Git @('-C',$repo,'add','nao-rastreado.txt') | Out-Null
Git @('-C',$repo,'commit','-m','commit apenas local') | Out-Null

# Caso 3: commits locais bloqueiam mesmo com arvore limpa.
Run-Update 1

# Publica APENAS no bare sintetico em disco, habilitando um fast-forward real depois.
Git @('-C',$repo,'push',$remote,'main') | Out-Null
$worker = Join-Path $base 'editor'
Git @('clone',$remote,$worker) | Out-Null
Git @('-C',$worker,'config','user.name','Teste local') | Out-Null
Git @('-C',$worker,'config','user.email','teste@example.invalid') | Out-Null
'novo' | Set-Content "$worker\novo.txt"
Git @('-C',$worker,'add','novo.txt') | Out-Null
Git @('-C',$worker,'commit','-m','atualizacao remota sintetica') | Out-Null
Git @('-C',$worker,'push','origin','main') | Out-Null
$remoteHead = [string](Git @('-C',$worker,'rev-parse','HEAD'))

# Caso 4: erro de build APOS fast-forward nao vira sucesso nem rollback destrutivo.
$env:PV_TEST_GRADLE_FAIL = '1'
try { Run-Update 1 } finally { Remove-Item Env:\PV_TEST_GRADLE_FAIL }
if ([string](Git @('-C',$repo,'rev-parse','HEAD')) -ne $remoteHead) { throw 'Fast-forward nao realizado' }

# Caso 5: recuperacao do bundle mais recente em outra pasta.
$bundle = Get-ChildItem "$local\backups\*.bundle" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
Git @('clone',$bundle.FullName,(Join-Path $base 'recuperado')) | Out-Null

# Caso 6: arquivo ignorado nunca e sobrescrito por um novo arquivo remoto.
'privado' | Set-Content "$repo\local-only.txt"
'remoto' | Set-Content "$worker\local-only.txt"
Git @('-C',$worker,'add','-f','local-only.txt') | Out-Null
Git @('-C',$worker,'commit','-m','novo caminho remoto sintetico') | Out-Null
Git @('-C',$worker,'push','origin','main') | Out-Null
Run-Update 1
if ((Get-Content "$repo\local-only.txt") -ne 'privado') { throw 'Arquivo ignorado sobrescrito' }
if ([string](Git @('-C',$repo,'rev-parse','HEAD')) -ne $remoteHead) { throw 'HEAD alterado com colisao ignorada' }

# Caso 7: branch de desenvolvimento e remoto errado recusados.
Git @('-C',$repo,'switch','-c','feature/teste') | Out-Null
Run-Update 1
Git @('-C',$repo,'remote','set-url','origin','https://github.com/outro/projeto.git') | Out-Null
Run-Update 1
Write-Output "7 cenarios de integracao passaram. Repositorios sinteticos preservados em: $base"
# O ultimo cenario espera exit=1 do atalho; nao vazar esse codigo ao wrapper do Actions.
exit 0
