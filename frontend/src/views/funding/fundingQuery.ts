/* 资金费率页的纯逻辑（参数组装 / 校验文案 / 结果概要），与 legacy app.js 第 8 区逐行等价 */
import { datetimeLocalToMs } from '../../lib/format'

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

export interface FundingInput {
  instId: string
  order: string
  limitRaw: string
  startRaw: string
  endRaw: string
}

/* 校验顺序与 legacy 完全一致：instId -> limit -> 时间范围 */
export function buildFundingParams(input: FundingInput): ParamsBuild {
  const instId = String(input.instId || '').trim()
  if (!instId) return { ok: false, error: '请填写 instId', toast: '请填写 instId' }
  const params: Record<string, unknown> = { instId: instId, order: input.order || 'desc' }
  const limit = readIntField(input.limitRaw, 1, 1000, 'limit')
  if (limit.error) return { ok: false, error: '', toast: limit.error }
  if (limit.value !== undefined) params.limit = limit.value
  const startMs = datetimeLocalToMs(input.startRaw)
  const endMs = datetimeLocalToMs(input.endRaw)
  if (startMs !== null && endMs !== null && startMs >= endMs) {
    return { ok: false, error: '开始时间必须早于结束时间', toast: '开始时间必须早于结束时间' }
  }
  if (startMs !== null) params.startMs = startMs
  if (endMs !== null) params.endMs = endMs
  return { ok: true, params: params }
}

export function fundingSummary(count: number, instId: string): string {
  return count ? '共 ' + count + ' 条 · ' + instId : '暂无数据'
}
