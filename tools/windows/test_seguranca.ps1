$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\seguranca.ps1"
$count = 0
function Expect-Rejection([scriptblock]$Body) {
    $rejected = $false
    try { & $Body } catch { $rejected = $true }
    if (!$rejected) { throw 'Operacao insegura nao foi rejeitada.' }
    $script:count++
}
Assert-OfficialRemote 'https://github.com/rolympio/PelotaVision.git'
Assert-OfficialRemote 'git@github.com:rolympio/PelotaVision.git'
$count += 2
Expect-Rejection { Assert-OfficialRemote 'https://github.com/outro/PelotaVision.git' }
Expect-Rejection { Assert-OfficialRemote 'https://github.com/rolympio/PelotaVision.git/evil' }
Expect-Rejection { Assert-OfficialRemote 'https://github.com.evil/rolympio/PelotaVision.git' }
Assert-CleanStatus @()
$count++
Expect-Rejection { Assert-CleanStatus @(' M README.md') }
Expect-Rejection { Assert-CleanStatus @('?? privado.png') }
Assert-UpdateBranch 'main' 'origin/main' 0
$count++
Expect-Rejection { Assert-UpdateBranch 'feature/ia' 'origin/main' 0 }
Expect-Rejection { Assert-UpdateBranch '' 'origin/main' 0 }
Expect-Rejection { Assert-UpdateBranch 'main' 'outro/main' 0 }
Expect-Rejection { Assert-UpdateBranch 'main' 'origin/main' 1 }
Expect-Rejection { Assert-OutsideOneDrive "$env:USERPROFILE\OneDrive\codigo" }
if (!(Test-WithinPath 'C:\dados\amostra' 'C:\dados')) { throw 'Contencao invalida' }
if (Test-WithinPath 'C:\dados2' 'C:\dados') { throw 'Prefixo confundido com diretorio' }
$count += 2
Write-Output "$count verificacoes de seguranca passaram. Sem acesso a rede."
