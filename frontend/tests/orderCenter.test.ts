import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'

/* 确认弹窗的返回值由测试控制，避免真去点全局 modal */
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
import {
  OC_EMPTY,
  OC_FILLS_COLS,
  OC_LIMIT,
  OC_POSITION_COLS,
  OC_BILLS_COLS,
  OC_BORROW_COLS,
  OC_TABS,
  ocMetaText
} from '../src/views/order-center/oc'
import { ORDER_EX_COLS } from '../src/views/account/acc'

type GetMock = {
  mockImplementation: (f: (p: string, q?: unknown) => unknown) => void
  mockRejectedValue: (v: unknown) => void
  mock: { calls: unknown[][] }
}
const mocked = apiGet as unknown as GetMock
const mockedPost = apiPost as unknown as {
  mockResolvedValue: (v: unknown) => void
  mock: { calls: unknown[][] }
}

const pendingPayload = {
  state: 'pending',
  ok: true,
  items: [
    {
      instId: 'BTC-USDT-SWAP',
      side: 'buy',
      posSide: 'long',
      ordType: 'limit',
      sz: '0.01',
      fillSz: '0',
      px: '70000',
      avgPx: '0',
      state: 'live',
      uTime: '2026-11-02T10:00:00Z'
    }
  ]
}

const historyPayload = { state: 'history', ok: true, items: [] }

const positionsPayload = {
  ok: true,
  items: [
    {
      instId: 'ETH-USDT-SWAP',
      posSide: 'short',
      mgnMode: 'cross',
      type: '2',
      lever: '3',
      openAvgPx: '3200',
      closeAvgPx: '3100',
      markPx: '3100',
      pnl: '-12.5',
      pnlRatio: '-0.1',
      realizedPnl: '-12.5',
      fee: '-0.2',
      fundingFee: '0.05',
      liqPx: '',
      cTime: '1700000000000',
      uTime: '1700000100000'
    }
  ]
}

const fillsPayload = {
  ok: true,
  items: [
    {
      instId: 'BTC-USDT-SWAP',
      side: 'sell',
      posSide: 'long',
      fillSz: '0.02',
      fillPx: '70100',
      fee: '-0.14',
      feeCcy: 'USDT',
      pnl: '2.4',
      execType: 'T',
      ordId: '9001',
      fillTime: '1700000200000'
    }
  ]
}

const borrowPayload = {
  ok: true,
  items: [{ ccy: 'USDT', type: '1', amt: '50', interest: '0.1', total: '50.1', mgnMode: 'cross', ts: '1700000000000' }]
}

const billsPayload = {
  ok: true,
  items: [
    {
      billId: '11',
      instId: 'BTC-USDT-SWAP',
      type: '2',
      subType: '3',
      ccy: 'USDT',
      balChg: '1.5',
      bal: '100',
      sz: '0.01',
      px: '70000',
      fee: '-0.1',
      pnl: '1.5',
      interest: '0',
      ts: '1700000000000'
    }
  ]
}

function mockAll() {
  mocked.mockImplementation((path: string, params?: unknown) => {
    if (path === EP.accountExchangeOrders) {
      const state = String((params as { state?: string } | undefined)?.state || '')
      if (state === 'pending') return Promise.resolve(pendingPayload)
      if (state === 'history') return Promise.resolve(historyPayload)
      return Promise.resolve({ ok: true, items: [] })
    }
    if (path === EP.accountExchangePositions) return Promise.resolve(positionsPayload)
    if (path === EP.accountExchangeFills) return Promise.resolve(fillsPayload)
    if (path === EP.accountBorrowRepay) return Promise.resolve(borrowPayload)
    if (path === EP.accountBills) return Promise.resolve(billsPayload)
    return Promise.reject(new Error('unexpected path ' + path))
  })
}

function mountView() {
  return mount(OrderCenterView, { global: { plugins: [createPinia()] } })
}

function callsFor(path: string): number {
  let n = 0
  for (const c of mocked.mock.calls) {
    if (c[0] === path) n++
  }
  return n
}

describe('订单中心常量与格式化', () => {
  it('6 个 tab 的顺序与标签与 OKX 订单中心一致（策略已归「策略」菜单）', () => {
    expect(OC_TABS.map((t) => t.name)).toEqual(['pending', 'history', 'positions', 'fills', 'borrow', 'bills'])
    expect(OC_TABS.map((t) => t.label)).toEqual([
      '当前委托',
      '历史委托',
      '历史仓位',
      '交易账单',
      '借币数据',
      '资金账单'
    ])
    expect(OC_LIMIT).toBe(100)
    expect(OC_EMPTY.pending).toBe('暂无当前委托')
  })

  it('列定义：委托复用交易所 18 列，其余 tab 各自成套', () => {
    expect(ORDER_EX_COLS.length).toBe(18)
    expect(OC_POSITION_COLS.length).toBe(16)
    expect(OC_FILLS_COLS.length).toBe(10)
    expect(OC_BILLS_COLS.length).toBe(13)
    expect(OC_BORROW_COLS.length).toBe(8)
    expect(OC_POSITION_COLS[1].render?.({ posSide: 'short' })).toContain('val-neg')
    expect(OC_FILLS_COLS[1].render?.({ side: 'buy' })).toContain('val-pos')
  })

  it('meta 文案只区分交易所条数', () => {
    expect(ocMetaText('fills', 3)).toBe('共 3 条（limit=100）')
    expect(ocMetaText('pending', 0)).toBe('共 0 条（limit=100）')
  })
})

