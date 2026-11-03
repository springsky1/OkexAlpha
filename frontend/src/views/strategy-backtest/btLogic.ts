/* 策略回测页的纯逻辑（1:1 移植 legacy app.js 14b 区）：
   解析后端 JSON 字符串字段、指标解包、徽章/单元格、进度、提交参数校验、
   资金曲线几何与摘要、逐笔排序、提升命名等。全部无副作用，便于单测。 */
import {
  datetimeLocalToMs,
  esc,
  fmtDur,
  fmtInt,
  fmtMoney,
  fmtNum,
  fmtPct,
  fmtTime,
  isNil,
  parseList,
  parseServerTime,
  toLocalInput
} from '../../lib/format'
import { COLOR_AXIS, COLOR_DOWN, COLOR_GRID, COLOR_UP, NL, TAB } from '../../lib/labels'
import { emptyRangesForm, rangesToPayload, type BtRangesFormState, type BtRangesPayload } from './btRanges'

/* 只有 PENDING/RUNNING 需要轮询 */
export const BT_ACTIVE_STATUSES = ['PENDING', 'RUNNING']
export const BT_PHASE_LABELS: Record<string, string> = { COARSE: '粗筛', REFINE: '精调' }

/* verdict -> [复用徽章配色, 中文] */
export const BT_VERDICT_LABELS: Record<string, [string, string]> = {
  CANDIDATE: ['DONE', '候选'],
  REJECT: ['FAILED', '淘汰'],
  INSUFFICIENT: ['PAUSED', '样本不足'],
  COIN: ['CANCELED', '单币']
}

/* 逐币/逐笔拉取条数（后端上限：coins 500 / trades 2000 / equity 5000） */
export const BT_COIN_LIMIT = 100
export const BT_TRADE_LIMIT = 200
export const BT_EQUITY_LIMIT = 5000
export const BT_LIST_LIMIT = 20
export const BT_POLL_MS = 2000

export interface BtRun {
  id: number
  strategyId?: number | null
  label?: string | null
  status: string
  bar?: string
  startMs?: number
  endMs?: number
  phase?: string | null
  request?: string | null
  coinTotal?: number
  coinDone?: number
  combosTotal?: number
  combosDone?: number
  bestResultId?: number | null
  error?: string | null
  createdAt?: string | null
  startedAt?: string | null
  finishedAt?: string | null
}

export interface BtResult {
  id: number
  runId?: number
  paramKey?: string | null
  params?: string | null
  instId?: string | null
  segment?: string | null
  metrics?: string | null
  coinCount?: number
  tradeCount?: number
  winRate?: number
  netPnl?: number
  netPnlPct?: number
  maxDrawdownPct?: number
  sharpe?: number
  liquidationCount?: number
  oosNetPnlPct?: number
  verdict?: string | null
  rank?: number | null
}

export interface BtTrade {
  id: number
  result_id: number
  inst_id: string
  side: string
  layers: number
  avg_entry: number
  max_adverse_pct: number
  entry_ts_ms: number
  exit_ts_ms: number
  entry_px: number
  exit_px: number
  sz: number
  notional_usd: number
  pnl: number
  pnl_pct: number
  fee: number
  funding_paid: number
  exit_reason: string
}

export interface BtEquityPoint {
  ts_ms: number
  equity: number
  drawdown_pct: number
}

export interface BtRunDetailPayload {
  run: BtRun | null
  results: BtResult[]
  resultTotal: number
  best: BtResult | null
}

export interface BtRunsPayload {
  total?: number
  limit?: number
  offset?: number
  items?: BtRun[]
}

export interface BtMetricsSample {
  returnPct?: number
  maxDrawdownPct?: number
  trades?: number
  sharpe?: number
}

/* 聚合行（ALL）是一层指标；COIN 行多包了一层 {"metrics":{...}} */
export interface BtMetricsJson {
  score?: number
  coinWinRatePct?: number
  coins?: number
  benchmark?: unknown
  inSample?: BtMetricsSample | null
  outOfSample?: BtMetricsSample | null
  calmar?: number
  sharpe?: number
  trades?: number
  winRate?: number
  returnPct?: number
  avgHoldHours?: number
  liquidations?: number
  profitFactor?: number
  worstTradePct?: number
  maxDrawdownPct?: number
  maxConsecutiveLosses?: number
}

export interface BtSubmitFormState {
  instIds: string
  bar: string
  start: string
  end: string
  combos: string
  topN: string
  margin: string
  maxHold: string
  coinLimit: string
  /** 选中的参数范围预设 key（null = 自定义/默认） */
  rangesPreset: string | null
  /** 参数范围（可选）：6 个维度全空 = 用内置网格 */
  ranges: BtRangesFormState
}

