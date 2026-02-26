@echo off
setlocal

set GRADLE_VERSION=8.2.1
set DIST_URL=https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip
set WRAPPER_DIR=%~dp0\.gradle-wrapper
set GRADLE_DIR=%WRAPPER_DIR%\gradle-%GRADLE_VERSION%
set GRADLE_BIN=%GRADLE_DIR%\bin\gradle.bat

if not exist "%GRADLE_BIN%" (
  if not exist "%WRAPPER_DIR%" mkdir "%WRAPPER_DIR%"
  echo Downloading Gradle %GRADLE_VERSION%...
  powershell -NoProfile -Command "Invoke-WebRequest -ErrorAction Stop -Uri '%DIST_URL%' -OutFile '%WRAPPER_DIR%\gradle-%GRADLE_VERSION%-bin.zip'"
  if errorlevel 1 exit /b 1
  powershell -NoProfile -Command "Expand-Archive -ErrorAction Stop -Force -Path '%WRAPPER_DIR%\gradle-%GRADLE_VERSION%-bin.zip' -DestinationPath '%WRAPPER_DIR%'"
  if errorlevel 1 exit /b 1
)

"%GRADLE_BIN%" %*
exit /b %ERRORLEVEL%

endlocal
