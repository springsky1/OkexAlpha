# install-node.ps1 - install the portable Node.js toolchain into .tools\node (Windows x64).
#
# The repo has no system-wide node; the frontend build needs one, so we keep a
# pinned portable copy under .tools\node (already gitignored via .tools).
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\install-node.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\install-node.ps1 -Version 24.22.2
#
# Notes:
#   - The upstream zip name is node-v<version>-win-x64.zip (NOT -x64.zip).
#   - The zip contains one top-level folder node-v<version>-win-x64\; its
#     contents are moved up so that .tools\node\node.exe exists afterwards.

param(
    [string]$Version = '24.22.2'
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$toolsDir = Join-Path $root '.tools'
$nodeDir = Join-Path $toolsDir 'node'
$zipPath = Join-Path $toolsDir ('node-v' + $Version + '-win-x64.zip')
$url = ('https://nodejs.org/dist/v' + $Version + '/node-v' + $Version + '-win-x64.zip')

if (-not (Test-Path $toolsDir)) {
    New-Item -ItemType Directory -Path $toolsDir | Out-Null
}

if (Test-Path (Join-Path $nodeDir 'node.exe')) {
    Write-Host ('[OK] node already installed at ' + $nodeDir)
    & (Join-Path $nodeDir 'node.exe') --version
    exit 0
}

if (-not (Test-Path $zipPath)) {
    Write-Host ('[1/4] downloading ' + $url)
    try {
        Invoke-WebRequest -Uri $url -OutFile $zipPath -UseBasicParsing
    } catch {
        Write-Host ('[FAIL] download failed: ' + $_.Exception.Message)
        exit 1
    }
} else {
    Write-Host '[1/4] reusing cached zip'
}

Write-Host '[2/4] extracting'
$extractDir = Join-Path $toolsDir ('node-extract-' + $Version)
if (Test-Path $extractDir) { Remove-Item $extractDir -Recurse -Force }
Expand-Archive -Path $zipPath -DestinationPath $extractDir -Force

$inner = Join-Path $extractDir ('node-v' + $Version + '-win-x64')
if (-not (Test-Path $inner)) {
    Write-Host '[FAIL] unexpected zip layout, top folder not found'
    exit 1
}

if (Test-Path $nodeDir) { Remove-Item $nodeDir -Recurse -Force }
Move-Item $inner $nodeDir
Remove-Item $extractDir -Recurse -Force
Remove-Item $zipPath -Force

Write-Host '[3/4] verifying'
& (Join-Path $nodeDir 'node.exe') --version
if ($LASTEXITCODE -ne 0) { exit 1 }
& (Join-Path $nodeDir 'npm.cmd') --version
if ($LASTEXITCODE -ne 0) { exit 1 }

Write-Host '[4/4] cleaning'
Get-ChildItem $nodeDir -Filter '*.md' | Remove-Item -Force

Write-Host ('[OK] portable node ready at ' + $nodeDir)
