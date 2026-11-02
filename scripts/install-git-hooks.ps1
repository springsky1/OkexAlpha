# install-git-hooks.ps1 - install a pre-commit hook that blocks credential leaks (ASCII only).
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/install-git-hooks.ps1
param(
    [string]$Root = (Resolve-Path "$PSScriptRoot\..").Path
)

$ErrorActionPreference = 'Stop'

$hooksDir = Join-Path $Root '.git\hooks'
if (-not (Test-Path $hooksDir)) {
    throw "not a git repository (missing .git\hooks): $Root"
}

$hookPath = Join-Path $hooksDir 'pre-commit'
$scriptRel = 'scripts/check-secrets.ps1'

$content = @'
#!/bin/sh
# Auto-installed by scripts/install-git-hooks.ps1
# Blocks commits containing credential-like literals.
if command -v powershell.exe >/dev/null 2>&1; then
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(git rev-parse --show-toplevel)/scripts/check-secrets.ps1"
else
  echo "pre-commit: powershell not found, skipping secret scan" >&2
fi
'@

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$content = $content -replace "`r`n", "`n"
[System.IO.File]::WriteAllText($hookPath, $content, $utf8NoBom)
Write-Output ("installed hook: " + $hookPath)
Write-Output ("it runs: " + $scriptRel)
Write-Output "bypass (not recommended): git commit --no-verify"
