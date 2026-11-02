import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('../src/api/client', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiRawGet: vi.fn()
}))

import { apiGet } from '../src/api/client'
import KlinesView from '../src/views/KlinesView.vue'
import {
  aggregateCandles,
  buildChartHint,
  chartLineColor,
  chartMetaText,
  klineSummaryText,
  movingAverage,
  paddedPriceRange,
  priceLabel,
  ratioPeriodOfBar,
  ratioScale
} from '../src/views/klines/chartModel'
import {
  ascendingRows,
  buildKlineQuery,
  klineLimitSuffix,
  klineMeta,
  quickRangeValues,
  readIntField
} from '../src/views/klines/form'
import { ensureRatio, loadChartSeries, loadRatioPoints } from '../src/views/klines/data'
import { fmtTime } from '../src/lib/format'
import { useUiStore } from '../src/stores/ui'

/* canvas：jsdom 没有 2d 上下文，用桩对象接住所有绘制调用（顺带断言不抛错） */
const ctxStub: Record<string, any> = {}
for (const k of [
  'setTransform',
  'clearRect',
  'fillText',
  'beginPath',
  'moveTo',
  'lineTo',
  'stroke',
  'fillRect',
  'save',
  'restore',
  'translate',
  'rotate',
  'setLineDash'
]) {
  ctxStub[k] = vi.fn()
}
vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation((() => ctxStub) as any)

const KROWS = [
  { tsMs: 1700000000000, open: 60000, high: 60500, low: 59500, close: 60250, vol: 10, volCcyQuote: 602500, confirm: true },
  { tsMs: 1700003600000, open: 60250, high: 61000, low: 60000, close: 60100, vol: 20, volCcyQuote: 1202000, confirm: false }
]

function mockApi(rows: unknown = KROWS) {
  ;(apiGet as unknown as { mockImplementation: (f: (p: string) => Promise<unknown>) => void }).mockImplementation(
    (path: string) => {
      if (path === '/api/v1/klines') return Promise.resolve(rows)
      if (path === '/api/v1/ratios/long-short') return Promise.resolve([])
      return Promise.resolve([])
    }
  )
}

/* 每次挂载用独立 pinia，测试里还能按需读回 toast 队列 */
let lastPinia: ReturnType<typeof createPinia>

function mountView() {
  lastPinia = createPinia()
  return mount(KlinesView, { global: { plugins: [lastPinia] } })
}

async function submitQuery(w: ReturnType<typeof mountView>) {
  await w.find('form').trigger('submit')
  await flushPromises()
  await flushPromises()
  await flushPromises()
}

/* ===================== 表单校验与参数组装 ===================== */

