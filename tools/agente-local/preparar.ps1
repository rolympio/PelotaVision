# Prepara o agente local (Ollama + opencode) e a venv da rede de borda. Pergunta antes de instalar qualquer coisa.
# Nao apaga nada; nao sobrescreve a configuracao global do opencode se ela ja existir.
#   PREPARAR_AGENTE_LOCAL.bat [-Modelo <tag do Ollama>] [-Contexto 32768] [-SemPython] [-SemOllama] [-Sim]
param([string]$Modelo = '', [int]$Contexto = 32768, [switch]$SemPython, [switch]$SemOllama, [switch]$Sim)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$LocalRoot = [IO.Path]::GetFullPath((Join-Path (Split-Path $repo -Parent) 'PelotaVision-local'))
New-Item -ItemType Directory -Force -Path $LocalRoot | Out-Null

function Ok([string]$m) { Write-Host "  [ok] $m" -ForegroundColor Green }
function Aviso([string]$m) { Write-Host "  [!]  $m" -ForegroundColor Yellow }
function Pergunta([string]$m) { if ($Sim) { return $true }; return ((Read-Host "$m [s/N]") -match '^(s|sim|y)$') }
function Tem([string]$c) { return [bool](Get-Command $c -ErrorAction SilentlyContinue) }
# Sonda um programa externo sem que a saida de erro dele derrube o script (Windows PowerShell 5.1 trata
# stderr redirecionado como erro fatal com ErrorActionPreference=Stop). Devolve @{ Codigo; Saida }.
function Sonda([string]$exe, [string[]]$argumentos) {
    $anterior = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $saida = & $exe @argumentos 2>$null; $codigo = $LASTEXITCODE } catch { $saida = $null; $codigo = 1 }
    finally { $ErrorActionPreference = $anterior }
    return @{ Codigo = $codigo; Saida = $saida }
}

Write-Host "PelotaVision - agente local`n  repositorio: $repo`n  area local:  $LocalRoot"
if ($repo -notmatch '^[Dd]:\\') { Aviso 'O repositorio nao esta em D:. Os projetos foram movidos para D:\Projetos; confira se esta e a copia certa.' }

