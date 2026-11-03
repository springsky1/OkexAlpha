/* 订单中心（需求 #4）：7 个 tab 的元数据、列定义与格式化。
   数据来源分两类：交易所只读接口（委托 / 历史仓位 / 成交 / 账单 / 借币）与本地策略聚合；
   格式化一律复用 views/account/acc.ts 与 lib/format.ts 的口径，不重复实现。 */
import { badge, esc, isNil } from '../../lib/format'
import type { TableCol } from '../../components/DataTable.vue'
import {
  ACC_NA,
  accInt,
  accMoney,
  accNum,
  accPct,
  accPnl,
  accPx,
  accQty,
  accTime,
  accIssueText,
  accNoticeText,
  kv,
  kvHtml,
  sideCell,
  strategyDetailPairs,
  strategyParamPairs,
  strategyUniverseOf,
  type KvPair,
  type Row,
  type StrategyDetail
} from '../account/acc'

/* 每个 tab 一次拉取的条数上限（交易所接口本身也限 100） */
export const OC_LIMIT = 100

export const OC_TAB_NAMES = [
  'pending',
  'history',
  'positions',
  'fills',
  'strategy',
  'borrow',
  'bills'
] as const

export type OcTabName = (typeof OC_TAB_NAMES)[number]

export interface OcTab {
  name: OcTabName
  label: string
  title: string
  desc: string
  /* 面板上方展示的接口调用说明，保留 legacy「接口路径可见」的习惯 */
  hint: string
}

/* 顺序与需求 #4 一致：当前委托 / 历史委托 / 历史仓位 / 交易账单 / 策略 / 借币数据 / 资金账单 */
export const OC_TABS: OcTab[] = [
  {
    name: 'pending',
    label: '当前委托',
    title: '当前委托',
    desc: '交易所未成交的挂单，直接来自 OKX 订单接口。',
    hint: 'GET /api/v1/account/orders?state=pending&instType=SWAP&limit=100'
  },
  {
    name: 'history',
    label: '历史委托',
    title: '历史委托',
    desc: '近 7 天已结束的委托；更早的归档委托需要时间段查询。',
    hint: 'GET /api/v1/account/orders?state=history&instType=SWAP&limit=100'
  },
  {
    name: 'positions',
    label: '历史仓位',
    title: '历史仓位',
    desc: '已平仓的仓位记录，含开平仓均价、已实现盈亏与手续费。',
    hint: 'GET /api/v1/account/positions-history?instType=SWAP&limit=100'
  },
  {
    name: 'fills',
    label: '交易账单',
    title: '交易账单',
    desc: '逐笔成交明细（fills-history），手续费与盈亏按笔计账。',
    hint: 'GET /api/v1/account/fills?instType=SWAP&limit=100'
  },
  {
    name: 'strategy',
    label: '策略',
    title: '策略',
    desc: '影子策略的资金与盈亏聚合：累计投入、总收益、收益率、套利收益与浮动收益。',
    hint: 'GET /api/v1/account/strategy-center?state=all&limit=100&offset=0'
  },
  {
    name: 'borrow',
    label: '借币数据',
    title: '借币数据',
    desc: '借币与还币记录（borrow-repay-history），仅杠杆账户有数据。',
    hint: 'GET /api/v1/account/borrow-repay?limit=100'
  },
  {
    name: 'bills',
    label: '资金账单',
    title: '资金账单',
    desc: '账户资金流水（bills）：手续费、资金费、盈亏与划转都在这里。',
    hint: 'GET /api/v1/account/bills?limit=100'
  }
]

export const OC_EMPTY: Record<OcTabName, string> = {
  pending: '暂无当前委托',
  history: '暂无历史委托',
  positions: '暂无历史仓位',
  fills: '暂无成交明细',
  strategy: '暂无策略',
  borrow: '暂无借币记录',
  bills: '暂无资金流水'
}

/* tab 面板右上角的计数文案 */
export function ocMetaText(name: OcTabName, count: number): string {
  return name === 'strategy' ? '共 ' + count + ' 个策略' : '共 ' + count + ' 条（limit=' + OC_LIMIT + '）'
}

/* 接口失败统一前缀，便于用户一眼看出是哪个 tab */
export function ocErrorText(name: OcTabName, err: unknown): string {
  const e = err as { message?: unknown } | null
  const msg = e && e.message ? String(e.message) : String(err)
  return (OC_TABS.filter((t) => t.name === name)[0] || { label: name }).label + '加载失败：' + msg
}

export { accIssueText, accNoticeText }

/* ===================== 历史仓位 ===================== */

