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
import StrategiesView from '../src/views/StrategiesView.vue'
import {
  STRATEGIES_LIMIT,
  STRATEGY_EMPTY,
  STRATEGY_PARAM_LABELS,
  strategiesMetaText,
  strategyDetailPairs,
  strategyParamPairs,
  strategyPositionsOf
} from '../src/views/account/acc'

type Mocked = {
  mockImplementation: (f: (p: string, q?: unknown) => unknown) => void
  mockRejectedValue: (v: unknown) => void
  mock: { calls: unknown[][] }
}
const mocked = apiGet as unknown as Mocked

const runningDefinition = "mode='SHADOW' AND status IN ('CANDIDATE','PROMOTED')（与影子引擎同口径）"

const row = {
  id: 1,
  name: 'bt-2-28d65e40',
  status: 'CANDIDATE',
  mode: 'SHADOW',
  running: true,
  capitalPerCoinUsd: 100.0,
  sourceRunId: 2,
  note: 'P2 promoted',
  openPositions: 4,
  closedPositions: 2,
  openNotionalUsd: 2001.82,
  unrealizedPnlUsd: 13.63,
  orderCount: 8,
  lastOrderAt: '2026-11-02T04:00:00',
  createdAt: '2026-11-01T10:00:00',
  updatedAt: '2026-11-02T04:00:00'
}

const detail = {
  ...row,
  params: {
    side: 'long',
    leverage: 5,
    maxLayers: 8,
    layerScale: 1.5,
    layerStepPct: 1.5,
    takeProfitPct: 2.0,
    stopLossPct: 25.0,
    maxHoldHours: 48,
    feeTaker: 0.0005,
    fundingPer8hPct: 0.01,
    initialMarginUsd: 100.0,
    slippageBps: 2.0
  },
  universe: { instIds: ['BTC-USDT-SWAP', 'ETH-USDT-SWAP', 'SOL-USDT-SWAP', 'DOGE-USDT-SWAP'] },
  positions: [
    {
      id: 21,
      strategyId: 1,
      instId: 'BTC-USDT-SWAP',
      side: 'long',
      sz: '0.01',
      entryPx: '77000.5',
      markPx: '77896.37616',
      notionalUsd: '778.96',
      unrealizedPnl: '8.96',
      leverage: 5.0,
      liqPx: '62000',
      marginUsd: '155.79',
      status: 'OPEN',
      openedAt: '2026-11-02T02:00:00'
    }
  ],
  recentOrders: [
    {
      id: 8,
      strategyId: 1,
      instId: 'BTC-USDT-SWAP',
      side: 'buy',
      ordType: 'market',
      sz: '0.01',
      px: '77896.37616',
      notionalUsd: '778.96',
      status: 'FILLED',
      reason: 'OPEN',
      decidedAt: '2026-11-02T04:30:00'
    }
  ]
}

function mockAll() {
  mocked.mockImplementation((path: string, params?: unknown) => {
    if (path === EP.strategyStrategies) {
      const state = String((params as { state?: string } | undefined)?.state || '')
      if (state === 'running') {
        return Promise.resolve({ state: 'running', total: 1, runningDefinition: runningDefinition, items: [row] })
      }
      return Promise.resolve({ state: 'history', total: 0, items: [] })
    }
    if (path === EP.strategyStrategy('1')) return Promise.resolve(detail)
    return Promise.reject(new Error('unexpected path ' + path))
  })
}

function mountView() {
  return mount(StrategiesView, { global: { plugins: [createPinia()] } })
}