export interface BtSubmitBody {
  bar: string
  instIds?: string[]
  startMs?: number
  endMs?: number
  combos?: number
  topN?: number
  coinLimit?: number
  maxHoldHours?: number
  marginUsd?: number
  /** 自定义参数范围；全空时不带该字段（服务端走内置 648 组网格） */
  ranges?: BtRangesPayload
}

/* ---------- 后端 JSON 字符串字段容错解析 ---------- */

export function btParseJson(text: unknown): unknown {
  if (isNil(text)) return null
  if (typeof text === 'object') return text
  try {
    return JSON.parse(String(text))
  } catch (err) {
    return null
  }
}

/* 兼容 COIN 行多包一层的 metrics */
export function btMetrics(row: BtResult | null | undefined): BtMetricsJson | null {
  const m = btParseJson(row ? row.metrics : null)
  if (!m || typeof m !== 'object') return null
  const obj = m as { metrics?: unknown }
  if (obj.metrics && typeof obj.metrics === 'object') return obj.metrics as BtMetricsJson
  return obj as unknown as BtMetricsJson
}

/* prettyJson：与 legacy prettyJson 等价（字符串先尝试解析再格式化） */
export function prettyJson(value: unknown): string {
  if (isNil(value)) return '-'
  if (typeof value === 'string') {
    try {
      return JSON.stringify(JSON.parse(value), null, 2)
    } catch (err) {
      return value
    }
  }
  try {
    return JSON.stringify(value, null, 2)
  } catch (err) {
    return String(value)
  }
}

/* ---------- 状态 / 徽章 / 单元格 ---------- */

export function btStatusActive(status: unknown): boolean {
  return BT_ACTIVE_STATUSES.indexOf(String(status || '')) >= 0
}

export function btHasActiveRun(runs: BtRun[]): boolean {
  for (let i = 0; i < runs.length; i++) {
    if (btStatusActive(runs[i].status)) return true
  }
  return false
}

export function btPhaseLabel(phase: unknown): string {
  if (isNil(phase)) return '-'
  return BT_PHASE_LABELS[String(phase)] || String(phase)
}

export function btVerdictBadge(verdict: unknown): string {
  const key = String(verdict || '')
  const hit = BT_VERDICT_LABELS[key]
  if (!hit) return '<span class="badge" data-status="UNKNOWN">' + esc(key || '-') + '</span>'
  return '<span class="badge" data-status="' + hit[0] + '">' + esc(hit[1]) + '</span>'
}

/* 百分比单元格：正绿负红 */
export function btPctCell(v: unknown): string {
  if (isNil(v)) return '-'
  const n = Number(v)
  if (!isFinite(n)) return esc(String(v))
  const cls = n > 0 ? 'bt-pos' : n < 0 ? 'bt-neg' : ''
  const txt = esc(fmtPct(n))
  return cls ? '<span class="' + cls + '">' + txt + '</span>' : txt
}

/* 金额单元格：正绿负红，不做百分比换算（用于 pnl 等 USD 字段） */
export function btMoneyCell(v: unknown): string {
  if (isNil(v)) return '-'
  const n = Number(v)
  if (!isFinite(n)) return esc(String(v))
  const cls = n > 0 ? 'bt-pos' : n < 0 ? 'bt-neg' : ''
  const txt = esc(fmtMoney(n))
  return cls ? '<span class="' + cls + '">' + txt + '</span>' : txt
}

/* 浮点近似相等（用于判断后端 inSample 是否与整体指标重复） */
export function btSame(a: unknown, b: unknown): boolean {
  if (isNil(a) && isNil(b)) return true
  if (isNil(a) || isNil(b)) return false
  const x = Number(a)
  const y = Number(b)
  if (!isFinite(x) || !isFinite(y)) return String(a) === String(b)
  return Math.abs(x - y) < 1e-9
}

export function btProgressPct(done: unknown, total: unknown): number {
  const t = Number(total) || 0
  const d = Number(done) || 0
  return t > 0 ? Math.min(100, Math.round((d * 100) / t)) : 0
}

export function btProgressBarClass(status: unknown): string {
  if (status === 'DONE') return 'is-done'
  if (status === 'FAILED') return 'is-failed'
  return ''
}

/* ---------- 结果行查找 / 排序 / 提升 ---------- */

