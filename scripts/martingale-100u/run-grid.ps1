<#
  100U 马丁格尔最小开单网格回测 —— 批量提交器（可断点续跑）

  每个 (币, 杠杆, 方向) 提交一个批次：
    - marginUsd = minNotionalUsd / leverage  →  第 1 层名义 = 平台最小下单额
    - 参数网格 4×3×3×3×2 = 216 组合（层数/加仓间距/加仓倍数/止盈/止损）
    - topN=20 → 落单币 COIN 明细，便于逐币复核
  进度只看数据库（okx_backtest_run.status），不轮询大 payload 的 GET /{id}。

  用法：
    pwsh -File scripts/martingale-100u/run-grid.ps1
    pwsh -File scripts/martingale-100u/run-grid.ps1 -Levers 3,10 -Coins BTC,ETH
#>
param(
    [string[]]$Coins = @(),
    [int[]]$Levers = @(3, 5, 10),
    [string[]]$Sides = @('long', 'short'),
    [string]$LabelPrefix = 'M100',
    [int]$Combos = 216,
    [int]$TopN = 20,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:18080/api/v1'
$psql = 'D:\Program Files\PostgreSQL\18\bin\psql.exe'
$env:PGPASSWORD = '123456'
$db = 'okex_alpha'
$startMs = 1767196800000   # 2026-01-01 00:00 +08
$endMs = 1794031200000     # 2026-11-08 00:00 +08（数据末端 11-07）
$logDir = Join-Path $PSScriptRoot '..\..\.run\grid'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$logFile = Join-Path $logDir 'submit-log.txt'

function Invoke-Psql([string]$sql) {
    (& $psql -h 127.0.0.1 -U postgres -d $db -t -A -F '|' -v ON_ERROR_STOP=1 -c "set statement_timeout='120s'; $sql" 2>&1) | Where-Object { $_ -ne 'SET' }
}

# 35 个候选币（1H 数据全覆盖，主流流动性）
$universe = @(
    'BTC-USDT-SWAP', 'ETH-USDT-SWAP', 'SOL-USDT-SWAP', 'XRP-USDT-SWAP', 'DOGE-USDT-SWAP',
    'ADA-USDT-SWAP', 'LINK-USDT-SWAP', 'AVAX-USDT-SWAP', 'TRX-USDT-SWAP', 'DOT-USDT-SWAP',
    'LTC-USDT-SWAP', 'BCH-USDT-SWAP', 'SUI-USDT-SWAP', 'NEAR-USDT-SWAP', 'APT-USDT-SWAP',
    'ETC-USDT-SWAP', 'FIL-USDT-SWAP', 'ATOM-USDT-SWAP', 'UNI-USDT-SWAP', 'AAVE-USDT-SWAP',
    'ARB-USDT-SWAP', 'OP-USDT-SWAP', 'TIA-USDT-SWAP', 'INJ-USDT-SWAP', 'CRV-USDT-SWAP',
    'WLD-USDT-SWAP', 'ORDI-USDT-SWAP', 'SEI-USDT-SWAP', 'JUP-USDT-SWAP', 'ONDO-USDT-SWAP',
    'ENA-USDT-SWAP', 'PEPE-USDT-SWAP', 'SHIB-USDT-SWAP', 'WIF-USDT-SWAP', 'BONK-USDT-SWAP'
)
if ($Coins.Count -gt 0) {
    $universe = $universe | Where-Object { $sym = $_ -replace '-USDT-SWAP$', ''; $Coins -contains $sym }
}

# 最小名义金额（USD/张）→ 每杠杆的初始保证金
$specRows = Invoke-Psql "select inst_id, min_notional_usd from okx_min_order where inst_id in ($(($universe | ForEach-Object { "'$_'" }) -join ',')) order by inst_id"
$spec = @{}
foreach ($row in $specRows) {
    $p = $row -split '\|'
    if ($p.Count -ge 2) { $spec[$p[0]] = [double]$p[1] }
}

$plan = @()
foreach ($inst in $universe) {
    if (-not $spec.ContainsKey($inst)) { Write-Warning "skip $inst (no min-order spec)"; continue }
    foreach ($lev in $Levers) {
        foreach ($side in $Sides) {
            $sym = $inst -replace '-USDT-SWAP$', ''
            $label = "$LabelPrefix-$sym-L$lev-$side"
            $margin = [math]::Round($spec[$inst] / $lev, 8)
            if ($margin -le 0) { Write-Warning "skip $label (margin<=0)"; continue }
            $plan += [pscustomobject]@{ InstId = $inst; Symbol = $sym; Lever = $lev; Side = $side; Label = $label; MarginUsd = $margin; MinNotional = $spec[$inst] }
        }
    }
}

Write-Host ("plan: {0} batches, {1} combos each" -f $plan.Count, $Combos)
if ($DryRun) { $plan | Format-Table -AutoSize; return }

$sw = [Diagnostics.Stopwatch]::StartNew()
$done = 0; $skip = 0; $fail = 0
foreach ($job in $plan) {
    $existing = Invoke-Psql "select id||'|'||status from okx_backtest_run where label='$($job.Label)' order by id desc limit 1"
    if ($existing) {
        $parts = ($existing | Select-Object -First 1) -split '\|'
        if ($parts.Count -ge 2 -and ($parts[1] -eq 'DONE' -or $parts[1] -eq 'RUNNING' -or $parts[1] -eq 'PENDING')) {
            Write-Host ("skip {0} (run {1} {2})" -f $job.Label, $parts[0], $parts[1]); $skip++; continue
        }
    }
    $body = @{
        instIds        = @($job.InstId)
        bar            = '1H'
        startMs        = $startMs
        endMs          = $endMs
        coinLimit      = 1
        combos         = $Combos
        topN           = $TopN
        marginUsd      = $job.MarginUsd
        maxHoldHours   = 48
        fundingPer8hPct = 0.01
        seed           = 20261107
        label          = $job.Label
        ranges         = @{
            leverage      = @{ min = $job.Lever; max = $job.Lever; step = 1 }
            maxLayers     = @{ min = 3; max = 8; step = 2 }
            layerStepPct  = @{ min = 1.5; max = 4.5; step = 1.5 }
            layerScale    = @{ min = 1.0; max = 2.0; step = 0.5 }
            takeProfitPct = @{ min = 1.0; max = 3.0; step = 1.0 }
            stopLossPct   = @{ min = 15.0; max = 25.0; step = 10.0 }
            sides         = @($job.Side)
        }
    } | ConvertTo-Json -Depth 8

    $runId = $null
    try {
        $resp = Invoke-RestMethod -Method Post -Uri "$base/strategy/backtests" -Body $body -ContentType 'application/json'
        $runId = $resp.data.runId
    } catch {
        $msg = $_.Exception.Message
        if ($msg -match '409') {
            Start-Sleep -Seconds 2
            try {
                $resp = Invoke-RestMethod -Method Post -Uri "$base/strategy/backtests" -Body $body -ContentType 'application/json'
                $runId = $resp.data.runId
            } catch { Write-Warning "submit failed $($job.Label): $($_.Exception.Message)"; $fail++; continue }
        } else {
            Write-Warning "submit failed $($job.Label): $msg"; $fail++; continue
        }
    }

    $deadline = (Get-Date).AddMinutes(6)
    $st = ''
    do {
        Start-Sleep -Milliseconds 300
        $st = (Invoke-Psql "select status from okx_backtest_run where id=$runId" | Select-Object -First 1)
    } while ($st -notmatch '^(DONE|FAILED|CANCELED)$' -and (Get-Date) -lt $deadline)

    if ($st -eq 'DONE') { $done++ } else { $fail++; Write-Warning "run $runId ($($job.Label)) ended $st" }
    $line = "{0} {1,-26} run={2} status={3} margin={4:F6} elapsed={5:F1}s" -f (Get-Date -Format 'HH:mm:ss'), $job.Label, $runId, $st, $job.MarginUsd, $sw.Elapsed.TotalSeconds
    Write-Host $line
    Add-Content -Path $logFile -Value $line
}
$sw.Stop()
Write-Host ("DONE batches={0} skipped={1} failed={2} in {3:F1}s" -f $done, $skip, $fail, $sw.Elapsed.TotalSeconds)
