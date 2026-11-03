import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ShadowView from '../src/views/ShadowView.vue'
import {
  SHADOW_PF_SHOW,
  collectErrors,
  drawEquityChart,
  equityExtent,
  equityPoints,
  metricFold,
  shadowHealthCols,
  shadowHealthMeta,
  shadowOrderCols,
  shadowOrderInfo,
  shadowPfMeta,
  shadowPortfolioKv,
  shadowPositionCols,
  shadowPosInfo,
  shadowStatsCards,
  shadowStatusKv,
  sideCell,
  signedMoney,
  statusPortfolio,
  timeText
} from '../src/views/shadow/logic'
import { apiGet, apiPost } from '../src/api/client'
import { EP } from '../src/api/endpoints'

vi.mock('../src/api/client', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiRawGet: vi.fn()
}))

vi.mock('../src/stores/ui', () => ({
  useUiStore: () => ({
    toast: vi.fn(),
    confirmDialog: vi.fn().mockResolvedValue(true)
  })
}))

describe('shadow/logic 纯函数', () => {
  it('sideCell 给 long/buy 绿色、short/sell 红色', () => {
    expect(sideCell('long')).toContain('val-pos')
    expect(sideCell('buy')).toContain('val-pos')
    expect(sideCell('short')).toContain('val-neg')
    expect(sideCell('sell')).toContain('val-neg')
    expect(sideCell(null)).toBe('-')
  })

  it('signedMoney 正数加 + 号并标绿，负数标红', () => {
    expect(signedMoney(12.345)).toContain('+12.35')
    expect(signedMoney(12.345)).toContain('val-pos')
    expect(signedMoney(-12.345)).toContain('val-neg')
    expect(signedMoney(-12.345)).toContain('-12.35')
    expect(signedMoney(0)).toBe('0')
    expect(signedMoney(null)).toBe('-')
  })

  it('timeText 支持毫秒数与服务端 ISO 时间', () => {
    expect(timeText(null)).toBe('-')
    expect(timeText(1700000000000)).toMatch(/^\d{4}-\d{2}-\d{2} /)
    expect(timeText('2024-01-02T03:04:05Z')).toMatch(/^\d{4}-\d{2}-\d{2} /)
  })

  it('metricFold 折叠 JSON，解析失败原样输出', () => {
    expect(metricFold(null)).toContain('muted')
    expect(metricFold('{"a":1}')).toContain('metric-fold')
    expect(metricFold('{"a":1}')).toContain('&quot;a&quot;')
    expect(metricFold('not-json')).toContain('not-json')
  })

  it('collectErrors 用中文分号拼接 Error.message', () => {
    expect(collectErrors([new Error('a'), 'x', new Error('b')])).toBe('a；b')
    expect(collectErrors([null])).toBe('')
  })

  it('shadowStatsCards 输出 4 张卡并带耗时与体检摘要', () => {
    const cards = shadowStatsCards({
      strategies: 1,
      openPositions: 4,
      lastTickAtMs: 1700000000000,
      lastTickDurationMs: 213,
      lastHealthSummary: 'ok=11 warn=4 fail=0 autoExited=0'
    })
    expect(cards).toHaveLength(4)
    expect(cards[0]).toMatchObject({ label: '策略数', value: '1' })
    expect(cards[1].sub).toBe('OPEN 状态')
    expect(cards[2].sub).toBe('耗时 213 ms')
    expect(cards[2].time).toBe(true)
    expect(cards[3].sub).toBe('ok=11 warn=4 fail=0 autoExited=0')
  })

  it('shadowStatsCards 缺字段时用占位', () => {
    const cards = shadowStatsCards({})
    expect(cards[2].value).toBe('-')
    expect(cards[2].sub).toBe('耗时 -')
    expect(cards[3].sub).toBe('尚未体检')
  })

  it('shadowStatusKv 输出 6 项 kv', () => {
    const kv = shadowStatusKv({ strategies: 1, openPositions: 4, lastTickDurationMs: 213 })
    expect(kv).toHaveLength(6)
    expect(kv[1]).toEqual(['影子持仓数 openPositions', '4'])
    expect(kv[3]).toEqual(['最近 tick 耗时', '213 ms'])
    expect(kv[5]).toEqual(['最近体检摘要', '-'])
  })

  it('statusPortfolio 优先 latestPortfolio，回退 portfolio', () => {
    expect(statusPortfolio({ latestPortfolio: { equityUsd: 1 } })).toEqual({ equityUsd: 1 })
    expect(statusPortfolio({ portfolio: { equityUsd: 2 } })).toEqual({ equityUsd: 2 })
    expect(statusPortfolio({ latestPortfolio: null, portfolio: { equityUsd: 3 } })).toEqual({ equityUsd: 3 })
    expect(statusPortfolio({})).toBeNull()
  })

  it('shadowPortfolioKv 金额带 USD 后缀', () => {
    const kv = shadowPortfolioKv({ equityUsd: 466.51, availableUsd: 400.36, tsMs: 1700000000000 })
    expect(kv[0][1]).toBe('466.51 USD')
    expect(kv[1][1]).toBe('400.36 USD')
    expect(kv[6][1]).toMatch(/^\d{4}-\d{2}-\d{2} /)
  })

  it('列表说明文案区分空与非空', () => {
    expect(shadowPosInfo(0)).toBe('暂无数据')
    expect(shadowPosInfo(4)).toContain('已加载 4 条 OPEN 持仓')
    expect(shadowPosInfo(4)).toContain('接口无 total 字段')
    expect(shadowOrderInfo(0)).toBe('暂无数据')
    expect(shadowOrderInfo(6)).toContain('已加载 6 条')
    expect(shadowHealthMeta(0)).toBe('取自 status.latestHealth')
    expect(shadowHealthMeta(15)).toBe('取自 status.latestHealth · 15 条')
  })

  it('shadowPfMeta 取最新快照与表内条数', () => {
    expect(shadowPfMeta([])).toBe('GET /portfolio?limit=200')
    const meta = shadowPfMeta([{ equityUsd: 466.51, availableUsd: 400.36 }])
    expect(meta).toContain('已加载 1 条')
    expect(meta).toContain('最新 equity 466.51 / available 400.36')
    expect(meta).toContain('表内仅列最近 1 条')
    expect(SHADOW_PF_SHOW).toBe(20)
  })

  it('持仓列包含平仓按钮与 11 列', () => {
    const cols = shadowPositionCols()
    expect(cols).toHaveLength(11)
    const html = cols[10].render!({ id: 9 })
    expect(html).toContain('data-shadow-close="9"')
    expect(html).toContain('平仓')
    expect(cols[6].render!({ leverage: 5 })).toBe('5x')
    expect(cols[6].render!({})).toBe('-')
  })

  it('订单列时间取 decidedAt，否则 signalTsMs', () => {
    const cols = shadowOrderCols()
    expect(cols).toHaveLength(8)
    expect(cols[0].render!({ decidedAt: 1700000000000 })).toContain('mono')
    expect(cols[0].render!({ signalTsMs: 1700000000000 })).toMatch(/\d{4}-\d{2}-\d{2}/)
    expect(cols[4].render!({ status: 'FILLED' })).toContain('data-status="FILLED"')
  })

  it('体检列 6 列且 instId 空值给 -', () => {
    const cols = shadowHealthCols()
    expect(cols).toHaveLength(6)
    expect(cols[3].render!({})).toContain('>-<')
    expect(cols[1].render!({ level: 'warn' })).toContain('data-status="WARN"')
  })

  it('equityPoints 取最近 200 条并转正序', () => {
    const rows = [
      { tsMs: 3, equityUsd: 30 },
      { tsMs: 2, equityUsd: 20 },
      { tsMs: 1, equityUsd: 10 }
    ]
    expect(equityPoints(rows).map((r) => r.tsMs)).toEqual([1, 2, 3])
    expect(equityPoints(null as never)).toEqual([])
  })

  it('equityExtent 计算区间并给单点值补 padding', () => {
    expect(equityExtent([])).toBeNull()
    expect(equityExtent([{ equityUsd: 'x' }])).toBeNull()
    expect(equityExtent([{ equityUsd: 10 }, { equityUsd: 20 }])).toEqual({ min: 10, max: 20 })
    const flat = equityExtent([{ equityUsd: 100 }])
    expect(flat).not.toBeNull()
    expect(flat!.min).toBeLessThan(100)
    expect(flat!.max).toBeGreaterThan(100)
  })

  it('drawEquityChart 空数据画占位文字，不抛异常', () => {
    const texts: string[] = []
    const ctx = {
      setTransform: vi.fn(),
      clearRect: vi.fn(),
      fillText: (t: string) => texts.push(t),
      beginPath: vi.fn(),
      moveTo: vi.fn(),
      lineTo: vi.fn(),
      stroke: vi.fn(),
      fill: vi.fn(),
      arc: vi.fn(),
      set fillStyle(_v: string) {},
      set strokeStyle(_v: string) {},
      set lineWidth(_v: number) {},
      set font(_v: string) {},
      set textAlign(_v: string) {},
      set textBaseline(_v: string) {}
    } as unknown as CanvasRenderingContext2D
    const canvas = {
      parentNode: { clientWidth: 600, clientHeight: 240 },
      width: 0,
      height: 0,
      style: {},
      getContext: () => ctx
    } as unknown as HTMLCanvasElement
    drawEquityChart(canvas, [])
    expect(texts).toContain('暂无组合快照')
    expect(drawEquityChart(null, [])).toBeUndefined()
  })
})

