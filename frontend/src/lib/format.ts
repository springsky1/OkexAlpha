/* 与 legacy app.js 第 1 区逐行等价的格式化 / 转义 / 查询串工具（1:1 移植） */
import { JOB_STATUS_LABELS, TASK_STATUS_LABELS } from './labels'

const nfInt = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 })
const nfNum = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 8 })
const nfMoney = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 })

export function isNil(v: unknown): boolean {
  return v === null || v === undefined || v === ''
}

/* HTML 转义 */
export function esc(v: unknown): string {
  let s = isNil(v) ? '' : String(v)
  s = s.split('&').join('&amp;')
  s = s.split('<').join('&lt;')
  s = s.split('>').join('&gt;')
  s = s.split('"').join('&quot;')
  s = s.split("'").join('&#39;')
  return s
}

export function fmtInt(v: unknown): string {
  if (isNil(v)) return '-'
  const n = Number(v)
  if (!isFinite(n)) return String(v)
  return nfInt.format(n)
}

export function fmtNum(v: unknown): string {
  if (isNil(v)) return '-'
  const n = Number(v)
  if (!isFinite(n)) return String(v)
  return nfNum.format(n)
}

export function fmtMoney(v: unknown): string {
  if (isNil(v)) return '-'
  const n = Number(v)
  if (!isFinite(n)) return String(v)
  return nfMoney.format(n)
}

export function fmtRatio(v: unknown): string {
  if (isNil(v)) return '-'
  const n = Number(v)
  if (!isFinite(n)) return String(v)
  return n.toFixed(4)
}

export function fmtPct(v: unknown): string {
  if (isNil(v)) return '-'
  const n = Number(v)
  if (!isFinite(n)) return String(v)
  const sign = n > 0 ? '+' : ''
  return sign + n.toFixed(2) + '%'
}

export function pad2(n: number): string {
  return (n < 10 ? '0' : '') + n
}

/* 毫秒时间戳 -> 本地时间 YYYY-MM-DD HH:mm:ss */
export function fmtTime(ms: unknown): string {
  const n = Number(ms)
  if (!isFinite(n) || n <= 0) return '-'
  const d = new Date(n)
  return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) +
    ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds())
}

/* 毫秒时间戳 -> 本地时间 MM-DD HH:mm（图表轴用） */
export function fmtShortTime(ms: unknown): string {
  const n = Number(ms)
  if (!isFinite(n) || n <= 0) return '-'
  const d = new Date(n)
  return pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes())
}

/* 毫秒时间戳 -> datetime-local 输入框需要的字符串 */
export function toLocalInput(ms: number): string {
  const d = new Date(ms)
  return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) +
    'T' + pad2(d.getHours()) + ':' + pad2(d.getMinutes())
}

/* datetime-local 输入框的值 -> 毫秒时间戳（空值返回 null） */
export function datetimeLocalToMs(v: unknown): number | null {
  if (v === null || v === undefined) return null
  const s = String(v).trim()
  if (!s) return null
  const t = new Date(s).getTime()
  return isFinite(t) ? t : null
}

/* 后端时间字符串（可能带 6 位微秒）-> 毫秒，兼容小数位数不足/超额 */
export function parseServerTime(s: unknown): number {
  if (!s) return NaN
  const t = String(s)
  const dot = t.indexOf('.')
  let out = t
  if (dot >= 0) {
    let i = dot + 1
    while (i < t.length && t.charAt(i) >= '0' && t.charAt(i) <= '9') i++
    let frac = t.slice(dot + 1, i)
    if (frac.length > 3) frac = frac.slice(0, 3)
    while (frac.length < 3) frac = frac + '0'
    out = t.slice(0, dot) + '.' + frac + t.slice(i)
  }
  const ms = Date.parse(out)
  return isFinite(ms) ? ms : NaN
}

export function fmtDur(ms: unknown): string {
  const n = Number(ms)
  if (!isFinite(n) || n < 0) return '-'
  if (n < 1000) return n + ' ms'
  const sec = n / 1000
  if (sec < 60) return sec.toFixed(1) + ' 秒'
  const m = Math.floor(sec / 60)
  const s = Math.round(sec - m * 60)
  return m + ' 分 ' + s + ' 秒'
}

/* 文本域 / 逗号分隔输入 -> 去重数组 */
export function parseList(text: unknown, NL: string, TAB: string): string[] {
  if (isNil(text)) return []
  let s = String(text)
  s = s.split(NL).join(',')
  s = s.split(TAB).join(',')
  s = s.split(',').join(',')
  s = s.split(';').join(',')
  s = s.split('|').join(',')
  s = s.split('，').join(',')
  s = s.split('、').join(',')
  s = s.split(' ').join(',')
  const out: string[] = []
  const parts = s.split(',')
  for (let i = 0; i < parts.length; i++) {
    const v = parts[i].trim()
    if (v && out.indexOf(v) < 0) out.push(v)
  }
  return out
}

/* 拼接查询串，跳过 null / undefined / 空串 */
export function buildQuery(params?: Record<string, unknown> | null): string {
  const sp = new URLSearchParams()
  if (params) {
    const keys = Object.keys(params)
    for (let i = 0; i < keys.length; i++) {
      const k = keys[i]
      const v = params[k]
      if (v === null || v === undefined) continue
      const s = String(v).trim()
      if (s === '') continue
      sp.append(k, s)
    }
  }
  const out = sp.toString()
  return out ? '?' + out : ''
}

/* 状态徽标 */
export function badge(status: unknown): string {
  const s = isNil(status) ? 'UNKNOWN' : String(status)
  const label = JOB_STATUS_LABELS[s] || TASK_STATUS_LABELS[s] || s
  return '<span class="badge" data-status="' + esc(s) + '">' + esc(label) + '</span>'
}

/* 分页文案 */
export function pagerInfo(offset: number, count: number): string {
  if (!count) return '暂无记录'
  return '第 ' + (offset + 1) + '–' + (offset + count) + ' 条'
}
