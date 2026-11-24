@echo off
rem Compara StarDist x StarDist + SAM 2 nas fotos de referencia, sem recompilar. Saida em ..\PelotaVision-local\results\sam2-avaliacao-*
setlocal DisableDelayedExpansion
rem Aberto do PowerShell 7, o PSModulePath herdado quebra modulos do Windows PowerShell 5.1.
set "PSModulePath="
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\windows\ambiente.ps1" -Action Verificar -AvaliarSam2 %*
set "PV_EXIT=%ERRORLEVEL%"
if not defined PV_NONINTERACTIVE pause
exit /b %PV_EXIT%
