@echo off
rem Esferas padrao + regua: diametro por limiar, StarDist e StarDist+SAM 2 (escala so da regua).
rem Fotos em ..\PelotaVision-local\datasets\esferas-11mm-20261111 ; saida em ..\PelotaVision-local\results\esferas-*
setlocal DisableDelayedExpansion
set "PSModulePath="
set "PVL=%~dp0..\PelotaVision-local"
"%PVL%\envs\sam2-export\Scripts\python.exe" "%~dp0tools\sam2\avaliar_esferas.py" --fotos "%PVL%\datasets\esferas-11mm-20261111" --saida "%PVL%\results\esferas-sam2" --modelo "%PVL%\models\sam2" %*
set "PV_EXIT=%ERRORLEVEL%"
if not defined PV_NONINTERACTIVE pause
exit /b %PV_EXIT%