describe('klines 表单（buildKlineQuery）', () => {
  const base = {
    instId: ' BTC-USDT-SWAP ',
    bar: '1H',
    klineType: 'CANDLE',
    order: 'desc',
    limitRaw: '500',
    startRaw: '',
    endRaw: ''
  }

  it('默认参数：instId 去空格、limit=500、无 startMs/endMs 键', () => {
    const r = buildKlineQuery(base)
    expect(r.ok).toBe(true)
    if (!r.ok) return
    expect(r.instId).toBe('BTC-USDT-SWAP')
    expect(r.params).toEqual({
      instId: 'BTC-USDT-SWAP',
      bar: '1H',
      klineType: 'CANDLE',
      order: 'desc',
      limit: 500
    })
    expect('startMs' in r.params).toBe(false)
    expect('endMs' in r.params).toBe(false)
    expect(r.startMs).toBeNull()
    expect(r.endMs).toBeNull()
  })

  it('klineType / order 留空时回落到 CANDLE / desc', () => {
    const r = buildKlineQuery({ ...base, klineType: '', order: '' })
    expect(r.ok).toBe(true)
    if (!r.ok) return
    expect(r.params.klineType).toBe('CANDLE')
    expect(r.params.order).toBe('desc')
    expect(r.klineType).toBe('CANDLE')
  })

  it('limit 留空则不下发 limit 参数', () => {
    const r = buildKlineQuery({ ...base, limitRaw: '  ' })
    expect(r.ok).toBe(true)
    if (!r.ok) return
    expect('limit' in r.params).toBe(false)
    expect(r.limit).toBeUndefined()
  })

  it('时间范围转为毫秒并进入 params', () => {
    const r = buildKlineQuery({ ...base, startRaw: '2026-01-01T00:00', endRaw: '2026-01-02T00:00' })
    expect(r.ok).toBe(true)
    if (!r.ok) return
    expect(typeof r.params.startMs).toBe('number')
    expect(typeof r.params.endMs).toBe('number')
    expect(Number(r.params.endMs) - Number(r.params.startMs)).toBe(86400000)
  })

  it('instId 为空：错误条与 toast 文案与 legacy 一致', () => {
    const r = buildKlineQuery({ ...base, instId: '   ' })
    expect(r.ok).toBe(false)
    if (r.ok) return
    expect(r.alert).toBe('请填写 instId（例如 BTC-USDT-SWAP，只支持永续合约）')
    expect(r.toast).toBe('请填写 instId')
  })

  it('limit 非法：只 toast、不写错误条（legacy readIntField 行为）', () => {
    for (const raw of ['0', '5001', '1.5', 'abc']) {
      const r = buildKlineQuery({ ...base, limitRaw: raw })
      expect(r.ok).toBe(false)
      if (r.ok) return
      expect(r.alert).toBe('')
      expect(r.toast).toBe('limit 需为 1 ~ 5000 的整数')
    }
  })

  it('开始时间不早于结束时间：错误条 + toast', () => {
    const r = buildKlineQuery({ ...base, startRaw: '2026-01-02T00:00', endRaw: '2026-01-02T00:00' })
    expect(r.ok).toBe(false)
    if (r.ok) return
    expect(r.alert).toBe('开始时间必须早于结束时间')
    expect(r.toast).toBe('开始时间必须早于结束时间')
  })

  it('校验优先级：instId 先于 limit，limit 先于时间区间', () => {
    const a = buildKlineQuery({ ...base, instId: '', limitRaw: '0' })
    expect(a.ok).toBe(false)
    if (a.ok) return
    expect(a.toast).toBe('请填写 instId')

    const b = buildKlineQuery({ ...base, limitRaw: '0', startRaw: '2026-01-02T00:00', endRaw: '2026-01-01T00:00' })
    expect(b.ok).toBe(false)
    if (b.ok) return
    expect(b.toast).toBe('limit 需为 1 ~ 5000 的整数')
    expect(b.alert).toBe('')
  })

  it('readIntField：空 -> undefined，合法 -> 数字，非法 -> 报错文案', () => {
    expect(readIntField('', 1, 5000, 'limit')).toEqual({ ok: true, value: undefined })
    expect(readIntField(' 42 ', 1, 5000, 'limit')).toEqual({ ok: true, value: 42 })
    const bad = readIntField('9000', 1, 5000, 'limit')
    expect(bad.ok).toBe(false)
    if (bad.ok) return
    expect(bad.message).toBe('limit 需为 1 ~ 5000 的整数')
  })

  it('快捷时间：近 N 天填充 start/end（datetime-local 格式）', () => {
    const now = new Date(2026, 10, 2, 12, 30).getTime()
    const r = quickRangeValues(7, now)
    expect(r.end).toBe('2026-11-02T12:30')
    expect(r.start).toBe('2026-10-26T12:30')
  })

  it('汇总文案与 limit 截断后缀', () => {
    expect(klineMeta('BTC-USDT-SWAP', '1H', 'CANDLE')).toBe('BTC-USDT-SWAP · 1H · CANDLE')
    expect(klineLimitSuffix(500, 500)).toBe('表格已满 limit=500（仅一页），完整区间见上方图表')
    expect(klineLimitSuffix(500, 499)).toBe('')
    expect(klineLimitSuffix(undefined, 900)).toBe('')
    expect(ascendingRows(KROWS).map((r) => r.tsMs)).toEqual([1700000000000, 1700003600000])
    expect(ascendingRows(KROWS.slice().reverse()).map((r) => r.tsMs)).toEqual([1700000000000, 1700003600000])
  })
})

/* ===================== 图表纯逻辑 ===================== */

