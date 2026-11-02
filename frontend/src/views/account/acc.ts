/* 账户 / 我的订单 / 马丁策略三页共用的格式化、判定与列定义。
   逐条移植 legacy app.js §16（5629–6137）：文案、单位、小数位与空值占位保持完全一致。 */
import { badge, esc, fmtInt, fmtNum, fmtTime, isNil, parseServerTime } from '../../lib/format'
import type { TableCol } from '../../components/DataTable.vue'

/* legacy 的空值占位（U+2014 EM DASH），不是连字符 */
export const ACC_NA = '—'

/* legacy 用 nfAccMoney(min2,max2) / nfAccQty(max6) / nfAccPx(max8)；
   format.ts 的 fmtMoney 只有 max2（无最小位）、fmtNum 是 max8，精度口径与 legacy 不同，
   直接复用会改变输出，所以这三个 formatter 按 legacy 原定义保留（其余一律走 format.ts）。 */
const nfAccMoney = new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const nfAccQty = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 6 })
const nfAccPx = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 8 })

/* 金额：固定 2 位小数 + 千分位 */
export function accMoney(v: unknown): string {
  if (isNil(v)) return ACC_NA
  const n = Number(v)
  if (!isFinite(n)) return String(v)
  return nfAccMoney.format(n)
}

/* 币数量：最多 6 位小数 */
export function accQty(v: unknown): string {
  if (isNil(v)) return ACC_NA
  const n = Number(v)
  if (!isFinite(n)) return String(v)
  return nfAccQty.format(n)
}

/* 价格：最多 8 位小数 */
export function accPx(v: unknown): string {
  if (isNil(v)) return ACC_NA
  const n = Number(v)
  if (!isFinite(n)) return String(v)
  return nfAccPx.format(n)
}

/* 普通数值：最多 8 位小数（与 legacy nfNum 同口径，复用 format.ts） */
export function accNum(v: unknown): string {
  if (isNil(v)) return ACC_NA
  return fmtNum(v)
}

export function accInt(v: unknown): string {
  if (isNil(v)) return ACC_NA
  return fmtInt(v)
}

/* 布尔值（含后端返回的字符串 "true"/"false"）-> 是 / 否；空值 — */
export function accBool(v: unknown): string {
  if (isNil(v) || v === '') return ACC_NA
  if (v === true || v === 'true') return '是'
  if (v === false || v === 'false') return '否'
  return String(v)
}

/* 比率 0.0158 -> 1.58%；signed=true 时正数带 + */
export function accPct(v: unknown, signed?: boolean): string {
  if (isNil(v)) return ACC_NA
  const n = Number(v)
  if (!isFinite(n)) return String(v)
  const p = n * 100
  return (signed && p > 0 ? '+' : '') + p.toFixed(2) + '%'
}

/* 毫秒时间戳或服务端 ISO 字符串 -> 本地时间；空值 — */
export function accTime(v: unknown): string {
  if (isNil(v)) return ACC_NA
  const t = timeText(v)
  return isNil(t) || t === '-' ? ACC_NA : t
}

/* legacy timeText：不含 '-' 视为毫秒时间戳，否则按服务端时间串解析 */
function timeText(value: unknown): string {
  if (isNil(value)) return '-'
  const raw = String(value)
  let ms = NaN
  if (raw.indexOf('-') < 0) {
    const n = Number(raw)
    if (isFinite(n)) ms = n
  } else {
    ms = parseServerTime(raw)
  }
  return fmtTime(ms)
}

/* 正负金额：正绿负红；空值 — */
export function accPnl(v: unknown): string {
  if (isNil(v)) return ACC_NA
  const n = Number(v)
  if (!isFinite(n)) return esc(String(v))
  const txt = nfAccMoney.format(Math.abs(n))
  if (n > 0) return '<span class="val-pos">+' + esc(txt) + '</span>'
  if (n < 0) return '<span class="val-neg">-' + esc(txt) + '</span>'
  return esc(txt)
}

/* 方向单元格：long/buy 绿、short/sell 红 */
export function sideCell(side: unknown): string {
  const s = isNil(side) ? '' : String(side).toLowerCase()
  const label = isNil(side) ? '-' : String(side)
  if (s === 'long' || s === 'buy') return '<span class="val-pos">' + esc(label) + '</span>'
  if (s === 'short' || s === 'sell') return '<span class="val-neg">' + esc(label) + '</span>'
  return esc(label)
}

