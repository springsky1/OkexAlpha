/* 健康度页纯逻辑（1:1 对应 legacy app.js 5003-5211） */
import { esc, fmtInt, fmtTime, isNil, pagerInfo, parseServerTime } from '../../lib/format'
import type { Col } from '../shadow/logic'
import { levelBadge, metricFold, timeText } from '../shadow/logic'

export const HEALTH_PAGE_LIMIT = 100
export const HEALTH_POLL_MS = 15000

/* 体检覆盖的 9 类规则（顺序固定，用于补齐缺失项） */
export const HEALTH_RULES: string[] = [
  'SHADOW_SCHEDULER',
  'MARKET_DATA_FRESH',
  'UTILIZATION',
  'POSITION_EXIT',
  'FUNDING_HEADWIND',
  'NO_SHADOW_POSITIONS',
  'REAL_TRADING_GATE',
  'API_ERROR_RATE',
  'BACKFILL_LOAD'
]

export const HEALTH_LEVELS: string[] = ['OK', 'WARN', 'FAIL']

export interface HealthSummary {
  ok: number
  warn: number
  fail: number
  other: number
  total: number
  rules: string[]
  latestMs: number
  verdict: string
}

/* 由最近一轮体检明细汇总计数；空数组返回 null */
export function summarizeHealth(rows: any[]): HealthSummary | null {
  const list = Array.isArray(rows) ? rows : []
  if (!list.length) return null
  let ok = 0
  let warn = 0
  let fail = 0
  let other = 0
  let latestMs = 0
  const seen: string[] = []
  for (let i = 0; i < list.length; i++) {
    const r = list[i] || {}
    const lv = isNil(r.level) ? '' : String(r.level).toUpperCase()
    if (lv === 'OK') ok++
    else if (lv === 'WARN') warn++
    else if (lv === 'FAIL') fail++
    else other++
    const ms = parseServerTime(r.checkedAt)
    if (isFinite(ms) && ms > latestMs) latestMs = ms
    const rule = isNil(r.rule) ? '' : String(r.rule)
    if (rule && seen.indexOf(rule) < 0) seen.push(rule)
  }
  const rules = seen.slice()
  for (let i = 0; i < HEALTH_RULES.length; i++) {
    if (rules.indexOf(HEALTH_RULES[i]) < 0) rules.push(HEALTH_RULES[i])
  }
  const verdict = fail > 0 ? 'FAIL' : warn > 0 ? 'WARN' : 'OK'
  return { ok, warn, fail, other, total: list.length, rules, latestMs, verdict }
}

/* 汇总徽章 HTML（legacy healthRenderSummary 逐行等价） */
export function healthSummaryHtml(s: HealthSummary | null): string {
  if (!s) return ''
  let html = '<div class="health-summary-row">'
  html += '<span class="health-pill" data-level="OK">OK <strong>' + fmtInt(s.ok) + '</strong></span>'
  html += '<span class="health-pill" data-level="WARN">WARN <strong>' + fmtInt(s.warn) + '</strong></span>'
  html += '<span class="health-pill" data-level="FAIL">FAIL <strong>' + fmtInt(s.fail) + '</strong></span>'
  if (s.other > 0) {
    html += '<span class="health-pill" data-level="OTHER">其他 <strong>' + fmtInt(s.other) + '</strong></span>'
  }
  html +=
    '<span class="health-total">共 ' + fmtInt(s.total) + ' 条检查 · 覆盖 ' + fmtInt(s.rules.length) +
    ' 类规则</span>'
  html += '<span class="health-time mono">最近体检 ' + esc(fmtTime(s.latestMs)) + '</span>'
  html += '</div>'
  html +=
    '<p class="hint health-verdict">整体判定 <strong data-verdict="' + esc(s.verdict) + '">' +
    esc(s.verdict) + '</strong>（有 FAIL 即 FAIL，否则有 WARN 即 WARN）。口径：上一轮 tick 之后的 9 类规则体检结果。</p>'
  return html
}

export function healthSummaryMeta(s: HealthSummary | null): string {
  if (!s) return '暂无体检记录'
  return '最近一轮 ' + fmtInt(s.total) + ' 条 · ' + fmtTime(s.latestMs)
}

export function healthCheckedAt(s: HealthSummary | null): string {
  return '最近体检 ' + (s ? fmtTime(s.latestMs) : '-')
}

/* 明细卡 meta：level=WARN · 每页 100 条 */
export function healthListMeta(level: string): string {
  const prefix = level ? 'level=' + level + ' · ' : '全部 level · '
  return prefix + '每页 ' + HEALTH_PAGE_LIMIT + ' 条'
}

export function healthChecksInfo(offset: number, count: number): string {
  return count ? pagerInfo(offset, count) : '暂无数据'
}

/* 体检明细表：时间 / 规则 / level / scope / instId / message / metric */
export function healthChecksCols(): Col[] {
  return [
    { title: '时间', render: (r: any) => '<span class="mono">' + esc(timeText(r.checkedAt)) + '</span>' },
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
