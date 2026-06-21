@echo off
rem ============================================================================
rem  FFTT04M Desktop App — portable launcher.
rem  Runs the built CoughAnalyzer.jar. Uses %~dp0 (this file's own folder), so it
rem  works from ANY drive letter — copy the whole AndroidProjects folder to a USB
rem  stick and double-click this. The app discovers its data + GPU dirs itself
rem  (no CUDA toolkit install needed); it falls back to CPU if there's no GPU.
rem ============================================================================
setlocal
set "HERE=%~dp0"
rem Works whether this file sits at the AndroidProjects root (…\FFTT04D\desktop\…) or inside FFTT04D (…\desktop\…).
set "JAR=%HERE%FFTT04D\desktop\build\libs\CoughAnalyzer.jar"
set "NATIVE=%HERE%FFTT04D\desktop\native"
if not exist "%JAR%" set "JAR=%HERE%desktop\build\libs\CoughAnalyzer.jar"
if not exist "%NATIVE%\cuda" set "NATIVE=%HERE%desktop\native"

rem Put the bundled GPU libs (cuFFT/cuDNN/cuBLAS) on PATH if present (GPU acceleration; harmless if absent).
if exist "%NATIVE%\cuda" set "PATH=%NATIVE%;%NATIVE%\cuda;%PATH%"

if not exist "%JAR%" (
  echo.
  echo   CoughAnalyzer.jar not found at:
  echo     %JAR%
  echo.
  echo   Build it once with:
  echo     cd /d "%HERE%FFTT04D"  ^&^&  gradlew :desktop:fatJar -PuseOnnxGpu
  echo.
  pause
  exit /b 1
)

where java >nul 2>nul
if errorlevel 1 (
  echo.
  echo   Java was not found on PATH. Install a JRE/JDK 8 or newer, or add java to PATH.
  echo.
  pause
  exit /b 1
)

rem Launch windowless (javaw) so no console lingers; fall back to java if javaw is missing.
where javaw >nul 2>nul && (start "FFTT04M Desktop" javaw -jar "%JAR%" %*) || (java -jar "%JAR%" %*)
