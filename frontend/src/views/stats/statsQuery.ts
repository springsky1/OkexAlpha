/* 统计查询页的纯逻辑（4 组参数组装 / 校验文案 / 结果概要 / ratioType 提示），与 legacy app.js 第 9 区逐行等价 */
import { datetimeLocalToMs } from '../../lib/format'
import { RATIO_TYPES } from '../../lib/labels'

export type StatsTabKey = 'ratios' | 'oiv' | 'taker' | 'oi'

/* 4 个 tab 的 key / 文案（与 legacy index.html #stats-tabs 一致） */
export const STATS_TABS: { key: StatsTabKey; label: string }[] = [
  { key: 'ratios', label: '多空比' },
  { key: 'oiv', label: '持仓量-成交额' },
  { key: 'taker', label: '主动买卖量' },
  { key: 'oi', label: '持仓量快照' }
]

export type ParamsBuild =
  | { ok: true; params: Record<string, unknown> }
  | { ok: false; error: string; toast: string }

/* legacy readIntField：空值 -> 不带该参数；非法 -> 只 toast 不写错误条（error 为空串） */
export function readIntField(raw: string, min: number, max: number, label: string): { value?: number; error?: string } {
  const s = String(raw === null || raw === undefined ? '' : raw).trim()
  if (!s) return {}
  const n = Number(s)
  if (!isFinite(n) || Math.floor(n) !== n || n < min || n > max) {
    return { error: (label || '数值') + ' 需为 ' + min + ' ~ ' + max + ' 的整数' }
  }
  return { value: n }
}

/* 时间范围：两个都填且 start >= end 时返回错误文案，否则把有值的写进 params */
function applyTimeRange(params: Record<string, unknown>, startRaw: string, endRaw: string): string {
  const startMs = datetimeLocalToMs(startRaw)
  const endMs = datetimeLocalToMs(endRaw)
  if (startMs !== null && endMs !== null && startMs >= endMs) return '开始时间必须早于结束时间'
  if (startMs !== null) params.startMs = startMs
  if (endMs !== null) params.endMs = endMs
  return ''
}

export interface RatioInput {
  ratioType: string
  scope: string
  period: string
  order: string
  limitRaw: string
  startRaw: string
  endRaw: string
}

export function buildRatioParams(input: RatioInput): ParamsBuild {
  const params: Record<string, unknown> = {
    ratioType: input.ratioType,
    scope: String(input.scope || '').trim(),
    period: input.period,
    order: input.order || 'desc'
  }
  if (!params.ratioType) return { ok: false, error: '请选择 ratioType', toast: '请选择 ratioType' }
  if (!params.scope) {
    return { ok: false, error: '请填写 scope（CCY 类型填币种，INST 类型填合约 ID）', toast: '请填写 scope' }
  }
  const limit = readIntField(input.limitRaw, 1, 1000, 'limit')
  if (limit.error) return { ok: false, error: '', toast: limit.error }
  if (limit.value !== undefined) params.limit = limit.value
  const timeErr = applyTimeRange(params, input.startRaw, input.endRaw)
  if (timeErr) return { ok: false, error: timeErr, toast: timeErr }
  return { ok: true, params: params }
}

export interface OivInput {
  ccy: string
  period: string
  order: string
  limitRaw: string
  startRaw: string
  endRaw: string
}

export function buildOivParams(input: OivInput): ParamsBuild {
  const params: Record<string, unknown> = {
    ccy: String(input.ccy || '').trim(),
    period: input.period,
    order: input.order || 'desc'
  }
  if (!params.ccy) return { ok: false, error: '请填写 ccy', toast: '请填写 ccy' }
  const limit = readIntField(input.limitRaw, 1, 1000, 'limit')
  if (limit.error) return { ok: false, error: '', toast: limit.error }
  if (limit.value !== undefined) params.limit = limit.value
  const timeErr = applyTimeRange(params, input.startRaw, input.endRaw)
  if (timeErr) return { ok: false, error: timeErr, toast: timeErr }
  return { ok: true, params: params }
}

export interface TakerInput {
  scopeKind: string
  scope: string
  period: string
  order: string
  limitRaw: string
  startRaw: string
  endRaw: string
}

export function buildTakerParams(input: TakerInput): ParamsBuild {
  const params: Record<string, unknown> = {
    scopeKind: input.scopeKind || 'CCY',
    scope: String(input.scope || '').trim(),
    period: input.period,
    order: input.order || 'desc'
  }
  if (!params.scope) {
    return { ok: false, error: '请填写 scope（CCY 填币种，INST 填合约 ID）', toast: '请填写 scope' }
  }
  const limit = readIntField(input.limitRaw, 1, 1000, 'limit')
  if (limit.error) return { ok: false, error: '', toast: limit.error }
  if (limit.value !== undefined) params.limit = limit.value
  const timeErr = applyTimeRange(params, input.startRaw, input.endRaw)
  if (timeErr) return { ok: false, error: timeErr, toast: timeErr }
  return { ok: true, params: params }
}

export interface OiInput {
  instId: string
  order: string
  limitRaw: string
  startRaw: string
  endRaw: string
}

export function buildOiParams(input: OiInput): ParamsBuild {
  const instId = String(input.instId || '').trim()
  if (!instId) return { ok: false, error: '请填写 instId', toast: '请填写 instId' }
  const params: Record<string, unknown> = { instId: instId, order: input.order || 'desc' }
  const limit = readIntField(input.limitRaw, 1, 1000, 'limit')
  if (limit.error) return { ok: false, error: '', toast: limit.error }
  if (limit.value !== undefined) params.limit = limit.value
  const timeErr = applyTimeRange(params, input.startRaw, input.endRaw)
  if (timeErr) return { ok: false, error: timeErr, toast: timeErr }
  return { ok: true, params: params }
}

/* ---------- 结果概要（随查询维度不同，与 legacy 文案一致） ---------- */
export function ratioSummary(count: number, ratioType: string, scope: string): string {
  return count ? '共 ' + count + ' 条 · ' + ratioType + ' / ' + scope : '暂无数据'
}

export function oivSummary(count: number, ccy: string, period: string): string {
  return count ? '共 ' + count + ' 条 · ' + ccy + ' · ' + period : '暂无数据'
}

export function takerSummary(count: number, scopeKind: string, scope: string): string {
  return count ? '共 ' + count + ' 条 · ' + scopeKind + ' / ' + scope : '暂无数据'
}

export function oiSummary(count: number, instId: string): string {
  return count ? '共 ' + count + ' 条 · ' + instId : '暂无数据'
}

/* ratioType 下拉的提示行（legacy updateRatioHint） */
export function ratioHint(code: string): string {
  let name = ''
  let scopeKind = ''
  for (let i = 0; i < RATIO_TYPES.length; i++) {
    if (RATIO_TYPES[i][0] === code) {
      name = RATIO_TYPES[i][1]
      scopeKind = RATIO_TYPES[i][2]
    }
  }
  if (!code) return '—'
  const scopeTip = scopeKind === 'CCY' ? 'scope 填币种（如 BTC）' : 'scope 填合约（如 BTC-USDT-SWAP）'
  return name + ' · scopeKind=' + scopeKind + ' · ' + scopeTip
}
