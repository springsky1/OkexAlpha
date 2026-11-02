# build-frontend.ps1 - build the Vue 3 SPA (frontend/) and deploy it into the
# Spring Boot static resources directory (backend\src\main\resources\static).
#
# Deployment model: the built SPA is served by Spring Boot from classpath:/static,
# so the backend keeps serving everything from one origin - no CORS, no second
# process. Unported pages are served from static\legacy (see docs).
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\build-frontend.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\build-frontend.ps1 -SkipInstall
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\build-frontend.ps1 -SkipDeploy
#
# Pure ASCII on purpose: Windows PowerShell 5.1 mis-decodes UTF-8 scripts.

param(
    [switch]$SkipInstall,
    [switch]$SkipDeploy
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$nodeDir = Join-Path $root '.tools\node'
$frontendDir = Join-Path $root 'frontend'
$staticDir = Join-Path $root 'backend\src\main\resources\static'

if (-not (Test-Path (Join-Path $nodeDir 'node.exe'))) {
    Write-Host '[FAIL] portable node not found at' $nodeDir
    Write-Host '       run scripts\install-node.ps1 first'
    exit 1
}
if (-not (Test-Path (Join-Path $frontendDir 'package.json'))) {
    Write-Host '[FAIL] frontend\package.json missing'
    exit 1
}

$env:Path = "$nodeDir;$env:Path"
Write-Host '[1/4] toolchain node' (& node --version)

Push-Location $frontendDir
try {
    $lockFile = Join-Path $frontendDir 'package-lock.json'
    if (-not $SkipInstall) {
        if (Test-Path $lockFile) {
            Write-Host '[2/4] npm ci'
            & npm ci --no-audit --no-fund
        } else {
            Write-Host '[2/4] npm install'
            & npm install --no-audit --no-fund
        }
        if ($LASTEXITCODE -ne 0) { throw ('dependency install failed with exit code ' + $LASTEXITCODE) }
    } else {
        Write-Host '[2/4] dependency install skipped'
    }

    Write-Host '[3/4] vite build'
    & npm run build
    if ($LASTEXITCODE -ne 0) { throw ('vite build failed with exit code ' + $LASTEXITCODE) }
} finally {
    Pop-Location
}

if ($SkipDeploy) {
    Write-Host '[4/4] deploy skipped'
    exit 0
}

$distDir = Join-Path $frontendDir 'dist'
if (-not (Test-Path (Join-Path $distDir 'index.html'))) {
    Write-Host '[FAIL] dist\index.html was not produced'
    exit 1
}

Write-Host '[4/5] deploy dist -> backend static resources'
$assetsStatic = Join-Path $staticDir 'assets'
if (Test-Path $assetsStatic) { Remove-Item $assetsStatic -Recurse -Force }
Copy-Item (Join-Path $distDir 'assets') $assetsStatic -Recurse -Force
Copy-Item (Join-Path $distDir 'index.html') (Join-Path $staticDir 'index.html') -Force

# The pre-migration root app.js / styles.css are dead weight: the new index.html is
# the Vue shell and the legacy copies live under static\legacy. Drop them so the jar
# does not ship 276 KB of unreferenced files.
$legacyRootFiles = @('app.js', 'styles.css')
foreach ($name in $legacyRootFiles) {
    $stale = Join-Path $staticDir $name
    if (Test-Path $stale) {
        Remove-Item $stale -Force
        Write-Host ('     removed stale static\' + $name)
    }
}

Write-Host '[5/5] frontend deployed to backend static resources'
Write-Host '     legacy UI still available under /legacy/'
