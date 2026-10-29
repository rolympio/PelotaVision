@echo off
rem PelotaVision Windows: monta a versao portatil (PelotaVision.exe) e, se o Inno Setup 6 estiver
rem instalado, o instalador. Mesmos passos de .github/workflows/windows.yml, sem o GitHub Actions.
rem Rode na raiz do repositorio, DEPOIS de: gradlew.bat -p desktop test jar runtimeLibs --no-daemon
rem Requisitos: JDK 17 com jpackage (JAVA_HOME), OpenCV em desktop\opencv-java (jar + x64\dll).
setlocal enabledelayedexpansion
cd /d "%~dp0\..\.."

if not defined JAVA_HOME (echo JAVA_HOME nao definido. Aponte para um JDK 17. & exit /b 1)
if not exist "%JAVA_HOME%\bin\jpackage.exe" (echo jpackage nao encontrado em %JAVA_HOME%\bin. Use um JDK 17 completo. & exit /b 1)
if not exist desktop\build\libs\pelotavision-desktop.jar (echo Falta desktop\build\libs\pelotavision-desktop.jar. Rode antes: gradlew.bat -p desktop test jar runtimeLibs & exit /b 1)
if not exist desktop\opencv-java\x64\opencv_java4120.dll (echo Falta desktop\opencv-java\x64\opencv_java4120.dll & exit /b 1)

for /f "tokens=3 delims= '" %%v in ('findstr /b "version = " desktop\build.gradle') do set VN=%%v
if "%VN%"=="" (echo Versao nao encontrada em desktop\build.gradle & exit /b 1)
echo Versao %VN%

set IN=pkg\input
if exist pkg rmdir /s /q pkg
mkdir %IN%\modelos
mkdir pkg\dist
copy /y desktop\build\libs\pelotavision-desktop.jar %IN%\ >nul
copy /y desktop\build\runtime-libs\*.jar %IN%\ >nul
copy /y desktop\opencv-java\x64\opencv_java4120.dll %IN%\ >nul
for %%d in (vcruntime140.dll vcruntime140_1.dll msvcp140.dll) do if exist "%JAVA_HOME%\bin\%%d" copy /y "%JAVA_HOME%\bin\%%d" %IN%\ >nul
set A=app\src\main\assets
for %%f in (crack_forest.pvrf metadata.json pellet_stardist.onnx pellet_stardist.json pellet_stardist_belt.onnx pellet_stardist_belt.json crack_mobilenetv3.onnx crack_mobilenetv3.json) do if exist %A%\%%f copy /y %A%\%%f %IN%\modelos\ >nul
copy /y %A%\FSRCNN_* %IN%\modelos\ >nul

echo Gerando PelotaVision.exe (jpackage)...
"%JAVA_HOME%\bin\jpackage" --type app-image --name PelotaVision --app-version %VN% --input %IN% --main-jar pelotavision-desktop.jar --main-class br.com.pelotavision.DesktopApp --icon desktop\installer\PelotaVision.ico --java-options "-Xmx3g" --vendor PelotaVision --description "PelotaVision Windows %VN%" --dest pkg\dist
if errorlevel 1 (echo jpackage falhou. & exit /b 1)
copy /y desktop\LEIA-ME.txt pkg\dist\PelotaVision\ >nul

echo Autoteste...
pkg\dist\PelotaVision\PelotaVision.exe --autoteste verification\fixtures\1000411645.jpg pkg\autoteste
findstr /c:"exit=0" pkg\autoteste\autoteste.txt >nul && echo Autoteste OK || echo ATENCAO: autoteste sem exit=0, veja pkg\autoteste\autoteste.txt

echo Compactando versao portatil...
powershell -NoProfile -Command "Compress-Archive -Force -Path 'pkg\dist\PelotaVision' -DestinationPath 'pkg\PelotaVision_Windows_%VN%_portatil.zip'"

set ISCC=%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe
if exist "%ISCC%" (
  echo Gerando instalador...
  "%ISCC%" /Q /DAppVersion=%VN% /DSourceDir="%CD%\pkg\dist\PelotaVision" /DOutputDir="%CD%\pkg\installer" desktop\installer\PelotaVision.iss
) else (
  echo Inno Setup 6 nao encontrado: instalador nao gerado, so a versao portatil.
)

echo.
echo Pronto:
echo   Programa:   %CD%\pkg\dist\PelotaVision\PelotaVision.exe
echo   Portatil:   %CD%\pkg\PelotaVision_Windows_%VN%_portatil.zip
if exist pkg\installer echo   Instalador: %CD%\pkg\installer
endlocal
