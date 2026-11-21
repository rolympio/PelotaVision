# Funcoes sem efeitos colaterais ao carregar; testadas sem acessar a rede.
Set-StrictMode -Version Latest

function Test-WithinPath([string]$Child, [string]$Parent) {
    $c = [IO.Path]::GetFullPath($Child).TrimEnd('\', '/')
    $p = [IO.Path]::GetFullPath($Parent).TrimEnd('\', '/')
    return $c.Equals($p, [StringComparison]::OrdinalIgnoreCase) -or
        $c.StartsWith($p + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)
}

function Assert-OutsideOneDrive([string]$Path) {
    foreach ($base in @($env:OneDrive, $env:OneDriveCommercial, $env:OneDriveConsumer, "$env:USERPROFILE\OneDrive")) {
        if ($base -and (Test-WithinPath $Path $base)) { throw 'Codigo e artefatos devem ficar fora do OneDrive.' }
    }
}

function Assert-NoReparseAncestors([string]$Path) {
    $current = [IO.Path]::GetFullPath($Path)
    while ($current) {
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -LiteralPath $current -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw 'Destino de codigo/artefatos passa por link ou junction; use caminho fisico fora do OneDrive.'
            }
        }
        $parent = Split-Path $current -Parent
        if ($parent -eq $current) { break }
        $current = $parent
    }
}

function Assert-OfficialRemote([string]$Url) {
    if ($Url.Trim() -cnotmatch '^(https://github\.com/rolympio/PelotaVision(?:\.git)?/?|git@github\.com:rolympio/PelotaVision(?:\.git)?)$') {
        throw 'Origin diferente do repositorio oficial. Nao sera alterado automaticamente.'
    }
}

function Assert-CleanStatus([string[]]$Status) {
    if (@($Status | Where-Object { $_.Trim() }).Count -gt 0) {
        throw 'Ha alteracoes locais ou arquivos nao rastreados. Preserve-os em commit/branch antes de atualizar.'
    }
}

function Assert-UpdateBranch([string]$Branch, [string]$Upstream, [int]$Ahead) {
    if ($Branch -ne 'main' -or $Upstream -ne 'origin/main') {
        throw 'Atualizacao de um clique permitida apenas em main acompanhando origin/main. Nenhuma branch sera trocada.'
    }
    if ($Ahead -ne 0) { throw 'Existem commits locais nao publicados ou divergentes. Atualizacao bloqueada.' }
}

function Assert-NoIncomingCollision([string]$Root, [string[]]$Incoming) {
    # Merge pode substituir arquivos ignorados que passem a ser rastreados.
    foreach ($relative in $Incoming) {
        if (!$relative) { continue }
        $target = Join-Path $Root $relative
        if (Test-Path -LiteralPath $target) { throw "Arquivo local no caminho recebido: $relative. Atualizacao bloqueada." }
        $parent = Split-Path $target -Parent
        while ($parent -and $parent -ne $Root) {
            if (Test-Path -LiteralPath $parent -PathType Leaf) { throw "Arquivo local impede criar diretorio: $parent" }
            $parent = Split-Path $parent -Parent
        }
    }
}
