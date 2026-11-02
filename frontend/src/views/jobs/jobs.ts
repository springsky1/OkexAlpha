import { badge, esc, fmtInt, fmtTime, isNil, parseServerTime } from '../../lib/format'
import { DATA_TYPE_LABELS } from '../../lib/labels'

/* ---------- 接口形状（对应 GET /api/v1/backfill/jobs 与 /jobs/{id}） ---------- */
export interface BackfillJob {
  id?: string | number
  dataType?: string
  status?: string
  taskTotal?: number
  taskDone?: number
  taskFailed?: number
  rowsWritten?: number
  createdAt?: string
  startedAt?: string
  finishedAt?: string
  error?: string
  request?: unknown
}

export interface BackfillTask {
  id?: string | number
  taskKey?: string
  status?: string
  cursorMs?: number | string | null
  pageCount?: number
  rowsWritten?: number
  attempts?: number
  truncated?: boolean | null
  lastError?: string | null
}

export interface JobDetailPayload {
  job?: BackfillJob
  tasks?: BackfillTask[]
}

export const JOB_ACTION_LABELS: Record<string, string> = { pause: '暂停', resume: '继续', cancel: '取消' }

export const JOB_LIMITS = [20, 50, 100]

/* legacy 的 ui.jobs 是模块级状态，跨路由保留（offset / limit / loaded / expanded 缓存） */
export interface JobsState {
  offset: number
  limit: number
  loaded: boolean
  expanded: Record<string, string>
}

export const jobsState: JobsState = { offset: 0, limit: 50, loaded: false, expanded: {} }

export function resetJobsState(): void {
  jobsState.offset = 0
  jobsState.limit = 50
  jobsState.loaded = false
  jobsState.expanded = {}
}

/* ---------- 进度条 ---------- */
export interface JobProgress {
  total: number
  done: number
  pct: number
  barClass: string
  text: string
}

export function progressOf(job: BackfillJob): JobProgress {
  const total = Number(job.taskTotal) || 0
  const done = Number(job.taskDone) || 0
  const pct = total > 0 ? Math.min(100, Math.round((done * 100) / total)) : 0
  let barClass = ''
  if (job.status === 'DONE') barClass = 'is-done'
  else if (job.status === 'FAILED') barClass = 'is-failed'
  return { total, done, pct, barClass, text: done + '/' + total }
}

/* ---------- 单元格文本 ---------- */
export function dataTypeText(job: BackfillJob): string {
  const raw = job.dataType === undefined || job.dataType === null ? '' : job.dataType
  return String(DATA_TYPE_LABELS[raw] || raw)
}

export function createdAtText(job: BackfillJob): string {
  return fmtTime(parseServerTime(job.createdAt))
}

/* ---------- 操作按钮可用性 ---------- */
export function canPause(job: BackfillJob): boolean {
  return job.status === 'PENDING' || job.status === 'RUNNING'
}

export function canResume(job: BackfillJob): boolean {
  return job.status === 'PAUSED' || job.status === 'FAILED'
}

export function canCancel(job: BackfillJob): boolean {
  return job.status !== 'DONE' && job.status !== 'CANCELED'
}

