import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('../src/api/client', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiRawGet: vi.fn()
}))

import { apiGet, apiPost } from '../src/api/client'
import SyncView from '../src/views/SyncView.vue'
import { useUiStore } from '../src/stores/ui'
import {
  BASIC_DEFAULTS,
  SYNC_INST_TYPES,
  SYNC_KLINE_TYPES,
  SYNC_RATIO_TYPES,
  basicConfirmHtml,
  buildBasicBody,
  buildInstrumentBody,
  errorBoxHtml,
  instrumentConfirmHtml,
  instrumentTypeText,
  parseIntField,
  renderSyncResultHtml,
  type BasicSyncInput
} from '../src/views/sync/sync'

const post = vi.mocked(apiPost)
const get = vi.mocked(apiGet)

function mountView() {
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(SyncView, { global: { plugins: [pinia] } })
}

const basicBase: BasicSyncInput = {
  instTypes: ['SWAP'],
  includeOptions: false,
  families: '',
  instType: 'SWAP',
  maxInstruments: '20',
  maxCcys: '10',
  days: '7',
  bars: '1H',
  klineTypes: ['CANDLE'],
  includeFunding: true,
  includeRatios: true,
  ratioTypes: ['LSR_ACCOUNT_CCY', 'TOP_TRADER_POSITION']
}

beforeEach(() => {
  post.mockReset()
  get.mockReset()
  /* 增量状态卡片在 onMounted 就会拉一次，默认给 null（不渲染状态格） */
  get.mockResolvedValue(null)
})

describe('同步品种（legacy submitSyncInstruments 等价）', () => {
  it('instTypes 全选时 body 只有 instTypes / includeOptions', () => {
    expect(buildInstrumentBody({ instTypes: [...SYNC_INST_TYPES], includeOptions: true, families: '' })).toEqual({
      instTypes: ['SPOT', 'MARGIN', 'SWAP', 'FUTURES', 'OPTION'],
      includeOptions: true
    })
  })

  it('families 去重去空白后按数组提交', () => {
    const body = buildInstrumentBody({ instTypes: [], includeOptions: false, families: 'BTC-USD, ETH-USD ETH-USD' })
    expect(body.families).toEqual(['BTC-USD', 'ETH-USD'])
    expect(body.instTypes).toEqual([])
    expect(body.includeOptions).toBe(false)
  })

  it('instTypes 为空时的确认文案与 legacy 一致', () => {
    expect(instrumentTypeText([])).toBe('（空 → 后端同步全部 5 类）')
    expect(instrumentTypeText(['SPOT', 'SWAP'])).toBe('SPOT、SWAP')
  })

  it('确认框列出 instTypes / includeOptions / families，且转义 families', () => {
    const html = instrumentConfirmHtml({ instTypes: ['SWAP'], includeOptions: true, families: '<b>x</b>' })
    expect(html).toContain('POST /api/v1/sync/instruments')
    expect(html).toContain('<li>instTypes：SWAP</li>')
    expect(html).toContain('是（逐家族拉取期权，最慢）')
    expect(html).toContain('&lt;b&gt;x&lt;/b&gt;')
    expect(instrumentConfirmHtml({ instTypes: [], includeOptions: false, families: '' })).toContain('families：（自动推断）')
  })
})

