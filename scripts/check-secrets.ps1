# check-secrets.ps1 - scan tracked files for leaked credentials (ASCII only).
# Usage:  powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-secrets.ps1
# Exit 0 = clean, 1 = potential secret found. Values are redacted in output.
param(
    [string]$Root = (Resolve-Path "$PSScriptRoot\..").Path,
    [switch]$IncludeIgnored
)

$ErrorActionPreference = 'Stop'

$skipDirs = @('.git', 'target', 'node_modules', '.tools', '.run', '.idea', '.vscode', 'logs')

$patterns = @(
    @{ Name = 'okx-api-key-uuid'; Regex = '\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b' },
    @{ Name = 'hex32-secret';    Regex = '\b[0-9a-fA-F]{32}\b' },
    @{ Name = 'assign-api-key';  Regex = '(?i)(OKX_API_KEY|api[-_]?key|apiKey)\s*[:=]\s*["'']?[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}' },
    @{ Name = 'assign-secret';   Regex = '(?i)(OKX_SECRET_KEY|secret[-_]?key|secretKey)\s*[:=]\s*["'']?[0-9a-fA-F]{32}\b' },
    @{ Name = 'assign-passphr';  Regex = '(?i)(OKX_PASSPHRASE|pass[-_]?phrase|passphrase)\s*[:=]\s*["'']?(?![$<(]|[xX*]|your|YOUR|example|Example|redacted)[A-Za-z0-9._!@#%^&*+-]{6,}\s*[,;})"]?\s*$' },
    @{ Name = 'private-key-pem'; Regex = '-----BEGIN (RSA |EC |OPENSSH |PGP )?PRIVATE KEY-----' },
    @{ Name = 'okx-live-host';   Regex = '(?i)OK-ACCESS-KEY\s*:\s*[0-9a-f]{8}-' }
)

$allowListNames = @(
    'check-secrets.ps1',
    'SECURITY.md'
)

# Obvious placeholders used by unit tests: a line containing one of these is not a secret.
# Keep them unmistakably synthetic so a real key can never match by accident.
$dummyMarkers = @(
    'AAAABBBB-CCCC-DDDD-EEEE-FFFF00001111',
    '0123456789abcdef0123456789abcdef',
    'passphrase-123',
    'test-key',
    'changeit',
    'changeme'
)

function Get-TextFiles([string]$dir) {
    Get-ChildItem -LiteralPath $dir -Recurse -File -Force -ErrorAction SilentlyContinue | Where-Object {
        $full = $_.FullName
        $rel = $full.Substring($Root.Length).TrimStart('\', '/')
        $parts = $rel -split '[\\/]'
        $skip = $false
        foreach ($p in $parts) { if ($skipDirs -contains $p) { $skip = $true; break } }
        if ($skip) { return $false }
        if ($allowListNames -contains $_.Name) { return $false }
        if ($_.Length -gt 3MB) { return $false }
        $ext = $_.Extension.ToLowerInvariant()
        if ($ext -in @('.png', '.jpg', '.jpeg', '.gif', '.ico', '.jar', '.zip', '.gz', '.class', '.so', '.dll', '.exe', '.pdf', '.woff', '.woff2', '.ttf')) { return $false }
        return $true
    }
}

$findings = New-Object System.Collections.ArrayList
$scanned = 0
$dummySkipped = 0

foreach ($file in (Get-TextFiles $Root)) {
    $scanned++
    try {
        $lines = Get-Content -LiteralPath $file.FullName -Encoding UTF8 -ErrorAction Stop
    } catch {
        continue
    }
    $rel = $file.FullName.Substring($Root.Length).TrimStart('\', '/')
    $lineNo = 0
    foreach ($line in $lines) {
        $lineNo++
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $isDummy = $false
        foreach ($m in $dummyMarkers) {
            if ($line.IndexOf($m, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) { $isDummy = $true; break }
        }
        if ($isDummy) { $dummySkipped++; continue }
        foreach ($pat in $patterns) {
            if ([regex]::IsMatch($line, $pat.Regex)) {
                $shown = $line.Trim()
                if ($shown.Length -gt 90) { $shown = $shown.Substring(0, 90) + '...' }
                $shown = [regex]::Replace($shown, '[0-9a-fA-F]{16,}', '<redacted>')
                $shown = [regex]::Replace($shown, '(?i)(passphrase|secret[-_]?key|api[-_]?key)\s*[:=]\s*\S+', '$1=<redacted>')
                [void]$findings.Add([pscustomobject]@{ File = $rel; Line = $lineNo; Kind = $pat.Name; Text = $shown })
            }
        }
    }
}

Write-Output ("scanned files: " + $scanned + " (placeholder lines skipped: " + $dummySkipped + ")")
if ($findings.Count -eq 0) {
    Write-Output 'OK: no credential-like literals found.'
    exit 0
}

Write-Output ("POTENTIAL SECRETS: " + $findings.Count)
foreach ($f in $findings) {
    Write-Output ("  " + $f.File + ":" + $f.Line + "  [" + $f.Kind + "]  " + $f.Text)
}
Write-Output ''
Write-Output 'Fix: move real values into environment variables (see .run/okx.env, gitignored) and keep only placeholders in code/docs.'
exit 1
