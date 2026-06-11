@echo off
REM FFTT04M Desktop Analyzer Launcher
REM Launches the cough analysis desktop app

cd /d "%~dp0"
java -jar desktop\build\libs\CoughAnalyzer.jar