/* ---------- JSON 美化（与 legacy prettyJson 逐行等价） ---------- */
export function prettyJson(value: unknown): string {
  if (isNil(value)) return '-'
  if (typeof value === 'string') {
    try {
      return JSON.stringify(JSON.parse(value), null, 2)
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

/* ---------- 分页摘要 ---------- */
export function jobsSummary(count: number, limit: number): string {
  return count ? '本页 ' + count + ' 条 · 每页 ' + limit + ' · 无总数字段，翻页按「本页是否满页」判断' : '本页无数据'
}

export function listParams(dataType: string, status: string, limit: number, offset: number): Record<string, string | number> {
  return { dataType, status, limit, offset }
}

/* ---------- 作业详情展开区（沿用 legacy 的 HTML 结构，所有插值都过 esc） ---------- */
function taskRowsHtml(tasks: BackfillTask[]): string {
  let html = ''
  for (let i = 0; i < tasks.length; i++) {
    const t = tasks[i]
    html += '<tr>'
    html += '<td class="mono">' + esc(t.id) + '</td>'
    html += '<td class="mono">' + esc(t.taskKey) + '</td>'
    html += '<td>' + badge(t.status) + '</td>'
    html += '<td class="mono">' + esc(isNil(t.cursorMs) ? '-' : fmtTime(t.cursorMs)) + '</td>'
    html += '<td class="num">' + fmtInt(t.pageCount) + '</td>'
    html += '<td class="num">' + fmtInt(t.rowsWritten) + '</td>'
    html += '<td class="num">' + fmtInt(t.attempts) + '</td>'
    html += '<td>' + esc(isNil(t.truncated) ? '-' : t.truncated ? '是' : '否') + '</td>'
    html += '<td>' + (isNil(t.lastError) ? '-' : '<span class="danger-text">' + esc(t.lastError) + '</span>') + '</td>'
    html += '</tr>'
  }
  return html
}

export function renderJobDetail(data: JobDetailPayload | BackfillJob | null | undefined): string {
  const payload = (data || {}) as JobDetailPayload
  const job: BackfillJob = payload.job ? payload.job : ((data || {}) as BackfillJob)
  const tasks: BackfillTask[] = Array.isArray(payload.tasks) ? payload.tasks : []

  let html = '<div class="table-detail-inner">'
  html += '<div style="padding:10px">'
  html += '<h4>作业信息</h4>'
  html += '<div class="kv-grid">'
  html += '<div class="kv"><span class="kv-key">jobId</span><span class="kv-val">' + esc(job.id) + '</span></div>'
  html += '<div class="kv"><span class="kv-key">状态</span><span class="kv-val">' + badge(job.status) + '</span></div>'
  html +=
    '<div class="kv"><span class="kv-key">任务进度</span><span class="kv-val">' +
    fmtInt(job.taskDone) +
    '/' +
    fmtInt(job.taskTotal) +
    '（失败 ' +
    fmtInt(job.taskFailed) +
    '）</span></div>'
  html += '<div class="kv"><span class="kv-key">写入行数</span><span class="kv-val">' + fmtInt(job.rowsWritten) + '</span></div>'
  html += '<div class="kv"><span class="kv-key">创建时间</span><span class="kv-val">' + esc(fmtTime(parseServerTime(job.createdAt))) + '</span></div>'
  html += '<div class="kv"><span class="kv-key">开始时间</span><span class="kv-val">' + esc(fmtTime(parseServerTime(job.startedAt))) + '</span></div>'
  html += '<div class="kv"><span class="kv-key">结束时间</span><span class="kv-val">' + esc(fmtTime(parseServerTime(job.finishedAt))) + '</span></div>'
  html += '</div>'
  if (job.error) {
    html += '<div class="alert danger" style="margin-top:10px">' + esc(job.error) + '</div>'
  }
  html += '<h4 style="margin-top:12px">request</h4>'
  html += '<pre class="json-out">' + esc(prettyJson(job.request)) + '</pre>'
  html += '</div>'

  html += '<div style="padding:0 10px 10px">'
  html += '<h4>任务列表（' + tasks.length + '）</h4>'
  if (!tasks.length) {
    html += '<div class="state">暂无任务明细</div>'
  } else {
    html +=
      '<div class="table-detail-inner"><table><thead><tr>' +
      '<th>id</th><th>taskKey</th><th>状态</th><th>cursorMs</th><th class="num">pageCount</th>' +
      '<th class="num">rowsWritten</th><th class="num">attempts</th><th>truncated</th><th>lastError</th>' +
      '</tr></thead><tbody>'
    html += taskRowsHtml(tasks)
    html += '</tbody></table></div>'
  }
  html += '</div>'
  html += '</div>'
  return html
}
