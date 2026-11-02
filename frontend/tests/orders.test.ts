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
import OrdersView from '../src/views/OrdersView.vue'
import { ORDER_EX_COLS, ORDER_LOCAL_COLS, ORDERS_EMPTY, ORDERS_LIMIT, ordersMetaText } from '../src/views/account/acc'

type Mocked = {
  mockImplementation: (f: (p: string, q?: unknown) => unknown) => void
  mockRejectedValue: (v: unknown) => void
  mock: { calls: unknown[][] }
}
const mocked = apiGet as unknown as Mocked

const localPayload = {
  state: 'local',
  items: [
    {
      source: 'LOCAL',
      id: 8,
      strategyId: 1,
      instId: 'BTC-USDT-SWAP',
      side: 'buy',
      ordType: 'market',
      sz: '0.01',
      px: '77896.37616',
      notionalUsd: '778.96',
      state: 'FILLED',
      reason: 'OPEN',
      signalTsMs: 1793534041145,
      decidedAt: '2026-11-02T04:30:00'
    },
    {
      source: 'LOCAL',
      id: 7,
      strategyId: 1,
      instId: 'ETH-USDT-SWAP',
      side: 'sell',
      ordType: 'market',
      sz: '0.12',
      px: '3251.5',
      notionalUsd: '390.18',
      state: 'FILLED',
      reason: 'TP',
      decidedAt: '2026-11-02T03:10:00'
    }
  ],
  ok: true
}

const pendingPayload = { state: 'pending', items: [], ok: true }
const historyPayload = { state: 'history', items: [], ok: true }

function mockAll() {
  mocked.mockImplementation((path: string, params?: unknown) => {
    if (path !== EP.accountOrders) return Promise.reject(new Error('unexpected path ' + path))
    const state = String((params as { state?: string } | undefined)?.state || '')
    if (state === 'local') return Promise.resolve(localPayload)
    if (state === 'pending') return Promise.resolve(pendingPayload)
    if (state === 'history') return Promise.resolve(historyPayload)
    return Promise.resolve({ state: state, items: [], ok: true })
  })
}

function mountView() {
  return mount(OrdersView, { global: { plugins: [createPinia()] } })
}

function callsFor(state: string): number {
  let n = 0
  for (const c of mocked.mock.calls) {
    const params = c[1] as { state?: string } | undefined
    if (c[0] === EP.accountOrders && params && params.state === state) n++
  }
  return n
}

describe('orders 页常量与文案（legacy §16b 1:1）', () => {
  it('meta 文案与 legacy 一致（交易所 tab 追加 instType=SWAP）', () => {
    expect(ordersMetaText('local', 2)).toBe('共 2 条 · state=local')
    expect(ordersMetaText('pending', 0)).toBe('共 0 条 · state=pending · instType=SWAP')
    expect(ordersMetaText('history', 5)).toBe('共 5 条 · state=history · instType=SWAP')
    expect(ORDERS_LIMIT).toBe(50)
    expect(ORDERS_EMPTY.local).toBe('本地暂无影子订单记录')
    expect(ORDERS_EMPTY.pending).toBe('交易所当前无挂单')
    expect(ORDERS_EMPTY.history).toBe('交易所无历史订单')
  })

  it('本地 12 列、交易所 18 列，方向列按买卖着色', () => {
    expect(ORDER_LOCAL_COLS.length).toBe(12)
    expect(ORDER_EX_COLS.length).toBe(18)
    expect(ORDER_LOCAL_COLS[5].render?.({ side: 'buy' })).toContain('val-pos')
    expect(ORDER_LOCAL_COLS[5].render?.({ side: 'sell' })).toContain('val-neg')
  })
})

describe('OrdersView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
    mockAll()
  })

  it('首屏只请求 local，并按接口参数 state/instType/limit 组装', async () => {
    const w = mountView()
    await flushPromises()
    expect(apiGet).toHaveBeenCalledTimes(1)
    expect(apiGet).toHaveBeenCalledWith(EP.accountOrders, { state: 'local', instType: 'SWAP', limit: ORDERS_LIMIT })
    w.unmount()
  })

  it('本地影子订单表渲染买卖记录与状态徽标', async () => {
    const w = mountView()
    await flushPromises()
    const text = w.text()
    expect(text).toContain('BTC-USDT-SWAP')
    expect(text).toContain('ETH-USDT-SWAP')
    expect(text).toContain('FILLED')
    expect(text).toContain('共 2 条 · state=local')
    expect(w.findAll('#orders-local-wrap tbody tr').length).toBe(2)
    expect(w.find('#orders-local-wrap .val-pos').text()).toBe('buy')
    expect(w.find('#orders-local-wrap .val-neg').text()).toBe('sell')
    expect(w.find('#orders-local-wrap .badge').attributes('data-status')).toBe('FILLED')
    w.unmount()
  })

  it('每个 tab 首次进入才请求一次（切回不重复请求）', async () => {
    const w = mountView()
    await flushPromises()
    const tabs = w.findAll('.tab')
    await tabs[1].trigger('click')
    await flushPromises()
    expect(callsFor('pending')).toBe(1)
    expect(w.find('#orders-pending-wrap').text()).toContain('交易所当前无挂单')
    expect(w.find('#orders-pending-meta').text()).toBe('共 0 条 · state=pending · instType=SWAP')
    await tabs[0].trigger('click')
    await flushPromises()
    expect(callsFor('local')).toBe(1)
    w.unmount()
  })

  it('刷新按钮强制重拉当前 tab', async () => {
    const w = mountView()
    await flushPromises()
    await w.find('#orders-refresh').trigger('click')
    await flushPromises()
    expect(callsFor('local')).toBe(2)
    w.unmount()
  })

  it('接口失败时顶部与 tab 内都给出错误文案，meta 变「加载失败」', async () => {
    mocked.mockRejectedValue(new Error('boom'))
    const w = mountView()
    await flushPromises()
    expect(w.find('.alert.danger').text()).toBe('订单加载失败（state=local）：boom')
    expect(w.find('#orders-local-wrap').text()).toContain('订单加载失败：boom')
    expect(w.text()).toContain('加载失败')
    w.unmount()
  })

  it('未配置凭证时后端只回 notice，也要在顶部展示', async () => {
    mocked.mockImplementation(() => Promise.resolve({ state: 'local', items: [], ok: true, notice: '未配置 OKX API 凭证' }))
    const w = mountView()
    await flushPromises()
    expect(w.find('.alert.danger').text()).toBe('未配置 OKX API 凭证')
    expect(w.find('#orders-local-wrap').text()).toContain('本地暂无影子订单记录')
    w.unmount()
  })
})