describe('klines 图表数据变换', () => {
  it('ratioPeriodOfBar：m -> 5m，H -> 1H，其它 -> 1D，utc 后缀先剥离', () => {
    expect(ratioPeriodOfBar('1m')).toBe('5m')
    expect(ratioPeriodOfBar('30m')).toBe('5m')
    expect(ratioPeriodOfBar('1H')).toBe('1H')
    expect(ratioPeriodOfBar('6Hutc')).toBe('1H')
    expect(ratioPeriodOfBar('1D')).toBe('1D')
    expect(ratioPeriodOfBar('1Dutc')).toBe('1D')
    expect(ratioPeriodOfBar('1W')).toBe('1D')
    expect(ratioPeriodOfBar('')).toBe('1D')
  })

  it('movingAverage：不足窗口期为 null，遇到 null 断档后重新累计', () => {
    expect(movingAverage([1, 2, 3, 4, 5], 3)).toEqual([null, null, 2, 3, 4])
    expect(movingAverage([1, null, 3, 4, 5], 2)).toEqual([null, null, 1.5, 3.5, 4.5])
  })

  it('aggregateCandles：列数够时原样返回，超了按列聚合 OHLC', () => {
    const plain = aggregateCandles(KROWS, 100)
    expect(plain.groupSize).toBe(1)
    expect(plain.rows.length).toBe(2)

    const rows = [
      { tsMs: 10, open: 1, high: 5, low: 0.5, close: 4, vol: 1 },
      { tsMs: 11, open: 4, high: 6, low: 3, close: 5, vol: 2 },
      { tsMs: 12, open: 5, high: 7, low: 2, close: 3, vol: 3 },
      { tsMs: 13, open: 3, high: 4, low: 1, close: 2, vol: 4 },
      { tsMs: 14, open: 2, high: 3, low: 0, close: 1, vol: 5 }
    ]
    const agg = aggregateCandles(rows, 2)
    expect(agg.groupSize).toBe(3)
    expect(agg.rows.length).toBe(2)
    expect(agg.rows[0]).toMatchObject({ tsMs: 10, open: 1, close: 3, high: 7, low: 0.5, vol: 6, merged: 3 })
    expect(agg.rows[1]).toMatchObject({ tsMs: 13, open: 3, close: 1, high: 4, low: 0, vol: 9, merged: 2 })
  })

  it('paddedPriceRange：上下各留 6%，高=低时扩成 ±1 再留余量，无有效 OHLC 返回 null', () => {
    const agg = aggregateCandles(
      [
        { tsMs: 1, open: 100, high: 110, low: 100, close: 105 },
        { tsMs: 2, open: 105, high: 108, low: 104, close: 106 }
      ],
      10
    )
    const r = paddedPriceRange(agg.rows)
    expect(r).not.toBeNull()
    if (!r) return
    expect(r.min).toBeCloseTo(99.4, 6)
    expect(r.max).toBeCloseTo(110.6, 6)

    const flat = paddedPriceRange([{ tsMs: 1, open: 50, close: 50, high: 50, low: 50, vol: 0, merged: 1 }])
    expect(flat).not.toBeNull()
    if (!flat) return
    expect(flat.min).toBeCloseTo(48.88, 6)
    expect(flat.max).toBeCloseTo(51.12, 6)

    expect(paddedPriceRange([{ tsMs: 1, open: NaN, close: NaN, high: NaN, low: NaN, vol: 0, merged: 1 }])).toBeNull()
  })

  it('ratioScale：必须包含 1.0，最小跨度 0.1，再留 8% 余量', () => {
    const s = ratioScale(
      [
        [
          [100, 1.05],
          [200, 1.1]
        ]
      ],
      100,
      200
    )
    expect(s.min).toBeCloseTo(0.992, 6)
    expect(s.max).toBeCloseTo(1.108, 6)

    const empty = ratioScale([], 0, 1)
    expect(empty.min).toBeCloseTo(0.768, 6)
    expect(empty.max).toBeCloseTo(1.232, 6)

    const below = ratioScale(
      [
        [
          [100, 0.9],
          [200, 0.95]
        ]
      ],
      100,
      200
    )
    expect(below.min).toBeCloseTo(0.892, 6)
    expect(below.max).toBeCloseTo(1.008, 6)

    /* 区间外的点不参与刻度 */
    const clipped = ratioScale(
      [
        [
          [10, 5],
          [100, 1.2],
          [200, 1.3],
          [300, 9]
        ]
      ],
      100,
      200
    )
    expect(clipped.max).toBeCloseTo(1.3 + (1.3 - 1) * 0.08, 6)
  })

  it('priceLabel：>=1000 千分位取整，>=1 两位小数，<1 六位小数', () => {
    expect(priceLabel(1500.4)).toBe('1,500')
    expect(priceLabel(-1234.6)).toBe('-1,235')
    expect(priceLabel(12.3456)).toBe('12.35')
    expect(priceLabel(0.123456789)).toBe('0.123457')
  })

  it('chartLineColor：价格线 / 多空比线 / 未知键回落 COLOR_AXIS', () => {
    expect(chartLineColor('close')).toBe('#2f6fed')
    expect(chartLineColor('ma60')).toBe('#0ea5e9')
    expect(chartLineColor('ratioPos')).toBe('#0f766e')
    expect(chartLineColor('ratioAcc')).toBe('#c2410c')
    expect(chartLineColor('ratioLsr')).toBe('#db2777')
    expect(chartLineColor('nope')).toBe('#98a2b3')
  })

  it('buildChartHint：区间/根数/折线/聚合/截断/多空比三种状态', () => {
    const opts = {
      close: true,
      ma5: true,
      ma20: false,
      ma60: false,
      ratioPos: false,
      ratioAcc: false,
      ratioLsr: false
    }
    const hint = buildChartHint({
      rows: KROWS,
      groupSize: 1,
      colCount: 2,
      truncated: false,
      opts,
      ratioLabels: [],
      ratioEnabled: false,
      ratioPeriod: null,
      hasRatioCache: false
    })
    expect(hint).toBe('区间 ' + fmtTime(KROWS[0].tsMs) + ' → ' + fmtTime(KROWS[1].tsMs) + ' · 共 2 根 · 折线：收盘价 / MA5')

    const aggHint = buildChartHint({
      rows: KROWS,
      groupSize: 3,
      colCount: 1,
      truncated: true,
      opts: { ...opts, close: false, ma5: false },
      ratioLabels: [],
      ratioEnabled: false,
      ratioPeriod: null,
      hasRatioCache: false
    })
    expect(aggHint).toContain('按 3 根/列聚合为 1 列')
    expect(aggHint).toContain('超出上限，仅显示最近 2 根')
    expect(aggHint).toContain('折线：未选择')

    const ratioHint = buildChartHint({
      rows: KROWS,
      groupSize: 1,
      colCount: 2,
      truncated: false,
      opts,
      ratioLabels: ['精英持仓多空比'],
      ratioEnabled: true,
      ratioPeriod: '1H',
      hasRatioCache: true
    })
    expect(ratioHint).toContain('多空比(右轴 1H)：精英持仓多空比')

    const cachedEmpty = buildChartHint({
      rows: KROWS,
      groupSize: 1,
      colCount: 2,
      truncated: false,
      opts,
      ratioLabels: [],
      ratioEnabled: true,
      ratioPeriod: '1H',
      hasRatioCache: true
    })
    expect(cachedEmpty).toContain('多空比：库内该区间无数据（可先补拉 ratios）')

    const notLoaded = buildChartHint({
      rows: KROWS,
      groupSize: 1,
      colCount: 2,
      truncated: false,
      opts,
      ratioLabels: [],
      ratioEnabled: true,
      ratioPeriod: null,
      hasRatioCache: false
    })
    expect(notLoaded).toContain('多空比：尚未加载')
  })

  it('klineSummaryText / chartMetaText：与 legacy 文案逐字一致', () => {
    expect(klineSummaryText([], 'x', '')).toBe('暂无数据')
    expect(klineSummaryText(KROWS, 'BTC-USDT-SWAP · 1H · CANDLE', '')).toBe(
      '共 2 条 · BTC-USDT-SWAP · 1H · CANDLE'
    )
    expect(klineSummaryText(KROWS, '', '')).toBe('共 2 条 · ')
    expect(klineSummaryText(KROWS, 'm', '表格已满 limit=500（仅一页），完整区间见上方图表')).toBe(
      '共 2 条 · m · 表格已满 limit=500（仅一页），完整区间见上方图表'
    )
    expect(chartMetaText(0, 'CANDLE')).toBe('暂无数据')
    expect(chartMetaText(2, 'CANDLE')).toBe('共 2 根 · 蜡烛 K 线 · 绿涨红跌')
    expect(chartMetaText(2, 'MARK')).toBe('共 2 根 · 标记价格（无成交量字段） · 绿涨红跌')
  })
})

