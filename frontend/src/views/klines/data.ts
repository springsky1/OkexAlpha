/* klines 页数据加载：与 legacy loadChartSeries / loadRatioPoints / loadRatioSeries / ensureChartRatio 等价 */
import { apiGet } from '../../api/client'
import { EP } from '../../api/endpoints'
import { CHART_MAX_POINTS, CHART_PAGE_SIZE, CHART_RATIO_PAGE, CHART_RATIO_SERIES, ratioPeriodOfBar } from './chartModel'
import type { KlineRow, RatioCache, RatioKey, RatioPoint } from './types'

export interface ChartSeriesBase {
  instId: string
  bar: string
  klineType: string
  startMs: number | null
  endMs: number | null
}

export interface ChartSeries {
  rows: KlineRow[]
  truncated: boolean
}

/* 按选中区间把整段 K 线读回来：每页 1000 条、按 ts 往前翻，直到覆盖 startMs 或读尽 */
export async function loadChartSeries(base: ChartSeriesBase, fallbackRows: KlineRow[]): Promise<ChartSeries> {
  const startMs = base.startMs
  if (startMs === null || startMs === undefined) {
    const rows = (fallbackRows || []).slice().sort((a, b) => Number(a.tsMs) - Number(b.tsMs))
    return { rows, truncated: false }
  }
  const seen: Record<string, number> = {}
  const rows: KlineRow[] = []
  let cursorEnd: number | null = base.endMs === null || base.endMs === undefined ? null : base.endMs
  let truncated = false
  for (let page = 0; page < 60; page++) {
    const params: Record<string, unknown> = {
      instId: base.instId,
      bar: base.bar,
      klineType: base.klineType,
      limit: CHART_PAGE_SIZE,
      order: 'desc',
      startMs: startMs
    }
    if (cursorEnd !== null) params.endMs = cursorEnd
    const batch = await apiGet<KlineRow[]>(EP.klines, params)
    const list = Array.isArray(batch) ? batch : []
    if (!list.length) break
    let minTs = Infinity
    for (let i = 0; i < list.length; i++) {
      const r = list[i]
      const ts = Number(r.tsMs)
      if (!isFinite(ts)) continue
      if (ts < minTs) minTs = ts
      if (!seen[ts]) {
        seen[ts] = 1
        rows.push(r)
      }
    }
    if (rows.length >= CHART_MAX_POINTS) {
      truncated = true
      break
    }
    if (!isFinite(minTs) || minTs <= startMs) break
    if (list.length < CHART_PAGE_SIZE) break
    cursorEnd = minTs - 1
  }
  rows.sort((a, b) => Number(a.tsMs) - Number(b.tsMs))
  if (rows.length > CHART_MAX_POINTS) {
    rows.splice(0, rows.length - CHART_MAX_POINTS)
    truncated = true
  }
  return { rows, truncated }
}

/* 拉取一条多空比序列（分页，每页 1000 点），返回 [[tsMs, ratio], ...] 升序 */
export async function loadRatioPoints(
  ratioType: string,
  scope: string,
  period: string,
  startMs: number | null,
  endMs: number | null
): Promise<RatioPoint[]> {
  const points: RatioPoint[] = []
  let cursor = startMs === null || startMs === undefined ? null : startMs
  for (let page = 0; page < 40; page++) {
    const params: Record<string, unknown> = {
      ratioType: ratioType,
      scope: scope,
      period: period,
      limit: CHART_RATIO_PAGE,
      order: 'asc'
    }
    if (cursor !== null) params.startMs = cursor
    if (endMs !== null && endMs !== undefined) params.endMs = endMs
    let batch: any
    try {
      batch = await apiGet<RatioPoint[]>(EP.ratios, params)
    } catch (e) {
      break
    }
    const list: any[] = Array.isArray(batch) ? batch : []
    if (!list.length) break
    for (let i = 0; i < list.length; i++) {
      const ts = Number(list[i].tsMs)
      const v = Number(list[i].longShortRatio)
      if (isFinite(ts) && isFinite(v)) points.push([ts, v])
    }
    const lastTs = Number(list[list.length - 1].tsMs)
    if (!isFinite(lastTs)) break
    if (list.length < CHART_RATIO_PAGE) break
    if (endMs !== null && endMs !== undefined && lastTs >= endMs) break
    cursor = lastTs + 1
  }
  points.sort((a, b) => a[0] - b[0])
  return points
}

export async function loadRatioSeries(
  instId: string,
  bar: string,
  startMs: number | null,
  endMs: number | null
): Promise<RatioCache> {
  const period = ratioPeriodOfBar(bar)
  const coin = String(instId || '').split('-')[0]
  const series = {} as Record<RatioKey, RatioPoint[]>
  for (let i = 0; i < CHART_RATIO_SERIES.length; i++) {
    const spec = CHART_RATIO_SERIES[i]
    const scope = spec.ratioType === 'LSR_ACCOUNT_CCY' ? coin : instId
    series[spec.key] = await loadRatioPoints(spec.ratioType, scope, period, startMs, endMs)
  }
  return { instId: instId, bar: bar, period: period, startMs: startMs, endMs: endMs, series: series }
}

export interface EnsureRatioOptions {
  enabled: boolean
  cached: RatioCache | null
  onLoading?: () => void
}

/* 勾选了多空比才去拉数据；同一查询条件命中缓存则直接复用 */
export async function ensureRatio(
  instId: string,
  bar: string,
  startMs: number | null,
  endMs: number | null,
  options: EnsureRatioOptions
): Promise<RatioCache | null> {
  if (!options.enabled) return null
  const s = startMs === undefined ? null : startMs
  const e = endMs === undefined ? null : endMs
  const cached = options.cached
  if (cached && cached.instId === instId && cached.bar === bar && cached.startMs === s && cached.endMs === e) {
    return cached
  }
  if (options.onLoading) options.onLoading()
  return loadRatioSeries(instId, bar, s, e)
}
