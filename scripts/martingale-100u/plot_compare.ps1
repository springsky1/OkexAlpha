# Draw comparison charts with System.Drawing (no matplotlib in this runtime)
Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = 'Stop'
$Out = 'D:\workplace\Github\OkexAlpha\reports\martingale-100u'
$Img = Join-Path $Out 'img'
if (-not (Test-Path $Img)) { New-Item -ItemType Directory -Path $Img | Out-Null }
# PS 5.1 decodes script text as GBK: keep this file ASCII-only, and read JSON as UTF-8 explicitly
$json = [System.IO.File]::ReadAllText((Join-Path $Out 'compare_summary.json'), [System.Text.Encoding]::UTF8)
$S = $json | ConvertFrom-Json

$W = 1000; $H = 560
$Font = New-Object System.Drawing.Font('Segoe UI', 9)
$FontS = New-Object System.Drawing.Font('Segoe UI', 8)
$FontT = New-Object System.Drawing.Font('Segoe UI', 12, [System.Drawing.FontStyle]::Bold)
$Bg = [System.Drawing.Color]::White
$Ink = [System.Drawing.Color]::FromArgb(31, 41, 55)
$Grid = [System.Drawing.Color]::FromArgb(235, 238, 242)
$Blue = [System.Drawing.Color]::FromArgb(37, 99, 235)
$Red = [System.Drawing.Color]::FromArgb(220, 38, 38)
$Gray = [System.Drawing.Color]::FromArgb(107, 114, 128)

$bmp = New-Object System.Drawing.Bitmap($W, $H)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
$g.Clear($Bg)
$g.DrawString('Cumulative PnL - martingale (2 layers, 3x, 35 coins) vs legacy shadow params', $FontT, (New-Object System.Drawing.SolidBrush($Ink)), 14, 10)

function Draw-Panel($g, $series, $title, $x0, $y0, $w, $h, $colorMap) {
  $all = @()
  foreach ($kv in $series.GetEnumerator()) { $all += @($kv.Value | ForEach-Object { [double]$_[1] }) }
  $mn = ($all | Measure-Object -Minimum).Minimum
  $mx = ($all | Measure-Object -Maximum).Maximum
  $pad = [Math]::Max(($mx - $mn) * 0.08, 1.0)
  $mn -= $pad; $mx += $pad
  $n = 0
  foreach ($kv in $series.GetEnumerator()) { $n = [Math]::Max($n, $kv.Value.Count) }

  $penAxis = New-Object System.Drawing.Pen($Grid, 1)
  for ($i = 0; $i -le 4; $i++) {
    $y = $y0 + $h * $i / 4
    $g.DrawLine($penAxis, $x0, $y, $x0 + $w, $y)
    $val = $mx - ($mx - $mn) * $i / 4
    $g.DrawString(('{0:N1}' -f $val), $FontS, (New-Object System.Drawing.SolidBrush($Gray)), $x0 + $w + 6, $y - 7)
  }
  $g.DrawString($title, $Font, (New-Object System.Drawing.SolidBrush($Ink)), $x0, $y0 - 20)

  $lx = $x0 + 10
  foreach ($kv in $series.GetEnumerator()) {
    $color = $colorMap[$kv.Key]
    $pen = New-Object System.Drawing.Pen($color, 2)
    $pts = @()
    $vals = @($kv.Value)
    for ($i = 0; $i -lt $vals.Count; $i++) {
      $v = [double]$vals[$i][1]
      $x = $x0 + $w * $i / [Math]::Max($vals.Count - 1, 1)
      $y = $y0 + $h * (1 - ($v - $mn) / ($mx - $mn))
      $pts += New-Object System.Drawing.PointF([float]$x, [float]$y)
    }
    if ($pts.Count -gt 1) { $g.DrawLines($pen, $pts) }
    $g.DrawLine($pen, $lx, $y0 + $h + 22, $lx + 24, $y0 + $h + 22)
    $last = [double]$vals[$vals.Count - 1][1]
    $label = ('{0}  {1:N1} U' -f $kv.Key, $last)
    $g.DrawString($label, $FontS, (New-Object System.Drawing.SolidBrush($Ink)), $lx + 30, $y0 + $h + 15)
    $lx += 150
  }
}

$colors = @{}
$colors['mart-long'] = $Red
$colors['mart-short'] = $Blue
$top = [ordered]@{}
$top['mart-long'] = $S.'mart-long'.curve
$top['mart-short'] = $S.'mart-short'.curve
Draw-Panel $g $top 'Martingale sets: absolute USD (capital 70.6 U each)' 60 50 860 200 $colors

$colors2 = @{}
$colors2['legacy'] = $Gray
$bot = [ordered]@{}
$bot['legacy'] = $S.'legacy'.curve
Draw-Panel $g $bot 'Legacy shadow params: absolute USD (needs 596 U for 35 coins)' 60 330 860 160 $colors2

$bmp.Save((Join-Path $Img 'compare_equity.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Output 'wrote img/compare_equity.png'
