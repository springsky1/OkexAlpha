# Draw PNG charts with System.Drawing (no matplotlib available)
Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = 'Stop'
$Out = 'D:\workplace\Github\OkexAlpha\reports\martingale-100u'
$Img = Join-Path $Out 'img'
if (-not (Test-Path $Img)) { New-Item -ItemType Directory -Path $Img | Out-Null }
$Curve = Import-Csv (Join-Path $Out 'portfolio_curve.csv')
$Monthly = Import-Csv (Join-Path $Out 'portfolio_monthly.csv')
$Sel = Import-Csv (Join-Path $Out 'selected.csv')
$Orders = Import-Csv (Join-Path $Out 'orders.csv')

$W = 1000; $H = 420
$Font = New-Object System.Drawing.Font('Microsoft YaHei', 9)
$FontS = New-Object System.Drawing.Font('Microsoft YaHei', 8)
$FontT = New-Object System.Drawing.Font('Microsoft YaHei', 12, [System.Drawing.FontStyle]::Bold)
$Bg = [System.Drawing.Color]::White
$Ink = [System.Drawing.Color]::FromArgb(31, 41, 55)
$Grid = [System.Drawing.Color]::FromArgb(243, 244, 246)
$Blue = [System.Drawing.Color]::FromArgb(37, 99, 235)
$Red = [System.Drawing.Color]::FromArgb(220, 38, 38)
$Green = [System.Drawing.Color]::FromArgb(22, 163, 74)
$Purple = [System.Drawing.Color]::FromArgb(124, 58, 237)
$Gray = [System.Drawing.Color]::FromArgb(107, 114, 128)

function New-Bitmap($w, $h, $title) {
  $bmp = New-Object System.Drawing.Bitmap($w, $h)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
  $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
  $g.Clear($Bg)
  $g.DrawString($title, $FontT, (New-Object System.Drawing.SolidBrush($Ink)), 14, 10)
  return @($bmp, $g)
}