/* ===================== 数据加载（分页 / 缓存） ===================== */

describe('klines 数据加载', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('loadChartSeries：没有 startMs 时直接用表格结果升序返回', async () => {
    const series = await loadChartSeries(
      { instId: 'BTC-USDT-SWAP', bar: '1H', klineType: 'CANDLE', startMs: null, endMs: null },
      KROWS.slice().reverse()
    )
    expect(apiGet).not.toHaveBeenCalled()
    expect(series.rows.map((r) => r.tsMs)).toEqual([1700000000000, 1700003600000])
    expect(series.truncated).toBe(false)
  })

  it('loadChartSeries：有 startMs 时按页往前翻，参数带限一页/desc/startMs', async () => {
    ;(apiGet as unknown as { mockImplementation: (f: (p: string, q: any) => Promise<unknown>) => void }).mockImplementation(
      (path: string, q: any) => {
        expect(path).toBe('/api/v1/klines')
        expect(q).toMatchObject({ instId: 'BTC-USDT-SWAP', bar: '1H', klineType: 'CANDLE', order: 'desc', startMs: 100 })
        return Promise.resolve([
          { tsMs: 200, open: 1, high: 2, low: 0, close: 1 },
          { tsMs: 150, open: 1, high: 2, low: 0, close: 1 }
        ])
      }
    )
    const series = await loadChartSeries(
      { instId: 'BTC-USDT-SWAP', bar: '1H', klineType: 'CANDLE', startMs: 100, endMs: null },
      []
    )
    expect(series.rows.map((r) => r.tsMs)).toEqual([150, 200])
    expect(series.truncated).toBe(false)
  })

  it('loadRatioPoints：解析 tsMs/longShortRatio 并升序', async () => {
    ;(apiGet as unknown as { mockResolvedValue: (v: unknown) => void }).mockResolvedValue([
      { tsMs: 300, longShortRatio: 1.2 },
      { tsMs: 100, longShortRatio: 0.9 }
    ])
    const pts = await loadRatioPoints('TOP_TRADER_POSITION', 'BTC-USDT-SWAP', '1H', 0, null)
    expect(pts).toEqual([
      [100, 0.9],
      [300, 1.2]
    ])
  })

  it('ensureRatio：未勾选直接返回 null，命中缓存不重复请求', async () => {
    expect(
      await ensureRatio('BTC-USDT-SWAP', '1H', null, null, { enabled: false, cached: null })
    ).toBeNull()
    expect(apiGet).not.toHaveBeenCalled()

    const cached = {
      instId: 'BTC-USDT-SWAP',
      bar: '1H',
      period: '1H',
      startMs: null,
      endMs: null,
      series: { ratioPos: [], ratioAcc: [], ratioLsr: [] }
    }
    const hit = await ensureRatio('BTC-USDT-SWAP', '1H', null, null, { enabled: true, cached })
    expect(hit).toBe(cached)
    expect(apiGet).not.toHaveBeenCalled()
  })

  it('ensureRatio：勾选后按三类 rubik 指标各拉一次', async () => {
    ;(apiGet as unknown as { mockResolvedValue: (v: unknown) => void }).mockResolvedValue([])
    const loaded = await ensureRatio('BTC-USDT-SWAP', '1m', 100, 200, { enabled: true, cached: null })
    expect(loaded).not.toBeNull()
    if (!loaded) return
    expect(loaded.period).toBe('5m')
    expect(apiGet).toHaveBeenCalledTimes(3)
    const calls = (apiGet as unknown as { mock: { calls: any[][] } }).mock.calls
    expect(calls[0][1]).toMatchObject({ ratioType: 'TOP_TRADER_POSITION', scope: 'BTC-USDT-SWAP', period: '5m' })
    expect(calls[1][1]).toMatchObject({ ratioType: 'TOP_TRADER_ACCOUNT', scope: 'BTC-USDT-SWAP' })
    expect(calls[2][1]).toMatchObject({ ratioType: 'LSR_ACCOUNT_CONTRACT', scope: 'BTC-USDT-SWAP' })
  })
})

