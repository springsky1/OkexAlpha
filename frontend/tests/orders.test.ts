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
import { ORDER_EX_COLS, ORDERS_EMPTY, ORDERS_LIMIT, ORDERS_TABS, ordersMetaText } from '../src/views/account/acc'

type Mocked = {
  mockImplementation: (f: (p: string, q?: unknown) => unknown) => void
  mockRejectedValue: (v: unknown) => void
  mock: { calls: unknown[][] }
}
const mocked = apiGet as unknown as Mocked

/* 本页只查交易所真实订单：影子订单已挪到「策略 → 影子订单」 */
const pendingPayload = {
  state: 'pending',
  ok: true,
  items: [
    {
      instId: 'BTC-USDT-SWAP',
      ordId: '9001',
      clOrdId: 'cli-9001',
      side: 'buy',
      posSide: 'long',
      ordType: 'limit',
      state: 'live',
      sz: '0.01',
      px: '70000',
      uTime: '2026-11-02T10:00:00Z'
    },
    {
      instId: 'ETH-USDT-SWAP',
      ordId: '9002',
      clOrdId: 'cli-9002',
      side: 'sell',
      posSide: 'short',
      ordType: 'limit',
      state: 'live',
      sz: '0.2',
      px: '3200',
      uTime: '2026-11-02T10:05:00Z'
    }
  ]
}

const historyPayload = { state: 'history', items: [], ok: true }

function mockAll() {
  mocked.mockImplementation((path: string, params?: unknown) => {
    if (path !== EP.accountOrders) return Promise.reject(new Error('unexpected path ' + path))
    const state = String((params as { state?: string } | undefined)?.state || '')
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

describe('orders 页常量与文案（只含交易所真实订单）', () => {
  it('两个 tab：交易所挂单 / 交易所历史，默认 pending', () => {
    expect(ORDERS_TABS.map((t) => t.name)).toEqual(['pending', 'history'])
    expect(ORDERS_TABS.map((t) => t.label)).toEqual(['交易所挂单', '交易所历史'])
    expect(ORDERS_LIMIT).toBe(50)
    expect(ORDERS_EMPTY.pending).toBe('交易所当前无挂单')
    expect(ORDERS_EMPTY.history).toBe('交易所无历史订单')
  })

  it('meta 文案固定带 instType=SWAP', () => {
    expect(ordersMetaText('pending', 0)).toBe('共 0 条 · state=pending · instType=SWAP')
    expect(ordersMetaText('history', 5)).toBe('共 5 条 · state=history · instType=SWAP')
  })

  it('交易所 18 列，方向列按买卖着色', () => {
    expect(ORDER_EX_COLS.length).toBe(18)
    expect(ORDER_EX_COLS[4].render?.({ side: 'buy' })).toContain('val-pos')
    expect(ORDER_EX_COLS[4].render?.({ side: 'sell' })).toContain('val-neg')
  })
})

describe('OrdersView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
    mockAll()
  })

  it('首屏只请求 pending，并按接口参数 state/instType/limit 组装', async () => {
    const w = mountView()
    await flushPromises()
    expect(apiGet).toHaveBeenCalledTimes(1)
    expect(apiGet).toHaveBeenCalledWith(EP.accountOrders, { state: 'pending', instType: 'SWAP', limit: ORDERS_LIMIT })
    w.unmount()
  })

  it('挂单表渲染买卖记录与状态徽标，且没有影子订单 tab', async () => {
    const w = mountView()
    await flushPromises()
    const text = w.text()
    expect(text).toContain('BTC-USDT-SWAP')
    expect(text).toContain('ETH-USDT-SWAP')
    expect(text).toContain('live')
    expect(text).toContain('共 2 条 · state=pending · instType=SWAP')
    expect(w.findAll('#orders-pending-wrap tbody tr').length).toBe(2)
    expect(w.find('#orders-pending-wrap .val-pos').text()).toBe('buy')
    expect(w.find('#orders-pending-wrap .val-neg').text()).toBe('sell')
    expect(w.find('#orders-pending-wrap .badge').attributes('data-status')).toBe('live')
    expect(w.find('[data-tab="local"]').exists()).toBe(false)
    expect(text).not.toContain('本地影子订单')
    w.unmount()
  })

  it('每个 tab 首次进入才请求一次（切回不重复请求）', async () => {
    const w = mountView()
    await flushPromises()
    const tabs = w.findAll('.tab')
    expect(tabs.length).toBe(2)
    await tabs[1].trigger('click')
    await flushPromises()
    expect(callsFor('history')).toBe(1)
    expect(w.find('#orders-history-wrap').text()).toContain('交易所无历史订单')
    expect(w.find('#orders-history-meta').text()).toBe('共 0 条 · state=history · instType=SWAP')
    await tabs[0].trigger('click')
    await flushPromises()
    expect(callsFor('pending')).toBe(1)
    w.unmount()
  })

  it('刷新按钮强制重拉当前 tab', async () => {
    const w = mountView()
    await flushPromises()
    await w.find('#orders-refresh').trigger('click')
    await flushPromises()
    expect(callsFor('pending')).toBe(2)
    w.unmount()
  })

  it('接口失败时顶部与 tab 内都给出错误文案，meta 变「加载失败」', async () => {
    mocked.mockRejectedValue(new Error('boom'))
    const w = mountView()
    await flushPromises()
    expect(w.find('.alert.danger').text()).toBe('订单加载失败（state=pending）：boom')
    expect(w.find('#orders-pending-wrap').text()).toContain('订单加载失败：boom')
    expect(w.text()).toContain('加载失败')
    w.unmount()
  })

  it('未配置凭证时后端只回 notice，也要在顶部展示', async () => {
    mocked.mockImplementation(() => Promise.resolve({ state: 'pending', items: [], ok: true, notice: '未配置 OKX API 凭证' }))
    const w = mountView()
    await flushPromises()
    expect(w.find('.alert.danger').text()).toBe('未配置 OKX API 凭证')
    expect(w.find('#orders-pending-wrap').text()).toContain('交易所当前无挂单')
    w.unmount()
  })
})