# ---------- hardware
$vram = 0; $gpu = ''
if (Tem 'nvidia-smi') {
    try {
        $l = (& nvidia-smi --query-gpu=name,memory.total --format=csv,noheader,nounits | Select-Object -First 1) -split ','
        $gpu = $l[0].Trim(); $vram = [int]([double]$l[1].Trim() / 1024)
    } catch { }
}
$ram = [int]((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB)
Write-Host "Hardware: GPU '$gpu' ${vram} GB VRAM, ${ram} GB RAM"

if (-not $Modelo) {
    # Escolha conservadora: agente precisa de chamada de ferramenta confiavel e contexto longo.
    if ($vram -ge 20) { $Modelo = 'qwen3-coder:30b' }
    elseif ($vram -ge 14) { $Modelo = 'gpt-oss:20b' }
    elseif ($ram -ge 32) { $Modelo = 'qwen3-coder:30b' }      # MoE ~3B ativos: roda com parte na CPU, mais lento
    elseif ($ram -ge 16) { $Modelo = 'gpt-oss:20b' }          # ~14 GB; com 8 GB de VRAM divide com a CPU
    else { $Modelo = 'qwen3:8b' }
}
Write-Host "Modelo escolhido: $Modelo (troque com -Modelo <tag>), contexto $Contexto"

# ---------- Ollama
$local = "pv-$(($Modelo -replace '[:/]', '-'))-$([int]($Contexto/1024))k"
if (-not $SemOllama) {
    if (!(Tem 'ollama')) {
        if ((Tem 'winget') -and (Pergunta 'Ollama nao encontrado. Instalar com winget (Ollama.Ollama)?')) {
            winget install --id Ollama.Ollama -e --accept-source-agreements --accept-package-agreements
            $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + [Environment]::GetEnvironmentVariable('Path', 'User')
        } else { Aviso 'Instale o Ollama (https://ollama.com/download) e rode de novo.' }
    }
    if (Tem 'ollama') {
        Ok "ollama $((& ollama --version) -join ' ')"
        $lista = (& ollama list) -join "`n"
        if ($lista -notmatch [regex]::Escape($Modelo)) {
            if (Pergunta "Baixar $Modelo (varios GB)?") { & ollama pull $Modelo; if ($LASTEXITCODE) { throw "ollama pull $Modelo falhou" } }
        } else { Ok "$Modelo ja baixado" }
        # O opencode nao ajusta num_ctx; o padrao do Ollama (poucos mil tokens) quebra as ferramentas em silencio.
        $mf = Join-Path $LocalRoot "agente\Modelfile.$local"
        New-Item -ItemType Directory -Force -Path (Split-Path $mf) | Out-Null
        "FROM $Modelo`nPARAMETER num_ctx $Contexto`n" | Set-Content -Encoding ASCII -LiteralPath $mf
        if ($lista -notmatch [regex]::Escape($local)) { & ollama create $local -f $mf; if ($LASTEXITCODE) { throw 'ollama create falhou' } }
        Ok "modelo local com contexto ${Contexto}: $local"
    }
}

# ---------- opencode
if (!(Tem 'opencode')) {
    if ((Tem 'npm') -and (Pergunta 'opencode nao encontrado. Instalar com npm (opencode-ai, global)?')) { npm install -g opencode-ai }
    else { Aviso 'Instale o opencode: npm install -g opencode-ai  (ou veja https://opencode.ai)' }
}
if (Tem 'opencode') { Ok "opencode $((& opencode --version) -join ' ')" }
$cfgDir = Join-Path $env:USERPROFILE '.config\opencode'
New-Item -ItemType Directory -Force -Path $cfgDir | Out-Null
$tpl = (Get-Content -Raw -Encoding UTF8 (Join-Path $PSScriptRoot 'opencode.global.exemplo.json')).Replace('__MODELO__', $local).Replace('__CONTEXTO__', "$Contexto")
$cfg = Join-Path $cfgDir 'opencode.json'
if (Test-Path -LiteralPath $cfg) {
    $alt = Join-Path $cfgDir 'opencode.pelotavision.json'
    $tpl | Set-Content -Encoding UTF8 -LiteralPath $alt
    Aviso "Ja existe $cfg (nao mexi). Bloco pronto em ${alt}: copie 'provider.ollama' e 'model' para o seu arquivo."
} else { $tpl | Set-Content -Encoding UTF8 -LiteralPath $cfg; Ok "configuracao global criada: $cfg" }

# ---------- Python da rede de borda
if (-not $SemPython) {
    $python = $null
    foreach ($v in @('3.12', '3.11', '3.10')) { if (Tem 'py') { $r = Sonda 'py' @("-$v", '-c', 'import sys;print(sys.executable)'); if ($r.Codigo -eq 0 -and $r.Saida) { $python = [string]($r.Saida | Select-Object -Last 1); break } } }
    if (!$python -and (Tem 'python')) { $python = (Get-Command python).Source }
    if (!$python) { Aviso 'Python 3.10-3.12 nao encontrado; a rede de borda fica para depois.' }
    else {
        $venv = Join-Path $LocalRoot 'envs\borda'; $vpy = Join-Path $venv 'Scripts\python.exe'
        if (!(Test-Path -LiteralPath $vpy)) { & $python -m venv $venv }
        $idx = if ($vram -gt 0) { 'https://download.pytorch.org/whl/cu124' } else { 'https://download.pytorch.org/whl/cpu' }
        if ((Sonda $vpy @('-c', 'import torch')).Codigo -ne 0) {
            if (Pergunta "Instalar PyTorch ($(if ($vram) {'CUDA'} else {'CPU'})) na venv isolada $venv?") {
                $ErrorActionPreference = 'Continue'
                & $vpy -m pip install --disable-pip-version-check --progress-bar off torch --index-url $idx
                $codigoTorch = $LASTEXITCODE
                $ErrorActionPreference = 'Stop'
                if ($codigoTorch -ne 0) { throw 'Instalacao do PyTorch falhou (veja acima).' }
            }
        }
        $ErrorActionPreference = 'Continue'
        & $vpy -m pip install --disable-pip-version-check -q -r (Join-Path $repo 'ml_training\borda\requirements.txt') --extra-index-url $idx
        if ($LASTEXITCODE -ne 0) { $ErrorActionPreference = 'Stop'; throw 'pip install dos requisitos falhou (veja acima).' }
        $ErrorActionPreference = 'Stop'
        $cuda = ((Sonda $vpy @('-c', 'import torch;print(torch.cuda.is_available())')).Saida) -join ''
        Ok "venv da rede de borda: $vpy (CUDA: $cuda)"
        Push-Location $repo; $ErrorActionPreference = 'Continue'; & $vpy -m pytest -q tools/externos ml_training/borda; $t = $LASTEXITCODE; $ErrorActionPreference = 'Stop'; Pop-Location
        if ($t -eq 0) { Ok 'testes sinteticos da rede de borda e dos externos passaram' } else { Aviso 'testes falharam: veja acima' }
    }
}

Write-Host "`nPronto. Para usar:`n  cd $repo`n  opencode`n  (no opencode: /models -> ollama/$local; prompts prontos em docs\agente-local.md)"
