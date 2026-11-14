# Wave-3 optimization grid: martingale-100U (ASCII only).
# Wider search around the wave-2 winners, margin = latest min-order notional / leverage.
param(
  [string]$Base = 'http://127.0.0.1:18080/api/v1',
  [int]$Combos = 640,
  [int]$TopN = 20,
  [int[]]$Levers = @(2, 3, 5, 8),
  [string[]]$Sides = @('long', 'short'),
  [string[]]$Coins = @(),
  [switch]$Resume
)
$ErrorActionPreference = 'Stop'
$root = 'D:\workplace\Github\OkexAlpha'
$cfg = Get-Content (Join-Path $root 'config\martingale-100u.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$logDir = Join-Path $root '.run\grid3'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }
$log = Join-Path $logDir 'run-all.log'
$psql = 'D:\Program Files\PostgreSQL\18\bin\psql.exe'
$env:PGPASSWORD = '123456'

function Invoke-Psql([string]$sql) {
  & $psql -h 127.0.0.1 -U postgres -d okex_alpha -t -A -c $sql 2>$null
}

$startMs = 1767196800000
$endMs = 1794031200000

# latest min order notional per instrument (refreshed 2026-11-07 23:29)
$mo = Invoke-RestMethod -Uri "$Base/strategy/min-order?limit=500" -TimeoutSec 120
$minNotional = @{}
foreach ($it in $mo.data.rows) { if ($it.instId) { $minNotional[$it.instId] = [double]$it.minNotionalUsd } }

if ($Coins.Count -eq 1 -and $Coins[0] -match ",") { $Coins = $Coins[0] -split "," }
$all = $cfg.coins
if ($Coins.Count -gt 0) {
  $all = $cfg.coins | Where-Object { $Coins -contains (($_.instId -split '-')[0]) }
}

$plan = @()
foreach ($c in $all) {
  $mn = $minNotional[$c.instId]
  if (-not $mn -or $mn -le 0) { Write-Warning "no min-order for $($c.instId)"; continue }
  foreach ($lev in $Levers) {
    foreach ($side in $Sides) {
      $sym = ($c.instId -split '-')[0]
      $label = 'M200-{0}-L{1}-{2}' -f $sym, $lev, $side
      $plan += [pscustomobject]@{
        InstId = $c.instId; Symbol = $sym; Lever = $lev; Side = $side; Label = $label
        MarginUsd = [math]::Round($mn / $lev, 8)
      }
    }
  }
}
Write-Host ("plan: {0} batches, {1} combos each" -f $plan.Count, $Combos)

$done = 0; $skip = 0; $fail = 0
foreach ($job in $plan) {
  if ($Resume) {
    $existing = Invoke-Psql "select status from okx_backtest_run where label='$($job.Label)' order by id desc limit 1"
    if ($existing -eq 'DONE') { $skip++; continue }
  }
  $body = @{
    instIds = @($job.InstId)
    bar = '1H'
    startMs = $startMs
    endMs = $endMs
    coinLimit = 1
    combos = $Combos
    topN = $TopN
    marginUsd = $job.MarginUsd
    maxHoldHours = 48
    fundingPer8hPct = 0.01
    seed = 20261110
    label = $job.Label
    ranges = @{
      leverage = @{ min = $job.Lever; max = $job.Lever; step = 1 }
      maxLayers = @{ min = 2; max = 3; step = 1 }
      layerStepPct = @{ min = 0.8; max = 2.0; step = 0.4 }
      layerScale = @{ min = 1.5; max = 3.0; step = 0.5 }
      takeProfitPct = @{ min = 0.6; max = 1.5; step = 0.3 }
      stopLossPct = @{ min = 10.0; max = 30.0; step = 5.0 }
      sides = @($job.Side)
    }
  } | ConvertTo-Json -Depth 8

  $sw = [Diagnostics.Stopwatch]::StartNew()
  $runId = $null
  for ($attempt = 1; $attempt -le 3; $attempt++) {
    try {
      $resp = Invoke-RestMethod -Method Post -Uri "$Base/strategy/backtests" -Body $body -ContentType 'application/json' -TimeoutSec 120
      $runId = $resp.data.runId; break
    } catch {
      if ($_.Exception.Message -match '409') { Start-Sleep -Seconds 2; continue }
      Write-Warning "submit failed $($job.Label): $($_.Exception.Message)"; break
    }
  }
  if (-not $runId) { $fail++; continue }

  $st = 'PENDING'
  for ($i = 0; $i -lt 2000; $i++) {
    $st = Invoke-Psql "select status from okx_backtest_run where id=$runId"
    if ($st -eq 'DONE' -or $st -eq 'FAILED' -or $st -eq 'CANCELLED') { break }
    Start-Sleep -Milliseconds 300
  }
  $sw.Stop()
  if ($st -eq 'DONE') { $done++ } else { $fail++ }
  $line = "{0} {1,-26} run={2} status={3} margin={4:F6} elapsed={5:F1}s" -f (Get-Date -Format 'HH:mm:ss'), $job.Label, $runId, $st, $job.MarginUsd, $sw.Elapsed.TotalSeconds
  Write-Host $line
  Add-Content -Path $log -Value $line -Encoding UTF8
}
$sum = "DONE batches={0} skipped={1} failed={2}" -f $done, $skip, $fail
Write-Host $sum
Add-Content -Path $log -Value $sum -Encoding UTF8
