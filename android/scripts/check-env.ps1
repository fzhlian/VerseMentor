param(
    [string]$JavaHome = "",
    [string]$SdkDir = ""
)

$ErrorActionPreference = "Stop"

function Resolve-JavaHome {
    if ($JavaHome) { return $JavaHome }
    if ($env:JAVA_HOME) { return $env:JAVA_HOME }
    return ""
}

function Resolve-SdkDir {
    if ($SdkDir) { return $SdkDir }
    if ($env:ANDROID_HOME) { return $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { return $env:ANDROID_SDK_ROOT }

    $localProps = Join-Path $PSScriptRoot "..\local.properties"
    if (Test-Path $localProps) {
        $line = Get-Content $localProps | Where-Object { $_ -match "^\s*sdk\.dir\s*=" } | Select-Object -First 1
        if ($line) {
            $raw = ($line -split "=", 2)[1].Trim()
            if ($raw) {
                # local.properties escapes backslash as \\.
                return $raw -replace "\\\\", "\"
            }
        }
    }
    return ""
}

function Print-Section($title) {
    Write-Host ""
    Write-Host "=== $title ==="
}

$failed = $false

Print-Section "JAVA"
$resolvedJavaHome = Resolve-JavaHome
if (-not $resolvedJavaHome) {
    Write-Host "FAIL: JAVA_HOME is not set."
    $failed = $true
} else {
    $javaExe = Join-Path $resolvedJavaHome "bin\java.exe"
    $modulesFile = Join-Path $resolvedJavaHome "lib\modules"

    Write-Host "JAVA_HOME: $resolvedJavaHome"
    if (-not (Test-Path $javaExe)) {
        Write-Host "FAIL: java.exe not found at $javaExe"
        $failed = $true
    } elseif (-not (Test-Path $modulesFile)) {
        Write-Host "FAIL: JDK appears incomplete (missing lib\\modules)."
        $failed = $true
    } else {
        Write-Host "PASS: JDK layout looks valid."
        & $javaExe -version
    }
}

Print-Section "ANDROID SDK"
$resolvedSdkDir = Resolve-SdkDir
if (-not $resolvedSdkDir) {
    Write-Host "FAIL: Android SDK path is not configured."
    Write-Host "Hint: set ANDROID_HOME or create android/local.properties with sdk.dir=..."
    $failed = $true
} else {
    Write-Host "SDK: $resolvedSdkDir"
    if (-not (Test-Path $resolvedSdkDir)) {
        Write-Host "FAIL: SDK directory does not exist."
        $failed = $true
    } else {
        $platforms = Join-Path $resolvedSdkDir "platforms"
        $buildTools = Join-Path $resolvedSdkDir "build-tools"
        $ok = $true
        if (-not (Test-Path $platforms)) {
            Write-Host "FAIL: platforms/ not found under SDK."
            $ok = $false
        }
        if (-not (Test-Path $buildTools)) {
            Write-Host "FAIL: build-tools/ not found under SDK."
            $ok = $false
        }
        if ($ok) {
            Write-Host "PASS: SDK base structure exists."
            Get-ChildItem $platforms -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name | ForEach-Object { Write-Host "  platform: $_" }
            Get-ChildItem $buildTools -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name | ForEach-Object { Write-Host "  build-tools: $_" }
        } else {
            $failed = $true
        }
    }
}

Print-Section "RESULT"
if ($failed) {
    Write-Host "Environment check failed."
    exit 1
}

Write-Host "Environment check passed."
exit 0