export const OC_POSITION_COLS: TableCol[] = [
  { title: '合约 instId', render: (r: Row) => '<span class="mono">' + esc(r.instId) + '</span>' },
  { title: '方向 posSide', render: (r: Row) => sideCell(r.posSide) },
  { title: '保证金模式', render: (r: Row) => esc(isNil(r.mgnMode) ? ACC_NA : r.mgnMode) },
  { title: '类型 type', num: true, render: (r: Row) => esc(accNum(r.type)) },
  { title: '杠杆 lever', num: true, render: (r: Row) => esc(accNum(r.lever)) + 'x' },
  { title: '开仓均价', num: true, render: (r: Row) => esc(accPx(r.openAvgPx)) },
  { title: '平仓均价', num: true, render: (r: Row) => esc(accPx(r.closeAvgPx)) },
  { title: '标记价格', num: true, render: (r: Row) => esc(accPx(r.markPx)) },
  { title: '盈亏 pnl', num: true, render: (r: Row) => accPnl(r.pnl) },
  { title: '收益率 pnlRatio', num: true, render: (r: Row) => esc(accPct(r.pnlRatio)) },
  { title: '已实现 realizedPnl', num: true, render: (r: Row) => accPnl(r.realizedPnl) },
  { title: '手续费 fee', num: true, render: (r: Row) => esc(accQty(r.fee)) },
  { title: '资金费 fundingFee', num: true, render: (r: Row) => esc(accQty(r.fundingFee)) },
  { title: '强平价 liqPx', num: true, render: (r: Row) => esc(accPx(r.liqPx)) },
  { title: '开仓时间 cTime', render: (r: Row) => '<span class="mono">' + esc(accTime(r.cTime)) + '</span>' },
  { title: '更新时间 uTime', render: (r: Row) => '<span class="mono">' + esc(accTime(r.uTime)) + '</span>' }
]

/* ===================== 交易账单（成交明细） ===================== */

export const OC_FILLS_COLS: TableCol[] = [
  { title: '合约 instId', render: (r: Row) => '<span class="mono">' + esc(r.instId) + '</span>' },
  { title: '方向 side', render: (r: Row) => sideCell(r.side) },
  { title: '持仓方向 posSide', render: (r: Row) => esc(isNil(r.posSide) ? ACC_NA : r.posSide) },
  { title: '成交量 fillSz', num: true, render: (r: Row) => esc(accQty(r.fillSz)) },
  { title: '成交价 fillPx', num: true, render: (r: Row) => esc(accPx(r.fillPx)) },
  {
    title: '手续费 fee',
    num: true,
    render: (r: Row) => {
      if (isNil(r.fee)) return esc(ACC_NA)
      return esc(accQty(r.fee)) + (isNil(r.feeCcy) ? '' : ' <span class="muted">' + esc(r.feeCcy) + '</span>')
    }
  },
  { title: '盈亏 pnl', num: true, render: (r: Row) => accPnl(r.pnl) },
  { title: '执行类型 execType', render: (r: Row) => esc(isNil(r.execType) ? ACC_NA : r.execType) },
  { title: '订单 ordId', render: (r: Row) => '<span class="mono">' + esc(isNil(r.ordId) ? ACC_NA : r.ordId) + '</span>' },
  { title: '成交时间 fillTime', render: (r: Row) => '<span class="mono">' + esc(accTime(r.fillTime)) + '</span>' }
]

/* ===================== 资金账单 ===================== */

export const OC_BILLS_COLS: TableCol[] = [
  { title: '账单 id', render: (r: Row) => '<span class="mono">' + esc(isNil(r.billId) ? ACC_NA : r.billId) + '</span>' },
  { title: '合约 instId', render: (r: Row) => '<span class="mono">' + esc(isNil(r.instId) ? ACC_NA : r.instId) + '</span>' },
  { title: '类型 type', render: (r: Row) => esc(isNil(r.type) ? ACC_NA : r.type) },
  { title: '子类型 subType', render: (r: Row) => esc(isNil(r.subType) ? ACC_NA : r.subType) },
  { title: '币种 ccy', render: (r: Row) => esc(isNil(r.ccy) ? ACC_NA : r.ccy) },
  { title: '余额变动 balChg', num: true, render: (r: Row) => accPnl(r.balChg) },
  { title: '余额 bal', num: true, render: (r: Row) => esc(accMoney(r.bal)) },
  { title: '数量 sz', num: true, render: (r: Row) => esc(accQty(r.sz)) },
  { title: '价格 px', num: true, render: (r: Row) => esc(accPx(r.px)) },
  { title: '手续费 fee', num: true, render: (r: Row) => esc(accQty(r.fee)) },
  { title: '盈亏 pnl', num: true, render: (r: Row) => accPnl(r.pnl) },
  { title: '利息 interest', num: true, render: (r: Row) => esc(accQty(r.interest)) },
  { title: '时间 ts', render: (r: Row) => '<span class="mono">' + esc(accTime(r.ts)) + '</span>' }
]

/* ===================== 借币数据 ===================== */

