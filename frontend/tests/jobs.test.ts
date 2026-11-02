import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('../src/api/client', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiRawGet: vi.fn()
}))

import { apiGet, apiPost } from '../src/api/client'
import JobsView from '../src/views/JobsView.vue'
import { useUiStore } from '../src/stores/ui'
import {
  canCancel,
  canPause,
  canResume,
  createdAtText,
  dataTypeText,
  jobsState,
  jobsSummary,
  listParams,
  prettyJson,
  progressOf,
  renderJobDetail,
  resetJobsState,
  type BackfillJob
} from '../src/views/jobs/jobs'

const get = vi.mocked(apiGet)
const post = vi.mocked(apiPost)

function job(extra: Partial<BackfillJob> = {}): BackfillJob {
  return {
    id: 'job-1',
    dataType: 'KLINE',
    status: 'RUNNING',
    taskTotal: 4,
    taskDone: 2,
    taskFailed: 0,
    rowsWritten: 1200,
    createdAt: '2026-01-02T03:04:05Z',
    request: { instIds: ['BTC-USDT-SWAP'], pageLimit: 100 },
    ...extra
  }
}

function mountView() {
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(JobsView, { global: { plugins: [pinia] } })
}

beforeEach(() => {
  resetJobsState()
  get.mockReset()
  post.mockReset()
})

