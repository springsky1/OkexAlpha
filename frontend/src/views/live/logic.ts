/* 实盘门禁页纯逻辑（1:1 对应 legacy app.js 5215-5625） */
import { esc, fmtInt, fmtNum, fmtRatio, isNil } from '../../lib/format'
import type { Col } from '../shadow/logic'
import { levelBadge, metricFold, prettyJson, timeText } from '../shadow/logic'

export const LIVE_POLL_MS = 10000
export const LIVE_CONFIRM_TOKEN = 'CONFIRM-REAL-TRADING'

/* caps 展示顺序与中文名（键序固定，后端返回的其余键补在最后） */
export const LIVE_CAP_LABELS: Record<string, string> = {
  maxPositions: '最大持仓数',
  maxDailyNotionalUsd: '当日成交名义上限 USD',
  usedTodayUsd: '当日已用名义 USD',
  capitalPerCoinUsd: '单币分配资金 USD',
  feeTaker: 'taker 手续费率',
  slippageBps: '滑点 bps',
  evaluatedAt: '评估时间'
}

export type Side = 'buy' | 'sell'
export type OrdType = 'market' | 'limit'
export type TdMode = 'isolated' | 'cross'

export interface LiveOrderInput {
  instId: string
  side: Side
  sz: string
  ordType: OrdType
  px: string
  tdMode: TdMode
  reduceOnly: boolean
  confirm: string
}

export type ValidateField = 'instId' | 'sz' | 'px'

export type ValidationResult =
  | { ok: true }
  | { ok: false; field: ValidateField; msg: string }

/* 下单前校验（顺序与 legacy 一致：instId → sz → limit 的 px） */
export function validateOrder(input: LiveOrderInput): ValidationResult {
  if (!input.instId.trim()) {
    return { ok: false, field: 'instId', msg: '请先填 instId（可聚焦输入框从下拉里选）' }
  }
  const sz = Number(input.sz)
  if (!isFinite(sz) || sz <= 0) {
    return { ok: false, field: 'sz', msg: 'sz 需为大于 0 的合约张数' }
  }
  if (input.ordType === 'limit') {
    const px = Number(input.px)
    if (!isFinite(px) || px <= 0) {
      return { ok: false, field: 'px', msg: '限价单必须填大于 0 的 px' }
    }
  }
  return { ok: true }
}

/* POST body：sz/px 转数字；市价不带 px；confirm 为空不带 */
export function buildOrderBody(input: LiveOrderInput): Record<string, unknown> {
  const body: Record<string, unknown> = {
    instId: input.instId.trim(),
    side: input.side,
    sz: Number(input.sz),
    ordType: input.ordType,
    tdMode: input.tdMode,
    reduceOnly: !!input.reduceOnly
  }
  if (input.ordType === 'limit') body.px = Number(input.px)
  const confirm = input.confirm.trim()
  if (confirm) body.confirm = confirm
  return body
}

/* 二次确认弹窗正文 */
export function confirmBodyHtml(input: LiveOrderInput, allowed: boolean, mode: string): string {
  let html = '<ul class="live-confirm-list">'
  html += '<li>instId：<code>' + esc(input.instId.trim()) + '</code></li>'
  html +=
    '<li>side / ordType：<code>' + esc(input.side) + '</code> / <code>' + esc(input.ordType) + '</code></li>'
  html +=
    '<li>sz：<code>' + esc(String(input.sz)) + '</code> 张' +
    (input.ordType === 'limit' ? '，px：<code>' + esc(String(input.px)) + '</code>' : '（市价）') +
    '</li>'
  html +=
    '<li>tdMode：<code>' + esc(input.tdMode) + '</code> · reduceOnly：' +
    (input.reduceOnly ? '是' : '否') + '</li>'
  html += '<li>confirm：<code>' + esc(input.confirm.trim() || '(空)') + '</code></li>'
  html += '</ul>'
  html += allowed
    ? '<p class="hint">门禁当前 liveAllowed=true，这次提交会真的发往交易所。</p>'
    : '<p class="hint">当前 mode=<strong>' + esc(mode) + '</strong>、liveAllowed=false：后端会以 HTTP 422 拒绝并列出拦截原因，这是预期行为，不会真的下单。</p>'
  return html
}

