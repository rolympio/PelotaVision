# Rede de borda + dados externos: um atalho por passo. Nada aqui apaga ou sobrescreve dados.
#   BORDA.bat <acao> [argumentos]
# Acoes: testes | pre <analise.json> [filtrada|original] | revisada <analise.json> <anotacao.json> |
#        baixar [args do roboflow_baixar.py] | converter <id> | caixas <id> | auditar <id> |
#        pretreino [--validar-fonte <id>] [--iteracoes N] | ajuste <pasta> [args] | exportar [pasta .pt] | avaliar <pasta> [args]
# Sem bloco param(): argumentos como '--prioridade' seguem intactos para o Python via $args.
$Acao = if ($args.Count) { [string]$args[0] } else { 'ajuda' }
[string[]]$Resto = if ($args.Count -gt 1) { @($args[1..($args.Count - 1)] | ForEach-Object { [string]$_ }) } else { @() }
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$LocalRoot = [IO.Path]::GetFullPath((Join-Path (Split-Path $repo -Parent) 'PelotaVision-local'))
$py = Join-Path $LocalRoot 'envs\borda\Scripts\python.exe'
$ext = Join-Path $LocalRoot 'datasets\externos'
$modelos = Join-Path $LocalRoot 'models\borda'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'

function Run([string[]]$a) {
    Write-Host ">> python $($a -join ' ')" -ForegroundColor DarkGray
    & $py @a
    if ($LASTEXITCODE -ne 0) { throw "Falhou (codigo $LASTEXITCODE)." }
}
function Fonte([string]$id) {
    $reg = Get-Content -Raw -Encoding UTF8 (Join-Path $repo 'tools\externos\fontes_roboflow.json') | ConvertFrom-Json
    $f = $reg.fontes | Where-Object { $_.id -eq $id }
    if (!$f) { throw "Fonte '$id' nao esta em tools\externos\fontes_roboflow.json" }
    return $f
}
function UltimoExport([string]$id) {
    $base = Join-Path $ext "roboflow\$id"
    $v = Get-ChildItem -LiteralPath $base -Directory -Filter 'v*' -ErrorAction SilentlyContinue | Sort-Object { [int]($_.Name.Substring(1)) } | Select-Object -Last 1
    if (!$v) { throw "Nada baixado para $id. Rode: BORDA.bat baixar --ids $id" }
    return $v.FullName
}

if (!(Test-Path -LiteralPath $py)) { throw "Venv nao encontrada ($py). Rode PREPARAR_AGENTE_LOCAL.bat primeiro." }
Set-Location $repo
switch ($Acao.ToLowerInvariant()) {
    'testes' { Run (@('-m', 'pytest', '-q', 'tools/externos', 'ml_training/borda')) }
    'baixar' { Run (@('tools/externos/roboflow_baixar.py', '--local', $LocalRoot) + $Resto) }
    'converter' {
        $id = $Resto[0]; $f = Fonte $id; $v = UltimoExport $id
        $a = @('tools/externos/coco_para_pelotavision.py', '--entrada', $v, '--id', $id, '--saida', (Join-Path $ext "convertido\$id"))
        if ($f.classes_pelota.Count -gt 0) { $a += @('--classes') + @($f.classes_pelota) }
        Run $a
    }
    'caixas' {
        $id = $Resto[0]; $f = Fonte $id; $v = UltimoExport $id
        $sam = Join-Path $LocalRoot 'models\sam2'
        if (!(Test-Path -LiteralPath (Join-Path $sam 'sam2_encoder.onnx'))) { throw "SAM 2 nao exportado em $sam (rode COMPILAR_PELOTAVISION_COMPLETO.bat uma vez)." }
        $a = @('tools/externos/caixas_para_contorno_sam2.py', '--entrada', $v, '--id', $id, '--sam2', $sam, '--saida', (Join-Path $ext "convertido\$id"))
        if ($f.classes_pelota.Count -gt 0) { $a += @('--classes') + @($f.classes_pelota) }
        Run ($a + @($Resto | Select-Object -Skip 1))
    }
    'pre' {
        # pre-anotacao para o X-AnyLabeling: BORDA.bat pre <analise.json> [original|filtrada]
        $alvo = if ($Resto.Count -gt 1) { $Resto[1] } else { 'filtrada' }
        Run @('tools/externos/xanylabeling_pelotavision.py', 'pre', '--analise', $Resto[0], '--alvo', $alvo)
    }
    'revisada' {
        # anotacao revisada -> conjunto proprio: BORDA.bat revisada <analise.json> <anotacao.json>
        Run @('tools/externos/xanylabeling_pelotavision.py', 'treino', '--analise', $Resto[0], '--anotacao', $Resto[1], '--saida', (Join-Path $LocalRoot 'datasets\bancada_revisada'))
    }
    'auditar' {
        $id = $Resto[0]
        Run @('tools/externos/auditar_borda_anotada.py', '--dados', (Join-Path $ext "convertido\$id"), '--saida', (Join-Path $LocalRoot "results\borda-auditoria\$id-$stamp"), '--pranchas', '12', '--aplicar')
    }
    'pretreino' {
        $dirs = @(Get-ChildItem -LiteralPath (Join-Path $ext 'convertido') -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'annotations.json') } | ForEach-Object { $_.FullName })
        if ($dirs.Count -lt 2) { throw 'Pre-treino precisa de pelo menos 2 fontes convertidas (uma fica para validacao).' }
        $a = @('ml_training/borda/treinar.py', '--fase', 'pretreino', '--arquivo', 'annotations_auditado.json', '--dados') + $dirs + @('--saida', (Join-Path $modelos "pretreino-$stamp"))
        if (-not ($Resto -contains '--validar-fonte')) { $a += @('--validar-fonte', (Split-Path $dirs[0] -Leaf)) }
        Run ($a + $Resto)
    }
    'ajuste' {
        $pt = Get-ChildItem -LiteralPath $modelos -Directory -Filter 'pretreino-*' -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -Last 1
        $a = @('ml_training/borda/treinar.py', '--fase', 'ajuste', '--dados', $Resto[0], '--saida', (Join-Path $modelos "ajuste-$stamp"), '--lr', '5e-4')
        if ($pt) { $a += @('--inicio', (Join-Path $pt.FullName 'melhor.pt')) } else { Write-Warning 'Sem pre-treino: ajuste parte do zero.' }
        Run ($a + @($Resto | Select-Object -Skip 1))
    }
    'exportar' {
        $dir = if ($Resto.Count) { $Resto[0] } else { (Get-ChildItem -LiteralPath $modelos -Directory | Where-Object { $_.Name -match '^(ajuste|pretreino)-' } | Sort-Object LastWriteTime | Select-Object -Last 1).FullName }
        Run @('ml_training/borda/exportar.py', '--pt', (Join-Path $dir 'melhor.pt'), '--saida', (Join-Path $modelos 'exportado'))
    }
    'avaliar' {
        Run (@('ml_training/borda/avaliar.py', '--modelo', (Join-Path $modelos 'exportado\pellet_borda.onnx'), '--dados', $Resto[0], '--saida', (Join-Path $LocalRoot "results\borda-avaliacao-$stamp")) + @($Resto | Select-Object -Skip 1))
    }
    default { Get-Content (Join-Path $PSScriptRoot 'borda.ps1') -TotalCount 5 | ForEach-Object { $_ -replace '^# ?', '' } }
}