export function btFindRow(
  results: BtResult[],
  coins: Record<number, BtResult[]>,
  resultId: unknown
): BtResult | null {
  const id = Number(resultId)
  if (!id) return null
  for (let i = 0; i < results.length; i++) {
    if (Number(results[i].id) === id) return results[i]
  }
  const keys = Object.keys(coins)
  for (let k = 0; k < keys.length; k++) {
    const list = coins[Number(keys[k])] || []
    for (let i = 0; i < list.length; i++) {
      if (Number(list[i].id) === id) return list[i]
    }
  }
  return null
}

export function btCanPromote(row: BtResult | null | undefined): boolean {
  return !!row && String(row.segment || '') === 'ALL'
}

export function btPromoteName(runId: number, rank: unknown, aggId: number): string {
  return 'bt-' + runId + '-r' + (isNil(rank) ? aggId : rank)
}

/* 逐笔按平仓时间倒序（不改原数组） */
export function btSortedTrades(trades: BtTrade[]): BtTrade[] {
  return (trades || []).slice().sort(function (a, b) {
    return (Number(b.exit_ts_ms) || 0) - (Number(a.exit_ts_ms) || 0)
  })
}

/* ---------- 提交参数校验 / 组装 ---------- */

export interface BtIntCheck {
  ok: boolean
  value?: number
  message?: string
}

/* 与 legacy readIntField 等价：空 -> undefined；非整数/越界 -> 错误文案 */
export function btCheckInt(raw: unknown, min: number, max: number, label: string): BtIntCheck {
  const s = isNil(raw) ? '' : String(raw).trim()
  if (!s) return { ok: true, value: undefined }
  const n = Number(s)
  if (!isFinite(n) || Math.floor(n) !== n || n < min || n > max) {
    return { ok: false, message: (label || '数值') + ' 需为 ' + min + ' ~ ' + max + ' 的整数' }
  }
  return { ok: true, value: n }
}

export function btCheckMargin(raw: unknown): BtIntCheck {
  const s = isNil(raw) ? '' : String(raw).trim()
  if (!s) return { ok: true, value: undefined }
  const m = Number(s)
  if (!isFinite(m) || m <= 0) return { ok: false, message: 'marginUsd 需为正数' }
  return { ok: true, value: m }
}

/* box=true 表示除了 toast 还要写页面错误框（legacy 只有 7 天窗口这一类） */
export type BtPayloadResult =
  | { ok: true; body: BtSubmitBody }
  | { ok: false; message: string; box: boolean }

/* 校验顺序与 legacy submitBacktest 完全一致：combos → topN → coinLimit → maxHold → margin → 7 天窗口 */
export function btBuildPayload(form: BtSubmitFormState): BtPayloadResult {
  const combos = btCheckInt(form.combos, 1, 2000, 'combos')
  if (!combos.ok) return { ok: false, message: combos.message || '', box: false }
  const topN = btCheckInt(form.topN, 1, 20, 'topN')
  if (!topN.ok) return { ok: false, message: topN.message || '', box: false }
  const coinLimit = btCheckInt(form.coinLimit, 1, 50, 'coinLimit')
  if (!coinLimit.ok) return { ok: false, message: coinLimit.message || '', box: false }
  const maxHold = btCheckInt(form.maxHold, 1, 8760, 'maxHoldHours')
  if (!maxHold.ok) return { ok: false, message: maxHold.message || '', box: false }
  const margin = btCheckMargin(form.margin)
  if (!margin.ok) return { ok: false, message: margin.message || '', box: false }

  const instIds = parseList(form.instIds, NL, TAB)
  const bar = String(form.bar || '1m')
  const startMs = datetimeLocalToMs(form.start)
  const endMs = datetimeLocalToMs(form.end)
  if (startMs !== null && endMs !== null && endMs - startMs < 7 * 86400000) {
    return { ok: false, message: '回测窗口最短 7 天，请调整起止时间', box: true }
  }

  let ranges: BtRangesPayload | null
  try {
    ranges = rangesToPayload(form.ranges)
  } catch (err) {
    return { ok: false, message: (err as Error).message || '参数范围不合法', box: false }
  }

  const body: BtSubmitBody = { bar: bar }
  if (instIds.length) body.instIds = instIds
  if (startMs !== null) body.startMs = startMs
  if (endMs !== null) body.endMs = endMs
  if (combos.value !== undefined) body.combos = combos.value
  if (topN.value !== undefined) body.topN = topN.value
  if (coinLimit.value !== undefined) body.coinLimit = coinLimit.value
  if (maxHold.value !== undefined) body.maxHoldHours = maxHold.value
  if (margin.value !== undefined) body.marginUsd = margin.value
  if (ranges) body.ranges = ranges
  return { ok: true, body: body }
}