/* 接口 error 对象/字符串 -> 一句话；无错误返回空串 */
export function accIssueText(data: unknown): string {
  const d = (data && typeof data === 'object' ? data : {}) as { error?: unknown; ok?: unknown }
  const err = d.error
  if (isNil(err)) return d.ok === false ? '接口返回 ok=false，未给出 error 详情' : ''
  if (typeof err === 'string') return err
  const e = err as { code?: unknown; message?: unknown; hint?: unknown }
  const parts: string[] = []
  if (!isNil(e.code)) parts.push('[' + String(e.code) + ']')
  if (!isNil(e.message)) parts.push(String(e.message))
  if (!isNil(e.hint)) parts.push('提示：' + String(e.hint))
  return parts.length ? parts.join(' ') : '接口返回 ok=false'
}

/* 后端 notice（未配置凭证时只回 notice 不回 account）-> 文本；无则空串 */
export function accNoticeText(data: unknown): string {
  if (!data || typeof data !== 'object') return ''
  const n = (data as { notice?: unknown }).notice
  return isNil(n) ? '' : String(n)
}

/* 多接口并发时把 Error 挑出来拼成一条提示 */
export function collectErrors(values: unknown[]): string {
  const parts: string[] = []
  for (let i = 0; i < values.length; i++) {
    const v = values[i]
    if (v instanceof Error && v.message) parts.push(v.message)
  }
  return parts.join('；')
}

/* key-value 网格的一项：html 已经是可安全插入的转义片段 */
export interface KvPair {
  key: string
  html: string
}

export function kv(key: string, value: unknown): KvPair {
  let v = value
  if (typeof v === 'boolean') v = v ? '是' : '否'
  return { key: key, html: esc(isNil(v) ? ACC_NA : v) }
}

export function kvHtml(key: string, html: string): KvPair {
  return { key: key, html: html }
}

/* ===================== 接口数据结构 ===================== */

/* 表格行：字段全部可选，列 render 自行判空（与 legacy 一致） */
export type Row = Record<string, unknown>

export interface AccountSummary {
  configured?: unknown
  mode?: unknown
  notice?: unknown
  credentials?: {
    label?: unknown
    apiKey?: unknown
    apiKeyConfigured?: unknown
    secretKeyConfigured?: unknown
    passphraseConfigured?: unknown
    source?: unknown
  }
  account?: {
    uid?: unknown
    mainUid?: unknown
    acctLv?: unknown
    posMode?: unknown
    acctStpMode?: unknown
    autoLoan?: unknown
    perm?: unknown
    kycLv?: unknown
    level?: unknown
  }
  permissionNote?: unknown
  ok?: unknown
  error?: unknown
  fetchedAt?: unknown
}

export interface AccountBalance {
  totalEq?: unknown
  isoEq?: unknown
  adjEq?: unknown
  availEq?: unknown
  mgnRatio?: unknown
  imr?: unknown
  mmr?: unknown
  notionalUsd?: unknown
  uTime?: unknown
  details?: Row[]
  ok?: unknown
  error?: unknown
  notice?: unknown
}

export interface AccountPositions {
  instType?: unknown
  items?: Row[]
  ok?: unknown
  error?: unknown
  notice?: unknown
}

export interface OrdersPayload {
  state?: unknown
  items?: Row[]
  ok?: unknown
  error?: unknown
  notice?: unknown
}

export interface StrategyDetail extends Row {
  params?: Record<string, unknown>
  universe?: { instIds?: unknown[] }
  positions?: Row[]
  openPositions?: unknown
  closedPositions?: unknown
  recentOrders?: Row[]
}

/* ===================== 账户页 ===================== */

export const ACC_BALANCE_COLS: TableCol[] = [
  { title: 'ccy', render: (r: Row) => '<span class="mono">' + esc(isNil(r.ccy) ? ACC_NA : r.ccy) + '</span>' },
  { title: 'eq', num: true, render: (r: Row) => esc(accQty(r.eq)) },
  { title: 'eqUsd', num: true, render: (r: Row) => esc(accMoney(r.eqUsd)) },
  { title: 'cashBal', num: true, render: (r: Row) => esc(accQty(r.cashBal)) },
  { title: 'availBal', num: true, render: (r: Row) => esc(accQty(r.availBal)) },
  { title: 'availEq', num: true, render: (r: Row) => esc(accQty(r.availEq)) },
  { title: 'frozenBal', num: true, render: (r: Row) => esc(accQty(r.frozenBal)) },
  { title: 'ordFrozen', num: true, render: (r: Row) => esc(accQty(r.ordFrozen)) },
  { title: 'upl', num: true, render: (r: Row) => accPnl(r.upl) },
  { title: 'uplLiab', num: true, render: (r: Row) => esc(accQty(r.uplLiab)) },
  { title: 'disEq', num: true, render: (r: Row) => esc(accMoney(r.disEq)) }
]