export const OC_BORROW_COLS: TableCol[] = [
  { title: '币种 ccy', render: (r: Row) => esc(isNil(r.ccy) ? ACC_NA : r.ccy) },
  { title: '类型 type', render: (r: Row) => esc(isNil(r.type) ? ACC_NA : r.type) },
  { title: '数量 amt', num: true, render: (r: Row) => esc(accNum(r.amt)) },
  { title: '利息 interest', num: true, render: (r: Row) => esc(accNum(r.interest)) },
  { title: '合计 total', num: true, render: (r: Row) => esc(accNum(r.total)) },
  { title: '保证金模式', render: (r: Row) => esc(isNil(r.mgnMode) ? ACC_NA : r.mgnMode) },
  { title: '合约 instId', render: (r: Row) => '<span class="mono">' + esc(isNil(r.instId) ? ACC_NA : r.instId) + '</span>' },
  { title: '时间 ts', render: (r: Row) => '<span class="mono">' + esc(accTime(r.ts)) + '</span>' }
]

/* ===================== 策略 tab ===================== */

/* 策略 tab 用自定义表格（操作列要放按钮，DataTable 只支持字符串渲染） */
export const OC_STRATEGY_HEAD = [
  '策略名称',
  '累计投入',
  '总收益',
  '收益率',
  '套利收益',
  '浮动收益',
  '状态',
  '操作'
]

/* 收益率：后端 returnPct 已是百分数（3 = 3%），不能走 accPct（那个吃比率） */
export function ocReturnPct(v: unknown): string {
  if (isNil(v)) return ACC_NA
  const n = Number(v)
  if (!isFinite(n)) return String(v)
  return (n > 0 ? '+' : '') + n.toFixed(2) + '%'
}

export interface OcStrategy extends Row {
  id?: number
  name?: string
  status?: string
  running?: boolean
  investedUsd?: number | null
  realizedPnlUsd?: number
  unrealizedPnlUsd?: number
  totalPnlUsd?: number
  returnPct?: number | null
  openPositions?: number
  closedPositions?: number
  orderCount?: number
  lastOrderAt?: unknown
  instIds?: string[]
}

export interface OcSummary {
  count: number
  running: number
  investedUsd: number
  realizedPnlUsd: number
  unrealizedPnlUsd: number
  totalPnlUsd: number
  returnPct: number | null
  openPositions: number
  closedPositions: number
}

function num(v: unknown): number {
  const n = Number(v)
  return isFinite(n) ? n : 0
}

/* 策略页顶部的合计指标：口径与单行一致（合计收益 / 合计投入） */
export function ocSummary(items: Row[]): OcSummary {
  let invested = 0
  let realized = 0
  let unrealized = 0
  let running = 0
  let open = 0
  let closed = 0
  const rows = Array.isArray(items) ? items : []
  for (let i = 0; i < rows.length; i++) {
    const r = rows[i] as OcStrategy
    invested += num(r.investedUsd)
    realized += num(r.realizedPnlUsd)
    unrealized += num(r.unrealizedPnlUsd)
    if (r.running === true) running++
    open += num(r.openPositions)
    closed += num(r.closedPositions)
  }
  const total = realized + unrealized
  return {
    count: rows.length,
    running: running,
    investedUsd: invested,
    realizedPnlUsd: realized,
    unrealizedPnlUsd: unrealized,
    totalPnlUsd: total,
    returnPct: invested > 0 ? (total / invested) * 100 : null,
    openPositions: open,
    closedPositions: closed
  }
}

export function ocSummaryPairs(items: Row[]): KvPair[] {
  const s = ocSummary(items)
  return [
    kv('策略数', accInt(s.count)),
    kv('运行中', accInt(s.running)),
    kv('累计投入 USD', accMoney(s.investedUsd)),
    kvHtml('总收益 USD', accPnl(s.totalPnlUsd)),
    kvHtml('套利收益 USD', accPnl(s.realizedPnlUsd)),
    kvHtml('浮动收益 USD', accPnl(s.unrealizedPnlUsd)),
    kv('综合收益率', ocReturnPct(s.returnPct)),
    kv('持仓（开/平）', accInt(s.openPositions) + ' / ' + accInt(s.closedPositions))
  ]
}

/* 状态列：badge 之外补一句运行中 / 已停止，避免只看到 CANDIDATE 这种内部状态 */
export function ocStrategyStatus(r: Row): string {
  const s = badge(r.status)
  const text = r.running === true ? '运行中' : '已停止'
  return s + ' <span class="muted">' + esc(text) + '</span>'
}

/* 策略详情：复用马丁策略页的 13 项概览 + 参数 + 自选合约 */
export function ocStrategyDetailPairs(detail: unknown, id: number): KvPair[] {
  return strategyDetailPairs(detail as StrategyDetail, id)
}

export function ocStrategyParamPairs(detail: unknown): KvPair[] {
  return strategyParamPairs(detail)
}

export function ocStrategyUniverse(detail: unknown): unknown[] {
  return strategyUniverseOf(detail)
}