/* 拦截原因：把 "MODE: ..." 的前缀加粗，其余原样 */
export function liveBlockerHtml(text: unknown): string {
  const raw = isNil(text) ? '' : String(text)
  const at = raw.indexOf(': ')
  if (at > 0 && at <= 24) {
    return '<strong>' + esc(raw.slice(0, at)) + '</strong><span>' + esc(raw.slice(at + 2)) + '</span>'
  }
  return esc(raw)
}

export interface LiveGateModel {
  mode: string
  allowed: boolean
  isShadow: boolean
  modeSub: string
  flagText: string
  flagSub: string
  verdictText: string
  verdictSub: string
  blockerCount: number
}

export function liveGateModel(gate: any): LiveGateModel {
  const g = gate || {}
  const mode = isNil(g.mode) ? '-' : String(g.mode)
  const allowed = !!g.liveAllowed
  const isShadow = mode.toUpperCase() === 'SHADOW'
  const blockers = Array.isArray(g.blockers) ? g.blockers : []
  return {
    mode,
    allowed,
    isShadow,
    modeSub: isShadow ? 'SHADOW 只做本地影子记账，不可能放行实盘' : '实盘模式受门禁约束',
    flagText: allowed ? 'ALLOW' : 'BLOCK',
    flagSub: allowed ? '门禁全部通过，可提交真实订单' : '门禁拦截，下单接口返回 HTTP 422',
    verdictText: allowed ? '放行' : '拦截 ' + blockers.length + ' 项',
    verdictSub: isShadow ? 'SHADOW 下 BLOCK 属预期行为' : '',
    blockerCount: blockers.length
  }
}

export function liveChecksCols(): Col[] {
  return [
    { title: '检查项 id', render: (r: any) => '<span class="mono">' + esc(r.id) + '</span>' },
    { title: 'level', render: (r: any) => levelBadge(r.level) },
    { title: 'message', render: (r: any) => esc(isNil(r.message) ? '-' : r.message) },
    { title: 'metric', render: (r: any) => metricFold(r.metric) }
  ]
}

/* checks meta：PASS n · WARN n · BLOCK n（顺序按 PASS/WARN/BLOCK，其余附加） */
export function liveChecksMeta(checks: any[]): string {
  const list = Array.isArray(checks) ? checks : []
  let pass = 0
  let warnN = 0
  let block = 0
  for (let i = 0; i < list.length; i++) {
    const lv = String(isNil(list[i].level) ? '' : list[i].level).toUpperCase()
    if (lv === 'PASS') pass++
    else if (lv === 'WARN') warnN++
    else if (lv === 'BLOCK') block++
  }
  return 'PASS ' + fmtInt(pass) + ' · WARN ' + fmtInt(warnN) + ' · BLOCK ' + fmtInt(block)
}

/* caps 单个值：时间、费率、USD/bps 数值、整数的展示规则
   与 legacy app.js:5402-5405 逐行等价：数字且键不含 Usd/Bps → fmtInt，其余一律 fmtNum。
   因此 capitalPerCoinUsd=0 或字符串 '0.0' 都显示 "0"（fmtNum 只保留有效小数，不补 2 位）。 */
export function liveCapValue(key: string, value: unknown): string {
  if (key === 'evaluatedAt') return timeText(value)
  if (isNil(value)) return '-'
  if (key === 'feeTaker') return fmtRatio(value)
  if (typeof value === 'number') return /Usd|Bps/.test(key) ? fmtNum(value) : fmtInt(value)
  return fmtNum(value)
}

export function liveCapEntries(caps: any): Array<[string, string]> {
  const c = caps && typeof caps === 'object' ? caps : null
  if (!c) return []
  const keys = Object.keys(c)
  const order: string[] = []
  for (const k of Object.keys(LIVE_CAP_LABELS)) {
    if (keys.indexOf(k) >= 0) order.push(k)
  }
  for (let i = 0; i < keys.length; i++) {
    if (order.indexOf(keys[i]) < 0) order.push(keys[i])
  }
  return order.map((k): [string, string] => [LIVE_CAP_LABELS[k] || k, liveCapValue(k, c[k])])
}

/* 交易所账户展示状态 */
export interface LiveAccountModel {
  configured: boolean
  mode: string
  title: string
  reason: string
  meta: string
  balanceCount: number
  positionCount: number
}

