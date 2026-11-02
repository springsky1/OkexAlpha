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
import FundingView from '../src/views/FundingView.vue'
import { buildFundingParams, fundingSummary, readIntField } from '../src/views/funding/fundingQuery'
import { datetimeLocalToMs } from '../src/lib/format'
import { useUiStore } from '../src/stores/ui'

const api = apiGet as unknown as {
  mockResolvedValue: (v: unknown) => void
  mockRejectedValue: (v: unknown) => void
  mock: { calls: any[][] }
}

/* 一行真实形态的资金费率记录 */
const row = {
  fundingTimeMs: 1700000000000,
  instId: 'BTC-USDT-SWAP',
  fundingRate: 0.0001,
  realizedRate: 0.00012,
  premium: 0.00002,
  interestRate: 0.0001,
  nextFundingTimeMs: 1700028800000,
  settled: true,
  method: 'current_period',
  formulaType: 'with_rate'
}

function mountView() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const w = mount(FundingView, { global: { plugins: [pinia] } })
  return { w, ui: useUiStore() }
}

describe('fundingQuery（纯逻辑）', () => {
  it('默认表单组装出 instId + order + limit，不带时间参数', () => {
    const built = buildFundingParams({ instId: 'BTC-USDT-SWAP', order: 'desc', limitRaw: '500', startRaw: '', endRaw: '' })
    expect(built).toEqual({ ok: true, params: { instId: 'BTC-USDT-SWAP', order: 'desc', limit: 500 } })
  })

  it('instId 为空报错，文案与 legacy 一致', () => {
    const built = buildFundingParams({ instId: '   ', order: 'desc', limitRaw: '500', startRaw: '', endRaw: '' })
    expect(built).toEqual({ ok: false, error: '请填写 instId', toast: '请填写 instId' })
  })

  it('limit 非法时只 toast（错误条留空），空 limit 则不带该参数', () => {
    const bad = buildFundingParams({ instId: 'BTC-USDT-SWAP', order: 'desc', limitRaw: '2000', startRaw: '', endRaw: '' })
    expect(bad).toEqual({ ok: false, error: '', toast: 'limit 需为 1 ~ 1000 的整数' })
    const empty = buildFundingParams({ instId: 'BTC-USDT-SWAP', order: 'desc', limitRaw: '', startRaw: '', endRaw: '' })
    expect(empty).toEqual({ ok: true, params: { instId: 'BTC-USDT-SWAP', order: 'desc' } })
  })

  it('readIntField：空 -> 不带值，小数/越界 -> 错误文案', () => {
    expect(readIntField('', 1, 1000, 'limit')).toEqual({})
    expect(readIntField('2.5', 1, 1000, 'limit').error).toBe('limit 需为 1 ~ 1000 的整数')
    expect(readIntField('1000', 1, 1000, 'limit')).toEqual({ value: 1000 })
  })

  it('时间范围：开始 >= 结束被拦截，否则转成毫秒', () => {
    const bad = buildFundingParams({ instId: 'A', order: 'desc', limitRaw: '', startRaw: '2026-01-02T00:00', endRaw: '2026-01-01T00:00' })
    expect(bad).toEqual({ ok: false, error: '开始时间必须早于结束时间', toast: '开始时间必须早于结束时间' })
    const good = buildFundingParams({ instId: 'A', order: 'desc', limitRaw: '', startRaw: '2026-01-01T00:00', endRaw: '2026-01-02T00:00' })
    expect(good).toEqual({
      ok: true,
      params: {
        instId: 'A',
        order: 'desc',
        startMs: datetimeLocalToMs('2026-01-01T00:00'),
        endMs: datetimeLocalToMs('2026-01-02T00:00')
      }
    })
  })

  it('结果概要：0 条显示暂无数据，有数据显示条数与 instId', () => {
    expect(fundingSummary(0, 'BTC-USDT-SWAP')).toBe('暂无数据')
    expect(fundingSummary(3, 'BTC-USDT-SWAP')).toBe('共 3 条 · BTC-USDT-SWAP')
  })
})

describe('FundingView（组件）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.mockResolvedValue([row])
  })

  it('挂载不发请求，结果区是「尚未查询」', () => {
    const { w } = mountView()
    expect(api.mock.calls.length).toBe(0)
    expect(w.text()).toContain('尚未查询')
    w.unmount()
  })

  it('提交后按 legacy 参数请求 funding-rates 并渲染表格与概要', async () => {
    const { w } = mountView()
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(api.mock.calls[0][0]).toBe(EP.fundingRates)
    expect(api.mock.calls[0][1]).toEqual({ instId: 'BTC-USDT-SWAP', order: 'desc', limit: 500 })
    expect(w.text()).toContain('共 1 条 · BTC-USDT-SWAP')
    expect(w.text()).toContain('资金费率时间')
    expect(w.text()).toContain('settled')
    w.unmount()
  })

  it('instId 清空后提交只提示不请求', async () => {
    const { w, ui } = mountView()
    await w.find('input.inst-input').setValue('')
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(api.mock.calls.length).toBe(0)
    expect(w.find('.alert.danger').text()).toBe('请填写 instId')
    expect(ui.toasts.map((x) => x.msg)).toContain('请填写 instId')
    w.unmount()
  })

  it('接口失败时错误条与 toast 都用 legacy 文案前缀', async () => {
    api.mockRejectedValue(new Error('boom'))
    const { w, ui } = mountView()
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(w.find('.alert.danger').text()).toBe('boom')
    expect(w.text()).toContain('查询失败：boom')
    expect(ui.toasts.map((x) => x.msg)).toContain('资金费率查询失败：boom')
    w.unmount()
  })

  it('近 7 天快捷按钮填充时间范围', async () => {
    const { w } = mountView()
    const chips = w.findAll('button.chip')
    await chips[0].trigger('click')
    const start = (w.find('#funding-start').element as HTMLInputElement).value
    const end = (w.find('#funding-end').element as HTMLInputElement).value
    expect(start).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/)
    expect(end > start).toBe(true)
    w.unmount()
  })
})
