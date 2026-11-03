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
  OC_STRATEGY_HEAD,
  OC_TABS,
  ocMetaText,
  ocReturnPct,
  ocSummary,
  ocSummaryPairs,
  ocStrategyStatus
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

const strategyPayload = {
  state: 'all',
  limit: 100,
  offset: 0,
  total: 1,
  items: [
    {
      id: 7,
      name: 'BTC 马丁',
      status: 'PROMOTED',
      mode: 'SHADOW',
      running: true,
      investedUsd: 1000,
      realizedPnlUsd: 40,
      unrealizedPnlUsd: -10,
      totalPnlUsd: 30,
      returnPct: 3,
      openPositions: 2,
      closedPositions: 3,
      orderCount: 9,
      lastOrderAt: '2026-02-03T04:05:06Z',
      instIds: ['BTC-USDT-SWAP']
    }
  ],
  ok: true
}

const detailPayload = {
  id: 7,
  name: 'BTC 马丁',
  status: 'PROMOTED',
  mode: 'SHADOW',
  running: true,
  capitalPerCoinUsd: 500,
  params: { layers: 8 },
  universe: { instIds: ['BTC-USDT-SWAP'] },
  positions: [],
  recentOrders: []
}

function mockAll() {
  mocked.mockImplementation((path: string, params?: unknown) => {
    if (path === EP.accountOrders) {
      const state = String((params as { state?: string } | undefined)?.state || '')
      if (state === 'pending') return Promise.resolve(pendingPayload)
      if (state === 'history') return Promise.resolve(historyPayload)
      return Promise.resolve({ ok: true, items: [] })
    }
    if (path === EP.accountPositionsHistory) return Promise.resolve(positionsPayload)
    if (path === EP.accountFills) return Promise.resolve(fillsPayload)
    if (path === EP.accountBorrowRepay) return Promise.resolve(borrowPayload)
    if (path === EP.accountBills) return Promise.resolve(billsPayload)
    if (path === EP.accountStrategyCenter) return Promise.resolve(strategyPayload)
    if (path === EP.strategyStrategy('7')) return Promise.resolve(detailPayload)
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
  it('7 个 tab 的顺序与标签与 OKX 订单中心一致', () => {
    expect(OC_TABS.map((t) => t.name)).toEqual([
      'pending',
      'history',
      'positions',
      'fills',
      'strategy',
      'borrow',
      'bills'
    ])
    expect(OC_TABS.map((t) => t.label)).toEqual([
      '当前委托',
      '历史委托',
      '历史仓位',
      '交易账单',
      '策略',
      '借币数据',
      '资金账单'
    ])
    expect(OC_LIMIT).toBe(100)
    expect(OC_EMPTY.pending).toBe('暂无当前委托')
    expect(OC_EMPTY.strategy).toBe('暂无策略')
  })

  it('列定义：委托复用交易所 18 列，其余 tab 各自成套', () => {
    expect(ORDER_EX_COLS.length).toBe(18)
    expect(OC_POSITION_COLS.length).toBe(16)
    expect(OC_FILLS_COLS.length).toBe(10)
    expect(OC_BILLS_COLS.length).toBe(13)
    expect(OC_BORROW_COLS.length).toBe(8)
    expect(OC_STRATEGY_HEAD).toEqual([
      '策略名称',
      '累计投入',
      '总收益',
      '收益率',
      '套利收益',
      '浮动收益',
      '状态',
      '操作'
    ])
    expect(OC_POSITION_COLS[1].render?.({ posSide: 'short' })).toContain('val-neg')
    expect(OC_FILLS_COLS[1].render?.({ side: 'buy' })).toContain('val-pos')
  })

  it('收益率按百分数格式化，累计投入/收益走 accMoney/accPnl', () => {
    expect(ocReturnPct(3)).toBe('+3.00%')
    expect(ocReturnPct(-1.5)).toBe('-1.50%')
    expect(ocReturnPct(0)).toBe('0.00%')
    expect(ocReturnPct(null)).toBe('—')
  })

  it('meta 文案区分策略数与交易所条数', () => {
    expect(ocMetaText('fills', 3)).toBe('共 3 条（limit=100）')
    expect(ocMetaText('strategy', 2)).toBe('共 2 个策略')
  })

  it('策略聚合：合计投入/收益/收益率，投入为 0 时收益率 null', () => {
    const s = ocSummary(strategyPayload.items)
    expect(s.count).toBe(1)
    expect(s.running).toBe(1)
    expect(s.investedUsd).toBe(1000)
    expect(s.realizedPnlUsd).toBe(40)
    expect(s.unrealizedPnlUsd).toBe(-10)
    expect(s.totalPnlUsd).toBe(30)
    expect(s.returnPct).toBe(3)
    expect(ocSummary([]).returnPct).toBeNull()
    expect(ocSummaryPairs(strategyPayload.items).length).toBe(8)
  })

  it('状态列同时给出 badge 与运行中/已停止', () => {
    const running = ocStrategyStatus({ status: 'PROMOTED', running: true })
    expect(running).toContain('data-status="PROMOTED"')
    expect(running).toContain('运行中')
    expect(ocStrategyStatus({ status: 'ARCHIVED', running: false })).toContain('已停止')
  })
})

describe('OrderCenterView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    uiMocks.confirmDialog.mockResolvedValue(true)
    mockedPost.mockResolvedValue({ status: 'STOPPED' })
    mockAll()
  })

  it('首屏只请求当前委托，其余 tab 懒加载', async () => {
    const w = mountView()
    await flushPromises()

    expect(apiGet).toHaveBeenCalledTimes(1)
    expect(apiGet).toHaveBeenCalledWith(EP.accountOrders, { state: 'pending', instType: 'SWAP', limit: 100 })
    expect(w.findAll('#oc-tabs .tab').length).toBe(7)
    expect(w.find('#oc-pending-wrap').text()).toContain('BTC-USDT-SWAP')
    expect(w.find('#oc-pending-meta').text()).toBe('共 1 条（limit=100）')
    w.unmount()
  })

  it('切换 tab 请求对应接口，切回不重复请求', async () => {
    const w = mountView()
    await flushPromises()

    await w.find('[data-tab="positions"]').trigger('click')
    await flushPromises()
    expect(apiGet).toHaveBeenCalledWith(EP.accountPositionsHistory, { instType: 'SWAP', limit: 100 })

    await w.find('[data-tab="fills"]').trigger('click')
    await flushPromises()
    expect(apiGet).toHaveBeenCalledWith(EP.accountFills, { instType: 'SWAP', limit: 100 })

    await w.find('[data-tab="borrow"]').trigger('click')
    await flushPromises()
    expect(apiGet).toHaveBeenCalledWith(EP.accountBorrowRepay, { limit: 100 })

    await w.find('[data-tab="bills"]').trigger('click')
    await flushPromises()
    expect(apiGet).toHaveBeenCalledWith(EP.accountBills, { limit: 100 })

    await w.find('[data-tab="strategy"]').trigger('click')
    await flushPromises()
    expect(apiGet).toHaveBeenCalledWith(EP.accountStrategyCenter, { state: 'all', limit: 100, offset: 0 })

    await w.find('[data-tab="history"]').trigger('click')
    await flushPromises()
    expect(apiGet).toHaveBeenCalledWith(EP.accountOrders, { state: 'history', instType: 'SWAP', limit: 100 })

    /* 切回已加载的 tab 不再请求 */
    await w.find('[data-tab="positions"]').trigger('click')
    await flushPromises()
    expect(callsFor(EP.accountPositionsHistory)).toBe(1)
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

  it('策略 tab 渲染指标卡与行格式化（投入/总收益/收益率/套利/浮动/状态）', async () => {
    const w = mountView()
    await flushPromises()
    await w.find('[data-tab="strategy"]').trigger('click')
    await flushPromises()

    const panel = w.find('#oc-strategy-wrap')
    expect(panel.findAll('#oc-strategy-table tbody tr').length).toBe(1)
    const text = panel.text()
    expect(text).toContain('BTC 马丁')
    expect(text).toContain('1,000.00')
    expect(text).toContain('+30.00')
    expect(text).toContain('+3.00%')
    expect(text).toContain('+40.00')
    expect(text).toContain('-10.00')
    expect(panel.find('.badge').attributes('data-status')).toBe('PROMOTED')
    expect(panel.find('[data-action="stop"]').exists()).toBe(true)
    expect(panel.find('[data-action="detail"]').exists()).toBe(true)
    w.unmount()
  })

  it('停止策略：确认后 POST 再刷新策略 tab', async () => {
    const w = mountView()
    await flushPromises()
    await w.find('[data-tab="strategy"]').trigger('click')
    await flushPromises()

    await w.find('[data-action="stop"]').trigger('click')
    await flushPromises()

    expect(uiMocks.confirmDialog).toHaveBeenCalledTimes(1)
    expect(apiPost).toHaveBeenCalledTimes(1)
    expect(apiPost).toHaveBeenCalledWith(EP.accountStrategyCenterStatus('7'), { status: 'STOPPED' })
    expect(callsFor(EP.accountStrategyCenter)).toBe(2)
    expect(uiMocks.toast).toHaveBeenCalled()
    expect(String(uiMocks.toast.mock.calls[0][0])).toContain('已停止')
    w.unmount()
  })

  it('停止策略：确认弹窗取消时发 HTTP 请求前就停下', async () => {
    uiMocks.confirmDialog.mockResolvedValue(false)
    const w = mountView()
    await flushPromises()
    await w.find('[data-tab="strategy"]').trigger('click')
    await flushPromises()

    await w.find('[data-action="stop"]').trigger('click')
    await flushPromises()

    expect(apiPost).not.toHaveBeenCalled()
    expect(callsFor(EP.accountStrategyCenter)).toBe(1)
    w.unmount()
  })

  it('详情复用策略详情接口并在下方展开', async () => {
    const w = mountView()
    await flushPromises()
    await w.find('[data-tab="strategy"]').trigger('click')
    await flushPromises()

    await w.find('[data-action="detail"]').trigger('click')
    await flushPromises()

    expect(apiGet).toHaveBeenCalledWith(EP.strategyStrategy('7'))
    const detail = w.find('#oc-strategy-detail')
    expect(detail.text()).toContain('BTC 马丁')
    expect(detail.text()).toContain('BTC-USDT-SWAP')
    w.unmount()
  })
})
