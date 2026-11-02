/* 数据浏览页的纯逻辑（列定义 / 参数组装 / 概要文案 / 分页换算），与 legacy app.js 第 13 区逐行等价 */
import { esc, fmtInt, fmtNum, fmtTime, isNil } from '../../lib/format'
import { INST_TYPES } from '../../lib/labels'
import type { TableCol } from '../../components/DataTable.vue'

/* legacy joinList：数组用「、」连接，空数组/空值给 '-' */
export function joinList(v: unknown): string {
  if (Array.isArray(v)) return v.length ? v.join('、') : '-'
  if (isNil(v)) return '-'
  return String(v)
}

export const COIN_COLS: TableCol[] = [
  { title: 'coin', render: (r: any) => esc(isNil(r.coin) ? '-' : r.coin) },
  { title: 'channels', render: (r: any) => esc(joinList(r.channels)) },
  { title: 'roles', render: (r: any) => esc(joinList(r.roles)) },
  { title: 'quoteCcys', render: (r: any) => esc(joinList(r.quoteCcys)) },
  { title: '品种数', num: true, render: (r: any) => fmtInt(r.instCount) },
  { title: '现货', num: true, render: (r: any) => fmtInt(r.spotInstCount) },
  { title: '永续', num: true, render: (r: any) => fmtInt(r.swapInstCount) },
  { title: '交割', num: true, render: (r: any) => fmtInt(r.futuresInstCount) },
  { title: '期权', num: true, render: (r: any) => fmtInt(r.optionInstCount) },
  { title: '杠杆', num: true, render: (r: any) => fmtInt(r.marginInstCount) }
]

export const INST_COLS: TableCol[] = [
  { title: 'instId', render: (r: any) => esc(isNil(r.instId) ? '-' : r.instId) },
  { title: 'instType', render: (r: any) => esc(isNil(r.instType) ? '-' : r.instType) },
  { title: 'baseCcy', render: (r: any) => esc(isNil(r.baseCcy) ? '-' : r.baseCcy) },
  { title: 'quoteCcy', render: (r: any) => esc(isNil(r.quoteCcy) ? '-' : r.quoteCcy) },
  { title: 'state', render: (r: any) => esc(isNil(r.state) ? '-' : r.state) },
  { title: 'ctVal', num: true, render: (r: any) => fmtNum(r.ctVal) },
  { title: 'ctMult', num: true, render: (r: any) => fmtNum(r.ctMult) },
  { title: 'lever', num: true, render: (r: any) => fmtNum(r.lever) },
  { title: 'tickSz', num: true, render: (r: any) => fmtNum(r.tickSz) },
  { title: 'lotSz', num: true, render: (r: any) => fmtNum(r.lotSz) },
  { title: '上市时间', render: (r: any) => '<span class="mono">' + esc(fmtTime(r.listTimeMs)) + '</span>' }
]

/* 每页条数下拉（legacy index.html #coins-limit / #inst-limit） */
export const LIMIT_OPTIONS: number[] = [20, 50, 100]

/* state 下拉（legacy index.html #inst-state） */
export const INST_STATE_OPTIONS: string[] = ['live', 'suspend', 'expired', 'preopen']

/* instType 下拉：首项「全部」+ INST_TYPES */
export const INST_TYPE_OPTIONS: { value: string; label: string }[] = INST_TYPES.map((x) => ({ value: x[0], label: x[1] }))

export interface CoinsQuery {
  channel: string
  q: string
  limit: number
  offset: number
}

export function buildCoinsParams(query: CoinsQuery): Record<string, unknown> {
  return {
    channel: query.channel,
    q: String(query.q === null || query.q === undefined ? '' : query.q).trim(),
    limit: query.limit,
    offset: query.offset
  }
}

export interface InstQuery {
  instType: string
  baseCcy: string
  state: string
  limit: number
  offset: number
}

export function buildInstParams(query: InstQuery): Record<string, unknown> {
  return {
    instType: query.instType,
    baseCcy: String(query.baseCcy === null || query.baseCcy === undefined ? '' : query.baseCcy).trim(),
    state: query.state,
    limit: query.limit,
    offset: query.offset
  }
}

/* 结果概要：legacy 只报本页条数与每页条数（后端不返回总数） */
export function pageSummary(count: number, limit: number): string {
  return count ? '本页 ' + count + ' 条 · 每页 ' + limit : '本页无数据'
}

/*
 * AppPager 需要一个 total 才能算出「第 a–b 条」与下一页可用性；
 * 后端不返回总数，用 offset + 本页条数 反推：满页时 +1 表示「后面可能还有」，
 * 这样 info 与 legacy pagerInfo(offset, count) 完全一致，next 的禁用条件也与
 * legacy 的 list.length < limit 等价。
 */
export function pagerTotal(offset: number, count: number, limit: number): number {
  return offset + count + (count >= limit ? 1 : 0)
}
