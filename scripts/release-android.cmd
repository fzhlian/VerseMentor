@echo off
setlocal
set SCRIPT_DIR=%~dp0
powershell -ExecutionPolicy Bypass -File "%SCRIPT_DIR%release-android.ps1" %*
exit /b %ERRORLEVEL%
