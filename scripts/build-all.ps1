# build-all.ps1 - build the Vue SPA and then package the Spring Boot jar.
#
# Order matters: the SPA must be in backend\src\main\resources\static before
# mvn package runs, otherwise the jar ships the previous frontend.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\build-all.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\build-all.ps1 -SkipTests
#
# Pure ASCII on purpose: Windows PowerShell 5.1 mis-decodes UTF-8 scripts.

param(
    [switch]$SkipFrontendInstall,
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot

& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'build-frontend.ps1') -SkipInstall:$SkipFrontendInstall
if ($LASTEXITCODE -ne 0) {
    Write-Host '[FAIL] frontend build failed'
    exit $LASTEXITCODE
}

$mvn = Join-Path $root '.tools\apache-maven-3.9.16\bin\mvn.cmd'
$javaHome = 'D:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot'

if (-not (Test-Path $mvn)) {
    Write-Host '[FAIL] maven not found at' $mvn
    exit 1
}
if (-not (Test-Path $javaHome)) {
    Write-Host '[FAIL] JAVA_HOME not found at' $javaHome
    exit 1
}

$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"

Push-Location (Join-Path $root 'backend')
try {
    # clean is mandatory: mvn package alone only ADDS copied resources to
    # target\classes, so stale hashed Vite chunks from an earlier frontend build
    # keep shipping inside the jar forever.
    if ($SkipTests) {
        Write-Host '[mvn] clean package (tests skipped)'
        & $mvn -B -ntp -q clean package -Dmaven.test.skip=true
    } else {
        Write-Host '[mvn] clean package (with tests)'
        & $mvn -B -ntp clean package
    }
    if ($LASTEXITCODE -ne 0) { throw ('maven package failed with exit code ' + $LASTEXITCODE) }
} finally {
    Pop-Location
}

Write-Host '[OK] build-all finished'