describe('strategies 页纯逻辑（legacy §16c 1:1）', () => {
  it('meta 文案：有数据带 limit，空列表只写「共 0 个」', () => {
    expect(strategiesMetaText(1)).toBe('共 1 个 · limit=' + STRATEGIES_LIMIT)
    expect(strategiesMetaText(0)).toBe('共 0 个')
    expect(STRATEGY_EMPTY.running).toBe('暂无运行中的马丁策略')
    expect(STRATEGY_EMPTY.history).toBe('暂无历史策略')
  })

  it('详情持仓兼容新契约 positions[] 与旧契约 openPositions[]', () => {
    expect(strategyPositionsOf({ positions: [{ id: 1 }] }).length).toBe(1)
    expect(strategyPositionsOf({ openPositions: [{ id: 2 }] }).length).toBe(1)
    expect(strategyPositionsOf({ openPositions: 4 }).length).toBe(0)
    expect(strategyPositionsOf(null).length).toBe(0)
  })

  it('参数标签映射成中文，值走数值格式化', () => {
    const pairs = strategyParamPairs({ params: { side: 'long', leverage: 5, feeTaker: 0.0005 } })
    expect(pairs[0]).toEqual({ key: STRATEGY_PARAM_LABELS.side, html: 'long' })
    expect(pairs[1]).toEqual({ key: STRATEGY_PARAM_LABELS.leverage, html: '5' })
    expect(pairs[2]).toEqual({ key: STRATEGY_PARAM_LABELS.feeTaker, html: '0.0005' })
    expect(strategyParamPairs({ params: {} }).length).toBe(0)
  })

  it('详情概览把布尔转成 是/否、盈亏带涨跌色', () => {
    const pairs = strategyDetailPairs({ running: true, unrealizedPnlUsd: -1.5, status: 'CANDIDATE' }, 1)
    const running = pairs.find((p) => p.key === '运行中 running')
    expect(running?.html).toBe('是')
    const pnl = pairs.find((p) => p.key === '未实现盈亏 USD')
    expect(pnl?.html).toContain('val-neg')
    const st = pairs.find((p) => p.key === '状态 status')
    expect(st?.html).toContain('data-status="CANDIDATE"')
  })
})

describe('StrategiesView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
    mockAll()
  })

  it('首屏请求 running 列表并渲染口径、id=1 行与关键列', async () => {
    const w = mountView()
    await flushPromises()
    expect(apiGet).toHaveBeenCalledWith(EP.strategyStrategies, { state: 'running', limit: STRATEGIES_LIMIT })
    const text = w.text()
    expect(text).toContain("口径：mode='SHADOW'")
    expect(text).toContain('bt-2-28d65e40')
    expect(text).toContain('CANDIDATE')
    expect(text).toContain('4 / 2')
    expect(text).toContain('2,001.82')
    expect(text).toContain('+13.63')
    expect(text).toContain('共 1 个 · limit=50')
    expect(w.find('#strategies-running-wrap .val-pos').text()).toBe('+13.63')
    expect(w.find('#strategies-running-wrap .badge').attributes('data-status')).toBe('CANDIDATE')
    w.unmount()
  })

  it('点击行展开详情（GET /strategies/{id}），再次点击收起', async () => {
    const w = mountView()
    await flushPromises()
    const tr = w.find('#strategies-running-wrap tbody tr')
    await tr.trigger('click')
    await flushPromises()
    await flushPromises()
    expect(apiGet).toHaveBeenCalledWith('/api/v1/strategy/strategies/1')
    expect(w.find('.row-link').classes()).toContain('is-selected')
    const card = w.find('#strategies-detail-card')
    expect(card.attributes('style') || '').not.toContain('display: none')
    const text = card.text()
    expect(text).toContain('参数 params（12）')
    expect(text).toContain('自选合约 universe（4）')
    expect(text).toContain('BTC-USDT-SWAP')
    expect(text).toContain('当前持仓 positions（1）')
    expect(text).toContain('最近订单 recentOrders（1）')

    await tr.trigger('click')
    await flushPromises()
    expect(w.find('#strategies-detail-card').attributes('style')).toContain('display: none')
    expect(w.find('.row-link').classes()).not.toContain('is-selected')
    w.unmount()
  })

  it('切到历史 tab 收起详情并按 state=history 请求', async () => {
    const w = mountView()
    await flushPromises()
    await w.find('#strategies-running-wrap tbody tr').trigger('click')
    await flushPromises()
    const tabs = w.findAll('.tab')
    await tabs[1].trigger('click')
    await flushPromises()
    expect(apiGet).toHaveBeenCalledWith(EP.strategyStrategies, { state: 'history', limit: STRATEGIES_LIMIT })
    expect(w.find('#strategies-detail-card').attributes('style')).toContain('display: none')
    expect(w.find('#strategies-history-wrap').text()).toContain('暂无历史策略')
    w.unmount()
  })

  it('列表接口失败时顶部与表内都给出错误文案', async () => {
    mocked.mockRejectedValue(new Error('boom'))
    const w = mountView()
    await flushPromises()
    expect(w.find('.alert.danger').text()).toBe('策略加载失败（state=running）：boom')
    expect(w.find('#strategies-running-wrap').text()).toContain('策略加载失败：boom')
    w.unmount()
  })
})
