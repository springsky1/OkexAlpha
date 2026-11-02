/* canvas 蜡烛图 + 多空比叠加副图：与 legacy app.js drawChart(1632~1953) 逐行等价，
   区别仅在于数据全部由参数传入（不再读 DOM / 全局 ui），hint 文案通过回调交回组件 */
import { fmtShortTime } from '../../lib/format'
import { CHART_FONT, COLOR_AXIS, COLOR_DOWN, COLOR_GRID, COLOR_UP } from '../../lib/labels'
import {
  aggregateCandles,
  buildChartHint,
  chartLineColor,
  CHART_RATIO_SERIES,
  movingAverage,
  paddedPriceRange,
  priceLabel,
  ratioScale
} from './chartModel'
import type { ChartInfo, ChartOpts, KlineRow, RatioCache } from './types'

export interface DrawParams {
  rows: KlineRow[]
  info: ChartInfo | null
  opts: ChartOpts
  ratio: RatioCache | null
}

interface RatioDrawSeries {
  key: string
  label: string
  points: [number, number][]
}

export function drawChart(
  canvas: HTMLCanvasElement | null,
  params: DrawParams,
  onHint: (text: string) => void
): void {
  if (!canvas) return
  const wrap = canvas.parentElement
  const w = Math.max(320, (wrap && wrap.clientWidth) || 600)
  const h = Math.max(200, (wrap && wrap.clientHeight) || 340)
  const dpr = window.devicePixelRatio || 1
  canvas.width = Math.round(w * dpr)
  canvas.height = Math.round(h * dpr)
  canvas.style.width = w + 'px'
  canvas.style.height = h + 'px'
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  ctx.clearRect(0, 0, w, h)

  const list: KlineRow[] = Array.isArray(params.rows) ? params.rows : []
  if (!list.length) {
    onHint('')
    ctx.fillStyle = COLOR_AXIS
    ctx.font = '12px sans-serif'
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillText('暂无数据', w / 2, h / 2)
    return
  }

  /* 多空比叠加：只有勾选且数据就绪才画；右轴需要额外的刻度空间 */
  const ratioSeries: RatioDrawSeries[] = []
  if (params.ratio && params.ratio.series) {
    for (let i = 0; i < CHART_RATIO_SERIES.length; i++) {
      const spec = CHART_RATIO_SERIES[i]
      if (!params.opts[spec.key]) continue
      const pts = params.ratio.series[spec.key] || []
      if (pts.length) ratioSeries.push({ key: spec.key, label: spec.label, points: pts })
    }
  }
  const hasRatio = ratioSeries.length > 0

  const padL = 66
  const padR = hasRatio ? 56 : 14
  const padT = 12
  const padB = 26
  const plotW = Math.max(20, w - padL - padR)
  const plotH = Math.max(20, h - padT - padB)

  /* 数据点比像素列还多时按列聚合 OHLC，保证选中区间的整段都画得下 */
  const agg = aggregateCandles(list, Math.max(40, Math.floor(plotW)))
  const cols = agg.rows

  const range = paddedPriceRange(cols)
  if (!range) {
    ctx.fillStyle = COLOR_AXIS
    ctx.font = '12px sans-serif'
    ctx.textAlign = 'center'
    ctx.fillText('数据缺少 OHLC 字段', w / 2, h / 2)
    return
  }
  const min = range.min
  const max = range.max

  function yOf(v: number): number {
    return padT + plotH - ((v - min) / (max - min)) * plotH
  }

  /* 网格与价格轴 */
  const gridN = 5
  ctx.font = CHART_FONT
  ctx.lineWidth = 1
  ctx.textAlign = 'right'
  ctx.textBaseline = 'middle'
  for (let i = 0; i <= gridN; i++) {
    const v = min + ((max - min) * i) / gridN
    const y = Math.round(yOf(v)) + 0.5
    ctx.strokeStyle = COLOR_GRID
    ctx.beginPath()
    ctx.moveTo(padL, y)
    ctx.lineTo(padL + plotW, y)
    ctx.stroke()
    ctx.fillStyle = COLOR_AXIS
    ctx.fillText(priceLabel(v), padL - 8, y)
  }

  /* 蜡烛 */
  const slot = plotW / cols.length
  let bodyW = Math.min(14, slot * 0.68)
  if (bodyW < 1) bodyW = 1
  ctx.lineWidth = 1
  for (let i = 0; i < cols.length; i++) {
    const r = cols[i]
    const o = Number(r.open)
    const c = Number(r.close)
    const hi = Number(r.high)
    const lo = Number(r.low)
    if (!isFinite(o) || !isFinite(c) || !isFinite(hi) || !isFinite(lo)) continue
    const cx = padL + slot * (i + 0.5)
    const up = c >= o
    const color = up ? COLOR_UP : COLOR_DOWN
    ctx.strokeStyle = color
    ctx.fillStyle = color
    const x = Math.round(cx) + 0.5
    ctx.beginPath()
    ctx.moveTo(x, yOf(hi))
    ctx.lineTo(x, yOf(lo))
    ctx.stroke()
    const yOpen = yOf(o)
    const yClose = yOf(c)
    const top = Math.min(yOpen, yClose)
    const height = Math.max(1, Math.abs(yClose - yOpen))
    ctx.fillRect(Math.round(cx - bodyW / 2), Math.round(top), Math.max(1, Math.round(bodyW)), Math.round(height))
  }

  /* 叠加折线：收盘价 / MA5 / MA20 / MA60 */
  const closes: (number | null)[] = []
  for (let i = 0; i < cols.length; i++) {
    const c = Number(cols[i].close)
    closes.push(isFinite(c) ? c : null)
  }
  const lines: { key: string; values: (number | null)[] }[] = []
  if (params.opts.close) lines.push({ key: 'close', values: closes })
  if (params.opts.ma5) lines.push({ key: 'ma5', values: movingAverage(closes, 5) })
  if (params.opts.ma20) lines.push({ key: 'ma20', values: movingAverage(closes, 20) })
  if (params.opts.ma60) lines.push({ key: 'ma60', values: movingAverage(closes, 60) })
  for (let s = 0; s < lines.length; s++) {
    const values = lines[s].values
    ctx.strokeStyle = chartLineColor(lines[s].key)
    ctx.lineWidth = 1.6
    ctx.beginPath()
    let started = false
    for (let i = 0; i < values.length; i++) {
      const v = values[i]
      if (v === null || !isFinite(v)) {
        started = false
        continue
      }
      const x = padL + slot * (i + 0.5)
      const y = yOf(v)
      if (!started) {
        ctx.moveTo(x, y)
        started = true
      } else {
        ctx.lineTo(x, y)
      }
    }
    ctx.stroke()
  }
  ctx.lineWidth = 1

  /* 多空比折线：右轴独立刻度，附 1.0 多空均衡基准虚线 */
  if (hasRatio) {
    const tsList: number[] = []
    for (let i = 0; i < list.length; i++) tsList.push(Number(list[i].tsMs))
    const firstTs = tsList[0]
    const lastTs = tsList[tsList.length - 1]

    function indexOfTs(ts: number): number {
      if (ts <= tsList[0]) return 0
      if (ts >= tsList[tsList.length - 1]) return tsList.length - 1
      let lo = 0
      let hi = tsList.length - 1
      while (lo < hi) {
        const mid = (lo + hi) >> 1
        if (tsList[mid] < ts) lo = mid + 1
        else hi = mid
      }
      return lo
    }

    const rscale = ratioScale(
      ratioSeries.map((s) => s.points),
      firstTs,
      lastTs
    )
    const rmin = rscale.min
    const rmax = rscale.max

    function ryOf(v: number): number {
      return padT + plotH - ((v - rmin) / (rmax - rmin)) * plotH
    }

    const yOne = ryOf(1)
    if (yOne >= padT && yOne <= padT + plotH) {
      ctx.setLineDash([4, 4])
      ctx.strokeStyle = '#c8cfdb'
      ctx.beginPath()
      ctx.moveTo(padL, yOne)
      ctx.lineTo(padL + plotW, yOne)
      ctx.stroke()
      ctx.setLineDash([])
    }

    for (let s = 0; s < ratioSeries.length; s++) {
      const pts = ratioSeries[s].points
      ctx.strokeStyle = chartLineColor(ratioSeries[s].key)
      ctx.lineWidth = 1.6
      ctx.beginPath()
      let started = false
      for (let i = 0; i < pts.length; i++) {
        const ts = pts[i][0]
        if (ts < firstTs || ts > lastTs) continue
        const idx = indexOfTs(ts)
        const col = Math.min(cols.length - 1, Math.floor(idx / agg.groupSize))
        const x = padL + slot * (col + 0.5)
        const y = ryOf(pts[i][1])
        if (!started) {
          ctx.moveTo(x, y)
          started = true
        } else {
          ctx.lineTo(x, y)
        }
      }
      ctx.stroke()
    }
    ctx.lineWidth = 1

    /* 右轴刻度（多空比量纲） */
    ctx.fillStyle = COLOR_AXIS
    ctx.textAlign = 'left'
    ctx.textBaseline = 'middle'
    const ticks = 3
    for (let i = 0; i < ticks; i++) {
      const v = rmin + ((rmax - rmin) * i) / (ticks - 1)
      const y = Math.min(padT + plotH - 6, Math.max(padT + 6, ryOf(v)))
      ctx.fillText(v.toFixed(2), padL + plotW + 8, y)
    }
    /* 右轴标题（竖排），与左轴价格区分 */
    ctx.save()
    ctx.translate(padL + plotW + padR - 6, padT + plotH / 2)
    ctx.rotate(-Math.PI / 2)
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillStyle = '#7a8496'
    ctx.fillText('多空比', 0, 0)
    ctx.restore()
  }

  /* 时间轴 */
  ctx.fillStyle = COLOR_AXIS
  ctx.textAlign = 'center'
  ctx.textBaseline = 'top'
  const labelN = Math.min(6, cols.length)
  for (let i = 0; i < labelN; i++) {
    const idx = labelN === 1 ? 0 : Math.round(((cols.length - 1) * i) / (labelN - 1))
    const cx = padL + slot * (idx + 0.5)
    const clamped = Math.min(padL + plotW - 34, Math.max(padL + 34, cx))
    ctx.fillText(fmtShortTime(cols[idx].tsMs), clamped, padT + plotH + 8)
  }

  /* 左上角说明 */
  ctx.textAlign = 'left'
  ctx.textBaseline = 'top'
  ctx.fillStyle = COLOR_AXIS
  ctx.fillText('共 ' + list.length + ' 根' + (agg.groupSize > 1 ? '（' + cols.length + ' 列）' : ''), padL + 2, padT + 2)

  /* 折线图例 + 区间信息 */
  onHint(
    buildChartHint({
      rows: list,
      groupSize: agg.groupSize,
      colCount: cols.length,
      truncated: !!(params.info && params.info.truncated),
      opts: params.opts,
      ratioLabels: ratioSeries.map((s) => s.label),
      ratioEnabled: anyRatioEnabled(params.opts),
      ratioPeriod: params.ratio ? params.ratio.period : null,
      hasRatioCache: !!params.ratio
    })
  )
}

export function anyRatioEnabled(opts: ChartOpts): boolean {
  return !!(opts.ratioPos || opts.ratioAcc || opts.ratioLsr)
}