# ---------- 1. equity curve
$eq = $Curve | ForEach-Object { [double]$_.equity }
$mn = ($eq | Measure-Object -Minimum).Minimum; $mx = ($eq | Measure-Object -Maximum).Maximum
$pad = [Math]::Max(($mx - $mn) * 0.08, 1); $mn -= $pad; $mx += $pad
$r = New-Bitmap $W $H 'Portfolio equity curve (27 strategies, min sizing, 1H)'
$bmp = $r[0]; $g = $r[1]
$L = 70; $R = $W - 24; $T = 56; $B = $H - 52
for ($i = 0; $i -le 4; $i++) {
  $y = $B - ($B - $T) * $i / 4
  $g.DrawLine((New-Object System.Drawing.Pen($Grid, 1)), $L, $y, $R, $y)
  $val = $mn + ($mx - $mn) * $i / 4
  $g.DrawString(('{0:N0} U' -f $val), $FontS, (New-Object System.Drawing.SolidBrush($Gray)), 8, ($y - 6))
}
$n = $eq.Count
$pts = New-Object 'System.Collections.Generic.List[System.Drawing.PointF]'
for ($i = 0; $i -lt $n; $i++) {
  $x = $L + ($R - $L) * $i / ($n - 1)
  $y = $B - ($B - $T) * ($eq[$i] - $mn) / ($mx - $mn)
  $pts.Add((New-Object System.Drawing.PointF($x, $y)))
}
$area = New-Object 'System.Collections.Generic.List[System.Drawing.PointF]'
$area.AddRange($pts); $area.Add((New-Object System.Drawing.PointF($R, $B))); $area.Add((New-Object System.Drawing.PointF($L, $B)))
$g.FillPolygon((New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(28, 37, 99, 235))), $area.ToArray())
$g.DrawLines((New-Object System.Drawing.Pen($Blue, 2)), $pts.ToArray())
# x labels
for ($i = 0; $i -le 9; $i++) {
  $k = [int](($n - 1) * $i / 9)
  $dt = [datetime]::Parse($Curve[$k].ts_ms)
  $x = $L + ($R - $L) * $k / ($n - 1)
  $g.DrawString($dt.ToString('MM-dd'), $FontS, (New-Object System.Drawing.SolidBrush($Gray)), ($x - 14), ($B + 8))
}
$g.DrawString(('start {0:N2} U   end {1:N2} U   peak {2:N2} U' -f $eq[0], $eq[$n-1], $mx), $FontS, (New-Object System.Drawing.SolidBrush($Ink)), $L, $T - 22)
$bmp.Save((Join-Path $Img 'equity.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()

# ---------- 2. monthly bars
$mv = $Monthly | ForEach-Object { [double]$_.'0' }
$mlab = $Monthly | ForEach-Object { ([datetime]::Parse($_.ts_ms)).ToString('yy-MM') }
$mxv = ($mv | Measure-Object -Maximum).Maximum
$r = New-Bitmap $W 360 'Monthly PnL (USD, portfolio)'
$bmp = $r[0]; $g = $r[1]
$L = 70; $R = $W - 24; $T = 56; $B = 360 - 60
$bw = ($R - $L) / $mv.Count * 0.62
for ($i = 0; $i -le 4; $i++) {
  $y = $B - ($B - $T) * $i / 4
  $g.DrawLine((New-Object System.Drawing.Pen($Grid, 1)), $L, $y, $R, $y)
  $g.DrawString(('{0:N0}' -f ($mxv * $i / 4)), $FontS, (New-Object System.Drawing.SolidBrush($Gray)), 12, ($y - 6))
}
for ($i = 0; $i -lt $mv.Count; $i++) {
  $x = $L + ($R - $L) * ($i + 0.5) / $mv.Count - $bw / 2
  $h = ($B - $T) * $mv[$i] / $mxv
  $g.FillRectangle((New-Object System.Drawing.SolidBrush($Green)), $x, ($B - $h), $bw, $h)
  $g.DrawString($mlab[$i], $FontS, (New-Object System.Drawing.SolidBrush($Gray)), ($x - 6), ($B + 6))
  $g.DrawString(('{0:N0}' -f $mv[$i]), $FontS, (New-Object System.Drawing.SolidBrush($Ink)), ($x - 4), ($B - $h - 16))
}
$bmp.Save((Join-Path $Img 'monthly.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()

# ---------- 3. allocation horizontal bars
$rows = @()
foreach ($s in $Sel) {
  $c = 0.0
  foreach ($o in $Orders) { if ($o.idx -eq $s.idx -and [int]$o.layer -gt 0) { $c += [double]$o.margin_usd } }
  $rows += [pscustomobject]@{ name = ($s.inst_id -replace '-USDT-SWAP',''); side = $s.side; cap = $c; lev = $s.leverage }
}
$rows = $rows | Sort-Object -Property cap -Descending
$mxv = ($rows | Measure-Object -Property cap -Maximum).Maximum
$H3 = 90 + $rows.Count * 20
$r = New-Bitmap 1000 $H3 'Capital per strategy (USD, lot-rounded) - total 47.15 U of 90.27 U available'
$bmp = $r[0]; $g = $r[1]
$L = 110; $R = 780; $T = 56
$barh = 13
for ($i = 0; $i -lt $rows.Count; $i++) {
  $y = $T + $i * 20
  $w = ($R - $L) * $rows[$i].cap / $mxv
  $col = if ($rows[$i].side -eq 'long') { $Blue } else { $Red }
  $g.FillRectangle((New-Object System.Drawing.SolidBrush($col)), $L, $y, $w, $barh)
  $g.DrawString(('{0} {1}x' -f $rows[$i].name, $rows[$i].lev), $FontS, (New-Object System.Drawing.SolidBrush($Ink)), 10, ($y - 1))
  $g.DrawString(('{0:N2} U' -f $rows[$i].cap), $FontS, (New-Object System.Drawing.SolidBrush($Ink)), ($L + $w + 6), ($y - 1))
}
$bmp.Save((Join-Path $Img 'alloc.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()

# ---------- 4. scatter dd vs return
$pts = @()
foreach ($s in $Sel) {
  $cap = 0.0
  foreach ($o in $Orders) { if ($o.idx -eq $s.idx -and [int]$o.layer -gt 0) { $cap += [double]$o.margin_usd } }
  $pts += [pscustomobject]@{ x = [double]$s.m_dd; y = [double]$s.m_ret; cap = $cap; n = ($s.inst_id -replace '-USDT-SWAP',''); t = $s.tier }
}
$xmax = ($pts | Measure-Object -Property x -Maximum).Maximum * 1.12
$ymax = ($pts | Measure-Object -Property y -Maximum).Maximum * 1.08
$cmax = ($pts | Measure-Object -Property cap -Maximum).Maximum
$r = New-Bitmap 1000 470 'Risk vs return per strategy (x = max drawdown %% of capital, y = total return %%)'
$bmp = $r[0]; $g = $r[1]
$L = 80; $R = 1000 - 24; $T = 56; $B = 470 - 56
for ($i = 0; $i -le 4; $i++) {
  $y = $B - ($B - $T) * $i / 4
  $g.DrawLine((New-Object System.Drawing.Pen($Grid, 1)), $L, $y, $R, $y)
  $g.DrawString(('{0:N0}%%' -f ($ymax * $i / 4)), $FontS, (New-Object System.Drawing.SolidBrush($Gray)), 16, ($y - 6))
  $x = $L + ($R - $L) * $i / 4
  $g.DrawString(('{0:N0}%%' -f ($xmax * $i / 4)), $FontS, (New-Object System.Drawing.SolidBrush($Gray)), ($x - 10), ($B + 8))
}
foreach ($p in $pts) {
  $x = $L + ($R - $L) * $p.x / $xmax
  $y = $B - ($B - $T) * $p.y / $ymax
  $rad = 4 + 10 * [Math]::Sqrt($p.cap / $cmax)
  $col = if ($p.t -eq 'T1') { $Blue } else { $Red }
  $g.FillEllipse((New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(140, $col))), ($x - $rad), ($y - $rad), (2 * $rad), (2 * $rad))
  $g.DrawString($p.n, $FontS, (New-Object System.Drawing.SolidBrush($Ink)), ($x - 12), ($y - $rad - 14))
}
$bmp.Save((Join-Path $Img 'scatter.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()

Get-ChildItem $Img | Select-Object Name, Length
