<#
OkexAlpha read-only smoke test.

Checks that a running OkexAlpha instance answers its health and read-only
query endpoints. Sends no POST/PUT/DELETE request, so no data is written.

Usage:
  powershell -File scripts/smoke-test.ps1
  powershell -File scripts/smoke-test.ps1 -BaseUrl http://localhost:18080

Exit codes: 0 = all checks passed, 1 = at least one check failed.
NOTE: this file must stay ASCII-only. Windows PowerShell 5.1 reads .ps1 as
      ANSI, and multi-byte UTF-8 comment bytes can swallow the CRLF and
      break param() parsing.
#>

param(
    [string]$BaseUrl = "http://localhost:18080",
    [int]$TimeoutSec = 30
)

$ErrorActionPreference = "Stop"

$base = $BaseUrl.TrimEnd("/")

$checks = @(
    @{ Name = "actuator health";      Path = "/actuator/health";                          ExpectCode = $false },
    @{ Name = "sync status";          Path = "/api/v1/sync/status";                       ExpectCode = $true  },
    @{ Name = "coins (limit=5)";      Path = "/api/v1/coins?limit=5";                     ExpectCode = $true  },
    @{ Name = "instruments (SWAP)";   Path = "/api/v1/instruments?instType=SWAP&limit=5"; ExpectCode = $true  },
    @{ Name = "storage report";       Path = "/api/v1/maintenance/storage";               ExpectCode = $true  }
)

function Get-DataCount {
    param($Json)
    if ($null -eq $Json) { return $null }
    if ($Json.PSObject.Properties.Name -contains "data") {
        $d = $Json.data
        if ($null -eq $d) { return $null }
        if ($d -is [array]) { return $d.Count }
        return 1
    }
    if ($Json.PSObject.Properties.Name -contains "rows") {
        $r = $Json.rows
        if ($null -eq $r) { return $null }
        return 1
    }
    return $null
}

$results = New-Object System.Collections.ArrayList
$failed = 0

foreach ($check in $checks) {
    $url = $base + $check.Path
    $status = 0
    $elapsed = 0
    $content = ""
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = Invoke-WebRequest -Uri $url -Method Get -UseBasicParsing -TimeoutSec $TimeoutSec
        $status = [int]$response.StatusCode
        $content = $response.Content
    } catch {
        $sw.Stop();
        Write-Error ("FAIL {0} -> {1} : {2}" -f $check.Name, $url, $_.Exception.Message)
        exit 1
    }
    $sw.Stop()
    $elapsed = [int]$sw.ElapsedMilliseconds

    if ($status -lt 200 -or $status -ge 300) {
        Write-Error ("FAIL {0} -> {1} : HTTP {2}" -f $check.Name, $url, $status)
        exit 1
    }

    $json = $null
    try { $json = $content | ConvertFrom-Json } catch { $json = $null }

    if ($check.ExpectCode) {
        if ($null -eq $json) {
            Write-Error ("FAIL {0} -> {1} : response is not JSON" -f $check.Name, $url)
            exit 1
        }
        if ($json.code -ne "0") {
            Write-Error ("FAIL {0} -> {1} : code={2} msg={3}" -f $check.Name, $url, $json.code, $json.msg)
            exit 1
        }
    }

    $count = Get-DataCount $json
    $null = $results.Add([pscustomobject]@{
        Check = $check.Name
        Status = $status
        Items = $count
        Ms = $elapsed
        Url = $url
    })
    Write-Host ("PASS {0} (HTTP {1}, {2} ms)" -f $check.Name, $status, $elapsed)
}

Write-Host ""
Write-Host ("Smoke test passed against {0}" -f $base)
Write-Host ("checks={0} failed={1}" -f $results.Count, $failed)
$results | Format-Table -AutoSize Check, Status, Items, Ms | Out-String -Width 200 | Write-Host

exit 0
