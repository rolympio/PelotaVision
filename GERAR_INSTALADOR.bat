@echo off
rem Gera o instalador Windows (Inno Setup 6) a partir do ultimo pacote ja testado, sem recompilar.
rem Precisa do Inno Setup 6:  winget install --id JRSoftware.InnoSetup -e
rem Saida: ..\PelotaVision-local\results\windows-<execucao>\PelotaVision_Windows_<versao>_instalador.exe
setlocal DisableDelayedExpansion
set "PSModulePath="
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\windows\ambiente.ps1" -Action Instalador %*
set "PV_EXIT=%ERRORLEVEL%"
if not defined PV_NONINTERACTIVE pause
exit /b %PV_EXIT%
