param(
    [switch]$DryRun,
    [string]$Skip = 'FIL-USDT-SWAP'
)
$ErrorActionPreference = 'Stop'
$root = 'D:\workplace\Github\OkexAlpha'
$csv = Join-Path $root '.run\layer1.csv'
$log = Join-Path $root '.run\live-orders.log'
$rows = Import-Csv $csv
$i = 0
$okCount = 0
$fail = 0
foreach ($r in $rows) {
    $i++
    if ($r.instId -eq $Skip) { continue }
    $posSide = $r.side
    $side = if ($r.side -eq 'long') { 'buy' } else { 'sell' }
    $sz = [double]$r.sz_contracts
    $payload = @{
        instId  = $r.instId
        side    = $side
        posSide = $posSide
        sz      = $sz
        ordType = 'market'
        tdMode  = 'isolated'
        confirm = 'CONFIRM-REAL-TRADING'
    } | ConvertTo-Json -Compress
    if ($DryRun) { "[$i] $($r.instId) $side $posSide $sz"; continue }
    $json = $payload | Out-File -Encoding ascii -NoNewline (Join-Path $root '.run\order-body.json')
    $resp = & curl.exe -s --max-time 90 -X POST 'http://127.0.0.1:18080/api/v1/strategy/live/orders' -H 'Content-Type: application/json' --data-binary "@$($root)\.run\order-body.json"
    $line = "[$i] $($r.instId) $side $posSide sz=$sz -> $resp"
    $line | Out-File -Append -Encoding UTF8 $log
    $line
    $o = $resp | ConvertFrom-Json
    if ($o.code -ne '0' -or $o.data.accepted -ne $true) { $fail++; "STOP: order failed"; break }
    $okCount++
    Start-Sleep -Milliseconds 300
}
"done ok=$okCount fail=$fail"
