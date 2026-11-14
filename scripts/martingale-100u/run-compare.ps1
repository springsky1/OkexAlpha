# Compare legacy auto-order params vs designed martingale, one batch per (strategy, coin, side).
# Every batch is a single fixed parameter point (combos=1) so the champion == that point and
# we get per-coin COIN rows + the daily equity curve for portfolio aggregation.
param(
    [string[]]$Sets = @('legacy', 'mart-long', 'mart-short'),
    [string[]]$Coins = @(),
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:18080/api/v1'
$psql = 'D:\Program Files\PostgreSQL\18\bin\psql.exe'
$env:PGPASSWORD = '123456'
$db = 'okex_alpha'
$startMs = 1767196800000
$endMs = 1794031200000
$logDir = Join-Path $PSScriptRoot '..\..\.run\cmp'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$logFile = Join-Path $logDir 'submit-log.txt'

function Invoke-Psql([string]$sql) {
    (& $psql -h 127.0.0.1 -U postgres -d $db -t -A -F '|' -v ON_ERROR_STOP=1 -c "set statement_timeout='120s'; $sql" 2>&1) | Where-Object { $_ -ne 'SET' }
}

$universe = @(
    'BTC-USDT-SWAP', 'ETH-USDT-SWAP', 'SOL-USDT-SWAP', 'XRP-USDT-SWAP', 'DOGE-USDT-SWAP',
    'ADA-USDT-SWAP', 'LINK-USDT-SWAP', 'AVAX-USDT-SWAP', 'TRX-USDT-SWAP', 'DOT-USDT-SWAP',
    'LTC-USDT-SWAP', 'BCH-USDT-SWAP', 'SUI-USDT-SWAP', 'NEAR-USDT-SWAP', 'APT-USDT-SWAP',
    'ETC-USDT-SWAP', 'FIL-USDT-SWAP', 'ATOM-USDT-SWAP', 'UNI-USDT-SWAP', 'AAVE-USDT-SWAP',
    'ARB-USDT-SWAP', 'OP-USDT-SWAP', 'TIA-USDT-SWAP', 'INJ-USDT-SWAP', 'CRV-USDT-SWAP',
    'WLD-USDT-SWAP', 'ORDI-USDT-SWAP', 'SEI-USDT-SWAP', 'JUP-USDT-SWAP', 'ONDO-USDT-SWAP',
    'ENA-USDT-SWAP', 'PEPE-USDT-SWAP', 'SHIB-USDT-SWAP', 'WIF-USDT-SWAP', 'BONK-USDT-SWAP'
)
# -File passes "BTC,FIL" as one string; split it so -Coins works from the command line
if ($Coins.Count -eq 1 -and $Coins[0] -match ',') { $Coins = $Coins[0] -split ',' }
if ($Coins.Count -gt 0) {
    $universe = $universe | Where-Object { $sym = $_ -replace '-USDT-SWAP$', ''; $Coins -contains $sym }
}
if ($universe.Count -eq 0) { throw 'empty universe - check -Coins' }

# legacy = the params of the live shadow strategy #1 (bt-2-28d65e40)
$defs = @{
    'legacy'     = @{ Lev = 5; Layers = 8; Step = 1.5; Scale = 1.5; Tp = 2.0; Sl = 25.0; Side = 'long' }
    'mart-long'  = @{ Lev = 3; Layers = 2; Step = 1.2; Scale = 2.5; Tp = 0.9; Sl = 20.0; Side = 'long' }
    'mart-short' = @{ Lev = 3; Layers = 2; Step = 1.2; Scale = 2.5; Tp = 0.9; Sl = 20.0; Side = 'short' }
}

$specRows = Invoke-Psql "select inst_id, min_notional_usd from okx_min_order where inst_id in ($(($universe | ForEach-Object { "'$_'" }) -join ',')) order by inst_id"
$spec = @{}
foreach ($row in $specRows) {
    $p = $row -split '\|'
    if ($p.Count -ge 2) { $spec[$p[0]] = [double]$p[1] }
}

$plan = @()
foreach ($inst in $universe) {
    if (-not $spec.ContainsKey($inst)) { continue }
    foreach ($setName in $Sets) {
        $d = $defs[$setName]
        $sym = $inst -replace '-USDT-SWAP$', ''
        $plan += [pscustomobject]@{
            InstId = $inst; Symbol = $sym; Set = $setName
            Label  = "CMP-$setName-$sym"
            Margin = [math]::Round($spec[$inst] / $d.Lev, 8)
            Require = $spec[$inst]
            Spec   = $d
        }
    }
}

Write-Host ("plan: {0} batches" -f $plan.Count)
if ($DryRun) { $plan | Format-Table InstId, Set, Margin, @{n='C';e={$_.Spec.Lev}} -AutoSize; return }

$sw = [Diagnostics.Stopwatch]::StartNew()
$done = 0; $skip = 0; $fail = 0
foreach ($job in $plan) {
    $existing = Invoke-Psql "select id||'|'||status from okx_backtest_run where label='$($job.Label)' order by id desc limit 1"
    if ($existing) {
        $parts = ($existing | Select-Object -First 1) -split '\|'
        if ($parts.Count -ge 2 -and ($parts[1] -eq 'DONE' -or $parts[1] -eq 'RUNNING' -or $parts[1] -eq 'PENDING')) {
            $skip++; continue
        }
    }
    $d = $job.Spec
    $body = @{
        instIds         = @($job.InstId)
        bar             = '1H'
        startMs         = $startMs
        endMs           = $endMs
        coinLimit       = 1
        combos          = 1
        topN            = 1
        marginUsd       = $job.Margin
        maxHoldHours    = 48
        fundingPer8hPct = 0.01
        seed            = 20261109
        label           = $job.Label
        ranges          = @{
            leverage      = @{ min = $d.Lev; max = $d.Lev; step = 1 }
            maxLayers     = @{ min = $d.Layers; max = $d.Layers; step = 1 }
            layerStepPct  = @{ min = $d.Step; max = $d.Step; step = 0.1 }
            layerScale    = @{ min = $d.Scale; max = $d.Scale; step = 0.1 }
            takeProfitPct = @{ min = $d.Tp; max = $d.Tp; step = 0.1 }
            stopLossPct   = @{ min = $d.Sl; max = $d.Sl; step = 1.0 }
            sides         = @($d.Side)
        }
    } | ConvertTo-Json -Depth 8

    $runId = $null
    try {
        $resp = Invoke-RestMethod -Method Post -Uri "$base/strategy/backtests" -Body $body -ContentType 'application/json'
        $runId = $resp.data.runId
    } catch {
        if ($_.Exception.Message -match '409') {
            Start-Sleep -Seconds 2
            try {
                $resp = Invoke-RestMethod -Method Post -Uri "$base/strategy/backtests" -Body $body -ContentType 'application/json'
                $runId = $resp.data.runId
            } catch { Write-Warning "submit failed $($job.Label)"; $fail++; continue }
        } else { Write-Warning "submit failed $($job.Label): $($_.Exception.Message)"; $fail++; continue }
    }

    $deadline = (Get-Date).AddMinutes(6)
    $st = ''
    do {
        Start-Sleep -Milliseconds 300
        $st = (Invoke-Psql "select status from okx_backtest_run where id=$runId" | Select-Object -First 1)
    } while ($st -notmatch '^(DONE|FAILED|CANCELED)$' -and (Get-Date) -lt $deadline)

    if ($st -eq 'DONE') { $done++ } else { $fail++; Write-Warning "run $runId ($($job.Label)) ended $st" }
    $line = "{0} {1,-28} run={2} status={3}" -f (Get-Date -Format 'HH:mm:ss'), $job.Label, $runId, $st
    Write-Host $line
    Add-Content -Path $logFile -Value $line
}
$sw.Stop()
Write-Host ("DONE batches={0} skipped={1} failed={2} in {3:F1}s" -f $done, $skip, $fail, $sw.Elapsed.TotalSeconds)