export function liveAccountModel(acct: any): LiveAccountModel {
  const a = acct || {}
  const mode = isNil(a.mode) ? '-' : String(a.mode)
  const balance = Array.isArray(a.balance) ? a.balance : []
  const positions = Array.isArray(a.positions) ? a.positions : []
  const configured = !!a.configured
  return {
    configured,
    mode,
    title: configured ? '已配置 API 密钥' : '未配置 API 密钥（configured=false）',
    reason: isNil(a.reason) ? '当前只有本地影子账本可用。' : String(a.reason),
    meta:
      'mode=' + mode + ' · balance=' + balance.length + ' 条 · positions=' + positions.length + ' 条',
    balanceCount: balance.length,
    positionCount: positions.length
  }
}

/* 账户余额/持仓这种动态结构：取首行最多 10 个键做列 */
export function liveDynamicCols(rows: any[]): Col[] {
  const list = Array.isArray(rows) ? rows : []
  if (!list.length) return []
  const first = list[0] && typeof list[0] === 'object' ? list[0] : {}
  const keys = Object.keys(first).slice(0, 10)
  return keys.map((k): Col => ({
    title: k,
    render: (r: any) => {
      const v = r ? r[k] : null
      if (v === null || v === undefined || v === '') return '-'
      if (typeof v === 'object') return '<span class="mono">' + esc(JSON.stringify(v)) + '</span>'
      return esc(String(v))
    }
  }))
}

/* 422 拦截响应：拆出 msg 里 "live trading blocked -> " 之后的逐条原因 */
export interface BlockedInfo {
  detail: string
  parts: string[]
}

export function blockedInfo(msg: unknown): BlockedInfo {
  const raw = isNil(msg) ? '' : String(msg)
  const prefix = 'live trading blocked -> '
  const detail = raw.indexOf(prefix) === 0 ? raw.slice(prefix.length) : raw
  const parts = detail
    .split(' | ')
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
  return { detail, parts }
}

/* 门禁拦截结果面板（琥珀色提示「符合预期」，不是故障） */
export function orderErrorHtml(err: any): string {
  const status = err && typeof err.status === 'number' ? err.status : 0
  const msg = err && err.message ? String(err.message) : ''
  if (status === 422) {
    const info = blockedInfo(msg)
    let html = '<div class="live-block-note">'
    html += '<div class="live-block-note-title">被门禁拦截（符合预期）</div>'
    html +=
      '<p class="live-block-note-sub">HTTP 422 · 请求在门禁处被拒绝，没有发往交易所。' +
      '当前 SHADOW 环境门禁必然 BLOCK，这是设计行为，不是接口故障。</p>'
    html +=
      '<div class="live-http mono">HTTP 422 · code=' +
      esc(err && !isNil(err.code) ? String(err.code) : 'INVALID_PARAM') + '</div>'
    html += '<ul class="live-blockers">'
    for (let i = 0; i < info.parts.length; i++) {
      html += '<li>' + liveBlockerHtml(info.parts[i]) + '</li>'
    }
    html += '</ul>'
    html +=
      '<details class="metric-fold"><summary class="hint">完整响应 msg</summary>' +
      '<pre class="json-out">' + esc(msg) + '</pre></details>'
    html += '</div>'
    return html
  }
  const code = status || 0
  return (
    '<div class="result-box is-err">' +
    esc('下单失败（HTTP ' + code + '）：' + (msg || '未知错误')) +
    '</div>'
  )
}

/* 下单成功（或 200 但 accepted=false）结果面板 */
export function orderResultHtml(res: any): string {
  const r = res || {}
  let html = '<div class="result-box is-ok">'
  html += '<strong>accepted=' + esc(isNil(r.accepted) ? '-' : String(r.accepted)) + '</strong>'
  html += ' · ordId ' + esc(isNil(r.ordId) ? '(空)' : String(r.ordId))
  html += ' · clOrdId ' + esc(isNil(r.clOrdId) ? '-' : String(r.clOrdId))
  html += '</div>'
  html +=
    '<details class="metric-fold"><summary class="hint">完整响应</summary>' +
    '<pre class="json-out">' + esc(prettyJson(r)) + '</pre></details>'
  return html
}
