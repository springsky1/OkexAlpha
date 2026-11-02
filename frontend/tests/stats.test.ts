import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('../src/api/client', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiRawGet: vi.fn()
}))

import { apiGet } from '../src/api/client'
import { EP } from '../src/api/endpoints'
import StatsView from '../src/views/StatsView.vue'
import {
  buildOiParams,
  buildOivParams,
  buildRatioParams,
  buildTakerParams,
  oiSummary,
  oivSummary,
  ratioHint,
  ratioSummary,
  takerSummary,
  readIntField
} from '../src/views/stats/statsQuery'
import { useUiStore } from '../src/stores/ui'

const api = apiGet as unknown as {
  mockResolvedValue: (v: unknown) => void
  mockRejectedValue: (v: unknown) => void
  mockImplementation: (fn: (path: any, params?: any) => Promise<unknown>) => void
  mock: { calls: any[][] }
}

const ratioRow = { tsMs: 1700000000000, ratioType: 'LSR_ACCOUNT_CCY', scopeKind: 'CCY', scope: 'BTC', period: '1H', longShortRatio: 1.2345 }
const oivRow = { tsMs: 1700000000000, ccy: 'BTC', period: '1H', oi: 123456, vol: 987654321 }
const takerRow = { tsMs: 1700000000000, scopeKind: 'CCY', scope: 'BTC', marketType: 'CONTRACT', period: '1H', sellVol: 111, buyVol: 222 }
const oiRow = { tsMs: 1700000000000, instId: 'BTC-USDT-SWAP', instType: 'SWAP', oi: 1, oiCcy: 2, oiUsd: 3 }

function mountView() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const w = mount(StatsView, { global: { plugins: [pinia] } })
  return { w, ui: useUiStore() }
}

/* 按路径返回不同的行，模拟真实后端 */
function routeByPath() {
  api.mockImplementation((path: any) => {
    if (path === EP.openInterestVolume) return Promise.resolve([oivRow])
    if (path === EP.takerVolume) return Promise.resolve([takerRow])
    if (path === EP.openInterest) return Promise.resolve([oiRow])
    return Promise.resolve([ratioRow])
  })
}

describe('statsQuery（纯逻辑）', () => {
  it('多空比参数：scope 去空格，默认 order=desc，limit 转数字', () => {
    const built = buildRatioParams({
      ratioType: 'LSR_ACCOUNT_CCY',
      scope: '  BTC ',
      period: '1H',
      order: 'desc',
      limitRaw: '500',
      startRaw: '',
      endRaw: ''
    })
    expect(built).toEqual({
      ok: true,
      params: { ratioType: 'LSR_ACCOUNT_CCY', scope: 'BTC', period: '1H', order: 'desc', limit: 500 }
    })
  })

  it('多空比：ratioType 空与 scope 空分别命中 legacy 的两种文案', () => {
    const noType = buildRatioParams({ ratioType: '', scope: 'BTC', period: '1H', order: 'desc', limitRaw: '', startRaw: '', endRaw: '' })
    expect(noType).toEqual({ ok: false, error: '请选择 ratioType', toast: '请选择 ratioType' })
    const noScope = buildRatioParams({ ratioType: 'LSR_ACCOUNT_CCY', scope: ' ', period: '1H', order: 'desc', limitRaw: '', startRaw: '', endRaw: '' })
    expect(noScope).toEqual({
      ok: false,
      error: '请填写 scope（CCY 类型填币种，INST 类型填合约 ID）',
      toast: '请填写 scope'
    })
  })

  it('持仓量-成交额：ccy 必填，period 原样进参数', () => {
    expect(buildOivParams({ ccy: '', period: '1H', order: 'desc', limitRaw: '', startRaw: '', endRaw: '' })).toEqual({
      ok: false,
      error: '请填写 ccy',
      toast: '请填写 ccy'
    })
    expect(buildOivParams({ ccy: 'BTC', period: '1D', order: 'asc', limitRaw: '10', startRaw: '', endRaw: '' })).toEqual({
      ok: true,
      params: { ccy: 'BTC', period: '1D', order: 'asc', limit: 10 }
    })
  })

  it('主动买卖量：scopeKind 缺省回落 CCY，scope 空的提示文案与多空比不同', () => {
    const built = buildTakerParams({ scopeKind: '', scope: 'BTC', period: '1H', order: 'desc', limitRaw: '', startRaw: '', endRaw: '' })
    expect(built).toEqual({ ok: true, params: { scopeKind: 'CCY', scope: 'BTC', period: '1H', order: 'desc' } })
    const noScope = buildTakerParams({ scopeKind: 'INST', scope: '', period: '1H', order: 'desc', limitRaw: '', startRaw: '', endRaw: '' })
    expect(noScope).toEqual({ ok: false, error: '请填写 scope（CCY 填币种，INST 填合约 ID）', toast: '请填写 scope' })
  })

  it('持仓量快照：instId 必填 + 时间范围校验', () => {
    expect(buildOiParams({ instId: '', order: 'desc', limitRaw: '', startRaw: '', endRaw: '' })).toEqual({
      ok: false,
      error: '请填写 instId',
      toast: '请填写 instId'
    })
    const bad = buildOiParams({ instId: 'X', order: 'desc', limitRaw: '', startRaw: '2026-02-02T00:00', endRaw: '2026-02-01T00:00' })
    expect(bad).toEqual({ ok: false, error: '开始时间必须早于结束时间', toast: '开始时间必须早于结束时间' })
  })

  it('limit 非法时错误条留空只 toast', () => {
    const built = buildRatioParams({ ratioType: 'LSR_ACCOUNT_CCY', scope: 'BTC', period: '1H', order: 'desc', limitRaw: '99999', startRaw: '', endRaw: '' })
    expect(built).toEqual({ ok: false, error: '', toast: 'limit 需为 1 ~ 1000 的整数' })
    expect(readIntField(' 7 ', 1, 1000, 'limit')).toEqual({ value: 7 })
  })

  it('4 组结果概要文案', () => {
    expect(ratioSummary(0, 'LSR_ACCOUNT_CCY', 'BTC')).toBe('暂无数据')
    expect(ratioSummary(2, 'LSR_ACCOUNT_CCY', 'BTC')).toBe('共 2 条 · LSR_ACCOUNT_CCY / BTC')
    expect(oivSummary(2, 'BTC', '1H')).toBe('共 2 条 · BTC · 1H')
    expect(takerSummary(2, 'INST', 'BTC-USDT-SWAP')).toBe('共 2 条 · INST / BTC-USDT-SWAP')
    expect(oiSummary(2, 'BTC-USDT-SWAP')).toBe('共 2 条 · BTC-USDT-SWAP')
  })

  it('ratioType 提示行随 scopeKind 变化', () => {
    expect(ratioHint('')).toBe('—')
    expect(ratioHint('LSR_ACCOUNT_CCY')).toContain('scopeKind=CCY')
    expect(ratioHint('LSR_ACCOUNT_CCY')).toContain('scope 填币种（如 BTC）')
    expect(ratioHint('TOP_TRADER_ACCOUNT')).toContain('scopeKind=INST')
    expect(ratioHint('TOP_TRADER_ACCOUNT')).toContain('scope 填合约（如 BTC-USDT-SWAP）')
  })
})