/* ===================== 组件级 ===================== */

describe('KlinesView 组件', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
  })

  it('挂载后不自动查询，表格与汇总显示「尚未查询」', async () => {
    mockApi()
    const w = mountView()
    await flushPromises()
    expect(apiGet).not.toHaveBeenCalled()
    expect(w.text()).toContain('尚未查询')
    w.unmount()
  })

  it('提交后按 legacy 参数请求 /api/v1/klines，并渲染表格/汇总/图表文案', async () => {
    mockApi()
    const w = mountView()
    await submitQuery(w)

    expect(apiGet).toHaveBeenCalledWith('/api/v1/klines', {
      instId: 'BTC-USDT-SWAP',
      bar: '1H',
      klineType: 'CANDLE',
      order: 'desc',
      limit: 500
    })
    const text = w.text()
    expect(text).toContain('共 2 条 · BTC-USDT-SWAP · 1H · CANDLE')
    expect(text).toContain('共 2 根 · 蜡烛 K 线 · 绿涨红跌')
    expect(text).toContain('60,000')
    expect(text).toContain('共 2 根')
    expect(text).toContain('折线：收盘价')
    expect(w.findAll('tbody tr').length).toBe(2)
    w.unmount()
  })

  it('快捷时间填充两个输入框，清空时间可恢复', async () => {
    mockApi()
    const w = mountView()
    const chips = w.findAll('.quick-group .chip')
    await chips[1].trigger('click')
    const start = w.find('#kline-start').element as HTMLInputElement
    const end = w.find('#kline-end').element as HTMLInputElement
    expect(start.value).not.toBe('')
    expect(end.value).not.toBe('')
    await chips[3].trigger('click')
    expect(start.value).toBe('')
    expect(end.value).toBe('')
    w.unmount()
  })

  it('instId 为空提交：错误条 + 不发请求', async () => {
    mockApi()
    const w = mountView()
    await w.find('.inst-input').setValue('   ')
    await submitQuery(w)
    expect(apiGet).not.toHaveBeenCalled()
    expect(w.text()).toContain('请填写 instId（例如 BTC-USDT-SWAP，只支持永续合约）')
    w.unmount()
  })

  it('接口失败：错误条 + 表格错误态', async () => {
    ;(apiGet as unknown as { mockRejectedValue: (v: unknown) => void }).mockRejectedValue(new Error('boom'))
    const w = mountView()
    await submitQuery(w)
    expect(w.text()).toContain('boom')
    expect(w.text()).toContain('查询失败：boom')
    w.unmount()
  })

  it('重置：回到初始文案并清空画布状态', async () => {
    mockApi()
    const w = mountView()
    await submitQuery(w)
    expect(w.text()).toContain('共 2 条')

    const resetBtn = w.findAll('.btn-group .btn').find((b) => b.text() === '重置')
    expect(resetBtn).toBeTruthy()
    await resetBtn!.trigger('click')
    const text = w.text()
    expect(text).toContain('尚未查询')
    expect(text).not.toContain('共 2 条')
    expect(w.find('tbody tr').exists()).toBe(false)
    w.unmount()
  })

  it('选中多空比前先查询：勾选框回退并提示', async () => {
    mockApi()
    const w = mountView()
    const box = w.findAll('.chart-toolbar input[type="checkbox"]')[4]
    expect(box).toBeTruthy()
    await box.setValue(true)
    await flushPromises()
    const toasts = useUiStore(lastPinia).toasts.map((t) => t.msg)
    expect(toasts).toContain('请先查询 K 线，再叠加多空比')
    expect((box.element as HTMLInputElement).checked).toBe(false)
    w.unmount()
  })
})