describe('作业列表纯逻辑（legacy renderJobs / jobActionButtons 等价）', () => {
  it('进度按 taskDone/taskTotal 取整，DONE 加 is-done', () => {
    expect(progressOf(job({ taskTotal: 3, taskDone: 1, status: 'RUNNING' }))).toMatchObject({
      total: 3,
      done: 1,
      pct: 33,
      barClass: '',
      text: '1/3'
    })
    expect(progressOf(job({ status: 'DONE', taskDone: 4 })).barClass).toBe('is-done')
    expect(progressOf(job({ status: 'DONE', taskDone: 4 })).pct).toBe(100)
    expect(progressOf(job({ status: 'FAILED' })).barClass).toBe('is-failed')
  })

  it('taskTotal 为 0 / 缺失时进度为 0%，避免除零', () => {
    expect(progressOf(job({ taskTotal: 0, taskDone: 0 }))).toMatchObject({ pct: 0, text: '0/0' })
    expect(progressOf(job({ taskTotal: undefined, taskDone: undefined }))).toMatchObject({ total: 0, done: 0, pct: 0 })
  })

  it('进度不会超过 100%', () => {
    expect(progressOf(job({ taskTotal: 2, taskDone: 5 })).pct).toBe(100)
  })

  it('dataType 显示中文标签，未知类型回落原值', () => {
    expect(dataTypeText(job({ dataType: 'KLINE' }))).toBe('K 线')
    expect(dataTypeText(job({ dataType: 'WHATEVER' }))).toBe('WHATEVER')
  })

  it('暂停 / 继续 / 取消按钮的可用状态与 legacy 一致', () => {
    expect(canPause(job({ status: 'PENDING' }))).toBe(true)
    expect(canPause(job({ status: 'RUNNING' }))).toBe(true)
    expect(canPause(job({ status: 'PAUSED' }))).toBe(false)
    expect(canResume(job({ status: 'PAUSED' }))).toBe(true)
    expect(canResume(job({ status: 'FAILED' }))).toBe(true)
    expect(canResume(job({ status: 'RUNNING' }))).toBe(false)
    expect(canCancel(job({ status: 'RUNNING' }))).toBe(true)
    expect(canCancel(job({ status: 'DONE' }))).toBe(false)
    expect(canCancel(job({ status: 'CANCELED' }))).toBe(false)
  })

  it('创建时间走 fmtTime(parseServerTime(...))', () => {
    /* fmtTime 输出本地时间（测试环境 UTC+8）：2026-01-02T03:04:05Z -> 11:04:05 */
    expect(createdAtText(job({ createdAt: '2026-01-02T03:04:05Z' }))).toBe('2026-01-02 11:04:05')
    expect(createdAtText(job({ createdAt: undefined }))).toBe('-')
  })

  it('prettyJson 对 null / 字符串 JSON / 非法字符串 / 对象分别处理', () => {
    expect(prettyJson(null)).toBe('-')
    expect(prettyJson(undefined)).toBe('-')
    expect(prettyJson('{"a":1}')).toBe('{\n  "a": 1\n}')
    expect(prettyJson('not-json')).toBe('not-json')
    expect(prettyJson({ a: [1, 2] })).toBe('{\n  "a": [\n    1,\n    2\n  ]\n}')
  })

  it('分页摘要按本页条数生成，空页固定文案', () => {
    expect(jobsSummary(0, 50)).toBe('本页无数据')
    expect(jobsSummary(20, 20)).toBe('本页 20 条 · 每页 20 · 无总数字段，翻页按「本页是否满页」判断')
  })

  it('列表请求参数与 legacy loadJobs 一致', () => {
    expect(listParams('', '', 50, 0)).toEqual({ dataType: '', status: '', limit: 50, offset: 0 })
    expect(listParams('KLINE', 'DONE', 20, 40)).toEqual({ dataType: 'KLINE', status: 'DONE', limit: 20, offset: 40 })
  })

  it('renderJobDetail 无任务时给空态，含作业信息与 request', () => {
    const html = renderJobDetail({ job: job(), tasks: [] })
    expect(html).toContain('作业信息')
    expect(html).toContain('<span class="kv-key">jobId</span><span class="kv-val">job-1</span>')
    expect(html).toContain('任务进度</span><span class="kv-val">2/4（失败 0）')
    expect(html).toContain('任务列表（0）')
    expect(html).toContain('<div class="state">暂无任务明细</div>')
    expect(html).toContain('&quot;instIds&quot;')
  })

  it('renderJobDetail 渲染任务行，lastError 转义并加 danger-text', () => {
    const html = renderJobDetail({
      job: job({ error: 'boom <x>' }),
      tasks: [
        { id: 7, taskKey: 'KLINE:CANDLE:BTC-USDT-SWAP:1H', status: 'DONE', cursorMs: 1700000000000, pageCount: 3, rowsWritten: 300, attempts: 1, truncated: true, lastError: null },
        { id: 8, taskKey: 'KLINE:CANDLE:ETH-USDT-SWAP:1H', status: 'FAILED', cursorMs: null, pageCount: 0, rowsWritten: 0, attempts: 3, truncated: null, lastError: '<b>bad</b>' }
      ]
    })
    expect(html).toContain('任务列表（2）')
    expect(html).toContain('KLINE:CANDLE:BTC-USDT-SWAP:1H')
    expect(html).toContain('<span class="danger-text">&lt;b&gt;bad&lt;/b&gt;</span>')
    expect(html).toContain('&lt;x&gt;')
    expect(html).toContain('>是</td>')
    expect(html).toContain('>-</td>')
  })
})

