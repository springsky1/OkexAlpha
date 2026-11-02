/* 补拉页四个表单的纯逻辑：与 legacy app.js submit{Kline,Funding,Ratio,Stats}Backfill 逐行等价
   （校验顺序、toast 文案、结果盒文案、body 字段顺序全部对齐） */
import { datetimeLocalToMs, parseList, toLocalInput } from '../../lib/format'
import { NL, STAT_KINDS, TAB } from '../../lib/labels'

export const DAY_MS = 86400000
export const PAGE_LIMIT_MIN = 1
export const PAGE_LIMIT_MAX = 300

/* 复选框组里 kinds 的展示文案（legacy index.html 原文） */
export const STATS_KIND_OPTIONS: [string, string][] = [
  ['OPEN_INTEREST_VOLUME', 'OPEN_INTEREST_VOLUME（持仓量-成交额）'],
  ['TAKER_VOLUME', 'TAKER_VOLUME（主动买卖量）'],
  ['OPEN_INTEREST', 'OPEN_INTEREST（持仓量快照）']
]

/* 提交结果：ok=false 时 toast 必显示，box 仅在 legacy 同时写结果盒时才有 */
export type SubmitOutcome =
  | { ok: true; body: Record<string, unknown> }
  | { ok: false; toast: string; box?: string }

export interface KlineFormInput {
  mode: string
  instIds: string
  instType: string
  baseCcy: string
  bars: string[]
  klineTypes: string[]
  start: string
  end: string
  pageLimit: string
}

export interface FundingFormInput {
  mode: string
  instIds: string
  instType: string
  start: string
  end: string
}

export interface RatioFormInput {
  ratioTypes: string[]
  ccys: string
  scopes: string
  periods: string[]
  start: string
  end: string
}

export interface StatsFormInput {
  kinds: string[]
  ccys: string
  instIds: string
  periods: string[]
  start: string
  end: string
}

/* 快捷时间：近 N 天（legacy setQuickRange） */
export function quickRange(days: number, now: number): { start: string; end: string; toast: string } {
  return {
    start: toLocalInput(now - days * DAY_MS),
    end: toLocalInput(now),
    toast: '已填充最近 ' + days + ' 天的时间范围'
  }
}

/* 清空时间（legacy clearQuickRange，无 toast） */
export function clearedRange(): { start: string; end: string } {
  return { start: '', end: '' }
}

type RangeOk = { ok: true; entries: [string, number][] }
type RangeErr = { ok: false; toast: string }

function checkRange(start: string, end: string): RangeOk | RangeErr {
  const startMs = datetimeLocalToMs(start)
  const endMs = datetimeLocalToMs(end)
  if (startMs !== null && endMs !== null && startMs >= endMs) {
    return { ok: false, toast: '开始时间必须早于结束时间' }
  }
  const entries: [string, number][] = []
  if (startMs !== null) entries.push(['startMs', startMs])
  if (endMs !== null) entries.push(['endMs', endMs])
  return { ok: true, entries }
}

/* legacy readIntField：未填 -> undefined；非法 -> toast 并终止提交 */
export function parsePageLimit(raw: string): { ok: true; value: number | undefined } | { ok: false; toast: string } {
  const s = String(raw === null || raw === undefined ? '' : raw).trim()
  if (!s) return { ok: true, value: undefined }
  const n = Number(s)
  if (!isFinite(n) || Math.floor(n) !== n || n < PAGE_LIMIT_MIN || n > PAGE_LIMIT_MAX) {
    return { ok: false, toast: 'pageLimit 需为 ' + PAGE_LIMIT_MIN + ' ~ ' + PAGE_LIMIT_MAX + ' 的整数' }
  }
  return { ok: true, value: n }
}

export function buildKlineBody(input: KlineFormInput): SubmitOutcome {
  const body: Record<string, unknown> = {}
  const mode = input.mode || 'type'

  if (mode === 'ids') {
    const ids = parseList(input.instIds, NL, TAB)
    if (!ids.length) {
      return { ok: false, toast: '按 instIds 模式需要至少填写一个合约 ID', box: '请填写 instIds，或切换到「按 instType」模式' }
    }
    body.instIds = ids
  } else {
    if (!input.instType) return { ok: false, toast: '请选择 instType' }
    body.instType = input.instType
    const baseCcy = String(input.baseCcy || '').trim()
    if (baseCcy) body.baseCcy = baseCcy
  }

  const bars = input.bars.slice()
  if (!bars.length) {
    return { ok: false, toast: 'bars 至少选择一个周期', box: 'bars 是必填项，请至少勾选一个周期' }
  }
  body.bars = bars

  const klineTypes = input.klineTypes.slice()
  if (!klineTypes.length) {
    return { ok: false, toast: 'klineTypes 至少选择一个类型', box: 'klineTypes 是必填项，请至少勾选一个类型' }
  }
  body.klineTypes = klineTypes

  const range = checkRange(input.start, input.end)
  if (!range.ok) return range
  for (let i = 0; i < range.entries.length; i++) {
    const e = range.entries[i]
    body[e[0]] = e[1]
  }

  const pageLimit = parsePageLimit(input.pageLimit)
  if (!pageLimit.ok) return pageLimit
  if (pageLimit.value !== undefined) body.pageLimit = pageLimit.value

  return { ok: true, body }
}