describe('StatsView（组件）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    routeByPath()
  })

  it('挂载不发请求，4 个面板都是「尚未查询」，默认激活多空比', () => {
    const { w } = mountView()
    expect(api.mock.calls.length).toBe(0)
    expect(w.findAll('.tab').length).toBe(4)
    expect(w.findAll('.tab-panel.is-active').length).toBe(1)
    expect(w.find('[data-panel="ratios"]').classes()).toContain('is-active')
    expect(w.findAll('.muted').filter((x) => x.text() === '尚未查询').length).toBe(4)
    w.unmount()
  })

  it('点 tab 即查询该 tab 的接口（与 legacy switchStatsTab 一致）', async () => {
    const { w } = mountView()
    await w.findAll('.tab')[2].trigger('click')
    await flushPromises()
    expect(api.mock.calls[0][0]).toBe(EP.takerVolume)
    expect(api.mock.calls[0][1]).toEqual({ scopeKind: 'CCY', scope: 'BTC', period: '1H', order: 'desc', limit: 500 })
    expect(w.text()).toContain('共 1 条 · CCY / BTC')

    await w.findAll('.tab')[1].trigger('click')
    await flushPromises()
    expect(api.mock.calls[1][0]).toBe(EP.openInterestVolume)
    expect(api.mock.calls[1][1]).toEqual({ ccy: 'BTC', period: '1H', order: 'desc', limit: 500 })
    expect(w.text()).toContain('共 1 条 · BTC · 1H')

    await w.findAll('.tab')[3].trigger('click')
    await flushPromises()
    expect(api.mock.calls[2][0]).toBe(EP.openInterest)
    expect(api.mock.calls[2][1]).toEqual({ instId: 'BTC-USDT-SWAP', order: 'desc', limit: 500 })
    w.unmount()
  })

  it('多空比 scope 清空后提交只提示不请求', async () => {
    const { w, ui } = mountView()
    await w.find('#ratio-scope').setValue('')
    await w.findAll('form')[0].trigger('submit')
    await flushPromises()
    expect(api.mock.calls.length).toBe(0)
    expect(w.find('.alert.danger').text()).toBe('请填写 scope（CCY 类型填币种，INST 类型填合约 ID）')
    expect(ui.toasts.map((x) => x.msg)).toContain('请填写 scope')
    w.unmount()
  })

  it('切换 ratioType 会实时更新提示行', async () => {
    const { w } = mountView()
    await w.find('#ratio-type').setValue('TOP_TRADER_POSITION')
    expect(w.text()).toContain('scopeKind=INST')
    w.unmount()
  })

  it('接口失败时错误条 + 查询失败态 + toast', async () => {
    api.mockRejectedValue(new Error('boom'))
    const { w, ui } = mountView()
    await w.findAll('form')[0].trigger('submit')
    await flushPromises()
    expect(w.find('.alert.danger').text()).toBe('boom')
    expect(w.text()).toContain('查询失败：boom')
    expect(ui.toasts.map((x) => x.msg)).toContain('多空比查询失败：boom')
    w.unmount()
  })
})
