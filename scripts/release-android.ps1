param(
    [string]$Tag = "",
    [string]$Title = "",
    [string]$Notes = "",
    [switch]$SkipBuild = $false,
    [switch]$SkipPush = $false,
    [switch]$Draft = $false,
    [switch]$Prerelease = $false,
    [switch]$AllowDirty = $false,
    [switch]$DryRun = $false
)

$ErrorActionPreference = "Stop"

function Invoke-External {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string[]]$Command,
        [string]$Workdir = "",
        [switch]$ReadOnly = $false
    )

    if ($DryRun -and -not $ReadOnly) {
        $wd = if ([string]::IsNullOrWhiteSpace($Workdir)) { (Get-Location).Path } else { $Workdir }
        Write-Host "[DRY] $Name" -ForegroundColor DarkGray
        Write-Host "      workdir: $wd" -ForegroundColor DarkGray
        Write-Host "      command: $($Command -join ' ')" -ForegroundColor DarkGray
        return @{
            ExitCode = 0
            StdOut = @()
        }
    }

    $stdoutFile = [System.IO.Path]::GetTempFileName()
    $stderrFile = [System.IO.Path]::GetTempFileName()
    try {
        if ([string]::IsNullOrWhiteSpace($Workdir)) {
            & $Command[0] @($Command[1..($Command.Count - 1)]) 1>$stdoutFile 2>$stderrFile
        } else {
            Push-Location $Workdir
            try {
                & $Command[0] @($Command[1..($Command.Count - 1)]) 1>$stdoutFile 2>$stderrFile
            } finally {
                Pop-Location
            }
        }
        $exitCode = $LASTEXITCODE
        $stdout = Get-Content $stdoutFile -ErrorAction SilentlyContinue
        $stderr = Get-Content $stderrFile -ErrorAction SilentlyContinue
        if ($exitCode -ne 0) {
            if ($stdout) { $stdout | ForEach-Object { Write-Host $_ } }
            if ($stderr) { $stderr | ForEach-Object { Write-Host $_ } }
            throw "$Name failed (exit $exitCode)."
        }
        return @{
            ExitCode = $exitCode
            StdOut = @($stdout)
            StdErr = @($stderr)
        }
    } finally {
        Remove-Item $stdoutFile, $stderrFile -Force -ErrorAction SilentlyContinue
    }
}

function Require-Command {
    param([Parameter(Mandatory = $true)][string]$CommandName)
    if (-not (Get-Command $CommandName -ErrorAction SilentlyContinue)) {
        throw "Required command not found: $CommandName"
    }
}