export const BT_FORM_DEFAULTS: BtSubmitFormState = {
  instIds: '',
  bar: '1m',
  start: '',
  end: '',
  combos: '400',
  topN: '5',
  margin: '100',
  maxHold: '48',
  coinLimit: '12',
  rangesPreset: null,
  ranges: emptyRangesForm()
}

/* 小样本预设：2 个币 · bar=1H · combos=40 · topN=3 · 近 90 天 */
export function btSampleForm(now: number): BtSubmitFormState {
  const base = isFinite(now) ? now : Date.now()
  return {
    instIds: 'BTC-USDT-SWAP, ETH-USDT-SWAP',
    bar: '1H',
    start: toLocalInput(base - 90 * 86400000),
    end: toLocalInput(base),
    combos: '40',
    topN: '3',
    margin: '100',
    maxHold: '48',
    coinLimit: '12',
    rangesPreset: null,
    ranges: emptyRangesForm()
  }
}

export function btQuickRange(days: number, now: number): { start: string; end: string } {
  const base = isFinite(now) ? now : Date.now()
  return { start: toLocalInput(base - days * 86400000), end: toLocalInput(base) }
}

/* ---------- 文案 ---------- */

export function btRunsSummary(total: number, shown: number, active: boolean): string {
  if (!shown) return '暂无批次'
  const t = isFinite(total) ? total : shown
  return '共 ' + fmtInt(t) + ' 个批次 · 显示最近 ' + shown + ' 个' + (active ? ' · 运行中每 2 秒刷新' : '')
}

export function btResultsSummary(shown: number, total: number): string {
  if (!shown) return ''
  return '展示前 ' + shown + ' 个 / 共 ' + fmtInt(total) + ' 个参数组合 · 点击行展开逐币明细'
}

export function btResultsEmptyText(active: boolean): string {
  return active
    ? '回测进行中，暂无可排行的参数组合（粗筛完成后出现）'
    : '暂无结果'
}

export function btChampionMeta(
  aggId: number,
  agg: BtResult | null,
  isChampion: boolean,
  coin: BtResult | null
): string {
  let text = '组合 #' + aggId
  if (agg && !isNil(agg.rank)) text += ' · rank ' + agg.rank
  if (isChampion) text += ' · 冠军（run.bestResultId）'
  if (coin) text += ' · 当前币 ' + String(coin.instId || '')
  return text
}

export function btTradesSummary(count: number, coinSelected: boolean): string {
  if (!count) return ''
  return '最近 ' + fmtInt(count) + ' 笔（平仓时间倒序）' + (coinSelected ? ' · 仅当前币' : ' · 组合内全部币')
}

export function btEquityEmptyText(isAgg: boolean): string {
  return isAgg
    ? '聚合行没有资金曲线数据，请在上方选择一个币'
    : '该币暂未落库资金曲线'
}

export function btDetailElapsed(run: BtRun | null | undefined, now: number): number {
  if (!run) return NaN
  const started = parseServerTime(run.startedAt)
  if (!isFinite(started)) return NaN
  const finished = parseServerTime(run.finishedAt)
  return (isFinite(finished) ? finished : now) - started
}

export function btDetailTimes(run: BtRun | null | undefined): {
  created: string
  started: string
  finished: string
  elapsed: string
} {
  const startedMs = parseServerTime(run ? run.startedAt : null)
  const finishedMs = parseServerTime(run ? run.finishedAt : null)
  const elapsed = btDetailElapsed(run, Date.now())
  return {
    created: fmtTime(parseServerTime(run ? run.createdAt : null)),
    started: fmtTime(startedMs),
    finished: fmtTime(finishedMs),
    elapsed: isFinite(elapsed) ? fmtDur(elapsed) : '-'
  }
}

/* ---------- 资金曲线：几何 + 摘要（画法与 legacy drawBtEquity 一致） ---------- */

export interface BtEquityTick {
  y: number
  value: number
}

export interface BtEquityGeometry {
  ok: boolean
  reason: '' | 'empty' | 'no-equity'
  width: number
  height: number
  padL: number
  padR: number
  padT: number
  padB: number
  plotW: number
  plotH: number
  min: number
  max: number
  minValue: number
  maxValue: number
  base: number
  last: number
  up: boolean
  color: string
  fillColor: string
  coords: [number, number][]
  ticks: BtEquityTick[]
  firstTs: number
  lastTs: number
  maxDD: number
}

