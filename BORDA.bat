@echo off
rem Rede de borda + dados externos (Roboflow). Ex.: BORDA.bat testes ^| baixar --prioridade 2 ^| converter ^<id^> ^| auditar ^<id^> ^| pretreino ^| exportar ^| avaliar ^<pasta^>
rem Saidas em ..\PelotaVision-local (fora do Git). Requer PREPARAR_AGENTE_LOCAL.bat uma vez.
setlocal DisableDelayedExpansion
set "PSModulePath="
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\agente-local\borda.ps1" %*
set "PV_EXIT=%ERRORLEVEL%"
if not defined PV_NONINTERACTIVE pause
exit /b %PV_EXIT%
