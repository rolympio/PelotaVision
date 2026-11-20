@echo off
rem Rajada em lote (0.19.0): laudo + empilhada (+ SR 2x se o laudo permitir) de N fotos da mesma cena.
rem Exemplo: RAJADA.bat --saida ..\PelotaVision-local\results\rajada-01 --sr D:\fotos\rajada-01
rem Ver docs\rajada-burst.md e tools\burst_validate\README.md.
setlocal DisableDelayedExpansion
set "PSModulePath="
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\burst_validate\rajada.ps1" %*
set "PV_EXIT=%ERRORLEVEL%"
if not defined PV_NONINTERACTIVE pause
exit /b %PV_EXIT%