function Parse-GithubRepo {
    param([Parameter(Mandatory = $true)][string]$RemoteUrl)
    $regex = "github\.com[:/](?<owner>[^/]+)/(?<repo>[^/.]+)(\.git)?$"
    $m = [regex]::Match($RemoteUrl, $regex)
    if (-not $m.Success) {
        throw "Cannot parse GitHub repo from remote URL: $RemoteUrl"
    }
    return "$($m.Groups['owner'].Value)/$($m.Groups['repo'].Value)"
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$androidDir = Join-Path $repoRoot "android"
$appGradleFile = Join-Path $repoRoot "android\app\build.gradle.kts"
$apkDir = Join-Path $repoRoot "android\app\build\outputs\apk\release"

Require-Command git
Require-Command gh

$gradleText = Get-Content $appGradleFile -Raw
$versionNameMatch = [regex]::Match($gradleText, 'versionName\s*=\s*"([^"]+)"')
if (-not $versionNameMatch.Success) {
    throw "Cannot read versionName from $appGradleFile"
}
$versionName = $versionNameMatch.Groups[1].Value

if ([string]::IsNullOrWhiteSpace($Tag)) {
    $Tag = "v$versionName"
}
if ([string]::IsNullOrWhiteSpace($Title)) {
    $Title = "VerseMentor $Tag"
}
if ([string]::IsNullOrWhiteSpace($Notes)) {
    $Notes = "Android release APK for $Tag."
}

Write-Host ""
Write-Host "Release target: $Tag" -ForegroundColor Cyan
Write-Host "Version name:   $versionName" -ForegroundColor Cyan

$remoteUrl = (Invoke-External -Name "Get origin URL" -Command @("git", "remote", "get-url", "origin") -Workdir $repoRoot -ReadOnly).StdOut | Select-Object -First 1
$repoSlug = Parse-GithubRepo -RemoteUrl $remoteUrl
Write-Host "GitHub repo:    $repoSlug" -ForegroundColor Cyan

$status = (Invoke-External -Name "Git status" -Command @("git", "status", "--porcelain") -Workdir $repoRoot -ReadOnly).StdOut
if (-not $AllowDirty -and $status.Count -gt 0) {
    throw "Git working tree is not clean. Commit/stash changes before release."
}

$headCommit = (Invoke-External -Name "HEAD commit" -Command @("git", "rev-parse", "HEAD") -Workdir $repoRoot -ReadOnly).StdOut | Select-Object -First 1
Push-Location $repoRoot
try {
    $tagVerify = (& cmd /c "git rev-parse --verify $Tag 2>nul")
    $hasTag = $LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace(($tagVerify | Select-Object -First 1))
} finally {
    Pop-Location
}
if ($hasTag) {
    $tagCommit = (Invoke-External -Name "Tag commit" -Command @("git", "rev-list", "-n", "1", "$Tag") -Workdir $repoRoot -ReadOnly).StdOut | Select-Object -First 1
    if ($tagCommit -ne $headCommit) {
        throw "Tag $Tag exists but does not point to HEAD ($headCommit)."
    }
} else {
    Invoke-External -Name "Create tag" -Command @("git", "tag", "$Tag") -Workdir $repoRoot | Out-Null
}

if (-not $SkipPush) {
    Invoke-External -Name "Push main" -Command @("git", "push", "origin", "main") -Workdir $repoRoot | Out-Null
    Invoke-External -Name "Push tag" -Command @("git", "push", "origin", "$Tag") -Workdir $repoRoot | Out-Null
}

if (-not $SkipBuild) {
    Invoke-External -Name "Build release APK" -Command @(".\gradlew", ":app:assembleRelease") -Workdir $androidDir | Out-Null
}

$expectedApkName = "VerseMentor-v$versionName-release.apk"
$expectedApkPath = Join-Path $apkDir $expectedApkName
if (-not (Test-Path $expectedApkPath)) {
    $fallback = Get-ChildItem -Path $apkDir -Filter "*.apk" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($null -eq $fallback) {
        throw "No APK found in $apkDir"
    }
    $expectedApkPath = $fallback.FullName
    $expectedApkName = $fallback.Name
}

$sha256 = if ($DryRun) { "dry-run" } else { (Get-FileHash -Path $expectedApkPath -Algorithm SHA256).Hash.ToLowerInvariant() }
Write-Host "APK:            $expectedApkName" -ForegroundColor Cyan
Write-Host "SHA256:         $sha256" -ForegroundColor Cyan

$releaseExists = $false
if ($DryRun) {
    Write-Host "[DRY] Check release existence for $Tag" -ForegroundColor DarkGray
} else {
    & gh release view $Tag --repo $repoSlug *> $null
    $releaseExists = $LASTEXITCODE -eq 0
}

$releaseFlags = @()
if ($Draft) { $releaseFlags += "--draft" }
if ($Prerelease) { $releaseFlags += "--prerelease" }

if ($releaseExists) {
    Invoke-External -Name "Upload APK asset" -Command @("gh", "release", "upload", $Tag, $expectedApkPath, "--repo", $repoSlug, "--clobber") -Workdir $repoRoot | Out-Null
    Invoke-External -Name "Update release metadata" -Command @("gh", "release", "edit", $Tag, "--repo", $repoSlug, "--title", $Title, "--notes", $Notes) -Workdir $repoRoot | Out-Null
} else {
    $cmd = @("gh", "release", "create", $Tag, $expectedApkPath, "--repo", $repoSlug, "--title", $Title, "--notes", $Notes) + $releaseFlags
    Invoke-External -Name "Create release" -Command $cmd -Workdir $repoRoot | Out-Null
}

$releaseJsonRaw = if ($DryRun) {
    "{`"url`":`"dry-run`",`"assets`":[{`"name`":`"$expectedApkName`",`"url`":`"dry-run`"}]}"
} else {
    (Invoke-External -Name "Read release" -Command @("gh", "release", "view", $Tag, "--repo", $repoSlug, "--json", "url,assets") -Workdir $repoRoot).StdOut -join ""
}
$releaseJson = $releaseJsonRaw | ConvertFrom-Json
$asset = $releaseJson.assets | Where-Object { $_.name -eq $expectedApkName } | Select-Object -First 1
if ($null -eq $asset) {
    throw "Release exists but APK asset not found: $expectedApkName"
}

Write-Host ""
Write-Host "Release completed." -ForegroundColor Green
Write-Host "Tag:             $Tag"
Write-Host "Release URL:     $($releaseJson.url)"
Write-Host "APK URL:         $($asset.url)"
Write-Host "APK SHA256:      $sha256"
