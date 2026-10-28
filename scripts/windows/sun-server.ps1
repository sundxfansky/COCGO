# Builds and runs the cloud config service natively on Windows (no Docker).
# The Go backend serves the built web frontend itself, so one process replaces the
# backend + nginx containers from docker-compose.yml.
#
# Usage (from any directory):
#   scripts\windows\sun-server.bat                 build if needed, then run on port 3280
#   scripts\windows\sun-server.bat -Port 8080      use another port
#   scripts\windows\sun-server.bat -Rebuild        force a rebuild (after git pull)
#   scripts\windows\sun-server.bat -BuildOnly      only produce the portable build\sun-server folder
#   scripts\windows\sun-server.bat -DataDir D:\sun-data
param(
    [int]$Port = 3280,
    [string]$Listen = "",
    [string]$DataDir = "",
    [switch]$Rebuild,
    [switch]$BuildOnly
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$Out = Join-Path $Root "build\sun-server"
$Exe = Join-Path $Out "sun-cloud.exe"
$WebOut = Join-Path $Out "web"
if (-not $DataDir) { $DataDir = Join-Path $Root "backend\data" }

function Require-Tool([string]$Name, [string]$WingetId) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "$Name not found in PATH. Install it with: winget install -e --id $WingetId  (then reopen the terminal)"
    }
}

function Invoke-Checked([string]$File, [string[]]$Arguments) {
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$File $($Arguments -join ' ') failed with exit code $LASTEXITCODE" }
}

function Build-Server {
    Require-Tool "go" "GoLang.Go"
    Require-Tool "npm" "OpenJS.NodeJS.LTS"
    New-Item -ItemType Directory -Force -Path $Out | Out-Null

    Write-Host "Building backend -> $Exe"
    Push-Location (Join-Path $Root "backend")
    try {
        $env:CGO_ENABLED = "0"
        Invoke-Checked "go" @("build", "-trimpath", "-o", $Exe, ".")
    } finally { Pop-Location }

    Write-Host "Building web frontend -> $WebOut"
    Push-Location (Join-Path $Root "web")
    try {
        Invoke-Checked "npm" @("ci", "--no-audit", "--no-fund")
        Invoke-Checked "npm" @("run", "build")
    } finally { Pop-Location }
    if (Test-Path $WebOut) { Remove-Item -Recurse -Force $WebOut }
    Copy-Item -Recurse (Join-Path $Root "web\dist") $WebOut

    # A launcher so the build\sun-server folder can be copied to another Windows PC and run as is.
    $launcher = @(
        '@echo off',
        'rem Portable launcher for the Sun cloud config service. Data is stored in .\data next to this file.',
        'setlocal',
        'cd /d "%~dp0"',
        'if "%PORT%"=="" set "PORT=3280"',
        'set "HTTP_ADDR=:%PORT%"',
        'set "DB_PATH=%~dp0data\sun.db"',
        'set "WEB_DIR=%~dp0web"',
        'echo Open http://127.0.0.1:%PORT%  (Ctrl+C to stop)',
        '"%~dp0sun-cloud.exe"',
        'pause'
    )
    Set-Content -Path (Join-Path $Out "start.bat") -Value $launcher -Encoding ASCII
    Write-Host "Build finished: $Out"
}

if ($Rebuild -or $BuildOnly -or -not (Test-Path $Exe) -or -not (Test-Path (Join-Path $WebOut "index.html"))) {
    Build-Server
}
if ($BuildOnly) { exit 0 }

if (-not $Listen) { $Listen = ":$Port" }
New-Item -ItemType Directory -Force -Path $DataDir | Out-Null
$env:HTTP_ADDR = $Listen
$env:DB_PATH = Join-Path $DataDir "sun.db"
$env:WEB_DIR = $WebOut

Write-Host "Data:   $($env:DB_PATH)"
Write-Host "Open:   http://127.0.0.1:$Port   (Android client: http://<this PC's LAN IP>:$Port/api/v1)"
Write-Host "The first-run setup code is printed below. Press Ctrl+C to stop."
& $Exe
exit $LASTEXITCODE