describe('ShadowView 组件', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(apiGet).mockImplementation((path: string) => {
      if (path === EP.strategyShadowStatus) {
        return Promise.resolve({
          strategies: 1,
          openPositions: 1,
          lastTickAtMs: 1700000000000,
          lastTickDurationMs: 213,
          lastTickNote: 'opened=0 closed=0',
          lastHealthCheckAtMs: 1700000000000,
          lastHealthSummary: 'ok=11 warn=4 fail=0 autoExited=0',
          latestPortfolio: { equityUsd: 466.51, availableUsd: 400.36, positionCount: 1 },
          latestHealth: [{ rule: 'SHADOW_SCHEDULER', level: 'WARN', scope: 'engine', message: 'm' }]
        } as never)
      }
      if (path === EP.strategyShadowPositions) {
        return Promise.resolve([
          { id: 9, instId: 'BTC-USDT-SWAP', side: 'long', sz: 1, entryPx: 100, markPx: 110, unrealizedPnl: 10, leverage: 5, liqPx: 80, marginUsd: 100, openedAt: 1700000000000 }
        ] as never)
      }
      if (path === EP.accountOrders) {
        return Promise.resolve({
          state: 'history',
          items: [
            { instId: 'BTC-USDT-SWAP', posSide: 'long', side: 'buy', sz: 2, avgPx: 80000, lever: 5 }
          ]
        } as never)
      }
      if (path === EP.strategyShadowOpenPreflight) {
        return Promise.resolve({
          instId: 'BTC-USDT-SWAP',
          tradable: true,
          minSz: 1,
          lotSz: 0.1,
          notionalPerContractUsd: 100,
          minNotionalUsd: 100,
          lastPx: 80000,
          suggestedSz: 1,
          suggestedMarginUsd: 20,
          leverageMax: 125
        } as never)
      }
      if (path === EP.strategyShadowOrders) return Promise.resolve([] as never)
      if (path === EP.strategyShadowPortfolio) return Promise.resolve([] as never)
      return Promise.resolve(null as never)
    })
    vi.mocked(apiPost).mockResolvedValue({ exitPx: 110, unrealizedPnl: 10 } as never)
  })

  it('渲染状态卡、持仓与平仓委托', async () => {
    const w = mount(ShadowView, { global: { plugins: [createPinia()] } })
    await flushPromises()
    expect(apiGet).toHaveBeenCalledWith(EP.strategyShadowPositions, { status: 'OPEN', limit: 100, offset: 0 })
    const text = w.text()
    expect(text).toContain('影子交易')
    expect(text).toContain('策略数')
    expect(text).toContain('BTC-USDT-SWAP')
    expect(text).toContain('466.51 USD')
    expect(text).toContain('取自 status.latestHealth · 1 条')
    const btn = w.find('button[data-shadow-close="9"]')
    expect(btn.exists()).toBe(true)
    await btn.trigger('click')
    await flushPromises()
    expect(apiPost).toHaveBeenCalledWith('/api/v1/strategy/shadow/positions/9/close')
    w.unmount()
  })

  it('复制开仓弹窗：打开后按 preflight 回填默认张数/价格，提交带上完整 body', async () => {
    const w = mount(ShadowView, { global: { plugins: [createPinia()] } })
    await flushPromises()

    const openBtn = w.find('button[data-action="copy-open"]')
    expect(openBtn.exists()).toBe(true)
    await openBtn.trigger('click')
    await flushPromises()

    const dialog = w.find('[data-dialog="shadow-copy-open"]')
    expect(dialog.exists()).toBe(true)
    expect(dialog.text()).toContain('只写入影子账本')
    expect(apiGet).toHaveBeenCalledWith(EP.accountOrders, { state: 'history', instType: 'SWAP', limit: 100 })

    // 从最近真实订单里选第一笔 → 回填合约并查询该合约的最小下单量
    await w.find('select[data-field="recent"]').setValue('0')
    await flushPromises()
    expect(apiGet).toHaveBeenCalledWith(EP.strategyShadowOpenPreflight, { instId: 'BTC-USDT-SWAP' })

    expect((w.find('input[data-field="instId"]').element as HTMLInputElement).value).toBe('BTC-USDT-SWAP')
    expect((w.find('input[data-field="sz"]').element as HTMLInputElement).value).toBe('1')
    expect((w.find('input[data-field="px"]').element as HTMLInputElement).value).toBe('80000')
    expect(w.text()).toContain('平台最小张数 minSz')

    await w.find('button[data-action="submit"]').trigger('click')
    await flushPromises()
    expect(apiPost).toHaveBeenCalledWith(EP.strategyShadowOpen, {
      instId: 'BTC-USDT-SWAP',
      side: 'long',
      sz: 1,
      px: 80000,
      leverage: 5,
      params: { takeProfitPct: 2, stopLossPct: 25, maxLayers: 5 }
    })
    expect(w.find('[data-dialog="shadow-copy-open"]').exists()).toBe(false)
    w.unmount()
  })

  it('复制开仓失败时展示错误文案且弹窗不关闭', async () => {
    vi.mocked(apiPost).mockRejectedValueOnce(new Error('sz must be > 0') as never)
    const w = mount(ShadowView, { global: { plugins: [createPinia()] } })
    await flushPromises()

    await w.find('button[data-action="copy-open"]').trigger('click')
    await flushPromises()
    await w.find('select[data-field="recent"]').setValue('0')
    await flushPromises()
    await w.find('button[data-action="submit"]').trigger('click')
    await flushPromises()

    expect(w.find('[data-dialog="shadow-copy-open"]').exists()).toBe(true)
    expect(w.text()).toContain('复制开仓失败：sz must be > 0')
    w.unmount()
  })
})
