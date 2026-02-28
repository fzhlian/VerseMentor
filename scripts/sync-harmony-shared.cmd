@echo off
setlocal
set SCRIPT_DIR=%~dp0
powershell -ExecutionPolicy Bypass -File "%SCRIPT_DIR%sync-harmony-shared.ps1" %*
exit /b %ERRORLEVEL%
