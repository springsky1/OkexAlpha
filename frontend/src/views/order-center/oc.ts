/* 订单中心：6 个 tab 的元数据、列定义与格式化（真实账户订单域，策略已归「策略」菜单）。
   数据来源：交易所只读接口（委托 / 历史仓位 / 成交 / 账单 / 借币）；
   格式化一律复用 views/account/acc.ts 与 lib/format.ts 的口径，不重复实现。 */
import { esc, isNil } from '../../lib/format'
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
  type KvPair,
  type Row
} from '../account/acc'

/* 每个 tab 一次拉取的条数上限（交易所接口本身也限 100） */
export const OC_LIMIT = 100

export const OC_TAB_NAMES = ['pending', 'history', 'positions', 'fills', 'borrow', 'bills'] as const

export type OcTabName = (typeof OC_TAB_NAMES)[number]

export interface OcTab {
  name: OcTabName
  label: string
  title: string
  desc: string
  /* 面板上方展示的接口调用说明，保留 legacy「接口路径可见」的习惯 */
  hint: string
}

/* 顺序：当前委托 / 历史委托 / 历史仓位 / 交易账单 / 借币数据 / 资金账单 */
export const OC_TABS: OcTab[] = [
  {
    name: 'pending',
    label: '当前委托',
    title: '当前委托',
    desc: '交易所未成交挂单的本地镜像：进入页面自动同步一次，也可点「强制刷新」重新拉取。',
    hint: 'POST /api/v1/account/exchange/sync → GET /api/v1/account/exchange/orders?state=pending&instType=SWAP&limit=100&offset=0'
  },
  {
    name: 'history',
    label: '历史委托',
    title: '历史委托',
    desc: '近 7 天已结束委托的本地镜像；交易所只保留 7 天，更早的由归档接口补齐。',
    hint: 'GET /api/v1/account/exchange/orders?state=history&instType=SWAP&limit=100&offset=0'
  },
  {
    name: 'positions',
    label: '历史仓位',
    title: '历史仓位',
    desc: '已平仓仓位的本地镜像，含开平仓均价、已实现盈亏、手续费与资金费。',
    hint: 'GET /api/v1/account/exchange/positions?instType=SWAP&limit=100&offset=0'
  },
  {
    name: 'fills',
    label: '交易账单',
    title: '交易账单',
    desc: '逐笔成交明细的本地镜像，手续费与盈亏按笔计账。',
    hint: 'GET /api/v1/account/exchange/fills?instType=SWAP&limit=100&offset=0'
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
  borrow: '暂无借币记录',
  bills: '暂无资金流水'
}

/* tab 面板右上角的计数文案：本地镜像优先展示后端 total 与最后同步时间 */
export function ocMetaText(_name: OcTabName, count: number, total?: unknown, lastSyncedAt?: unknown): string {
  let text =
    isNil(total) || total === null
      ? '共 ' + count + ' 条（limit=' + OC_LIMIT + '）'
      : '共 ' + String(total) + ' 条（本页 ' + count + '，limit=' + OC_LIMIT + '）'
  if (!isNil(lastSyncedAt)) text += ' · 同步于 ' + accTime(lastSyncedAt)
  return text
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

/* ===================== 本地镜像 tab 与勾选汇总 ===================== */

/* 这 4 个 tab 改读本地镜像（okx_exchange_*），借币与资金账单仍直连既有接口 */
export const OC_MIRROR_TABS: OcTabName[] = ['pending', 'history', 'positions', 'fills']

export function isMirrorTab(name: OcTabName): boolean {
  return OC_MIRROR_TABS.indexOf(name) >= 0
}

/* 勾选主键：委托用 ordId，仓位用「合约+方向+开平时间」，成交用 tradeId */
export function ocRowKey(name: OcTabName, row: Row): string {
  if (name === 'positions') {
    return 'pos-' + String(row.instId) + '-' + String(row.posSide) + '-' + String(row.cTime) + '-' + String(row.uTime)
  }
  if (name === 'fills') return 'fill-' + String(row.tradeId)
  return 'ord-' + String(row.ordId)
}

export interface OcInstSummary {
  instId: string
  count: number
  netPnl: number
}

export interface OcDaySummary {
  day: string
  netPnl: number
}

export interface OcSelectionSummary {
  count: number
  wins: number
  losses: number
  realizedPnl: number
  fee: number
  fundingFee: number
  netPnl: number
  winRate: number | null
  byInst: OcInstSummary[]
  days: OcDaySummary[]
}

function numOf(v: unknown): number {
  const n = Number(v)
  return isFinite(n) ? n : 0
}

function selectionTime(name: OcTabName, row: Row): unknown {
  if (name === 'positions') return row.uTime
  if (name === 'fills') return row.fillTime
  return row.cTime
}

function utcDayOf(v: unknown): string | null {
  const n = Number(v)
  if (!isFinite(n) || n <= 0) return null
  return new Date(n).toISOString().slice(0, 10)
}

/*
 * 勾选汇总口径（4 个 tab 通用）：
 *   已实现盈亏 = 行 pnl 之和（历史仓位没有 pnl 时回落 realizedPnl）；
 *   手续费     = 行 fee 之和（OKX 返回负数，即成本）；
 *   资金费     = 行 fundingFee 之和（只有历史仓位有）；
 *   净收益     = 已实现盈亏 + 手续费 + 资金费；
 *   胜率       = pnl > 0 的笔数 / pnl ≠ 0 的笔数（开仓单 pnl=0 不参与），无可比笔数时为 null；
 *   按日归集   = 委托用 cTime、仓位用 uTime、成交用 fillTime，按 UTC 日期聚合。
 */
export function ocSelectionSummary(name: OcTabName, rows: Row[]): OcSelectionSummary {
  const list = Array.isArray(rows) ? rows : []
  let realized = 0
  let fee = 0
  let funding = 0
  let wins = 0
  let losses = 0
  const instOrder: string[] = []
  const instMap: Record<string, OcInstSummary> = {}
  const dayOrder: string[] = []
  const dayMap: Record<string, OcDaySummary> = {}

  for (let i = 0; i < list.length; i++) {
    const row = list[i]
    const pnl = isNil(row.pnl) ? numOf(row.realizedPnl) : numOf(row.pnl)
    const rowFee = numOf(row.fee)
    const rowFunding = numOf(row.fundingFee)
    realized += pnl
    fee += rowFee
    funding += rowFunding
    if (pnl > 0) wins++
    else if (pnl < 0) losses++

    const inst = isNil(row.instId) ? ACC_NA : String(row.instId)
    if (!instMap[inst]) {
      instOrder.push(inst)
      instMap[inst] = { instId: inst, count: 0, netPnl: 0 }
    }
    instMap[inst].count++
    instMap[inst].netPnl += pnl + rowFee + rowFunding

    const day = utcDayOf(selectionTime(name, row))
    if (day) {
      if (!dayMap[day]) {
        dayOrder.push(day)
        dayMap[day] = { day: day, netPnl: 0 }
      }
      dayMap[day].netPnl += pnl + rowFee + rowFunding
    }
  }

  const comparable = wins + losses
  return {
    count: list.length,
    wins: wins,
    losses: losses,
    realizedPnl: realized,
    fee: fee,
    fundingFee: funding,
    netPnl: realized + fee + funding,
    winRate: comparable > 0 ? wins / comparable : null,
    byInst: instOrder.map((key) => instMap[key]),
    days: dayOrder.sort().map((key) => dayMap[key])
  }
}

export function ocSelectionPairs(s: OcSelectionSummary): KvPair[] {
  return [
    kv('已选笔数', accInt(s.count)),
    kvHtml('已实现盈亏', accPnl(s.realizedPnl)),
    kvHtml('手续费', accPnl(s.fee)),
    kvHtml('资金费', accPnl(s.fundingFee)),
    kvHtml('净收益', accPnl(s.netPnl)),
    kv('胜率', s.winRate === null ? ACC_NA : (s.winRate * 100).toFixed(2) + '%')
  ]
}

