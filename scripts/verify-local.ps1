param(
    [switch]$SkipAndroid = $false,
    [switch]$SkipSharedCore = $false,
    [switch]$SkipSharedCoreBuild = $false,
    [switch]$SkipSharedCoreBridgeBuild = $false,
    [switch]$SkipSharedCoreTests = $false,
    [switch]$SkipUnitTests = $false,
    [switch]$DryRun = $false
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$androidDir = Join-Path $repoRoot "android"
$sharedCoreDir = Join-Path $repoRoot "shared-core"
$resolvedAndroidJdk = $null

$steps = @()

if (-not $SkipAndroid) {
    if ($DryRun) {
        $resolvedAndroidJdk = "<auto-resolve via .\scripts\ensure-jdk17.cmd -AutoDownload>"
    } else {
        Write-Host ""
        Write-Host "=== Resolve Android JDK17 ===" -ForegroundColor Cyan
        Push-Location $androidDir
        try {
            $jdkOutput = & ".\scripts\ensure-jdk17.cmd" "-AutoDownload"
            if ($LASTEXITCODE -ne 0) {
                throw "Failed resolving Android JDK17 (exit $LASTEXITCODE)"
            }
            $resolvedAndroidJdk = $jdkOutput |
                ForEach-Object { $_.ToString().Trim() } |
                Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
                Where-Object { Test-Path $_ } |
                Select-Object -Last 1
            if ([string]::IsNullOrWhiteSpace($resolvedAndroidJdk)) {
                $resolvedAndroidJdk = ($jdkOutput | ForEach-Object { $_.ToString().Trim() } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Last 1)
            }
            if ([string]::IsNullOrWhiteSpace($resolvedAndroidJdk)) {
                throw "Resolved Android JDK17 path is empty."
            }
            Write-Host "JDK17: $resolvedAndroidJdk" -ForegroundColor DarkGray
        } finally {
            Pop-Location
        }
    }

    $steps += @{
        Name = "Android preflight"
        Workdir = $androidDir
        Command = @(".\scripts\check-env.cmd", "-JavaHome", $resolvedAndroidJdk)
    }
    $steps += @{
        Name = "Android compileDebugKotlin"
        Workdir = $androidDir
        Command = @(".\scripts\gradlew-with-jdk.cmd", ":app:compileDebugKotlin", "--console=plain")
    }
    if (-not $SkipUnitTests) {
        $steps += @{
            Name = "Android testDebugUnitTest"
            Workdir = $androidDir
            Command = @(".\scripts\gradlew-with-jdk.cmd", ":app:testDebugUnitTest", "--console=plain")
        }
    }
}

if (-not $SkipSharedCore) {
    if (-not $SkipSharedCoreBuild) {
        $steps += @{
            Name = "shared-core build"
            Workdir = $sharedCoreDir
            Command = @("npm.cmd", "run", "build")
        }
        if (-not $SkipSharedCoreBridgeBuild) {
            $steps += @{
                Name = "shared-core build:bridge"
                Workdir = $sharedCoreDir
                Command = @("npm.cmd", "run", "build:bridge")
            }
        }
    }
    if (-not $SkipSharedCoreTests) {
        $steps += @{
            Name = "shared-core test"
            Workdir = $sharedCoreDir
            Command = @("npm.cmd", "test")
        }
    }
}

if ($steps.Count -eq 0) {
    Write-Host "No steps selected. Nothing to run." -ForegroundColor Yellow
    exit 0
}

if ($DryRun) {
    Write-Host ""
    Write-Host "=== Planned Steps (Dry Run) ===" -ForegroundColor Cyan
    foreach ($step in $steps) {
        $commandText = $step.Command -join " "
        Write-Host "- $($step.Name)" -ForegroundColor DarkGray
        Write-Host "  workdir: $($step.Workdir)" -ForegroundColor DarkGray
        Write-Host "  command: $commandText" -ForegroundColor DarkGray
    }
    Write-Host ""
    Write-Host "Dry run complete. No commands executed." -ForegroundColor Green
    exit 0
}

foreach ($step in $steps) {
    Write-Host ""
    Write-Host "=== $($step.Name) ===" -ForegroundColor Cyan
    Push-Location $step.Workdir
    try {
        if ($step.Command.Count -le 1) {
            & $step.Command[0]
        } else {
            & $step.Command[0] @($step.Command[1..($step.Command.Count - 1)])
        }
        if ($LASTEXITCODE -ne 0) {
            throw "Step failed: $($step.Name) (exit $LASTEXITCODE)"
        }
    } finally {
        Pop-Location
    }
}

Write-Host ""
Write-Host "All selected checks passed." -ForegroundColor Green
exit 0
