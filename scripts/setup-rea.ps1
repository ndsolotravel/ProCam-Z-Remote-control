<#
.SYNOPSIS
    Installs and configures REA (Reverse Engineer Anything) and its supporting toolchain.
.DESCRIPTION
    Sets up isolated Node.js (v26 LTS), REA 6.8.0, JADX MCP Bridge, Apktool, FFmpeg,
    Python venv with Frida, and registers REA with the active Antigravity coding agent.
#>
[CmdletBinding()]
param(
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $ScriptRoot
$ToolsDir = Join-Path $ProjectRoot ".tools"

Write-Host "====================================================" -ForegroundColor Cyan
Write-Host "  REA Toolchain Setup for CamPro Remote rea-main   " -ForegroundColor Cyan
Write-Host "====================================================" -ForegroundColor Cyan
Write-Host "Project Root : $ProjectRoot"
Write-Host "Tools Root   : $ToolsDir"

# 1. Ensure Directory Structure
$dirs = @("node", "rea", "jadx", "apktool", "ffmpeg\bin", "venv")
foreach ($d in $dirs) {
    $targetPath = Join-Path $ToolsDir $d
    if (-not (Test-Path $targetPath)) {
        New-Item -ItemType Directory -Path $targetPath -Force | Out-Null
    }
}

# 2. Portable Node.js (v26.13.0 LTS)
$nodeExe = Join-Path $ToolsDir "node\node.exe"
$npmCmd  = Join-Path $ToolsDir "node\npm.cmd"
if (-not (Test-Path $nodeExe) -or $Force) {
    Write-Host "[1/6] Downloading portable Node.js 26.13.0 LTS..." -ForegroundColor Yellow
    $nodeZip = Join-Path $ToolsDir "node\node.zip"
    curl.exe -L "https://nodejs.org/dist/v26.13.0/node-v26.13.0-win-x64.zip" -o $nodeZip
    tar -xf $nodeZip -C (Join-Path $ToolsDir "node")
    Move-Item (Join-Path $ToolsDir "node\node-v26.13.0-win-x64\*") -Destination (Join-Path $ToolsDir "node") -Force
    Remove-Item -Recurse -Force (Join-Path $ToolsDir "node\node-v26.13.0-win-x64"), $nodeZip -ErrorAction SilentlyContinue
}
$nodeVer = & $nodeExe -v
Write-Host "[1/6] Node.js ready: $nodeVer" -ForegroundColor Green

# 3. Persistent Local REA Installation (rea-agents@6.8.0)
$reaEntry = Join-Path $ToolsDir "rea\node_modules\rea-agents\scripts\rea.mjs"
$reaPkgJson = Join-Path $ToolsDir "rea\package.json"
if (-not (Test-Path $reaEntry) -or $Force) {
    Write-Host "[2/6] Installing isolated rea-agents@6.8.0..." -ForegroundColor Yellow
    Push-Location (Join-Path $ToolsDir "rea")
    try {
        if (-not (Test-Path $reaPkgJson)) {
            & $npmCmd init -y | Out-Null
        }
        & $npmCmd install rea-agents@6.8.0 --save-exact
    } finally {
        Pop-Location
    }
}
$reaVer = & $nodeExe $reaEntry --version
Write-Host "[2/6] REA CLI ready: v$reaVer" -ForegroundColor Green

# 4. JADX Headless MCP Bridge (0.7.1)
$jadxJar = Join-Path $ToolsDir "jadx\jadx-headless-mcp-0.7.1-all.jar"
$expectedJadxHash = "6E5EACF500B64292BFB73C49797C1958F6EE44646E43E868039AE7FEB573FF75"
if (-not (Test-Path $jadxJar) -or $Force) {
    Write-Host "[3/6] Downloading jadx-headless-mcp-0.7.1-all.jar..." -ForegroundColor Yellow
    curl.exe -L "https://github.com/1013503897/jadx-headless-mcp/releases/download/v0.7.1/jadx-headless-mcp-0.7.1-all.jar" -o $jadxJar
}
$actualJadxHash = (Get-FileHash $jadxJar -Algorithm SHA256).Hash
if ($actualJadxHash -ne $expectedJadxHash) {
    throw "JADX JAR SHA-256 verification failed! Expected: $expectedJadxHash, Got: $actualJadxHash"
}
Write-Host "[3/6] JADX MCP JAR verified: SHA256 matched ($actualJadxHash)" -ForegroundColor Green

# 5. Apktool & Media Inspection (FFmpeg/FFprobe)
$apktoolJar = Join-Path $ToolsDir "apktool\apktool.jar"
$apktoolBat = Join-Path $ToolsDir "apktool\apktool.bat"
if (-not (Test-Path $apktoolJar) -or $Force) {
    Write-Host "[4/6] Downloading Apktool 2.11.1..." -ForegroundColor Yellow
    curl.exe -L "https://github.com/iBotPeaches/Apktool/releases/download/v2.11.1/apktool_2.11.1.jar" -o $apktoolJar
}
if (-not (Test-Path $apktoolBat)) {
    "@echo off`r`nsetlocal`r`nset ""DIR=%~dp0""`r`njava -jar ""%DIR%apktool.jar"" %*`r`n" | Out-File -FilePath $apktoolBat -Encoding ascii
}
Write-Host "[4/6] Apktool ready: 2.11.1" -ForegroundColor Green

# 6. Python venv & Frida
$venvPython = Join-Path $ToolsDir "venv\Scripts\python.exe"
$fridaExe = Join-Path $ToolsDir "venv\Scripts\frida.exe"
if (-not (Test-Path $venvPython) -or $Force) {
    Write-Host "[5/6] Initializing isolated Python venv..." -ForegroundColor Yellow
    python -m venv (Join-Path $ToolsDir "venv")
    & $venvPython -m pip install --upgrade pip | Out-Null
    & $venvPython -m pip install frida frida-tools | Out-Null
}
$fridaVer = & $fridaExe --version
Write-Host "[5/6] Frida runtime ready: v$fridaVer" -ForegroundColor Green

# 7. Configure Antigravity MCP Registration
Write-Host "[6/6] Connecting REA to Antigravity coding agent..." -ForegroundColor Yellow
$env:REA_JADX_MCP_JAR = $jadxJar
$setupArgs = @(
    $reaEntry,
    "setup",
    "--client", "antigravity",
    "--skill=false",
    "--yes",
    "--json"
)
& $nodeExe $setupArgs | Out-Null

# 8. Diagnostics Check
Write-Host "`nRunning REA MCP Doctor..." -ForegroundColor Yellow
& $nodeExe $reaEntry mcp doctor

Write-Host "`nSetup complete! REA MCP server is registered and aligned." -ForegroundColor Green
