@echo off
setlocal
set SCRIPT_DIR=%~dp0
powershell -ExecutionPolicy Bypass -File "%SCRIPT_DIR%ensure-jdk17.ps1" %*
exit /b %ERRORLEVEL%
