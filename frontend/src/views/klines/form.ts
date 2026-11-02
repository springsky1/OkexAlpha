/* K 线查询表单：参数组装与校验（legacy loadKlines 1973~2046 的等价纯函数） */
import { datetimeLocalToMs, toLocalInput } from '../../lib/format'
import type { KlineRow } from './types'

export interface KlineFormInput {
  instId: string
  bar: string
  klineType: string
  order: string
  limitRaw: string
  startRaw: string
  endRaw: string
}

export interface KlineFormOk {
  ok: true
  instId: string
  bar: string
  klineType: string
  limit: number | undefined
  startMs: number | null
  endMs: number | null
  params: Record<string, unknown>
}

export interface KlineFormFail {
  ok: false
  /* 非空时同时写入表单上方红色错误条（legacy showError） */
  alert: string
  /* toast 文案（legacy toast） */
  toast: string
}

export type KlineFormResult = KlineFormOk | KlineFormFail

/* 数字型输入校验：返回 undefined（未填）/ null（非法）/ 数值（legacy readIntField） */
export function readIntField(
  raw: string,
  min: number,
  max: number,
  label: string
): { ok: true; value: number | undefined } | { ok: false; message: string } {
  const s = String(raw === null || raw === undefined ? '' : raw).trim()
  if (!s) return { ok: true, value: undefined }
  const n = Number(s)
  if (!isFinite(n) || Math.floor(n) !== n || n < min || n > max) {
    return { ok: false, message: (label || '数值') + ' 需为 ' + min + ' ~ ' + max + ' 的整数' }
  }
  return { ok: true, value: n }
}

export function buildKlineQuery(input: KlineFormInput): KlineFormResult {
  const instId = String(input.instId || '').trim()
  if (!instId) {
    return {
      ok: false,
      alert: '请填写 instId（例如 BTC-USDT-SWAP，只支持永续合约）',
      toast: '请填写 instId'
    }
  }
  const params: Record<string, unknown> = {
    instId: instId,
    bar: input.bar,
    klineType: input.klineType || 'CANDLE',
    order: input.order || 'desc'
  }
  const limit = readIntField(input.limitRaw, 1, 5000, 'limit')
  if (!limit.ok) return { ok: false, alert: '', toast: limit.message }
  if (limit.value !== undefined) params.limit = limit.value
  const startMs = datetimeLocalToMs(input.startRaw)
  const endMs = datetimeLocalToMs(input.endRaw)
  if (startMs !== null && endMs !== null && startMs >= endMs) {
    return { ok: false, alert: '开始时间必须早于结束时间', toast: '开始时间必须早于结束时间' }
  }
  if (startMs !== null) params.startMs = startMs
  if (endMs !== null) params.endMs = endMs
  return {
    ok: true,
    instId,
    bar: input.bar,
    klineType: String(params.klineType),
    limit: limit.value,
    startMs,
    endMs,
    params
  }
}

/* 快捷时间：近 N 天（legacy setQuickRange，end 取现在、start 取 N 天前） */
export function quickRangeValues(days: number, now: number): { start: string; end: string } {
  return { start: toLocalInput(now - days * 86400000), end: toLocalInput(now) }
}

/* 表格汇总里的 meta 串：instId · bar · klineType */
export function klineMeta(instId: string, bar: string, klineType: string): string {
  return instId + ' · ' + bar + ' · ' + klineType
}

/* 表格被 limit 截断时的提示后缀（legacy cappedBy 分支） */
export function klineLimitSuffix(limit: number | undefined, count: number): string {
  const cappedBy = limit && count >= limit ? limit : 0
  return cappedBy ? '表格已满 limit=' + cappedBy + '（仅一页），完整区间见上方图表' : ''
}

/* 图表数据源：直接复用表格接口返回的行（升序副本） */
export function ascendingRows(rows: KlineRow[]): KlineRow[] {
  return rows
    .slice()
    .sort((a, b) => Number(a.tsMs) - Number(b.tsMs))
}