describe('组合同步（legacy submitSyncBasic 等价）', () => {
  it('数字型字段的取值/非法/留空三态', () => {
    expect(parseIntField('', 1, 500, 'maxInstruments')).toEqual({ ok: true, value: undefined })
    expect(parseIntField('20', 1, 500, 'maxInstruments')).toEqual({ ok: true, value: 20 })
    expect(parseIntField('500', 1, 500, 'maxInstruments')).toEqual({ ok: true, value: 500 })
    expect(parseIntField('0', 1, 500, 'maxInstruments')).toEqual({ ok: false, toast: 'maxInstruments 需为 1 ~ 500 的整数' })
    expect(parseIntField('1.5', 1, 500, 'maxInstruments')).toEqual({ ok: false, toast: 'maxInstruments 需为 1 ~ 500 的整数' })
    expect(parseIntField('abc', 1, 100, 'maxCcys')).toEqual({ ok: false, toast: 'maxCcys 需为 1 ~ 100 的整数' })
  })

  it('默认值组装出完整 body', () => {
    const out = buildBasicBody(basicBase)
    expect(out.ok).toBe(true)
    if (!out.ok) return
    expect(out.body).toEqual({
      instTypes: ['SWAP'],
      includeOptions: false,
      instType: 'SWAP',
      maxInstruments: 20,
      maxCcys: 10,
      days: 7,
      bars: ['1H'],
      klineTypes: ['CANDLE'],
      includeFunding: true,
      includeRatios: true,
      ratioTypes: ['LSR_ACCOUNT_CCY', 'TOP_TRADER_POSITION']
    })
  })

  it('留空的可选字段与空数组不进入 body', () => {
    const out = buildBasicBody({
      ...basicBase,
      instTypes: [],
      families: '',
      instType: '',
      maxInstruments: '',
      maxCcys: '',
      days: '',
      bars: '',
      klineTypes: [],
      ratioTypes: []
    })
    expect(out.ok).toBe(true)
    if (!out.ok) return
    expect(Object.keys(out.body)).toEqual(['includeOptions', 'includeFunding', 'includeRatios'])
  })

  it('数字越界时返回 toast 且不产出 body', () => {
    const out = buildBasicBody({ ...basicBase, days: '3650' })
    expect(out).toEqual({ ok: false, toast: 'days 需为 1 ~ 365 的整数' })
    expect(buildBasicBody({ ...basicBase, maxCcys: '101' })).toEqual({
      ok: false,
      toast: 'maxCcys 需为 1 ~ 100 的整数'
    })
  })

  it('确认文案里未填的数字显示默认值', () => {
    const html = basicConfirmHtml({ ...basicBase, maxInstruments: '', days: '' })
    expect(html).toContain('POST /api/v1/sync/basic')
    expect(html).toContain('<li>maxInstruments：默认 20</li>')
    expect(html).toContain('<li>days：默认 7</li>')
    expect(html).toContain('includeFunding / includeRatios：是 / 是')
    expect(basicConfirmHtml({ ...basicBase, instType: '' })).toContain('<li>instType：（默认）</li>')
  })
})

describe('同步结果盒（legacy renderSyncResult 等价）', () => {
  it('标量字段拼成 muted 一览，JSON 原文进 json-out', () => {
    const html = renderSyncResultHtml({ instruments: 5, coins: 3 }, '品种同步', 1234)
    expect(html).toContain('<strong>品种同步</strong> 完成，耗时 1.2 秒')
    expect(html).toContain('instruments：<strong>5</strong>')
    expect(html).toContain('coins：<strong>3</strong>')
    expect(html).toContain('<pre class="json-out">')
    expect(html).toContain('&quot;instruments&quot;: 5')
  })

  it('数组 / null 不生成标量一览，只给 JSON', () => {
    expect(renderSyncResultHtml([1, 2], '组合同步', 0)).not.toContain('class="muted"')
    expect(renderSyncResultHtml(null, '组合同步', 0)).toContain('<pre class="json-out">-</pre>')
  })

  it('错误盒转义消息', () => {
    expect(errorBoxHtml('boom <x>')).toBe('<div class="result-box is-err">boom &lt;x&gt;</div>')
  })

  it('常量与 legacy 一致', () => {
    expect(SYNC_KLINE_TYPES).toEqual(['CANDLE', 'MARK'])
    expect(SYNC_RATIO_TYPES).toEqual(['LSR_ACCOUNT_CCY', 'LSR_ACCOUNT_CONTRACT', 'TOP_TRADER_ACCOUNT', 'TOP_TRADER_POSITION'])
    expect(BASIC_DEFAULTS).toEqual({ maxInstruments: '20', maxCcys: '10', days: '7', bars: '1H' })
  })
})

