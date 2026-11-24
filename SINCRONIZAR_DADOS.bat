@echo off
setlocal DisableDelayedExpansion
rem Aberto do PowerShell 7, o PSModulePath herdado quebra modulos do Windows PowerShell 5.1.
set "PSModulePath="
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\windows\ambiente.ps1" -Action Sincronizar %*
set "PV_EXIT=%ERRORLEVEL%"
if not defined PV_NONINTERACTIVE pause
exit /b %PV_EXIT%
