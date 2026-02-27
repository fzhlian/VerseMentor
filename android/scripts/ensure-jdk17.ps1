param(
    [string]$JavaHome = "",
    [switch]$AutoDownload = $false,
    [string]$InstallRoot = ""
)

$ErrorActionPreference = "Stop"

function Test-JdkHome {
    param([string]$JdkHome)

    if (-not $JdkHome) { return $false }
    $javaExe = Join-Path $JdkHome "bin\java.exe"
    $javacExe = Join-Path $JdkHome "bin\javac.exe"
    $modulesFile = Join-Path $JdkHome "lib\modules"

    return (Test-Path $javaExe) -and (Test-Path $javacExe) -and (Test-Path $modulesFile)
}

function Find-JdkCandidates {
    $roots = @()
    if ($env:ProgramFiles) {
        $roots += Join-Path $env:ProgramFiles "Java"
        $roots += Join-Path $env:ProgramFiles "Eclipse Adoptium"
        $roots += Join-Path $env:ProgramFiles "Microsoft"
        $roots += Join-Path $env:ProgramFiles "Android\Android Studio\jbr"
    }
    if ($env:LOCALAPPDATA) {
        $roots += Join-Path $env:LOCALAPPDATA "Programs\Android Studio\jbr"
    }

    $seen = @{}
    $result = @()

    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }

        $dirs = @()
        if (Test-Path (Join-Path $root "bin\java.exe")) {
            $dirs += (Get-Item $root)
        }
        $dirs += Get-ChildItem -Path $root -Directory -ErrorAction SilentlyContinue

        foreach ($dir in $dirs) {
            $full = $dir.FullName
            if ($seen.ContainsKey($full)) { continue }
            $seen[$full] = $true

            if (Test-JdkHome $full) {
                $result += $full
            }
        }
    }

    return $result | Sort-Object -Unique
}

function Resolve-InstallRoot {
    if ($InstallRoot) { return $InstallRoot }
    if (-not $env:LOCALAPPDATA) {
        throw "LOCALAPPDATA is not set; cannot determine JDK install root."
    }
    return (Join-Path $env:LOCALAPPDATA "VerseMentor\jdks")
}

function Ensure-DownloadedTemurin17 {
    param([string]$Root)

    $extractRoot = Join-Path $Root "temurin17"
    if (Test-Path $extractRoot) {
        $existing = Get-ChildItem -Path $extractRoot -Directory -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending
        foreach ($dir in $existing) {
            if (Test-JdkHome $dir.FullName) {
                return $dir.FullName
            }
        }
    }

    New-Item -ItemType Directory -Force -Path $Root | Out-Null
    $zipPath = Join-Path $Root "temurin17.zip"
    if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
    if (Test-Path $extractRoot) { Remove-Item $extractRoot -Recurse -Force }

    $api = "https://api.adoptium.net/v3/assets/latest/17/hotspot?image_type=jdk&os=windows&architecture=x64&heap_size=normal&jvm_impl=hotspot&vendor=eclipse"
    $assets = Invoke-RestMethod -Uri $api
    if (-not $assets -or $assets.Count -lt 1) {
        throw "No JDK assets returned by Adoptium API."
    }
    $download = $assets[0].binary.package.link
    if (-not $download) {
        throw "Missing package link from Adoptium API."
    }

    Invoke-WebRequest -Uri $download -OutFile $zipPath
    Expand-Archive -Path $zipPath -DestinationPath $extractRoot -Force

    $downloaded = Get-ChildItem -Path $extractRoot -Directory -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $downloaded) {
        throw "Downloaded archive did not produce a JDK directory."
    }
    if (-not (Test-JdkHome $downloaded.FullName)) {
        throw "Downloaded JDK is incomplete: $($downloaded.FullName)"
    }
    return $downloaded.FullName
}

$candidates = @()
if ($JavaHome) { $candidates += $JavaHome }
if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
$candidates += Find-JdkCandidates

$seen = @{}
foreach ($candidate in $candidates) {
    if (-not $candidate) { continue }
    if ($seen.ContainsKey($candidate)) { continue }
    $seen[$candidate] = $true
    if (Test-JdkHome $candidate) {
        Write-Output $candidate
        exit 0
    }
}

if (-not $AutoDownload) {
    Write-Error "No complete JDK 17 found. Re-run with -AutoDownload or set JAVA_HOME to a valid JDK."
    exit 1
}

$installRoot = Resolve-InstallRoot
$downloadedJdk = Ensure-DownloadedTemurin17 -Root $installRoot
Write-Output $downloadedJdk
exit 0
