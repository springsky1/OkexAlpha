param([switch]$DryRun)
$ErrorActionPreference = 'Stop'
$root = 'D:\workplace\Github\OkexAlpha'
$plan = Join-Path $root '.run\leverage-plan2.csv'
$log = Join-Path $root '.run\live-leverage.log'
$rows = Import-Csv $plan
$i = 0; $ok = 0; $fail = 0
foreach ($r in $rows) {
    $i++
    $payload = @{
        instId  = $r.inst_id
        lever   = $r.lever
        mgnMode = 'isolated'
        posSide = $r.posSide
        confirm = 'CONFIRM-REAL-TRADING'
    } | ConvertTo-Json -Compress
    if ($DryRun) { "[$i] $($r.inst_id) lever=$($r.lever) posSide=$($r.posSide)"; continue }
    $payload | Out-File -Encoding ascii -NoNewline (Join-Path $root '.run\lv-body.json')
    $resp = & curl.exe -s --max-time 120 -X POST 'http://127.0.0.1:18080/api/v1/strategy/live/leverage' -H 'Content-Type: application/json' --data-binary "@$($root)\.run\lv-body.json"
    $line = "[$i] $($r.inst_id) lever=$($r.lever) $($r.posSide) -> $resp"
    $line | Out-File -Append -Encoding UTF8 $log
    $line
    $o = $resp | ConvertFrom-Json
    if ($o.code -ne '0' -or $o.data.accepted -ne $true) { $fail++; "STOP at $($r.inst_id)"; break }
    $ok++
    Start-Sleep -Milliseconds 200
}
"done ok=$ok fail=$fail"
