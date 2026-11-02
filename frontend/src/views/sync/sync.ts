/* 同步页（legacy submitSyncInstruments / submitSyncBasic / renderSyncResult 1:1 等价） */
import { esc, fmtDur, parseList } from '../../lib/format'
import { NL, TAB } from '../../lib/labels'
import { prettyJson } from '../jobs/jobs'

export const SYNC_INST_TYPES = ['SPOT', 'MARGIN', 'SWAP', 'FUTURES', 'OPTION']
export const SYNC_MAIN_INST_TYPES = ['SPOT', 'MARGIN', 'SWAP', 'FUTURES', 'OPTION']
export const SYNC_KLINE_TYPES = ['CANDLE', 'MARK']
export const SYNC_RATIO_TYPES = ['LSR_ACCOUNT_CCY', 'LSR_ACCOUNT_CONTRACT', 'TOP_TRADER_ACCOUNT', 'TOP_TRADER_POSITION']
export const DEFAULT_BASIC_INST_TYPE = 'SWAP'
export const BASIC_DEFAULTS = { maxInstruments: '20', maxCcys: '10', days: '7', bars: '1H' }

/* ---------- 同步品种（POST /api/v1/sync/instruments） ---------- */
export interface InstrumentSyncInput {
  instTypes: string[]
  includeOptions: boolean
  families: string
}

export function instrumentTypeText(instTypes: string[]): string {
  return instTypes.length ? instTypes.join('、') : '（空 → 后端同步全部 5 类）'
}

export function buildInstrumentBody(input: InstrumentSyncInput): Record<string, unknown> {
  const families = parseList(input.families, NL, TAB)
  const body: Record<string, unknown> = { instTypes: input.instTypes, includeOptions: input.includeOptions }
  if (families.length) body.families = families
  return body
}

export function instrumentConfirmHtml(input: InstrumentSyncInput): string {
  const families = parseList(input.families, NL, TAB)
  return (
    '<p>即将调用 <span class="mono">POST /api/v1/sync/instruments</span>。</p><ul>' +
    '<li>instTypes：' + esc(instrumentTypeText(input.instTypes)) + '</li>' +
    '<li>includeOptions：' + (input.includeOptions ? '是（逐家族拉取期权，最慢）' : '否') + '</li>' +
    '<li>families：' + esc(families.length ? families.join('、') : '（自动推断）') + '</li>' +
    '</ul><p class="muted">该接口是同步阻塞调用，可能跑几分钟，期间页面只显示已耗时。请勿刷新页面。</p>'
  )
}

/* ---------- 组合同步（POST /api/v1/sync/basic） ---------- */
export interface BasicSyncInput {
  instTypes: string[]
  includeOptions: boolean
  families: string
  instType: string
  maxInstruments: string
  maxCcys: string
  days: string
  bars: string
  klineTypes: string[]
  includeFunding: boolean
  includeRatios: boolean
  ratioTypes: string[]
}

export type IntFieldOutcome = { ok: true; value: number | undefined } | { ok: false; toast: string }

/* legacy readIntField：空 -> undefined；非 [min,max] 整数 -> toast 并中止 */
export function parseIntField(raw: unknown, min: number, max: number, label: string): IntFieldOutcome {
  const s = String(raw === null || raw === undefined ? '' : raw).trim()
  if (!s) return { ok: true, value: undefined }
  const n = Number(s)
  if (!isFinite(n) || Math.floor(n) !== n || n < min || n > max) {
    return { ok: false, toast: label + ' 需为 ' + min + ' ~ ' + max + ' 的整数' }
  }
  return { ok: true, value: n }
}

export type BasicSyncOutcome =
  | { ok: true; body: Record<string, unknown> }
  | { ok: false; toast: string }

export function buildBasicBody(input: BasicSyncInput): BasicSyncOutcome {
  const body: Record<string, unknown> = {}
  if (input.instTypes.length) body.instTypes = input.instTypes
  body.includeOptions = input.includeOptions
  const families = parseList(input.families, NL, TAB)
  if (families.length) body.families = families
  if (input.instType) body.instType = input.instType

  const maxInst = parseIntField(input.maxInstruments, 1, 500, 'maxInstruments')
  if (!maxInst.ok) return maxInst
  if (maxInst.value !== undefined) body.maxInstruments = maxInst.value
  const maxCcys = parseIntField(input.maxCcys, 1, 100, 'maxCcys')
  if (!maxCcys.ok) return maxCcys
  if (maxCcys.value !== undefined) body.maxCcys = maxCcys.value
  const days = parseIntField(input.days, 1, 365, 'days')
  if (!days.ok) return days
  if (days.value !== undefined) body.days = days.value

  const bars = parseList(input.bars, NL, TAB)
  if (bars.length) body.bars = bars
  if (input.klineTypes.length) body.klineTypes = input.klineTypes
  body.includeFunding = input.includeFunding
  body.includeRatios = input.includeRatios
  if (input.ratioTypes.length) body.ratioTypes = input.ratioTypes
  return { ok: true, body }
}

export function basicConfirmHtml(input: BasicSyncInput): string {
  const maxInst = parseIntField(input.maxInstruments, 1, 500, 'maxInstruments')
  const days = parseIntField(input.days, 1, 365, 'days')
  const maxInstText = maxInst.ok && maxInst.value !== undefined ? String(maxInst.value) : '默认 20'
  const daysText = days.ok && days.value !== undefined ? String(days.value) : '默认 7'
  return (
    '<p>即将调用 <span class="mono">POST /api/v1/sync/basic</span>（品种同步 + 初始化小规模补拉作业）。</p><ul>' +
    '<li>instType：' + esc(input.instType || '（默认）') + '</li>' +
    '<li>maxInstruments：' + esc(maxInstText) + '</li>' +
    '<li>days：' + esc(daysText) + '</li>' +
    '<li>includeFunding / includeRatios：' + (input.includeFunding ? '是' : '否') + ' / ' + (input.includeRatios ? '是' : '否') + '</li>' +
    '</ul><p class="muted">阻塞调用，可能跑几分钟。</p>'
  )
}

/* ---------- 结果盒（legacy renderSyncResult / renderErrorBox） ---------- */
export function renderSyncResultHtml(data: unknown, title: string, elapsedMs: number): string {
  let head =
    '<div class="result-box is-ok"><strong>' + esc(title) + '</strong> 完成，耗时 ' + esc(fmtDur(elapsedMs)) + '</div>'
  if (data && typeof data === 'object' && !Array.isArray(data)) {
    const obj = data as Record<string, unknown>
    const keys = Object.keys(obj)
    const parts: string[] = []
    for (let i = 0; i < keys.length; i++) {
      const k = keys[i]
      const v = obj[k]
      if (typeof v === 'number' || typeof v === 'string' || typeof v === 'boolean') {
        parts.push(esc(k) + '：<strong>' + esc(String(v)) + '</strong>')
      }
    }
    if (parts.length) {
      head =
        '<div class="result-box is-ok"><strong>' + esc(title) + '</strong> 完成，耗时 ' + esc(fmtDur(elapsedMs)) +
        '<div class="muted" style="margin-top:4px">' + parts.join(' · ') + '</div></div>'
    }
  }
  return head + '<pre class="json-out">' + esc(prettyJson(data)) + '</pre>'
}

export function errorBoxHtml(msg: string): string {
  return '<div class="result-box is-err">' + esc(msg) + '</div>'
}