export function btEquityGeometry(points: BtEquityPoint[], w: number, h: number): BtEquityGeometry {
  const padL = 66
  const padR = 16
  const padT = 14
  const padB = 28
  const plotW = Math.max(20, w - padL - padR)
  const plotH = Math.max(20, h - padT - padB)
  const geo: BtEquityGeometry = {
    ok: false,
    reason: '',
    width: w,
    height: h,
    padL: padL,
    padR: padR,
    padT: padT,
    padB: padB,
    plotW: plotW,
    plotH: plotH,
    min: 0,
    max: 0,
    minValue: 0,
    maxValue: 0,
    base: NaN,
    last: NaN,
    up: true,
    color: COLOR_UP,
    fillColor: 'rgba(18,164,92,0.10)',
    coords: [],
    ticks: [],
    firstTs: NaN,
    lastTs: NaN,
    maxDD: 0
  }
  const pts = Array.isArray(points) ? points : []
  if (!pts.length) {
    geo.reason = 'empty'
    return geo
  }

  let min = Infinity
  let max = -Infinity
  let maxDD = 0
  for (let i = 0; i < pts.length; i++) {
    const v = Number(pts[i].equity)
    if (isFinite(v)) {
      if (v < min) min = v
      if (v > max) max = v
    }
    const dd = Number(pts[i].drawdown_pct)
    if (isFinite(dd) && dd > maxDD) maxDD = dd
  }
  geo.maxDD = maxDD
  if (!isFinite(min) || !isFinite(max)) {
    geo.reason = 'no-equity'
    return geo
  }

  const minValue = min
  const maxValue = max
  if (max === min) {
    max = min + 1
    min = min - 1
  }
  const pad = (max - min) * 0.08
  min -= pad
  max += pad

  const yOf = function (v: number): number {
    return padT + plotH - ((v - min) / (max - min)) * plotH
  }
  const xOf = function (i: number): number {
    return padL + (pts.length <= 1 ? plotW / 2 : (i / (pts.length - 1)) * plotW)
  }

  const base = Number(pts[0].equity)
  const last = Number(pts[pts.length - 1].equity)
  const up = !(isFinite(last) && isFinite(base) && last < base)

  const coords: [number, number][] = []
  for (let i = 0; i < pts.length; i++) {
    const v = Number(pts[i].equity)
    if (!isFinite(v)) continue
    coords.push([xOf(i), yOf(v)])
  }

  const ticks: BtEquityTick[] = []
  for (let g = 0; g <= 4; g++) {
    ticks.push({ y: padT + (plotH * g) / 4, value: max - ((max - min) * g) / 4 })
  }

  geo.ok = true
  geo.min = min
  geo.max = max
  geo.minValue = minValue
  geo.maxValue = maxValue
  geo.base = base
  geo.last = last
  geo.up = up
  geo.color = up ? COLOR_UP : COLOR_DOWN
  geo.fillColor = up ? 'rgba(18,164,92,0.10)' : 'rgba(224,83,60,0.10)'
  geo.coords = coords
  geo.ticks = ticks
  geo.firstTs = Number(pts[0].ts_ms)
  geo.lastTs = Number(pts[pts.length - 1].ts_ms)
  return geo
}

/* 曲线摘要文案：起点 X → 末值 Y · N 个点 · 峰值 A · 谷值 B · 最大回撤 C% */
export function btEquitySummary(points: BtEquityPoint[]): string {
  const pts = Array.isArray(points) ? points : []
  if (!pts.length) return ''
  let min = Infinity
  let max = -Infinity
  let maxDD = 0
  for (let i = 0; i < pts.length; i++) {
    const v = Number(pts[i].equity)
    if (isFinite(v)) {
      if (v < min) min = v
      if (v > max) max = v
    }
    const dd = Number(pts[i].drawdown_pct)
    if (isFinite(dd) && dd > maxDD) maxDD = dd
  }
  if (!isFinite(min) || !isFinite(max)) return '资金曲线缺少 equity 字段'
  const base = Number(pts[0].equity)
  const last = Number(pts[pts.length - 1].equity)
  return (
    '起点 ' + fmtMoney(base) +
    ' → 末值 ' + fmtMoney(last) +
    ' · ' + fmtInt(pts.length) + ' 个点' +
    ' · 峰值 ' + fmtMoney(max) +
    ' · 谷值 ' + fmtMoney(min) +
    ' · 最大回撤 ' + fmtNum(maxDD) + '%'
  )
}

export { COLOR_AXIS, COLOR_GRID }
