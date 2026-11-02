import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('../src/api/client', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiRawGet: vi.fn()
}))

import { apiPost } from '../src/api/client'
import BackfillView from '../src/views/BackfillView.vue'
import {
  buildFundingBody,
  buildKlineBody,
  buildRatioBody,
  buildStatsBody,
  clearedRange,
  parsePageLimit,
  quickRange
} from '../src/views/backfill/forms'

const klineBase = {
  mode: 'ids',
  instIds: 'BTC-USDT-SWAP',
  instType: 'SWAP',
  baseCcy: '',
  bars: ['1H'],
  klineTypes: ['CANDLE'],
  start: '',
  end: '',
  pageLimit: ''
}

function mountView() {
  return mount(BackfillView, { global: { plugins: [createPinia()] } })
}

describe('补拉表单参数组装（legacy submit*Backfill 等价）', () => {
  it('K 线 instIds 模式：body 只含 instIds / bars / klineTypes', () => {
    const r = buildKlineBody(klineBase)
    expect(r).toEqual({ ok: true, body: { instIds: ['BTC-USDT-SWAP'], bars: ['1H'], klineTypes: ['CANDLE'] } })
  })

  it('K 线 instType 模式：展开 instType 并 trim baseCcy', () => {
    const r = buildKlineBody({ ...klineBase, mode: 'type', baseCcy: '  BTC ' })
    expect(r).toEqual({ ok: true, body: { instType: 'SWAP', baseCcy: 'BTC', bars: ['1H'], klineTypes: ['CANDLE'] } })
  })

  it('K 线 instIds 为空：toast + 结果盒提示', () => {
    const r = buildKlineBody({ ...klineBase, instIds: '  ' })
    expect(r).toEqual({ ok: false, toast: '按 instIds 模式需要至少填写一个合约 ID', box: '请填写 instIds，或切换到「按 instType」模式' })
  })

  it('K 线 bars / klineTypes 为空：分别报必填', () => {
    expect(buildKlineBody({ ...klineBase, bars: [] })).toEqual({
      ok: false,
      toast: 'bars 至少选择一个周期',
      box: 'bars 是必填项，请至少勾选一个周期'
    })
    expect(buildKlineBody({ ...klineBase, klineTypes: [] })).toEqual({
      ok: false,
      toast: 'klineTypes 至少选择一个类型',
      box: 'klineTypes 是必填项，请至少勾选一个类型'
    })
  })

  it('开始时间不早于结束时间：只 toast，不动结果盒', () => {
    const r = buildKlineBody({ ...klineBase, start: '2026-01-02T00:00', end: '2026-01-01T00:00' })
    expect(r).toEqual({ ok: false, toast: '开始时间必须早于结束时间' })
  })

  it('pageLimit：1~300 整数校验 + 填入 body', () => {
    expect(parsePageLimit('')).toEqual({ ok: true, value: undefined })
    expect(parsePageLimit('250')).toEqual({ ok: true, value: 250 })
    expect(parsePageLimit('301')).toEqual({ ok: false, toast: 'pageLimit 需为 1 ~ 300 的整数' })
    expect(parsePageLimit('1.5')).toEqual({ ok: false, toast: 'pageLimit 需为 1 ~ 300 的整数' })
    const r = buildKlineBody({ ...klineBase, pageLimit: '100' })
    expect(r.ok && (r.body as any).pageLimit).toBe(100)
  })

  it('K 线起止时间转毫秒并保持字段顺序（startMs 在 endMs 前）', () => {
    const r = buildKlineBody({ ...klineBase, start: '2026-01-01T00:00', end: '2026-01-02T00:00' })
    expect(r.ok).toBe(true)
    if (!r.ok) return
    const keys = Object.keys(r.body)
    expect(keys.indexOf('startMs')).toBeGreaterThan(keys.indexOf('klineTypes'))
    expect(keys.indexOf('endMs')).toBe(keys.indexOf('startMs') + 1)
    expect(typeof r.body.startMs).toBe('number')
  })

  it('资金费率：instType 模式与 instIds 必填', () => {
    expect(buildFundingBody({ mode: 'type', instIds: '', instType: 'FUTURES', start: '', end: '' })).toEqual({
      ok: true,
      body: { instType: 'FUTURES' }
    })
    expect(buildFundingBody({ mode: 'ids', instIds: '', instType: 'SWAP', start: '', end: '' })).toEqual({
      ok: false,
      toast: '按 instIds 模式需要至少填写一个合约 ID',
      box: '请填写 instIds，或切换到「按 instType」模式'
    })
  })

  it('多空比：CCY 类型缺 ccys / INST 类型缺 scopes 分别报错', () => {
    const r1 = buildRatioBody({ ratioTypes: ['LSR_ACCOUNT_CCY'], ccys: '', scopes: '', periods: ['1H'], start: '', end: '' })
    expect(r1).toEqual({
      ok: false,
      toast: '所选 ratioTypes 含 CCY 类型，必须填写 ccys',
      box: 'CCY 类型（LSR_ACCOUNT_CCY）必须填写 ccys'
    })
    const r2 = buildRatioBody({ ratioTypes: ['TOP_TRADER_POSITION'], ccys: 'BTC', scopes: '', periods: ['1H'], start: '', end: '' })
    expect(r2).toEqual({
      ok: false,
      toast: '所选 ratioTypes 含 INST 类型，必须填写 scopes',
      box: 'INST 类型（合约维度）必须填写 scopes'
    })
  })

  it('多空比：两类都勾时 ccys 与 scopes 都进 body，periods 必填', () => {
    const r = buildRatioBody({
      ratioTypes: ['LSR_ACCOUNT_CCY', 'LSR_ACCOUNT_CONTRACT'],
      ccys: 'BTC, ETH',
      scopes: 'BTC-USDT-SWAP',
      periods: ['1H'],
      start: '',
      end: ''
    })
    expect(r).toEqual({
      ok: true,
      body: { ratioTypes: ['LSR_ACCOUNT_CCY', 'LSR_ACCOUNT_CONTRACT'], ccys: ['BTC', 'ETH'], scopes: ['BTC-USDT-SWAP'], periods: ['1H'] }
    })
    const bad = buildRatioBody({ ratioTypes: ['LSR_ACCOUNT_CCY'], ccys: 'BTC', scopes: '', periods: [], start: '', end: '' })
    expect(bad).toEqual({ ok: false, toast: 'periods 至少选择一个周期', box: 'periods 是必填项' })
  })

  it('市场统计：kinds 留空 = 不带 kinds，但 OPEN_INTEREST_VOLUME 仍需 ccys', () => {
    const bad = buildStatsBody({ kinds: [], ccys: '', instIds: '', periods: [], start: '', end: '' })
    expect(bad).toEqual({
      ok: false,
      toast: 'OPEN_INTEREST_VOLUME 需要 ccys',
      box: '所选 kinds 含 OPEN_INTEREST_VOLUME，必须填写 ccys'
    })
    const ok = buildStatsBody({ kinds: [], ccys: 'BTC', instIds: '', periods: ['1H'], start: '', end: '' })
    expect(ok).toEqual({ ok: true, body: { ccys: ['BTC'], periods: ['1H'] } })
  })

  it('市场统计：只勾 OPEN_INTEREST 时不强制 ccys', () => {
    const r = buildStatsBody({ kinds: ['OPEN_INTEREST'], ccys: '', instIds: 'BTC-USDT-SWAP', periods: [], start: '', end: '' })
    expect(r).toEqual({ ok: true, body: { kinds: ['OPEN_INTEREST'], instIds: ['BTC-USDT-SWAP'] } })
  })

  it('快捷时间：近 N 天填充 start/end（UTC 无关，取本地格式化）', () => {
    const now = new Date(2026, 0, 8, 12, 30).getTime()
    const q = quickRange(7, now)
    expect(q.end).toBe('2026-01-08T12:30')
    expect(q.start).toBe('2026-01-01T12:30')
    expect(q.toast).toBe('已填充最近 7 天的时间范围')
    expect(clearedRange()).toEqual({ start: '', end: '' })
  })
})