describe('同步页组件', () => {
  it('同步品种：确认后 POST 全 5 类并显示结果盒', async () => {
    post.mockResolvedValue({ instruments: 1200, coins: 40 })
    const w = mountView()
    const ui = useUiStore()
    await w.find('#form-sync-instruments').trigger('submit')
    expect(ui.modal.visible).toBe(true)
    expect(ui.modal.title).toBe('确认全量同步品种')
    expect(post).not.toHaveBeenCalled()
    ui.resolveModal(true)
    await flushPromises()
    expect(post).toHaveBeenCalledWith('/api/v1/sync/instruments', {
      instTypes: ['SPOT', 'MARGIN', 'SWAP', 'FUTURES', 'OPTION'],
      includeOptions: true
    })
    const box = w.find('#sync-instruments-result')
    expect(box.text()).toContain('品种同步 完成，耗时')
    expect(box.text()).toContain('instruments：1200')
    expect(ui.toasts.some((t) => t.msg === '品种同步完成' && t.kind === 'ok')).toBe(true)
    w.unmount()
  })

  it('同步品种：取消确认则不下发请求', async () => {
    const w = mountView()
    const ui = useUiStore()
    await w.find('#form-sync-instruments').trigger('submit')
    ui.resolveModal(false)
    await flushPromises()
    expect(post).not.toHaveBeenCalled()
    w.unmount()
  })

  it('组合同步：非法数字先 toast 且不弹确认框', async () => {
    const w = mountView()
    const ui = useUiStore()
    await w.find('#sync-basic-max-ccys').setValue('0')
    await w.find('#form-sync-basic').trigger('submit')
    await flushPromises()
    expect(ui.modal.visible).toBe(false)
    expect(post).not.toHaveBeenCalled()
    expect(ui.toasts.some((t) => t.msg === 'maxCcys 需为 1 ~ 100 的整数' && t.kind === 'err')).toBe(true)
    w.unmount()
  })

  it('组合同步：确认后按 body 提交并显示等待/结果', async () => {
    post.mockResolvedValue({ jobId: 'job-9', taskTotal: 3 })
    const w = mountView()
    const ui = useUiStore()
    await w.find('#form-sync-basic').trigger('submit')
    expect(ui.modal.visible).toBe(true)
    expect(ui.modal.title).toBe('确认组合同步')
    expect(ui.modal.bodyHtml).toContain('POST /api/v1/sync/basic')
    ui.resolveModal(true)
    await flushPromises()
    expect(post).toHaveBeenCalledWith('/api/v1/sync/basic', {
      instTypes: ['SWAP'],
      includeOptions: false,
      instType: 'SWAP',
      maxInstruments: 20,
      maxCcys: 10,
      days: 7,
      bars: ['1H'],
      klineTypes: ['CANDLE'],
      includeFunding: true,
      includeRatios: true,
      ratioTypes: ['LSR_ACCOUNT_CCY', 'TOP_TRADER_POSITION']
    })
    const box = w.find('#sync-basic-result')
    expect(box.text()).toContain('组合同步 完成，耗时')
    expect(box.text()).toContain('jobId：job-9')
    expect(ui.toasts.some((t) => t.msg === '组合同步完成' && t.kind === 'ok')).toBe(true)
    w.unmount()
  })

  it('接口失败时显示错误盒并 toast', async () => {
    post.mockRejectedValue(new Error('boom'))
    const w = mountView()
    const ui = useUiStore()
    await w.find('#form-sync-instruments').trigger('submit')
    ui.resolveModal(true)
    await flushPromises()
    expect(w.find('#sync-instruments-result .result-box.is-err').text()).toBe('同步失败：boom')
    expect(ui.toasts.some((t) => t.msg === '品种同步失败：boom' && t.kind === 'err')).toBe(true)
    w.unmount()
  })
})
describe('定时增量补数据卡片（GET /api/v1/sync/incremental）', () => {
  const status = {
    enabled: true,
    intervalMs: 300000,
    lookbackMs: 3600000,
    lagMs: 60000,
    bars: ['1m', '5m'],
    klineTypes: ['CANDLE'],
    maxInstIds: 20,
    lastRunAtMs: 1_700_000_000_000,
    lastNote: '提交 1 个作业（1 个游标推进到 end），播种 0 个游标',
    lastJobId: 7,
    cursorCount: 12,
    cursors: [{ key: 'kline|CANDLE|BTC-USDT-SWAP|1m', lastTsMs: 1_700_000_000_000 }],
    runningKlineJobs: 1,
    pendingJobs: 3
  }

  it('渲染开关 / 间隔 / 最近运行 / 游标数 / RUNNING 作业数', async () => {
    get.mockResolvedValue(status)
    const w = mountView()
    await flushPromises()
    const card = w.find('#card-sync-incremental')
    expect(card.exists()).toBe(true)
    expect(card.find('.card-head h2').text()).toBe('定时增量补数据')
    expect(card.text()).toContain('已启用')
    expect(card.text()).toContain('5 分钟')
    expect(card.text()).toContain('游标数')
    expect(card.text()).toContain('12')
    expect(card.text()).toContain('RUNNING 作业数')
    expect(card.text()).toContain('待推进作业数')
    expect(w.find('#sync-incremental-pending').text()).toBe('3')
    expect(w.find('#sync-incremental-note').text()).toContain('提交 1 个作业')
    expect(get).toHaveBeenCalledWith('/api/v1/sync/incremental')
    w.unmount()
  })

  it('关闭时显示「已关闭」', async () => {
    get.mockResolvedValue({ ...status, enabled: false, intervalMs: 90000, cursorCount: 0, runningKlineJobs: 0 })
    const w = mountView()
    await flushPromises()
    expect(w.find('#card-sync-incremental').text()).toContain('已关闭')
    expect(w.find('#card-sync-incremental').text()).toContain('1.5 分钟')
    w.unmount()
  })

  it('点击「立即补增量」POST 增量补数据路径，成功后 toast 并刷新状态', async () => {
    get.mockResolvedValue(status)
    post.mockResolvedValue({
      submitted: true,
      jobs: [{ jobId: 7, taskTotal: 2, bar: '1m', klineType: 'CANDLE' }],
      cursorAdvanced: 2,
      seeded: 0,
      note: '提交 1 个作业（2 个游标推进到 end），播种 0 个游标'
    })
    const w = mountView()
    const ui = useUiStore()
    await flushPromises()
    await w.find('#sync-incremental-run').trigger('click')
    await flushPromises()
    expect(post).toHaveBeenCalledWith('/api/v1/sync/incremental/run', null)
    expect(w.find('#sync-incremental-result').text()).toContain('增量补数据 完成，耗时')
    expect(w.find('#sync-incremental-result').text()).toContain('submitted：true')
    expect(
      ui.toasts.some(
        (t) => t.msg === '已提交 1 个增量作业（完成后自动推进水位），本轮对账推进 2 个游标' && t.kind === 'ok'
      )
    ).toBe(true)
    /* 成功后重拉一次状态 */
    expect(get.mock.calls.length).toBeGreaterThan(1)
    w.unmount()
  })

  it('跳过（已有 K 线作业在跑）时 toast 说明原因', async () => {
    get.mockResolvedValue(status)
    post.mockResolvedValue({ submitted: false, skippedReason: 'kline-job-running', jobId: 9, jobs: [], seeded: 0, cursorAdvanced: 0, note: '已有 KLINE 作业在跑' })
    const w = mountView()
    const ui = useUiStore()
    await flushPromises()
    await w.find('#sync-incremental-run').trigger('click')
    await flushPromises()
    expect(ui.toasts.some((t) => t.msg === '未提交作业：已有 K 线补拉作业在跑，本轮跳过')).toBe(true)
    w.unmount()
  })

  it('只对账推进（无新提交）时 toast 说明推进了几个游标', async () => {
    get.mockResolvedValue(status)
    post.mockResolvedValue({ submitted: false, skippedReason: null, jobId: null, jobs: [], seeded: 0, cursorAdvanced: 4, note: '本轮对账推进 4 个游标' })
    const w = mountView()
    const ui = useUiStore()
    await flushPromises()
    await w.find('#sync-incremental-run').trigger('click')
    await flushPromises()
    expect(ui.toasts.some((t) => t.msg === '本轮未提交作业，对账推进 4 个游标')).toBe(true)
    w.unmount()
  })

  it('只播种（无缺口）时 toast 说明已播种', async () => {
    get.mockResolvedValue(status)
    post.mockResolvedValue({ submitted: false, skippedReason: null, jobId: null, jobs: [], seeded: 3, cursorAdvanced: 0, note: '播种 3 个游标' })
    const w = mountView()
    const ui = useUiStore()
    await flushPromises()
    await w.find('#sync-incremental-run').trigger('click')
    await flushPromises()
    expect(ui.toasts.some((t) => t.msg === '已播种 3 个游标（本轮无缺口，下一轮开始补）')).toBe(true)
    w.unmount()
  })

  it('接口失败时显示错误盒并 toast', async () => {
    get.mockResolvedValue(status)
    post.mockRejectedValue(new Error('boom'))
    const w = mountView()
    const ui = useUiStore()
    await flushPromises()
    await w.find('#sync-incremental-run').trigger('click')
    await flushPromises()
    expect(w.find('#sync-incremental-result .result-box.is-err').text()).toBe('同步失败：boom')
    expect(ui.toasts.some((t) => t.msg === '增量补数据失败：boom' && t.kind === 'err')).toBe(true)
    w.unmount()
  })
})
