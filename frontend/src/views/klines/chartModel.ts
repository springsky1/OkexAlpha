/* K 线图表纯逻辑：与 legacy app.js 1402~1953 / 1527~1630 逐行等价，抽成纯函数便于单测 */
import { fmtInt, fmtTime } from '../../lib/format'
import { COLOR_AXIS } from '../../lib/labels'
import type {
  AggregatedCandle,
  ChartOpts,
  KlineRow,
  LineKey,
  RatioPoint,
  RatioSpec
} from './types'

export const CHART_LINE_COLORS: Record<string, string> = {
  close: '#2f6fed',
  ma5: '#f5a524',
  ma20: '#8b5cf6',
  ma60: '#0ea5e9'
}
export const CHART_SERIES_KEYS: LineKey[] = ['close', 'ma5', 'ma20', 'ma60']
export const CHART_PAGE_SIZE = 1000
export const CHART_MAX_POINTS = 30000

/* 多空比叠加：三类 rubik 指标，走右轴（独立刻度），与蜡烛/价格折线不共享量纲 */
export const CHART_RATIO_SERIES: RatioSpec[] = [
  { key: 'ratioPos', ratioType: 'TOP_TRADER_POSITION', label: '精英持仓多空比' },
  { key: 'ratioAcc', ratioType: 'TOP_TRADER_ACCOUNT', label: '大户账户多空比' },
  { key: 'ratioLsr', ratioType: 'LSR_ACCOUNT_CONTRACT', label: '全账户多空比' }
]
export const CHART_RATIO_COLORS: Record<string, string> = {
  ratioPos: '#0f766e',
  ratioAcc: '#c2410c',
  ratioLsr: '#db2777'
}
export const CHART_RATIO_PAGE = 1000

export function chartLineColor(key: string): string {
  return CHART_LINE_COLORS[key] || CHART_RATIO_COLORS[key] || COLOR_AXIS
}

/* bar -> 多空比周期：rubik 只提供 5m / 1H / 1D，取能覆盖该 bar 的最小周期 */
export function ratioPeriodOfBar(bar: unknown): string {
  const b = String(bar || '').replace(/utc$/i, '')
  const unit = b.charAt(b.length - 1)
  if (unit === 'm') return '5m'
  if (unit === 'H') return '1H'
  return '1D'
}

/* 简单移动平均，不足窗口期为 null */
export function movingAverage(values: (number | null)[], window: number): (number | null)[] {
  const out: (number | null)[] = new Array(values.length)
  let sum = 0
  for (let i = 0; i < values.length; i++) {
    const v = values[i]
    if (v === null || !isFinite(v)) {
      out[i] = null
      continue
    }
    sum += v
    if (i >= window) {
      const drop = values[i - window]
      if (drop !== null && isFinite(drop)) sum -= drop
    }
    out[i] = i >= window - 1 ? sum / window : null
  }
  return out
}

/* 点数多于像素列时按列聚合 OHLC（开=首根开，收=末根收，高=最高，低=最低） */
export function aggregateCandles(
  list: KlineRow[],
  maxCols: number
): { rows: AggregatedCandle[]; groupSize: number } {
  if (list.length <= maxCols) return { rows: list.map(toCandle), groupSize: 1 }
  const groupSize = Math.ceil(list.length / maxCols)
  const rows: AggregatedCandle[] = []
  for (let i = 0; i < list.length; i += groupSize) {
    const chunk = list.slice(i, i + groupSize)
    let hi = -Infinity
    let lo = Infinity
    let open: number | null = null
    let close: number | null = null
    let vol = 0
    for (let j = 0; j < chunk.length; j++) {
      const r = chunk[j]
      const o = Number(r.open)
      const c = Number(r.close)
      const h = Number(r.high)
      const l = Number(r.low)
      if (open === null && isFinite(o)) open = o
      if (isFinite(c)) close = c
      if (isFinite(h) && h > hi) hi = h
      if (isFinite(l) && l < lo) lo = l
      vol += Number(r.vol) || 0
    }
    rows.push({ tsMs: Number(chunk[0].tsMs), open, close, high: hi, low: lo, vol, merged: chunk.length })
  }
  return { rows, groupSize }
}

/* 单根 K 线 -> 聚合结构（groupSize === 1 时与 legacy 一致：原始行直接参与绘制，不做额外取舍） */
function toCandle(r: KlineRow): AggregatedCandle {
  return {
    tsMs: Number(r.tsMs),
    open: Number(r.open),
    close: Number(r.close),
    high: Number(r.high),
    low: Number(r.low),
    vol: Number(r.vol) || 0,
    merged: 1
  }
}

