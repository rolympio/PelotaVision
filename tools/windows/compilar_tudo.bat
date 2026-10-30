@echo off
rem =====================================================================================
rem  PelotaVision - do download do codigo ate o pacote Windows (e o APK, se houver SDK)
rem
rem  Uso:   compilar_tudo.bat  [pasta_de_trabalho]  [branch]
rem         padrao: E:\PelotaVision\build   integracao/0.16.0
rem  Opcoes (defina antes de rodar, ex.: set SEM_TESTES=1):
rem         SEM_TESTES=1   pula os testes do Windows
rem         SEM_ANDROID=1  nao compila o APK
rem
rem  Precisa: Git for Windows, JDK 17 COMPLETO (com jpackage; ex. Temurin 17), internet.
rem  Opcional: Android SDK (APK), Inno Setup 6 (instalador).
rem  Log completo: <pasta>\compilar_tudo.log
rem =====================================================================================
setlocal enabledelayedexpansion

set "WORK=%~1"
if "%WORK%"=="" set "WORK=E:\PelotaVision\build"
set "BRANCH=%~2"
if "%BRANCH%"=="" set "BRANCH=integracao/0.16.0"
set "REPO=https://github.com/rolympio/PelotaVision.git"
set "SRC=%WORK%\pelotavision"
rem OpenCV: usa C:\opencv-4.12.0 se ja existir; senao baixa e extrai dentro da pasta de trabalho.
set "OPENCV=C:\opencv-4.12.0"
set "OPENCV_URL=https://github.com/opencv/opencv/releases/download/4.12.0/opencv-4.12.0-windows.exe"

if not exist "%WORK%" mkdir "%WORK%"
set "LOG=%WORK%\compilar_tudo.log"
echo ==== %DATE% %TIME% ==== > "%LOG%"
echo Pasta: %WORK%   Branch: %BRANCH%
echo Log:   %LOG%
echo.

rem ---------------------------------------------------------------- 1. ferramentas
echo [1/7] Conferindo ferramentas...
where git >nul 2>&1 || (echo ERRO: Git nao encontrado. Instale o Git for Windows. & goto :falha)