export const ACC_POSITION_COLS: TableCol[] = [
  { title: 'instId', render: (r: Row) => '<span class="mono">' + esc(isNil(r.instId) ? ACC_NA : r.instId) + '</span>' },
  { title: 'posSide', render: (r: Row) => sideCell(r.posSide) },
  { title: 'pos 张', num: true, render: (r: Row) => esc(accQty(r.pos)) },
  { title: 'avgPx', num: true, render: (r: Row) => esc(accPx(r.avgPx)) },
  { title: 'markPx', num: true, render: (r: Row) => esc(accPx(r.markPx)) },
  { title: 'upl', num: true, render: (r: Row) => accPnl(r.upl) },
  { title: 'uplRatio', num: true, render: (r: Row) => esc(accPct(r.uplRatio, true)) },
  { title: 'lever', num: true, render: (r: Row) => esc(accNum(r.lever)) },
  { title: 'liqPx', num: true, render: (r: Row) => esc(accPx(r.liqPx)) },
  { title: 'margin', num: true, render: (r: Row) => esc(accMoney(r.margin)) },
  { title: 'notionalUsd', num: true, render: (r: Row) => esc(accMoney(r.notionalUsd)) },
  { title: 'mgnMode', render: (r: Row) => esc(isNil(r.mgnMode) ? ACC_NA : r.mgnMode) },
  { title: 'adl', num: true, render: (r: Row) => esc(isNil(r.adl) ? ACC_NA : r.adl) },
  { title: 'uTime', render: (r: Row) => '<span class="mono">' + esc(accTime(r.uTime)) + '</span>' }
]

/* ===================== 我的订单页 ===================== */

export const ORDERS_LIMIT = 50

export const ORDERS_STATES = ['local', 'pending', 'history'] as const
export type OrdersState = (typeof ORDERS_STATES)[number]

export const ORDERS_EMPTY: Record<OrdersState, string> = {
  local: '本地暂无影子订单记录',
  pending: '交易所当前无挂单',
  history: '交易所无历史订单'
}

export const ORDERS_TABS: { name: OrdersState; label: string; title: string }[] = [
  { name: 'local', label: '本地影子订单', title: '本地影子订单' },
  { name: 'pending', label: '交易所挂单', title: '交易所当前挂单' },
  { name: 'history', label: '交易所历史', title: '交易所历史订单' }
]

/* legacy：共 N 条 · state=xxx（交易所两个 tab 追加 instType=SWAP） */
export function ordersMetaText(name: OrdersState, count: number): string {
  return '共 ' + count + ' 条 · state=' + name + (name === 'local' ? '' : ' · instType=SWAP')
}

export const ORDER_LOCAL_COLS: TableCol[] = [
  { title: '时间 decidedAt', render: (r: Row) => '<span class="mono">' + esc(accTime(r.decidedAt)) + '</span>' },
  { title: 'source', render: (r: Row) => '<span class="mono">' + esc(isNil(r.source) ? ACC_NA : r.source) + '</span>' },
  { title: 'id', num: true, render: (r: Row) => esc(accInt(r.id)) },
  { title: '策略 strategyId', num: true, render: (r: Row) => esc(accInt(r.strategyId)) },
  { title: 'instId', render: (r: Row) => '<span class="mono">' + esc(isNil(r.instId) ? ACC_NA : r.instId) + '</span>' },
  { title: '方向', render: (r: Row) => sideCell(r.side) },
  { title: '类型 ordType', render: (r: Row) => esc(isNil(r.ordType) ? ACC_NA : r.ordType) },
  { title: '数量 sz', num: true, render: (r: Row) => esc(accQty(r.sz)) },
  { title: '价格 px', num: true, render: (r: Row) => esc(accPx(r.px)) },
  { title: '名义 USD', num: true, render: (r: Row) => esc(accMoney(r.notionalUsd)) },
  { title: '状态 state', render: (r: Row) => badge(r.state) },
  { title: '原因 reason', render: (r: Row) => esc(isNil(r.reason) ? ACC_NA : r.reason) }
]

