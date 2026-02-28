param(
    [switch]$DryRun = $false
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$sourceDir = Join-Path $repoRoot "harmonyos\entry\src\main\ets\shared"
$targetDir = Join-Path $repoRoot "harmony-next\entry\src\main\ets\ets\shared"

$files = @(
    "session_controller.ts",
    "shared_core_bridge_host.ts",
    "shared_core_delegate_demo.ts",
    "shared_core_runtime.ts",
    "speech.ts",
    "speech_impl.ts",
    "storage.ts",
    "storage_impl.ts",
    "title_matcher.ts"
)

$copied = @()

foreach ($file in $files) {
    $source = Join-Path $sourceDir $file
    $target = Join-Path $targetDir $file

    if (-not (Test-Path $source)) {
        throw "Missing source file: $source"
    }
    if (-not (Test-Path $target)) {
        throw "Missing target file: $target"
    }

    if ($DryRun) {
        Write-Host "[DRY] copy $file" -ForegroundColor DarkGray
        continue
    }

    Copy-Item $source $target -Force
    $copied += $file
    Write-Host "[OK] copied $file" -ForegroundColor DarkGray
}

if ($DryRun) {
    Write-Host ""
    Write-Host "Dry run complete. No files copied." -ForegroundColor Green
    exit 0
}

Write-Host ""
Write-Host "Harmony shared sync complete: $($copied.Count) file(s) copied." -ForegroundColor Green
exit 0