rem JDK 17 com jpackage: usa JAVA_HOME; se nao servir, procura o Temurin/Microsoft/Oracle 17 instalado.
set "JDK="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\jpackage.exe" set "JDK=%JAVA_HOME%"
if not defined JDK for /d %%d in ("%ProgramFiles%\Eclipse Adoptium\jdk-17*" "%ProgramFiles%\Microsoft\jdk-17*" "%ProgramFiles%\Java\jdk-17*" "%ProgramFiles%\Zulu\zulu-17*") do if exist "%%~d\bin\jpackage.exe" set "JDK=%%~d"
if not defined JDK (
  echo ERRO: JDK 17 com jpackage nao encontrado.
  echo       Instale o Temurin 17 ^(https://adoptium.net^) ou defina JAVA_HOME para um JDK 17 completo.
  goto :falha
)
set "JAVA_HOME=%JDK%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo       JDK: %JAVA_HOME%
"%JAVA_HOME%\bin\java" -version 2>&1 | findstr /i "version" >> "%LOG%"

rem ---------------------------------------------------------------- 2. codigo
echo [2/7] Baixando o codigo (%BRANCH%)...
git config --global http.version HTTP/1.1
git config --global http.lowSpeedLimit 0
git config --global http.lowSpeedTime 999999
if exist "%SRC%\.git" goto :atualizar

:clonar_inicio
if exist "%SRC%" rmdir /s /q "%SRC%"
set /a TENT=0
:clonar
set /a TENT+=1
echo       download completo, tentativa %TENT% de 5 (~270 MB; quedas de conexao sao comuns)
git clone --depth 1 -b %BRANCH% %REPO% "%SRC%" >> "%LOG%" 2>&1
if not errorlevel 1 goto :codigo_ok
if exist "%SRC%" rmdir /s /q "%SRC%"
if %TENT% lss 5 (timeout /t 10 /nobreak >nul & goto :clonar)
echo ERRO: nao foi possivel baixar o codigo. Ultimas linhas do log:
powershell -NoProfile -Command "Get-Content -Tail 10 '%LOG%'"
echo Confira o login no GitHub e a rede; se estiver na rede da Vale, tente outra rede.
goto :falha

:atualizar
echo       ja existe uma copia: atualizando
git -C "%SRC%" remote set-url origin %REPO% >> "%LOG%" 2>&1
set /a TENT=0
:buscar
set /a TENT+=1
echo       atualizacao, tentativa %TENT% de 3
git -C "%SRC%" fetch --depth 1 origin %BRANCH% >> "%LOG%" 2>&1
if not errorlevel 1 goto :buscou
if %TENT% lss 3 (timeout /t 10 /nobreak >nul & goto :buscar)
echo       a atualizacao falhou; apagando a copia antiga e baixando de novo. Motivo:
powershell -NoProfile -Command "Get-Content -Tail 6 '%LOG%'"
goto :clonar_inicio
:buscou
git -C "%SRC%" checkout -f -B %BRANCH% FETCH_HEAD >> "%LOG%" 2>&1
if errorlevel 1 (
  echo       checkout falhou; apagando a copia antiga e baixando de novo
  goto :clonar_inicio
)

:codigo_ok
for /f %%c in ('git -C "%SRC%" rev-parse --short HEAD') do set COMMIT=%%c
echo       commit %COMMIT%

rem ---------------------------------------------------------------- 3. OpenCV
echo [3/7] OpenCV 4.12.0...
if exist "%OPENCV%\opencv\build\java\opencv-4120.jar" goto :opencv_ok
set "OPENCV=%WORK%\opencv-4.12.0"
if exist "%OPENCV%\opencv\build\java\opencv-4120.jar" goto :opencv_ok
echo       nao encontrado: baixando ^(~200 MB^)
set "OCVEXE=%WORK%\opencv-4.12.0-windows.exe"
if exist "%OCVEXE%" goto :extrair
curl.exe -L --retry 5 --retry-delay 5 -C - -o "%OCVEXE%" "%OPENCV_URL%" || (echo ERRO: download do OpenCV falhou. Baixe a mao: %OPENCV_URL% & goto :falha)
:extrair
echo       extraindo em %OPENCV%
"%OCVEXE%" -o"%OPENCV%" -y >> "%LOG%" 2>&1
if not exist "%OPENCV%\opencv\build\java\opencv-4120.jar" (echo ERRO: extracao do OpenCV falhou ^(veja o log^). & goto :falha)
:opencv_ok
if not exist "%SRC%\desktop\opencv-java\x64" mkdir "%SRC%\desktop\opencv-java\x64"
copy /y "%OPENCV%\opencv\build\java\opencv-4120.jar" "%SRC%\desktop\opencv-java\" >nul
copy /y "%OPENCV%\opencv\build\java\x64\opencv_java4120.dll" "%SRC%\desktop\opencv-java\x64\" >nul
echo       copiado para desktop\opencv-java

rem ---------------------------------------------------------------- 4. compilar Windows
cd /d "%SRC%"
call gradlew.bat --stop >nul 2>&1
if "%SEM_TESTES%"=="1" (
  echo [4/7] Compilando o Windows ^(sem testes^)...
  call gradlew.bat -p desktop jar runtimeLibs --no-daemon --stacktrace >> "%LOG%" 2>&1
) else (
  echo [4/7] Compilando e testando o Windows ^(primeira vez: ~10-20 min, baixa ~300 MB^)...
  call gradlew.bat -p desktop test jar runtimeLibs --no-daemon --stacktrace >> "%LOG%" 2>&1
)
if errorlevel 1 (
  echo ERRO na compilacao/testes do Windows. Veja o final do log:
  powershell -NoProfile -Command "Get-Content -Tail 40 '%LOG%'"
  echo Relatorio dos testes: %SRC%\desktop\build\reports\tests\test\index.html
  echo Para empacotar mesmo assim: set SEM_TESTES=1 e rode de novo.
  goto :falha
)
echo       OK

rem ---------------------------------------------------------------- 5. empacotar Windows
echo [5/7] Empacotando ^(PelotaVision.exe, zip portatil, instalador^)...
call desktop\installer\empacotar.bat >> "%LOG%" 2>&1
if not exist "%SRC%\pkg\dist\PelotaVision\PelotaVision.exe" (
  echo ERRO no empacotamento. Final do log:
  powershell -NoProfile -Command "Get-Content -Tail 30 '%LOG%'"
  goto :falha
)
findstr /c:"Autoteste OK" "%LOG%" >nul && (echo       autoteste OK) || (echo       ATENCAO: autoteste sem exit=0 - veja %SRC%\pkg\autoteste\autoteste.txt)

rem ---------------------------------------------------------------- 6. Android (opcional)
if "%SEM_ANDROID%"=="1" goto :sem_android
set "SDK=%LOCALAPPDATA%\Android\Sdk"
if defined ANDROID_HOME set "SDK=%ANDROID_HOME%"
if not exist "%SDK%\platforms\android-35" (
  echo [6/7] Android: pulado ^(SDK sem platforms\android-35 em %SDK%^)
  goto :sem_android
)
echo [6/7] Compilando o APK...
set "SDKESC=%SDK:\=\\%"
set "SDKESC=%SDKESC::=\:%"
> local.properties echo sdk.dir=%SDKESC%
call gradlew.bat assembleDebug testDebugUnitTest lintDebug --no-daemon --stacktrace >> "%LOG%" 2>&1
if errorlevel 1 (echo       ATENCAO: o APK falhou ^(o pacote Windows esta pronto^). Veja o log. & goto :sem_android)
echo       OK
:sem_android

rem ---------------------------------------------------------------- 7. resumo
echo [7/7] Copiando os resultados para %WORK%\saida...
set "OUT=%WORK%\saida"
if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%OUT%"
copy /y "%SRC%\pkg\*.zip" "%OUT%\" >nul 2>&1
copy /y "%SRC%\pkg\installer\*.exe" "%OUT%\" >nul 2>&1
copy /y "%SRC%\app\build\outputs\apk\debug\app-arm64-v8a-debug.apk" "%OUT%\PelotaVision_android_arm64_debug.apk" >nul 2>&1
pushd "%OUT%"
if exist "%WORK%\SHA256.tmp" del "%WORK%\SHA256.tmp"
for %%f in (*.*) do for /f "delims=" %%h in ('certutil -hashfile "%%f" SHA256 ^| findstr /v ":"') do echo %%h  %%f>> "%WORK%\SHA256.tmp"
if exist "%WORK%\SHA256.tmp" move /y "%WORK%\SHA256.tmp" SHA256.txt >nul
popd
echo.
echo ===================== PRONTO (commit %COMMIT%) =====================
echo  Programa Windows:  %SRC%\pkg\dist\PelotaVision\PelotaVision.exe
echo                     ^(a pasta PelotaVision inteira e o programa^)
echo  Para distribuir:   %OUT%
dir /b "%OUT%"
echo  Log:               %LOG%
echo ====================================================================
endlocal
exit /b 0

:falha
echo.
echo Parou com erro. Log completo: %LOG%
endlocal
exit /b 1
