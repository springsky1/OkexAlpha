/* 影子交易页纯逻辑（1:1 对应 legacy app.js 4443-4507 帮助函数与 4511-4999 页面逻辑） */
import {
  badge,
  esc,
  fmtInt,
  fmtMoney,
  fmtNum,
  fmtShortTime,
  fmtTime,
  isNil,
  parseServerTime
} from '../../lib/format'
import { CHART_FONT, COLOR_AXIS, COLOR_GRID } from '../../lib/labels'

export interface Col {
  title: string
  key?: string
  num?: boolean
  render?: (row: any) => string
}

export const SHADOW_POS_LIMIT = 100
export const SHADOW_ORDER_LIMIT = 50
export const SHADOW_PF_LIMIT = 200
export const SHADOW_PF_SHOW = 20
export const SHADOW_POLL_MS = 5000

/* 体检/门禁 level 徽章：OK / WARN / FAIL / PASS / BLOCK 直接映射 data-status */
export function levelBadge(level: unknown): string {
  const s = isNil(level) ? 'UNKNOWN' : String(level).toUpperCase()
  return '<span class="badge" data-status="' + esc(s) + '">' + esc(s) + '</span>'
}

/* 方向单元格：long/buy 绿、short/sell 红 */
export function sideCell(side: unknown): string {
  const s = isNil(side) ? '' : String(side).toLowerCase()
  const label = isNil(side) ? '-' : String(side)
  if (s === 'long' || s === 'buy') return '<span class="val-pos">' + esc(label) + '</span>'
  if (s === 'short' || s === 'sell') return '<span class="val-neg">' + esc(label) + '</span>'
  return esc(label)
}

/* 正负金额：正绿负红，便于扫盈亏列 */
export function signedMoney(v: unknown): string {
  if (isNil(v)) return '-'
  const n = Number(v)
  if (!isFinite(n)) return esc(String(v))
  const txt = fmtMoney(n)
  if (n > 0) return '<span class="val-pos">+' + esc(txt) + '</span>'
  if (n < 0) return '<span class="val-neg">' + esc(txt) + '</span>'
  return esc(txt)
}

/* 时间字段：毫秒数或服务端 ISO 字符串都能显示成本地时间 */
export function timeText(value: unknown): string {
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

/* prettyJson：字符串先尝试解析，失败原样；对象缩进 2 */
export function prettyJson(value: unknown): string {
  if (isNil(value)) return '-'
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value)
      return JSON.stringify(parsed, null, 2)
    } catch {
      return value
    }
  }
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