export const ORDER_EX_COLS: TableCol[] = [
  { title: '时间 cTime', render: (r: Row) => '<span class="mono">' + esc(accTime(r.cTime)) + '</span>' },
  { title: 'instId', render: (r: Row) => '<span class="mono">' + esc(isNil(r.instId) ? ACC_NA : r.instId) + '</span>' },
  { title: 'ordId', render: (r: Row) => '<span class="mono">' + esc(isNil(r.ordId) ? ACC_NA : r.ordId) + '</span>' },
  { title: 'clOrdId', render: (r: Row) => '<span class="mono">' + esc(isNil(r.clOrdId) ? ACC_NA : r.clOrdId) + '</span>' },
  { title: '方向', render: (r: Row) => sideCell(r.side) },
  { title: 'posSide', render: (r: Row) => sideCell(r.posSide) },
  { title: '类型 ordType', render: (r: Row) => esc(isNil(r.ordType) ? ACC_NA : r.ordType) },
  { title: '状态 state', render: (r: Row) => badge(r.state) },
  { title: '数量 sz', num: true, render: (r: Row) => esc(accQty(r.sz)) },
  { title: '已成交 fillSz', num: true, render: (r: Row) => esc(accQty(r.fillSz)) },
  { title: '价格 px', num: true, render: (r: Row) => esc(accPx(r.px)) },
  { title: '均价 avgPx', num: true, render: (r: Row) => esc(accPx(r.avgPx)) },
  {
    title: '手续费 fee',
    num: true,
    render: (r: Row) => {
      if (isNil(r.fee)) return esc(ACC_NA)
      return esc(accQty(r.fee)) + (isNil(r.feeCcy) ? '' : ' <span class="muted">' + esc(r.feeCcy) + '</span>')
    }
  },
  { title: '盈亏 pnl', num: true, render: (r: Row) => accPnl(r.pnl) },
  { title: 'lever', num: true, render: (r: Row) => esc(accNum(r.lever)) },
  { title: '只减仓', render: (r: Row) => esc(accBool(r.reduceOnly)) },
  { title: 'algoId', render: (r: Row) => '<span class="mono">' + esc(isNil(r.algoId) ? ACC_NA : r.algoId) + '</span>' },
  { title: '更新时间 uTime', render: (r: Row) => '<span class="mono">' + esc(accTime(r.uTime)) + '</span>' }
]

/* ===================== 马丁策略页 ===================== */

export const STRATEGIES_LIMIT = 50

export const STRATEGY_EMPTY: Record<'running' | 'history', string> = {
  running: '暂无运行中的马丁策略',
  history: '暂无历史策略'
}

export const STRATEGY_TABS: { name: 'running' | 'history'; label: string; title: string }[] = [
  { name: 'running', label: '运行中', title: '运行中策略' },
  { name: 'history', label: '历史', title: '历史策略' }
]

/* legacy：有数据才带 limit，空列表只写「共 0 个」 */
export function strategiesMetaText(count: number): string {
  return count ? '共 ' + count + ' 个 · limit=' + STRATEGIES_LIMIT : '共 0 个'
}

export const STRATEGY_PARAM_LABELS: Record<string, string> = {
  side: '方向 side',
  leverage: '杠杆 leverage',
  maxLayers: '最大层数 maxLayers',
  layerScale: '加仓倍数 layerScale',
  layerStepPct: '加仓间距 % layerStepPct',
  takeProfitPct: '止盈 % takeProfitPct',
  stopLossPct: '止损 % stopLossPct',
  maxHoldHours: '最长持仓 小时 maxHoldHours',
  feeTaker: 'taker 费率 feeTaker',
  fundingPer8hPct: '8h 资金费率 % fundingPer8hPct',
  initialMarginUsd: '初始保证金 USD initialMarginUsd',
  slippageBps: '滑点 bps slippageBps'
}

