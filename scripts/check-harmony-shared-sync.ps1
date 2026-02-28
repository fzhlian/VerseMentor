param()

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$leftDir = Join-Path $repoRoot "harmonyos\entry\src\main\ets\shared"
$rightDir = Join-Path $repoRoot "harmony-next\entry\src\main\ets\ets\shared"

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

$failures = @()

foreach ($file in $files) {
    $left = Join-Path $leftDir $file
    $right = Join-Path $rightDir $file

    if (-not (Test-Path $left)) {
        $failures += "Missing file: $left"
        continue
    }
    if (-not (Test-Path $right)) {
        $failures += "Missing file: $right"
        continue
    }

    $leftHash = (Get-FileHash $left -Algorithm SHA256).Hash
    $rightHash = (Get-FileHash $right -Algorithm SHA256).Hash

    if ($leftHash -ne $rightHash) {
        $failures += "Hash mismatch for ${file}:`n  $left`n  $right"
        continue
    }

    Write-Host "[OK] $file" -ForegroundColor DarkGray
}

if ($failures.Count -gt 0) {
    Write-Host ""
    Write-Host "Harmony shared file sync check failed:" -ForegroundColor Red
    foreach ($failure in $failures) {
        Write-Host "- $failure" -ForegroundColor Red
    }
    exit 1
}

Write-Host ""
Write-Host "Harmony shared file sync check passed." -ForegroundColor Green
exit 0
