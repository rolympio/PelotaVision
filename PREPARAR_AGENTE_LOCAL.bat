@echo off
rem Prepara Ollama + opencode (agente local) e a venv da rede de borda. Pergunta antes de instalar. Ex.: PREPARAR_AGENTE_LOCAL.bat -Modelo gpt-oss:20b
setlocal DisableDelayedExpansion
set "PSModulePath="
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\agente-local\preparar.ps1" %*
set "PV_EXIT=%ERRORLEVEL%"
if not defined PV_NONINTERACTIVE pause
exit /b %PV_EXIT%
