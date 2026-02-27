param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs
)

$ErrorActionPreference = "Stop"

$scriptDir = $PSScriptRoot
$projectRoot = (Resolve-Path (Join-Path $scriptDir "..")).Path
$ensureScript = Join-Path $scriptDir "ensure-jdk17.ps1"

$jdkHome = (& powershell -ExecutionPolicy Bypass -File $ensureScript -AutoDownload) | Select-Object -Last 1
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($jdkHome)) {
    throw "Failed to resolve a complete JDK 17."
}

$jdkHome = $jdkHome.Trim()
$env:JAVA_HOME = $jdkHome
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

if (-not $GradleArgs -or $GradleArgs.Count -eq 0) {
    $GradleArgs = @("tasks")
}

Push-Location $projectRoot
try {
    & .\gradlew.bat @GradleArgs
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
