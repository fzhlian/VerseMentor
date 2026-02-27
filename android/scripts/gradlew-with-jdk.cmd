@echo off
setlocal
set SCRIPT_DIR=%~dp0
powershell -ExecutionPolicy Bypass -File "%SCRIPT_DIR%gradlew-with-jdk.ps1" %*
exit /b %ERRORLEVEL%