describe('BackfillView 组件', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
  })

  it('提交 K 线表单：POST /api/v1/backfill/klines 并在结果盒显示 jobId/taskTotal', async () => {
    ;(apiPost as unknown as { mockResolvedValue: (v: unknown) => void }).mockResolvedValue({ jobId: 'J1', taskTotal: 1234 })
    const w = mountView()
    await w.findAll('form')[0].trigger('submit')
    await flushPromises()
    expect(apiPost).toHaveBeenCalledWith('/api/v1/backfill/klines', {
      instIds: ['BTC-USDT-SWAP'],
      bars: ['1H'],
      klineTypes: ['CANDLE']
    })
    const box = w.find('.result-box.is-ok')
    expect(box.exists()).toBe(true)
    expect(box.text()).toContain('K 线补拉')
    expect(box.text()).toContain('提交成功（HTTP 202）')
    expect(box.text()).toContain('J1')
    expect(box.text()).toContain('1,234')
    w.unmount()
  })

  it('提交失败：结果盒显示 提交失败：原因', async () => {
    ;(apiPost as unknown as { mockRejectedValue: (v: unknown) => void }).mockRejectedValue(new Error('boom'))
    const w = mountView()
    await w.findAll('form')[0].trigger('submit')
    await flushPromises()
    expect(w.find('.result-box.is-err').text()).toBe('提交失败：boom')
    w.unmount()
  })

  it('校验失败不调接口，只在结果盒提示必填', async () => {
    const w = mountView()
    const form = w.findAll('form')[0]
    await form.find('textarea').setValue('')
    await form.trigger('submit')
    await flushPromises()
    expect(apiPost).not.toHaveBeenCalled()
    expect(w.find('.result-box.is-err').text()).toBe('请填写 instIds，或切换到「按 instType」模式')
    w.unmount()
  })

  it('K 线表单默认回填：instIds=BTC-USDT-SWAP、bars 勾 1H、klineTypes 勾 CANDLE', async () => {
    const w = mountView()
    const klineForm = w.findAll('form')[0]
    expect((klineForm.find('textarea').element as HTMLTextAreaElement).value).toBe('BTC-USDT-SWAP')
    const barsChecked = klineForm.findAll('.check-grid')[0].findAll('input[type="checkbox"]').filter((c) => (c.element as HTMLInputElement).checked)
    expect(barsChecked.map((c) => (c.element as HTMLInputElement).value)).toEqual(['1H'])
    w.unmount()
  })
})