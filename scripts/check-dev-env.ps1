$ErrorActionPreference = 'Continue'

$checks = @(
  @{ Name = 'git --version'; Cmd = 'git --version' },
  @{ Name = 'node -v'; Cmd = 'node -v' },
  @{ Name = 'python --version'; Cmd = 'python --version' },
  @{ Name = 'conda --version'; Cmd = 'conda --version' },
  @{ Name = 'conda run -n py312 python --version'; Cmd = 'conda run -n py312 python --version' },
  @{ Name = 'curl.exe --version'; Cmd = 'curl.exe --version' },
  @{ Name = '7z'; Cmd = '7z' }
)

foreach ($check in $checks) {
  Write-Host "=== $($check.Name) ===" -ForegroundColor Cyan
  try {
    $out = Invoke-Expression $check.Cmd 2>&1
    if ($check.Name -eq 'curl.exe --version') {
      $out | Select-Object -First 1
    } elseif ($check.Name -eq '7z') {
      $out | Select-Object -First 2
    } else {
      $out
    }
  } catch {
    Write-Host "FAILED: $($_.Exception.Message)" -ForegroundColor Red
  }
  Write-Host ''
}