/* 左侧价格轴范围：上下各留 6% 余量；高低全不可用返回 null（图表画「数据缺少 OHLC 字段」） */
export function paddedPriceRange(cols: AggregatedCandle[]): { min: number; max: number } | null {
  let min = Infinity
  let max = -Infinity
  for (let i = 0; i < cols.length; i++) {
    const lo = Number(cols[i].low)
    const hi = Number(cols[i].high)
    if (isFinite(lo) && lo < min) min = lo
    if (isFinite(hi) && hi > max) max = hi
  }
  if (!isFinite(min) || !isFinite(max)) return null
  if (max === min) {
    max = min + 1
    min = min - 1
  }
  const pad = (max - min) * 0.06
  return { min: min - pad, max: max + pad }
}

/* 右侧多空比刻度：必须包含 1.0 均衡线，最小跨度 0.1，再留 8% 余量 */
export function ratioScale(
  seriesList: RatioPoint[][],
  firstTs: number,
  lastTs: number
): { min: number; max: number } {
  let rmin = Infinity
  let rmax = -Infinity
  for (let s = 0; s < seriesList.length; s++) {
    const pts = seriesList[s]
    for (let i = 0; i < pts.length; i++) {
      const ts = pts[i][0]
      if (ts < firstTs || ts > lastTs) continue
      const v = pts[i][1]
      if (v < rmin) rmin = v
      if (v > rmax) rmax = v
    }
  }
  if (!isFinite(rmin) || !isFinite(rmax)) {
    rmin = 0.8
    rmax = 1.2
  }
  if (rmin > 1) rmin = 1
  if (rmax < 1) rmax = 1
  if (rmax - rmin < 0.05) {
    rmin -= 0.05
    rmax += 0.05
  }
  const rpad = (rmax - rmin) * 0.08
  return { min: rmin - rpad, max: rmax + rpad }
}

/* 价格轴刻度文案（legacy 内联逻辑） */
export function priceLabel(v: number): string {
  if (Math.abs(v) >= 1000) return fmtInt(Math.round(v))
  if (Math.abs(v) >= 1) return v.toFixed(2)
  return v.toFixed(6)
}

export interface ChartHintInput {
  rows: KlineRow[]
  groupSize: number
  colCount: number
  truncated: boolean
  opts: ChartOpts
  ratioLabels: string[]
  ratioEnabled: boolean
  ratioPeriod: string | null
  hasRatioCache: boolean
}

/* 图表下方 hint 文案（legacy drawChart 尾部拼接逻辑） */
export function buildChartHint(p: ChartHintInput): string {
  const list = p.rows
  const names: string[] = []
  if (p.opts.close) names.push('收盘价')
  if (p.opts.ma5) names.push('MA5')
  if (p.opts.ma20) names.push('MA20')
  if (p.opts.ma60) names.push('MA60')
  const parts: string[] = []
  parts.push('区间 ' + fmtTime(list[0].tsMs) + ' → ' + fmtTime(list[list.length - 1].tsMs))
  parts.push('共 ' + list.length + ' 根')
  if (p.groupSize > 1) parts.push('按 ' + p.groupSize + ' 根/列聚合为 ' + p.colCount + ' 列')
  if (p.truncated) parts.push('超出上限，仅显示最近 ' + list.length + ' 根')
  parts.push('折线：' + (names.length ? names.join(' / ') : '未选择'))
  if (p.ratioEnabled) {
    if (p.ratioLabels.length) {
      parts.push('多空比(右轴 ' + (p.ratioPeriod || '') + ')：' + p.ratioLabels.join(' / '))
    } else if (p.hasRatioCache) {
      parts.push('多空比：库内该区间无数据（可先补拉 ratios）')
    } else {
      parts.push('多空比：尚未加载')
    }
  }
  return parts.join(' · ')
}

/* 查询结果标题右侧汇总文案（renderKlineSummary） */
export function klineSummaryText(rows: KlineRow[], meta: string, suffix: string): string {
  if (!rows || !rows.length) return '暂无数据'
  return '共 ' + rows.length + ' 条 · ' + (meta || '') + (suffix ? ' · ' + suffix : '')
}

/* 图表卡片标题右侧文案（renderKlines 里的 chartMeta） */
export function chartMetaText(count: number, klineType: string): string {
  if (!count) return '暂无数据'
  return (
    '共 ' + count + ' 根 · ' + (klineType === 'MARK' ? '标记价格（无成交量字段）' : '蜡烛 K 线') + ' · 绿涨红跌'
  )
}