export const STRATEGY_POSITION_COLS: TableCol[] = [
  { title: 'instId', render: (r: Row) => '<span class="mono">' + esc(isNil(r.instId) ? ACC_NA : r.instId) + '</span>' },
  { title: 'side', render: (r: Row) => sideCell(r.side) },
  { title: 'sz', num: true, render: (r: Row) => esc(accQty(r.sz)) },
  { title: 'entryPx', num: true, render: (r: Row) => esc(accPx(r.entryPx)) },
  { title: 'markPx', num: true, render: (r: Row) => esc(accPx(r.markPx)) },
  { title: '名义 USD', num: true, render: (r: Row) => esc(accMoney(r.notionalUsd)) },
  { title: '未实现盈亏', num: true, render: (r: Row) => accPnl(r.unrealizedPnl) },
  { title: 'lever', num: true, render: (r: Row) => esc(accNum(r.leverage)) },
  { title: 'liqPx', num: true, render: (r: Row) => esc(accPx(r.liqPx)) },
  { title: '保证金 USD', num: true, render: (r: Row) => esc(accMoney(r.marginUsd)) },
  { title: '状态', render: (r: Row) => badge(r.status) },
  { title: '开仓时间', render: (r: Row) => '<span class="mono">' + esc(accTime(r.openedAt)) + '</span>' }
]

export const STRATEGY_ORDER_COLS: TableCol[] = [
  { title: '时间 decidedAt', render: (r: Row) => '<span class="mono">' + esc(accTime(r.decidedAt)) + '</span>' },
  { title: 'id', num: true, render: (r: Row) => esc(accInt(r.id)) },
  { title: 'instId', render: (r: Row) => '<span class="mono">' + esc(isNil(r.instId) ? ACC_NA : r.instId) + '</span>' },
  { title: '方向', render: (r: Row) => sideCell(r.side) },
  { title: '类型 ordType', render: (r: Row) => esc(isNil(r.ordType) ? ACC_NA : r.ordType) },
  { title: '数量 sz', num: true, render: (r: Row) => esc(accQty(r.sz)) },
  { title: '价格 px', num: true, render: (r: Row) => esc(accPx(r.px)) },
  { title: '名义 USD', num: true, render: (r: Row) => esc(accMoney(r.notionalUsd)) },
  { title: '状态', render: (r: Row) => badge(r.status) },
  { title: '原因 reason', render: (r: Row) => esc(isNil(r.reason) ? ACC_NA : r.reason) }
]

/* 详情里的持仓明细：新契约是 positions[]（openPositions 已变成开仓数量），旧契约是数组 */
export function strategyPositionsOf(detail: unknown): Row[] {
  const s = (detail && typeof detail === 'object' ? detail : {}) as StrategyDetail
  if (Array.isArray(s.positions)) return s.positions as Row[]
  if (Array.isArray(s.openPositions)) return s.openPositions as Row[]
  return []
}

export function strategyOrdersOf(detail: unknown): Row[] {
  const s = (detail && typeof detail === 'object' ? detail : {}) as StrategyDetail
  return Array.isArray(s.recentOrders) ? (s.recentOrders as Row[]) : []
}

export function strategyUniverseOf(detail: unknown): unknown[] {
  const s = (detail && typeof detail === 'object' ? detail : {}) as StrategyDetail
  const u = s.universe
  return u && Array.isArray(u.instIds) ? u.instIds : []
}

/* 参数区：按后端 JSON 的键顺序渲染，键名映射成中文标签，值走 accNum */
export function strategyParamPairs(detail: unknown): KvPair[] {
  const s = (detail && typeof detail === 'object' ? detail : {}) as StrategyDetail
  const params = s.params && typeof s.params === 'object' ? s.params : {}
  const keys = Object.keys(params)
  const out: KvPair[] = []
  for (let i = 0; i < keys.length; i++) {
    const k = keys[i]
    out.push({ key: STRATEGY_PARAM_LABELS[k] || k, html: esc(accNum(params[k])) })
  }
  return out
}

/* 详情顶部 13 项概览（legacy strategiesRenderDetail 的 accKvGrid） */
export function strategyDetailPairs(detail: unknown, id: number): KvPair[] {
  const s = (detail && typeof detail === 'object' ? detail : {}) as StrategyDetail
  return [
    kvHtml('策略 id', '<span class="mono">#' + esc(id) + '</span>'),
    kv('名称 name', s.name),
    kvHtml('状态 status', badge(s.status)),
    kv('模式 mode', s.mode),
    kv('运行中 running', s.running),
    kv('单币资金 USD', accMoney(s.capitalPerCoinUsd)),
    kv('来源回测 run', s.sourceRunId),
    kv('备注 note', s.note),
    kvHtml('未实现盈亏 USD', accPnl(s.unrealizedPnlUsd)),
    kv('订单数', accInt(s.orderCount)),
    kv('最近下单', accTime(s.lastOrderAt)),
    kv('创建时间', accTime(s.createdAt)),
    kv('更新时间', accTime(s.updatedAt))
  ]
}