describe('OrderCenterView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    uiMocks.confirmDialog.mockResolvedValue(true)
    mockedPost.mockResolvedValue({ status: 'STOPPED' })
    mockAll()
  })

  it('首屏先同步一次镜像，再请求当前委托，其余 tab 懒加载', async () => {
    const w = mountView()
    await flushPromises()

    expect(apiPost).toHaveBeenCalledWith(EP.accountExchangeSync, undefined, {
      instType: 'SWAP',
      limit: 100,
      force: false
    })
    expect(apiGet).toHaveBeenCalledTimes(1)
    expect(apiGet).toHaveBeenCalledWith(EP.accountExchangeOrders, {
      state: 'pending',
      instType: 'SWAP',
      limit: 100,
      offset: 0
    })
    expect(w.findAll('#oc-tabs .tab').length).toBe(6)
    expect(w.find('[data-tab="strategy"]').exists()).toBe(false)
    expect(w.find('#oc-pending-wrap').text()).toContain('BTC-USDT-SWAP')
    expect(w.find('#oc-pending-meta').text()).toBe('共 1 条（limit=100）')
    w.unmount()
  })

  it('切换 tab 请求对应接口，切回不重复请求', async () => {
    const w = mountView()
    await flushPromises()

    await w.find('[data-tab="positions"]').trigger('click')
    await flushPromises()
    expect(apiGet).toHaveBeenCalledWith(EP.accountExchangePositions, { instType: 'SWAP', limit: 100, offset: 0 })

    await w.find('[data-tab="fills"]').trigger('click')
    await flushPromises()
    expect(apiGet).toHaveBeenCalledWith(EP.accountExchangeFills, { instType: 'SWAP', limit: 100, offset: 0 })

    await w.find('[data-tab="borrow"]').trigger('click')
    await flushPromises()
    expect(apiGet).toHaveBeenCalledWith(EP.accountBorrowRepay, { limit: 100 })

    await w.find('[data-tab="bills"]').trigger('click')
    await flushPromises()
    expect(apiGet).toHaveBeenCalledWith(EP.accountBills, { limit: 100 })

    await w.find('[data-tab="history"]').trigger('click')
    await flushPromises()
    expect(apiGet).toHaveBeenCalledWith(EP.accountExchangeOrders, {
      state: 'history',
      instType: 'SWAP',
      limit: 100,
      offset: 0
    })

    /* 切回已加载的 tab 不再请求 */
    await w.find('[data-tab="positions"]').trigger('click')
    await flushPromises()
    expect(callsFor(EP.accountExchangePositions)).toBe(1)
    w.unmount()
  })

  it('历史仓位与资金账单按列渲染，盈亏带正负色', async () => {
    const w = mountView()
    await flushPromises()

    await w.find('[data-tab="positions"]').trigger('click')
    await flushPromises()
    expect(w.findAll('#oc-positions-wrap tbody tr').length).toBe(1)
    /* 第一处负值是方向 short，盈亏列也要带负色 */
    expect(w.find('#oc-positions-wrap tbody tr .val-neg').text()).toBe('short')
    expect(w.find('#oc-positions-wrap tbody').text()).toContain('-12.50')

    await w.find('[data-tab="bills"]').trigger('click')
    await flushPromises()
    expect(w.find('#oc-bills-wrap').text()).toContain('USDT')
    expect(w.findAll('#oc-bills-wrap tbody tr').length).toBe(1)
    w.unmount()
  })

  it('接口失败时顶部与 tab 内都有错误条，meta 变「加载失败」', async () => {
    mocked.mockRejectedValue(new Error('boom'))
    const w = mountView()
    await flushPromises()

    expect(w.find('#oc-error').text()).toBe('当前委托加载失败：boom')
    expect(w.find('#oc-pending-wrap').text()).toContain('当前委托加载失败：boom')
    expect(w.find('#oc-pending-meta').text()).toBe('加载失败')
    w.unmount()
  })

  it('接口返回 error 对象时在 tab 内展示错误并保留空态列', async () => {
    mocked.mockImplementation(() =>
      Promise.resolve({ ok: false, items: [], error: { code: '50011', message: '限流', kind: 'RATE_LIMITED' } })
    )
    const w = mountView()
    await flushPromises()

    expect(w.find('#oc-pending-wrap .alert.danger').text()).toContain('50011')
    w.unmount()
  })
})