export function buildFundingBody(input: FundingFormInput): SubmitOutcome {
  const body: Record<string, unknown> = {}
  const mode = input.mode || 'type'

  if (mode === 'ids') {
    const ids = parseList(input.instIds, NL, TAB)
    if (!ids.length) {
      return { ok: false, toast: '按 instIds 模式需要至少填写一个合约 ID', box: '请填写 instIds，或切换到「按 instType」模式' }
    }
    body.instIds = ids
  } else {
    if (!input.instType) return { ok: false, toast: '请选择 instType' }
    body.instType = input.instType
  }

  const range = checkRange(input.start, input.end)
  if (!range.ok) return range
  for (let i = 0; i < range.entries.length; i++) {
    const e = range.entries[i]
    body[e[0]] = e[1]
  }
  return { ok: true, body }
}

export function buildRatioBody(input: RatioFormInput): SubmitOutcome {
  const body: Record<string, unknown> = {}

  const ratioTypes = input.ratioTypes.slice()
  if (!ratioTypes.length) {
    return { ok: false, toast: 'ratioTypes 至少选择一个类型', box: 'ratioTypes 是必填项' }
  }
  body.ratioTypes = ratioTypes

  const ccys = parseList(input.ccys, NL, TAB)
  const scopes = parseList(input.scopes, NL, TAB)
  const needCcy = ratioTypes.indexOf('LSR_ACCOUNT_CCY') >= 0
  const needInst =
    ratioTypes.indexOf('LSR_ACCOUNT_CONTRACT') >= 0 ||
    ratioTypes.indexOf('TOP_TRADER_ACCOUNT') >= 0 ||
    ratioTypes.indexOf('TOP_TRADER_POSITION') >= 0
  if (needCcy && !ccys.length) {
    return { ok: false, toast: '所选 ratioTypes 含 CCY 类型，必须填写 ccys', box: 'CCY 类型（LSR_ACCOUNT_CCY）必须填写 ccys' }
  }
  if (needInst && !scopes.length) {
    return { ok: false, toast: '所选 ratioTypes 含 INST 类型，必须填写 scopes', box: 'INST 类型（合约维度）必须填写 scopes' }
  }
  if (ccys.length) body.ccys = ccys
  if (scopes.length) body.scopes = scopes

  const periods = input.periods.slice()
  if (!periods.length) {
    return { ok: false, toast: 'periods 至少选择一个周期', box: 'periods 是必填项' }
  }
  body.periods = periods

  const range = checkRange(input.start, input.end)
  if (!range.ok) return range
  for (let i = 0; i < range.entries.length; i++) {
    const e = range.entries[i]
    body[e[0]] = e[1]
  }
  return { ok: true, body }
}

export function buildStatsBody(input: StatsFormInput): SubmitOutcome {
  const body: Record<string, unknown> = {}

  const kinds = input.kinds.slice()
  const effectiveKinds = kinds.length ? kinds : STAT_KINDS.slice()
  if (kinds.length) body.kinds = kinds

  const ccys = parseList(input.ccys, NL, TAB)
  const instIds = parseList(input.instIds, NL, TAB)
  if (effectiveKinds.indexOf('OPEN_INTEREST_VOLUME') >= 0 && !ccys.length) {
    return {
      ok: false,
      toast: 'OPEN_INTEREST_VOLUME 需要 ccys',
      box: '所选 kinds 含 OPEN_INTEREST_VOLUME，必须填写 ccys'
    }
  }
  if (ccys.length) body.ccys = ccys
  if (instIds.length) body.instIds = instIds

  const periods = input.periods.slice()
  if (periods.length) body.periods = periods

  const range = checkRange(input.start, input.end)
  if (!range.ok) return range
  for (let i = 0; i < range.entries.length; i++) {
    const e = range.entries[i]
    body[e[0]] = e[1]
  }
  return { ok: true, body }
}