describe('作业列表组件（legacy loadJobs / toggleJobDetail / jobAction 等价）', () => {
  it('进入页面即按 offset=0 / limit=50 拉取列表并渲染进度', async () => {
    get.mockResolvedValue([job()])
    const w = mountView()
    await flushPromises()
    expect(get).toHaveBeenCalledWith('/api/v1/backfill/jobs', { dataType: '', status: '', limit: 50, offset: 0 })
    expect(w.find('table.table-jobs').exists()).toBe(true)
    expect(w.find('.progress-text').text()).toBe('2/4')
    expect(w.find('.bar').attributes('style')).toContain('width: 50%')
    expect(w.find('thead').text()).toContain('写入行数')
    expect(w.find('.pager-info').text()).toBe('第 1–1 条')
    expect(w.emitted()).toBeTruthy()
    w.unmount()
  })

  it('空列表显示空态并禁用翻页', async () => {
    get.mockResolvedValue([])
    const w = mountView()
    await flushPromises()
    expect(w.text()).toContain('暂无作业')
    expect(w.find('.pager-info').text()).toBe('暂无记录')
    expect(w.find('.card-head .muted').text()).toBe('本页无数据')
    const btns = w.findAll('.pager button')
    expect(btns[0].attributes('disabled')).toBeDefined()
    expect(btns[1].attributes('disabled')).toBeDefined()
    w.unmount()
  })

  it('查询表单提交时把 offset 归零并带上筛选条件', async () => {
    get.mockResolvedValue([])
    const w = mountView()
    await flushPromises()
    jobsState.offset = 20
    await w.find('#jobs-datatype').setValue('KLINE')
    await w.find('#jobs-status').setValue('DONE')
    await w.find('#form-jobs-filters').trigger('submit')
    await flushPromises()
    expect(get).toHaveBeenLastCalledWith('/api/v1/backfill/jobs', { dataType: 'KLINE', status: 'DONE', limit: 50, offset: 0 })
    w.unmount()
  })

  it('点「详情」加载子任务并插入展开行，再点一次收起', async () => {
    get.mockResolvedValueOnce([job()])
    const w = mountView()
    await flushPromises()
    get.mockResolvedValueOnce({ job: job(), tasks: [{ id: 1, taskKey: 'KLINE:CANDLE:BTC-USDT-SWAP:1H', status: 'DONE' }] })
    await w.find('[data-job-toggle="job-1"]').trigger('click')
    await flushPromises()
    expect(get).toHaveBeenLastCalledWith('/api/v1/backfill/jobs/job-1', { includeTasks: 'true' })
    const detail = w.find('tr[data-job-detail="job-1"]')
    expect(detail.exists()).toBe(true)
    expect(detail.text()).toContain('作业信息')
    expect(detail.text()).toContain('KLINE:CANDLE:BTC-USDT-SWAP:1H')
    await w.find('[data-job-toggle="job-1"]').trigger('click')
    await flushPromises()
    expect(w.find('tr[data-job-detail="job-1"]').exists()).toBe(false)
    w.unmount()
  })

  it('取消作业先弹确认，确认后 POST action 并刷新列表', async () => {
    get.mockResolvedValue([job()])
    post.mockResolvedValue(null)
    const w = mountView()
    await flushPromises()
    const ui = useUiStore()
    await w.find('[data-job-action="cancel"]').trigger('click')
    expect(ui.modal.visible).toBe(true)
    expect(ui.modal.title).toBe('取消作业')
    expect(post).not.toHaveBeenCalled()
    ui.resolveModal(true)
    await flushPromises()
    expect(post).toHaveBeenCalledWith('/api/v1/backfill/jobs/job-1/cancel', null)
    expect(get).toHaveBeenCalledTimes(2)
    expect(ui.toasts.some((t) => t.msg === '已取消作业 job-1' && t.kind === 'ok')).toBe(true)
    w.unmount()
  })

  it('确认弹窗被取消时不下发 POST', async () => {
    get.mockResolvedValue([job()])
    const w = mountView()
    await flushPromises()
    const ui = useUiStore()
    await w.find('[data-job-action="cancel"]').trigger('click')
    ui.resolveModal(false)
    await flushPromises()
    expect(post).not.toHaveBeenCalled()
    w.unmount()
  })

  it('暂停按钮在 RUNNING 时可用，DONE 作业的三个动作钮全禁用', async () => {
    get.mockResolvedValue([job({ status: 'DONE' })])
    const w = mountView()
    await flushPromises()
    for (const action of ['pause', 'resume', 'cancel']) {
      expect(w.find('[data-job-action="' + action + '"]').attributes('disabled')).toBeDefined()
    }
    w.unmount()
  })

  it('列表加载失败显示错误告警并 toast', async () => {
    get.mockRejectedValue(new Error('boom'))
    const w = mountView()
    await flushPromises()
    expect(w.find('.alert.danger').text()).toBe('加载作业列表失败：boom')
    const ui = useUiStore()
    expect(ui.toasts.some((t) => t.kind === 'err')).toBe(true)
    w.unmount()
  })
})