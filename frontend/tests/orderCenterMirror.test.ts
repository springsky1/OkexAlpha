import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'

const uiMocks = vi.hoisted(() => ({
  toast: vi.fn(),
  confirmDialog: vi.fn(async () => true),
  lastRefresh: { value: '—' }
}))

vi.mock('../src/api/client', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiRawGet: vi.fn()
}))

vi.mock('../src/stores/ui', () => ({
  useUiStore: () => uiMocks
}))

import { apiGet, apiPost } from '../src/api/client'
import { EP } from '../src/api/endpoints'
import OrderCenterView from '../src/views/OrderCenterView.vue'

const SYNC_PATH = '/api/v1/account/exchange/sync'
const EXCHANGE_ORDERS = '/api/v1/account/exchange/orders'
const EXCHANGE_POSITIONS = '/api/v1/account/exchange/positions'
const EXCHANGE_FILLS = '/api/v1/account/exchange/fills'

const ordersPayload = {
  ok: true,
  total: 2,
  lastSyncedAt: '2026-11-11T20:00:00Z',
  items: [
    {
      ordId: 'A1',
      instId: 'BTC-USDT-SWAP',
      side: 'sell',
      posSide: 'long',
      ordType: 'market',
      sz: '1',
      fillSz: '1',
      avgPx: '70100',
      state: 'filled',
      fee: '-0.14',
      pnl: '2.4',
      cTime: '1700000000000',
      uTime: '1700000200000'
    },
    {
      ordId: 'A2',
      instId: 'ETH-USDT-SWAP',
      side: 'buy',
      posSide: 'short',
      ordType: 'market',
      sz: '2',
      fillSz: '2',
      avgPx: '3100',
      state: 'filled',
      fee: '-0.2',
      pnl: '-1.1',
      cTime: '1700000000000',
      uTime: '1700000300000'
    }
  ]
}

const emptyPayload = { ok: true, total: 0, items: [] }

const syncPayload = {
  ok: true,
  fetched: { pending: 1, history: 1, positions: 0, fills: 0 },
  stored: { orders: 2, positions: 0, fills: 0 },
  syncedAt: '2026-11-11T20:00:00Z',
  lastSyncedAt: '2026-11-11T20:00:00Z'
}

const getMock = apiGet as unknown as {
  mockImplementation: (f: (p: string, q?: unknown) => unknown) => void
  mock: { calls: unknown[][] }
}
const postMock = apiPost as unknown as {
  mockResolvedValue: (v: unknown) => void
  mock: { calls: unknown[][] }
}

function mockAll() {
  getMock.mockImplementation((path: string) => {
    if (path === EXCHANGE_ORDERS) return Promise.resolve(ordersPayload)
    if (path === EXCHANGE_POSITIONS) return Promise.resolve(emptyPayload)
    if (path === EXCHANGE_FILLS) return Promise.resolve(emptyPayload)
    if (path === EP.accountStrategyCenter) return Promise.resolve({ ok: true, total: 1, items: [] })
    if (path === EP.accountBorrowRepay || path === EP.accountBills) return Promise.resolve(emptyPayload)
    return Promise.reject(new Error('unexpected path ' + path))
  })
}

function mountView() {
  return mount(OrderCenterView, { global: { plugins: [createPinia()] } })
}

describe('OrderCenterView 本地镜像与勾选汇总', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    postMock.mockResolvedValue(syncPayload)
    mockAll()
  })

  it('挂载后自动同步一次，并从本地镜像读当前委托', async () => {
    const w = mountView()
    await flushPromises()

    expect(apiPost).toHaveBeenCalledWith(SYNC_PATH, undefined, { instType: 'SWAP', limit: 100, force: false })
    expect(apiGet).toHaveBeenCalledWith(EXCHANGE_ORDERS, {
      state: 'pending',
      instType: 'SWAP',
      limit: 100,
      offset: 0
    })
    expect(w.find('#oc-pending-wrap').text()).toContain('BTC-USDT-SWAP')
    w.unmount()
  })

  it('强制刷新按钮带 force=true 再同步一次', async () => {
    const w = mountView()
    await flushPromises()

    await w.find('#oc-refresh').trigger('click')
    await flushPromises()

    const syncCalls = postMock.mock.calls.filter((call) => call[0] === SYNC_PATH)
    expect(syncCalls.length).toBe(2)
    expect(syncCalls[1][2]).toEqual({ instType: 'SWAP', limit: 100, force: true })
    expect(uiMocks.toast).toHaveBeenCalled()
    w.unmount()
  })

  it('勾选多行后展示汇总：笔数、净收益、胜率与按日柱', async () => {
    const w = mountView()
    await flushPromises()

    const boxes = w.findAll('#oc-pending-wrap tbody input[type="checkbox"]')
    expect(boxes.length).toBe(2)
    await boxes[0].setValue(true)
    await boxes[1].setValue(true)
    await flushPromises()

    const panel = w.find('#oc-selection')
    expect(panel.exists()).toBe(true)
    expect(panel.text()).toContain('已选 2 笔')
    expect(panel.text()).toContain('净收益')
    expect(panel.text()).toContain('0.96')
    expect(panel.text()).toContain('50.00%')
    w.unmount()
  })

  it('同步失败只提示，本地列表照常展示', async () => {
    postMock.mockResolvedValue({ ok: false, error: { kind: 'NETWORK', message: 'boom' } })

    const w = mountView()
    await flushPromises()

    expect(uiMocks.toast).toHaveBeenCalled()
    expect(String(uiMocks.toast.mock.calls[0][0])).toContain('同步失败')
    expect(w.find('#oc-pending-wrap').text()).toContain('BTC-USDT-SWAP')
    w.unmount()
  })
})