/* metric 是 JSON 字符串：折叠展示；解析不出就原样给出 */
export function metricFold(metric: unknown): string {
  if (isNil(metric)) return '<span class="muted">-</span>'
  return (
    '<details class="metric-fold"><summary class="hint">metric</summary>' +
    '<pre class="json-out">' + esc(prettyJson(metric)) + '</pre></details>'
  )
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

export interface StatCard {
  label: string
  value: string
  sub: string
  time: boolean
}

/* 4 张 stat-card（策略数 / 持仓 / 最近 tick / 最近体检） */
export function shadowStatsCards(st: any): StatCard[] {
  const s = st || {}
  const dur = isNil(s.lastTickDurationMs) ? '-' : fmtInt(s.lastTickDurationMs) + ' ms'
  return [
    { label: '策略数', value: fmtInt(s.strategies), sub: '参与影子引擎', time: false },
    { label: '影子持仓', value: fmtInt(s.openPositions), sub: 'OPEN 状态', time: false },
    { label: '最近 tick', value: fmtTime(s.lastTickAtMs), sub: '耗时 ' + dur, time: true },
    {
      label: '最近体检',
      value: fmtTime(s.lastHealthCheckAtMs),
      sub: isNil(s.lastHealthSummary) ? '尚未体检' : String(s.lastHealthSummary),
      time: true
    }
  ]
}

/* 引擎状态卡 kv 6 项 */
export function shadowStatusKv(st: any): Array<[string, string]> {
  const s = st || {}
  return [
    ['策略数', fmtInt(s.strategies)],
    ['影子持仓数 openPositions', fmtInt(s.openPositions)],
    ['最近 tick 时间', fmtTime(s.lastTickAtMs)],
    ['最近 tick 耗时', isNil(s.lastTickDurationMs) ? '-' : fmtInt(s.lastTickDurationMs) + ' ms'],
    ['最近体检时间', fmtTime(s.lastHealthCheckAtMs)],
    ['最近体检摘要', isNil(s.lastHealthSummary) ? '-' : String(s.lastHealthSummary)]
  ]
}

/* status 里带的组合快照（接口键名是 latestPortfolio，兼容 portfolio） */
export function statusPortfolio(st: any): any {
  const s = st || {}
  const pf = s.latestPortfolio || s.portfolio
  return pf && typeof pf === 'object' ? pf : null
}

export function shadowPortfolioKv(pf: any): Array<[string, string]> {
  const p = pf || {}
  return [
    ['最新组合 equity', fmtMoney(p.equityUsd) + ' USD'],
    ['available', fmtMoney(p.availableUsd) + ' USD'],
    ['持仓数', fmtInt(p.positionCount)],
    ['gross 名义', fmtMoney(p.grossNotionalUsd) + ' USD'],
    ['net 名义', fmtMoney(p.netNotionalUsd) + ' USD'],
    ['未实现盈亏', fmtMoney(p.unrealizedPnlUsd) + ' USD'],
    ['快照时间', timeText(p.tsMs || p.createdAt)]
  ]
}

export function shadowPosInfo(n: number): string {
  return n ? '已加载 ' + n + ' 条 OPEN 持仓（接口无 total 字段，按返回条数判断是否还有更多）' : '暂无数据'
}

export function shadowOrderInfo(n: number): string {
  return n ? '已加载 ' + n + ' 条（接口无 total 字段，按返回条数判断是否还有更多）' : '暂无数据'
}

export function shadowPfMeta(rows: any[]): string {
  const list = Array.isArray(rows) ? rows : []
  const latest = list.length ? list[0] : null
  if (!latest) return 'GET /portfolio?limit=' + SHADOW_PF_LIMIT
  return (
    '已加载 ' + list.length + ' 条 · 最新 equity ' + fmtMoney(latest.equityUsd) +
    ' / available ' + fmtMoney(latest.availableUsd) + ' · 表内仅列最近 ' +
    Math.min(list.length, SHADOW_PF_SHOW) + ' 条'
  )
}

export function shadowHealthMeta(n: number): string {
  return n ? '取自 status.latestHealth · ' + n + ' 条' : '取自 status.latestHealth'
}

/* ---- 表格列 ---- */

export function shadowPositionCols(): Col[] {
  return [
    { title: 'instId', render: (r: any) => '<span class="mono">' + esc(r.instId) + '</span>' },
    { title: 'side', render: (r: any) => sideCell(r.side) },
    { title: 'sz', num: true, render: (r: any) => esc(fmtNum(r.sz)) },
    { title: '开仓价', num: true, render: (r: any) => esc(fmtNum(r.entryPx)) },
    { title: '标记价', num: true, render: (r: any) => esc(fmtNum(r.markPx)) },
    { title: '未实现盈亏 USD', num: true, render: (r: any) => signedMoney(r.unrealizedPnl) },
    { title: '杠杆', num: true, render: (r: any) => (isNil(r.leverage) ? '-' : esc(fmtNum(r.leverage)) + 'x') },
    { title: '强平价', num: true, render: (r: any) => esc(fmtNum(r.liqPx)) },
    { title: '保证金 USD', num: true, render: (r: any) => esc(fmtMoney(r.marginUsd)) },
    { title: '开仓时间', render: (r: any) => '<span class="mono">' + esc(timeText(r.openedAt)) + '</span>' },
    {
      title: '操作',
      render: (r: any) =>
        '<button class="btn btn-xs btn-danger" type="button" data-shadow-close="' + esc(r.id) + '">平仓</button>'
    }
  ]
}

export function shadowOrderCols(): Col[] {
  return [
    {
      title: '时间',
      render: (r: any) => '<span class="mono">' + esc(timeText(r.decidedAt || r.signalTsMs)) + '</span>'
    },
    { title: 'instId', render: (r: any) => '<span class="mono">' + esc(r.instId) + '</span>' },
    { title: 'side', render: (r: any) => sideCell(r.side) },
    { title: 'reason', render: (r: any) => '<span class="mono">' + esc(isNil(r.reason) ? '-' : r.reason) + '</span>' },
    { title: 'status', render: (r: any) => badge(r.status) },
    { title: 'sz', num: true, render: (r: any) => esc(fmtNum(r.sz)) },
    { title: 'px', num: true, render: (r: any) => esc(fmtNum(r.px)) },
    { title: '名义 USD', num: true, render: (r: any) => esc(fmtMoney(r.notionalUsd)) }
  ]
}

export function shadowPortfolioCols(): Col[] {
  return [
    { title: '时间', render: (r: any) => '<span class="mono">' + esc(timeText(r.tsMs)) + '</span>' },
    { title: 'equity USD', num: true, render: (r: any) => esc(fmtMoney(r.equityUsd)) },
    { title: 'available USD', num: true, render: (r: any) => esc(fmtMoney(r.availableUsd)) },
    { title: '持仓数', num: true, render: (r: any) => esc(fmtInt(r.positionCount)) },
    { title: 'gross 名义', num: true, render: (r: any) => esc(fmtMoney(r.grossNotionalUsd)) },
    { title: 'net 名义', num: true, render: (r: any) => esc(fmtMoney(r.netNotionalUsd)) },
    { title: '未实现盈亏 USD', num: true, render: (r: any) => signedMoney(r.unrealizedPnlUsd) }
  ]
}

export function shadowHealthCols(): Col[] {
  return [
    { title: '规则 rule', render: (r: any) => '<span class="mono">' + esc(r.rule) + '</span>' },
    { title: 'level', render: (r: any) => levelBadge(r.level) },
    { title: 'scope', render: (r: any) => '<span class="mono">' + esc(r.scope) + '</span>' },
    {
      title: 'instId',
      render: (r: any) => '<span class="mono">' + esc(isNil(r.instId) ? '-' : r.instId) + '</span>'
    },
    { title: 'message', render: (r: any) => esc(isNil(r.message) ? '-' : r.message) },
    { title: 'metric', render: (r: any) => metricFold(r.metric) }
  ]
}

/* ---- 组合曲线 ---- */

/* 取最近 200 条并按时间正序（接口按时间倒序返回） */
export function equityPoints(rows: any[]): any[] {
  const all = Array.isArray(rows) ? rows : []
  return all.slice(0, SHADOW_PF_LIMIT).slice().reverse()
}

/* 曲线取值区间；无有效 equityUsd 返回 null */
export function equityExtent(pts: any[]): { min: number; max: number } | null {
  let min = Infinity
  let max = -Infinity
  for (let i = 0; i < pts.length; i++) {
    const v = Number(pts[i].equityUsd)
    if (isFinite(v)) {
      if (v < min) min = v
      if (v > max) max = v
    }
  }
  if (!isFinite(min) || !isFinite(max)) return null
  if (max - min < 1e-9) {
    const pad = Math.max(0.5, Math.abs(max) * 0.002)
    min -= pad
    max += pad
  }
  return { min, max }
}

/* 组合曲线：canvas 折线（与 legacy shadowDrawEquity 逐行等价） */
export function drawEquityChart(canvas: HTMLCanvasElement | null, rows: any[]): void {
  if (!canvas) return
  const wrap = canvas.parentNode as HTMLElement | null
  const w = Math.max(320, (wrap && wrap.clientWidth) || 600)
  const h = Math.max(180, (wrap && wrap.clientHeight) || 240)
  const dpr = window.devicePixelRatio || 1
  canvas.width = Math.round(w * dpr)
  canvas.height = Math.round(h * dpr)
  canvas.style.width = w + 'px'
  canvas.style.height = h + 'px'
  let ctx: CanvasRenderingContext2D | null = null
  try {
    ctx = canvas.getContext('2d')
  } catch {
    ctx = null
  }
  if (!ctx) return
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  ctx.clearRect(0, 0, w, h)

  const pts = equityPoints(rows)
  if (!pts.length) {
    ctx.fillStyle = COLOR_AXIS
    ctx.font = '12px sans-serif'
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillText('暂无组合快照', w / 2, h / 2)
    return
  }

  const padL = 74
  const padR = 16
  const padT = 14
  const padB = 26
  const plotW = Math.max(20, w - padL - padR)
  const plotH = Math.max(20, h - padT - padB)

  const extent = equityExtent(pts)
  if (!extent) {
    ctx.fillStyle = COLOR_AXIS
    ctx.font = '12px sans-serif'
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillText('equity 字段不可解析', w / 2, h / 2)
    return
  }
  const min = extent.min
  const max = extent.max
  const yOf = (v: number): number => padT + plotH - ((v - min) / (max - min)) * plotH
  const xOf = (i: number): number =>
    pts.length <= 1 ? padL + plotW / 2 : padL + (i / (pts.length - 1)) * plotW

  /* 横向网格 + y 轴刻度 */
  ctx.font = CHART_FONT
  ctx.lineWidth = 1
  const gridRows = 4
  for (let i = 0; i <= gridRows; i++) {
    const v = min + (max - min) * (i / gridRows)
    const y = yOf(v)
    ctx.strokeStyle = COLOR_GRID
    ctx.beginPath()
    ctx.moveTo(padL, y)
    ctx.lineTo(padL + plotW, y)
    ctx.stroke()
    ctx.fillStyle = COLOR_AXIS
    ctx.textAlign = 'right'
    ctx.textBaseline = 'middle'
    ctx.fillText(fmtMoney(v), padL - 8, y)
  }

  /* x 轴：首 / 中 / 尾三个时间刻度 */
  const marks = [0, Math.floor((pts.length - 1) / 2), pts.length - 1]
  ctx.textAlign = 'center'
  ctx.textBaseline = 'top'
  for (let i = 0; i < marks.length; i++) {
    const idx = marks[i]
    if (idx < 0) continue
    const x = xOf(idx)
    ctx.strokeStyle = COLOR_GRID
    ctx.beginPath()
    ctx.moveTo(x, padT)
    ctx.lineTo(x, padT + plotH)
    ctx.stroke()
    ctx.fillStyle = COLOR_AXIS
    ctx.fillText(fmtShortTime(Number(pts[idx].tsMs)), x, padT + plotH + 6)
  }

  /* 净值折线 */
  ctx.strokeStyle = '#2f6fed'
  ctx.lineWidth = 1.6
  ctx.beginPath()
  let started = false
  for (let i = 0; i < pts.length; i++) {
    const v = Number(pts[i].equityUsd)
    if (!isFinite(v)) continue
    const x = xOf(i)
    const y = yOf(v)
    if (!started) {
      ctx.moveTo(x, y)
      started = true
    } else {
      ctx.lineTo(x, y)
    }
  }
  ctx.stroke()

  /* 最新点标记 */
  const lastV = Number(pts[pts.length - 1].equityUsd)
  if (isFinite(lastV)) {
    ctx.fillStyle = '#2f6fed'
    ctx.beginPath()
    ctx.arc(xOf(pts.length - 1), yOf(lastV), 2.6, 0, Math.PI * 2)
    ctx.fill()
  }
}
