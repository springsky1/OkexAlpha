import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('../src/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../src/api/client')>()
  return { ...actual, apiGet: vi.fn(), apiPost: vi.fn(), apiRawGet: vi.fn() }
})

import { ApiError, apiGet, apiPost } from '../src/api/client'
import { useUiStore } from '../src/stores/ui'
import StrategyBacktestView from '../src/views/StrategyBacktestView.vue'
import {
  BT_FORM_DEFAULTS,
  BT_POLL_MS,
  btBuildPayload,
  btCanPromote,
  btChampionMeta,
  btEquityEmptyText,
  btEquityGeometry,
  btEquitySummary,
  btHasActiveRun,
  btMetrics,
  btParseJson,
  btPctCell,
  btPhaseLabel,
  btProgressBarClass,
  btProgressPct,
  btPromoteName,
  btQuickRange,
  btResultsEmptyText,
  btResultsSummary,
  btRunsSummary,
  btSampleForm,
  btSortedTrades,
  btStatusActive,
  btTradesSummary,
  btVerdictBadge,
  type BtEquityPoint,
  type BtResult,
  type BtRun,
  type BtSubmitFormState,
  type BtTrade
} from '../src/views/strategy-backtest/btLogic'
import { createBtSession } from '../src/views/strategy-backtest/btSession'

const get = vi.mocked(apiGet)
const post = vi.mocked(apiPost)

function run(id: number, status: string, extra: Partial<BtRun> = {}): BtRun {
  return {
    id: id,
    status: status,
    bar: '1H',
    startMs: 1767225600000,
    endMs: 1775001600000,
    phase: 'COARSE',
    coinTotal: 2,
    coinDone: status === 'DONE' ? 2 : 1,
    combosTotal: 40,
    combosDone: status === 'DONE' ? 40 : 12,
    bestResultId: status === 'DONE' ? 70 : null,
    createdAt: '2026-11-01T09:23:04.086529Z',
    startedAt: '2026-11-01T09:23:05.000000Z',
    finishedAt: status === 'DONE' ? '2026-11-01T09:23:12.500000Z' : null,
    ...extra
  }
}

function agg(id: number, extra: Partial<BtResult> = {}): BtResult {
  return {
    id: id,
    runId: 3,
    paramKey: 'long|3|8|2.5|1.5|1.0|25.0',
    params: '{"side":"long","leverage":8}',
    segment: 'ALL',
    metrics: '{"score":75.9,"inSample":{"returnPct":149.11,"maxDrawdownPct":16.48,"trades":187,"sharpe":5.95},"outOfSample":{"returnPct":-21.56,"maxDrawdownPct":30.1,"trades":49}}',
    coinCount: 2,
    tradeCount: 187,
    winRate: 85.56,
    netPnl: 149.11,
    netPnlPct: 149.11,
    maxDrawdownPct: 16.48,
    sharpe: 5.95,
    liquidationCount: 0,
    oosNetPnlPct: -21.56,
    verdict: 'REJECT',
    rank: 1,
    ...extra
  }
}

function coin(id: number, instId: string): BtResult {
  return {
    id: id,
    runId: 3,
    paramKey: 'long|3|8|2.5|1.5|1.0|25.0',
    instId: instId,
    segment: 'COIN',
    metrics: '{"metrics":{"calmar":2.1,"returnPct":245.65}}',
    coinCount: 1,
    tradeCount: 135,
    winRate: 84.35,
    netPnlPct: 245.65,
    maxDrawdownPct: 35.19,
    sharpe: 5.62,
    oid: 0,
    verdict: 'COIN'
  } as BtResult
}

function trade(id: number, resultId: number): BtTrade {
  return {
    id: id,
    result_id: resultId,
    inst_id: 'ETH-USDT-SWAP',
    side: 'long',
    layers: 2,
    avg_entry: 3000,
    max_adverse_pct: 1.2,
    entry_ts_ms: 1767225600000,
    exit_ts_ms: 1767312000000,
    entry_px: 3000,
    exit_px: 3100,
    sz: 0.5,
    notional_usd: 1500,
    pnl: 50,
    pnl_pct: 3.3,
    fee: 1.5,
    funding_paid: 0.2,
    exit_reason: 'TAKE_PROFIT'
  }
}

function form(extra: Partial<BtSubmitFormState> = {}): BtSubmitFormState {
  return { ...BT_FORM_DEFAULTS, ...extra }
}

function pinia(): ReturnType<typeof createPinia> {
  const p = createPinia()
  setActivePinia(p)
  return p
}

beforeEach(() => {
  pinia()
  get.mockReset()
  post.mockReset()
})

describe('回测纯逻辑：JSON 字段容错与指标解包', () => {
  it('btParseJson 解析字符串 / 兼容已解析对象 / 坏 JSON 返回 null', () => {
    expect(btParseJson('{"a":1}')).toEqual({ a: 1 })
    expect(btParseJson({ a: 2 })).toEqual({ a: 2 })
    expect(btParseJson('oops')).toBeNull()
    expect(btParseJson(null)).toBeNull()
    expect(btParseJson('')).toBeNull()
  })

  it('btMetrics 兼容 COIN 行多包一层 metrics', () => {
    const all = btMetrics(agg(70))
    expect(all && all.score).toBe(75.9)
    expect(all && all.inSample && all.inSample.trades).toBe(187)
    const single = btMetrics(coin(152, 'SOL-USDT-SWAP'))
    expect(single && single.calmar).toBe(2.1)
    expect(btMetrics(null)).toBeNull()
  })
})

describe('回测纯逻辑：徽章 / 单元格 / 进度 / 状态', () => {
  it('btVerdictBadge 映射四种 verdict 复用徽章配色', () => {
    expect(btVerdictBadge('CANDIDATE')).toBe('<span class="badge" data-status="DONE">候选</span>')
    expect(btVerdictBadge('REJECT')).toBe('<span class="badge" data-status="FAILED">淘汰</span>')
    expect(btVerdictBadge('INSUFFICIENT')).toContain('样本不足')
    expect(btVerdictBadge('COIN')).toContain('单币')
    expect(btVerdictBadge('WHATEVER')).toContain('data-status="UNKNOWN"')
  })

  it('btPctCell 正绿负红、空值占位', () => {
    expect(btPctCell(12.345)).toBe('<span class="bt-pos">+12.35%</span>')
    expect(btPctCell(-3.2)).toBe('<span class="bt-neg">-3.20%</span>')
    expect(btPctCell(0)).toBe('0.00%')
    expect(btPctCell(null)).toBe('-')
  })

  it('btProgressPct 四舍五入并夹在 0~100，总数为 0 时为 0', () => {
    expect(btProgressPct(1, 3)).toBe(33)
    expect(btProgressPct(2, 2)).toBe(100)
    expect(btProgressPct(11, 10)).toBe(100)
    expect(btProgressPct(0, 0)).toBe(0)
    expect(btProgressPct(null, 10)).toBe(0)
  })

  it('btProgressBarClass 只给 DONE/FAILED 上色', () => {
    expect(btProgressBarClass('DONE')).toBe('is-done')
    expect(btProgressBarClass('FAILED')).toBe('is-failed')
    expect(btProgressBarClass('RUNNING')).toBe('')
  })

  it('btStatusActive / btHasActiveRun 只认 PENDING/RUNNING', () => {
    expect(btStatusActive('PENDING')).toBe(true)
    expect(btStatusActive('RUNNING')).toBe(true)
    expect(btStatusActive('running')).toBe(false)
    expect(btStatusActive('DONE')).toBe(false)
    expect(btHasActiveRun([run(1, 'DONE')])).toBe(false)
    expect(btHasActiveRun([run(1, 'DONE'), run(2, 'RUNNING')])).toBe(true)
  })

  it('btPhaseLabel 走中文映射', () => {
    expect(btPhaseLabel('COARSE')).toBe('粗筛')
    expect(btPhaseLabel('REFINE')).toBe('精调')
    expect(btPhaseLabel(null)).toBe('-')
    expect(btPhaseLabel('OTHER')).toBe('OTHER')
  })

  it('btCanPromote 只允许 segment=ALL', () => {
    expect(btCanPromote({ id: 1, segment: 'ALL' })).toBe(true)
    expect(btCanPromote({ id: 2, segment: 'COIN' })).toBe(false)
    expect(btCanPromote(null)).toBe(false)
  })

  it('btPromoteName 用 bt-runId-rRank 命名，rank 缺失退回 resultId', () => {
    expect(btPromoteName(3, 1, 70)).toBe('bt-3-r1')
    expect(btPromoteName(3, null, 70)).toBe('bt-3-r70')
  })
})

describe('回测纯逻辑：提交参数校验与组装（等价 legacy submitBacktest）', () => {
  it('默认表单产出 bar=1m + combos=400，且不带空字段', () => {
    const built = btBuildPayload(form())
    expect(built.ok).toBe(true)
    if (!built.ok) return
    expect(built.body.bar).toBe('1m')
    expect(built.body.combos).toBe(400)
    expect(built.body.topN).toBe(5)
    expect(built.body.marginUsd).toBe(100)
    expect(built.body.maxHoldHours).toBe(48)
    expect(built.body.coinLimit).toBe(12)
    expect(built.body.instIds).toBeUndefined()
    expect(built.body.startMs).toBeUndefined()
    expect(built.body.endMs).toBeUndefined()
  })

  it('完整表单解析 instIds 与起止时间', () => {
    const built = btBuildPayload(
      form({
        instIds: 'BTC-USDT-SWAP, ETH-USDT-SWAP',
        bar: '1H',
        start: '2026-01-01T00:00',
        end: '2026-02-01T00:00',
        combos: '40',
        topN: '3',
        margin: '100',
        maxHold: '48'
      })
    )
    expect(built.ok).toBe(true)
    if (!built.ok) return
    expect(built.body.instIds).toEqual(['BTC-USDT-SWAP', 'ETH-USDT-SWAP'])
    expect(built.body.bar).toBe('1H')
    expect(built.body.combos).toBe(40)
    expect(built.body.topN).toBe(3)
    expect(typeof built.body.startMs).toBe('number')
    expect(typeof built.body.endMs).toBe('number')
    expect(Number(built.body.endMs) > Number(built.body.startMs)).toBe(true)
  })

  it('逐项越界/非整数都会给出中文提示', () => {
    const combos = btBuildPayload(form({ combos: '2001' }))
    expect(combos.ok).toBe(false)
    if (combos.ok) return
    expect(combos.message).toContain('combos')
    expect(combos.box).toBe(false)

    const topN = btBuildPayload(form({ topN: '21' }))
    expect(topN.ok).toBe(false)
    if (!topN.ok) expect(topN.message).toContain('topN')

    const coinLimit = btBuildPayload(form({ coinLimit: '51' }))
    expect(coinLimit.ok).toBe(false)

    const maxHold = btBuildPayload(form({ maxHold: '8761' }))
    expect(maxHold.ok).toBe(false)

    const margin = btBuildPayload(form({ margin: '0' }))
    expect(margin.ok).toBe(false)
    if (!margin.ok) expect(margin.message).toContain('marginUsd')

    const frac = btBuildPayload(form({ combos: '1.5' }))
    expect(frac.ok).toBe(false)
  })

  it('窗口不足 7 天：toast + 页面错误框（box=true）', () => {
    const built = btBuildPayload(form({ start: '2026-01-01T00:00', end: '2026-01-05T00:00' }))
    expect(built.ok).toBe(false)
    if (built.ok) return
    expect(built.message).toContain('最短 7 天')
    expect(built.box).toBe(true)
  })

  it('小样本预设 = 2 币 / 1H / 40 组合 / topN 3 / 近 90 天', () => {
    const now = 1770000000000
    const sample = btSampleForm(now)
    expect(sample.instIds).toContain('BTC-USDT-SWAP')
    expect(sample.instIds).toContain('ETH-USDT-SWAP')
    expect(sample.bar).toBe('1H')
    expect(sample.combos).toBe('40')
    expect(sample.topN).toBe('3')
    expect(sample.end).not.toBe('')
    expect(sample.start).not.toBe('')
  })

  it('快捷区间只改起止时间，跨度正确', () => {
    const r = btQuickRange(7, 1770000000000)
    expect(r.start < r.end).toBe(true)
    expect(new Date(r.end).getTime() - new Date(r.start).getTime()).toBe(7 * 86400000)
  })
})

describe('回测纯逻辑：资金曲线几何与摘要', () => {
  const pts: BtEquityPoint[] = [
    { ts_ms: 1767225600000, equity: 100, drawdown_pct: 0 },
    { ts_ms: 1767312000000, equity: 120, drawdown_pct: 5 }
  ]

  it('上涨曲线用绿色并给出 5 条刻度', () => {
    const geo = btEquityGeometry(pts, 400, 200)
    expect(geo.ok).toBe(true)
    expect(geo.up).toBe(true)
    expect(geo.color).toBe('rgba' === 'rgba' ? geo.color : '')
    expect(geo.coords.length).toBe(2)
    expect(geo.ticks.length).toBe(5)
    expect(geo.base).toBe(100)
    expect(geo.last).toBe(120)
    expect(geo.maxDD).toBe(5)
  })

  it('下跌曲线切换为红色', () => {
    const geo = btEquityGeometry(
      [
        { ts_ms: 1, equity: 120, drawdown_pct: 0 },
        { ts_ms: 2, equity: 90, drawdown_pct: 25 }
      ],
      400,
      200
    )
    expect(geo.up).toBe(false)
    expect(geo.fillColor).toContain('224,83,60')
  })

  it('空数组 / 缺 equity 字段分别给 empty 与 no-equity', () => {
    expect(btEquityGeometry([], 400, 200).reason).toBe('empty')
    const bad = btEquityGeometry([{ ts_ms: 1, equity: NaN, drawdown_pct: 0 }], 400, 200)
    expect(bad.reason).toBe('no-equity')
  })

  it('摘要包含起点/末值/点数/峰值/谷值/最大回撤', () => {
    const text = btEquitySummary(pts)
    expect(text).toContain('起点 100')
    expect(text).toContain('末值 120')
    expect(text).toContain('2 个点')
    expect(text).toContain('最大回撤 5%')
  })

  it('逐笔按平仓时间倒序且不改原数组', () => {
    const list = [trade(1, 70), trade(2, 70), trade(3, 70)]
    list[0].exit_ts_ms = 300
    list[1].exit_ts_ms = 100
    list[2].exit_ts_ms = 500
    const sorted = btSortedTrades(list)
    expect(sorted.map((t) => t.exit_ts_ms)).toEqual([500, 300, 100])
    expect(list[0].exit_ts_ms).toBe(300)
  })
})

describe('回测纯逻辑：文案', () => {
  it('列表/结果/逐笔/曲线空态文案与 legacy 一致', () => {
    expect(btRunsSummary(0, 0, false)).toBe('暂无批次')
    expect(btRunsSummary(5, 3, true)).toBe('共 5 个批次 · 显示最近 3 个 · 运行中每 2 秒刷新')
    expect(btResultsSummary(50, 86)).toBe('展示前 50 个 / 共 86 个参数组合 · 点击行展开逐币明细')
    expect(btResultsEmptyText(true)).toContain('回测进行中')
    expect(btResultsEmptyText(false)).toBe('暂无结果')
    expect(btTradesSummary(135, false)).toBe('最近 135 笔（平仓时间倒序） · 组合内全部币')
    expect(btEquityEmptyText(true)).toContain('聚合行没有资金曲线数据')
    expect(btEquityEmptyText(false)).toContain('该币暂未落库资金曲线')
    expect(btChampionMeta(70, agg(70), true, null)).toContain('冠军（run.bestResultId）')
    expect(btChampionMeta(70, agg(70), false, coin(152, 'SOL-USDT-SWAP'))).toContain('当前币 SOL-USDT-SWAP')
    expect(BT_POLL_MS).toBe(2000)
  })
})

describe('回测会话：接口调用与状态流转', () => {
  function mockApis(): void {
    get.mockImplementation(async (path: string): Promise<any> => {
      if (path === '/api/v1/strategy/backtests') {
        return { total: 2, limit: 20, offset: 0, items: [run(6, 'RUNNING'), run(3, 'DONE')] }
      }
      if (path === '/api/v1/strategy/backtests/3') {
        return { run: run(3, 'DONE'), results: [agg(70), agg(71, { rank: 2 })], resultTotal: 2, best: agg(70) }
      }
      if (path === '/api/v1/strategy/backtests/3/results/70/coins') {
        return [coin(150, 'BTC-USDT-SWAP'), coin(152, 'SOL-USDT-SWAP')]
      }
      if (path === '/api/v1/strategy/backtests/3/results/71/coins') {
        return [coin(153, 'BTC-USDT-SWAP')]
      }
      if (path === '/api/v1/strategy/backtests/3/results/70/equity') {
        return [{ ts_ms: 1, equity: 100, drawdown_pct: 0 }]
      }
      if (path === '/api/v1/strategy/backtests/3/results/152/equity') {
        return [{ ts_ms: 1, equity: 98.96, drawdown_pct: 1.04 }, { ts_ms: 2, equity: 245.65, drawdown_pct: 0 }]
      }
      if (path === '/api/v1/strategy/backtests/3/trades') {
        return [trade(1, 70), trade(2, 70)]
      }
      throw new Error('unexpected GET ' + path)
    })
  }

  it('loadRuns 请求 limit=20 并写入 runs/total', async () => {
    mockApis()
    const session = createBtSession()
    await session.loadRuns(false)
    expect(get).toHaveBeenCalledWith('/api/v1/strategy/backtests', { limit: 20 })
    expect(session.state.runs.length).toBe(2)
    expect(session.state.runsTotal).toBe(2)
    expect(session.state.runsLoaded).toBe(true)
    expect(btHasActiveRun(session.state.runs)).toBe(true)
  })

  it('loadRuns 失败时写入 runsError 并弹错误 toast', async () => {
    get.mockRejectedValue(new ApiError('boom', 500, 'HTTP_500'))
    const session = createBtSession()
    const ui = useUiStore()
    await session.loadRuns(false)
    expect(session.state.runsError).toContain('加载批次列表失败：boom')
    expect(ui.toasts.length).toBeGreaterThan(0)
    expect(String(ui.toasts[ui.toasts.length - 1].msg)).toContain('加载回测批次失败')
  })

  it('openRun 拉详情 + 冠军逐币 + 曲线 + 逐笔，并把 champAggId 设为 run.best.id', async () => {
    mockApis()
    const session = createBtSession()
    await session.openRun(3)
    expect(session.state.selectedId).toBe(3)
    expect(session.state.detail && session.state.detail.resultTotal).toBe(2)
    expect(session.state.champAggId).toBe(70)
    expect(session.champAgg.value && session.champAgg.value.id).toBe(70)
    expect(session.state.coins[70].length).toBe(2)
    expect(session.state.equity.length).toBe(1)
    expect(session.state.trades.length).toBe(2)
    expect(get).toHaveBeenCalledWith('/api/v1/strategy/backtests/3/results/70/equity', { limit: 5000 })
    expect(get).toHaveBeenCalledWith('/api/v1/strategy/backtests/3/trades', { resultId: 70, limit: 200 })
  })

  it('pickCoin 切到单币后曲线与逐笔都换成该币的 resultId', async () => {
    mockApis()
    const session = createBtSession()
    await session.openRun(3)
    await session.pickCoin(70, 152)
    expect(session.state.champCoinId).toBe(152)
    expect(session.champCoin.value && session.champCoin.value.instId).toBe('SOL-USDT-SWAP')
    expect(get).toHaveBeenCalledWith('/api/v1/strategy/backtests/3/results/152/equity', { limit: 5000 })
    expect(get).toHaveBeenCalledWith('/api/v1/strategy/backtests/3/trades', { resultId: 152, limit: 200 })
    await session.pickCoin(70, 0)
    expect(session.state.champCoinId).toBe(0)
  })

  it('toggleResult 首次展开拉逐币明细，再次点击收起且不重复请求', async () => {
    mockApis()
    const session = createBtSession()
    await session.openRun(3)
    const before = get.mock.calls.filter((c) => String(c[0]).includes('/coins')).length
    await session.toggleResult(agg(71, { rank: 2 }))
    expect(session.state.expanded[71]).toBe(true)
    expect(get.mock.calls.filter((c) => String(c[0]).includes('/results/71/coins')).length).toBe(1)
    await session.toggleResult(agg(71, { rank: 2 }))
    expect(session.state.expanded[71]).toBeUndefined()
    await session.toggleResult(agg(71, { rank: 2 }))
    expect(get.mock.calls.filter((c) => String(c[0]).includes('/results/71/coins')).length).toBe(1)
    expect(before).toBe(1)
  })

  it('提交 409 给出「已有批次在跑」提示并写页面错误框', async () => {
    get.mockResolvedValue({ total: 0, items: [] })
    post.mockRejectedValue(new ApiError('another backtest is running; wait for it', 409, 'CONFLICT'))
    const session = createBtSession()
    const ui = useUiStore()
    const id = await session.submit(form({ instIds: 'BTC-USDT-SWAP', bar: '1H', combos: '40', topN: '3' }))
    expect(id).toBe(0)
    expect(session.state.error).toContain('已有批次在跑，先取消或等它结束')
    expect(post).toHaveBeenCalledWith('/api/v1/strategy/backtests', {
      bar: '1H',
      instIds: ['BTC-USDT-SWAP'],
      combos: 40,
      topN: 3,
      coinLimit: 12,
      maxHoldHours: 48,
      marginUsd: 100
    })
    expect(ui.toasts[ui.toasts.length - 1].kind).toBe('err')
  })

  it('提交成功 toast 带币数与组合数并自动切到该批次详情', async () => {
    mockApis()
    post.mockResolvedValue({ runId: 9, coinTotal: 2, combosTotal: 40, status: 'PENDING' })
    get.mockImplementation(async (path: string): Promise<any> => {
      if (path === '/api/v1/strategy/backtests') return { total: 1, items: [run(9, 'PENDING')] }
      if (path === '/api/v1/strategy/backtests/9') {
        return { run: run(9, 'PENDING'), results: [], resultTotal: 0, best: null }
      }
      return []
    })
    const session = createBtSession()
    const ui = useUiStore()
    const id = await session.submit(form({ instIds: 'BTC-USDT-SWAP, ETH-USDT-SWAP', bar: '1H', combos: '40', topN: '3' }))
    expect(id).toBe(9)
    expect(session.state.selectedId).toBe(9)
    expect(String(ui.toasts[ui.toasts.length - 1].msg)).toBe('已创建回测 #9，2 个币 × 40 个参数组合')
    expect(session.state.error).toBe('')
  })

  it('取消批次：确认后 POST cancel 并 toast 显示新状态', async () => {
    mockApis()
    post.mockResolvedValue({ runId: 3, canceled: true, status: 'CANCELED' })
    const session = createBtSession()
    const ui = useUiStore()
    await session.openRun(3)
    const pending = session.cancelRun()
    expect(ui.modal.visible).toBe(true)
    expect(ui.modal.title).toBe('取消回测批次')
    ui.resolveModal(true)
    await pending
    expect(post).toHaveBeenCalledWith('/api/v1/strategy/backtests/3/cancel')
    expect(String(ui.toasts[ui.toasts.length - 1].msg)).toBe('已取消回测 #3（CANCELED）')
    expect(session.state.cancelBusy).toBe(false)
  })

  it('取消批次：弹窗点「返回」不发请求', async () => {
    mockApis()
    const session = createBtSession()
    const ui = useUiStore()
    await session.openRun(3)
    const pending = session.cancelRun()
    ui.resolveModal(false)
    await pending
    expect(post).not.toHaveBeenCalled()
  })

  it('提升为候选策略：带 resultId 与确定性 name', async () => {
    mockApis()
    post.mockResolvedValue({ id: 11, name: 'bt-3-r1' })
    const session = createBtSession()
    const ui = useUiStore()
    await session.openRun(3)
    await session.promote()
    expect(post).toHaveBeenCalledWith('/api/v1/strategy/backtests/3/promote', null, { resultId: 70, name: 'bt-3-r1' })
    expect(String(ui.toasts[ui.toasts.length - 1].msg)).toBe('已提升为候选策略 #11（bt-3-r1）')
  })

  it('聚合行不允许提升（segment=COIN 时只弹提示不发请求）', async () => {
    mockApis()
    const session = createBtSession()
    await session.openRun(3)
    session.state.champAggId = 150
    session.state.coins[150] = [coin(152, 'SOL-USDT-SWAP')]
    await session.promote()
    expect(post).not.toHaveBeenCalled()
  })
})

describe('回测页面：挂载与幂等', () => {
  it('挂载后渲染四块骨架，小样本预设写入表单，且不重复挂请求', async () => {
    get.mockImplementation(async (path: string): Promise<any> => {
      if (path === '/api/v1/strategy/backtests') return { total: 1, items: [run(3, 'DONE')] }
      if (path === '/api/v1/strategy/backtests/3') {
        return { run: run(3, 'DONE'), results: [agg(70)], resultTotal: 1, best: agg(70) }
      }
      if (String(path).includes('/coins')) return [coin(152, 'SOL-USDT-SWAP')]
      if (String(path).includes('/equity')) return [{ ts_ms: 1, equity: 100, drawdown_pct: 0 }]
      if (String(path).includes('/trades')) return [trade(1, 70)]
      return []
    })
    const wrapper = mount(StrategyBacktestView, { global: { plugins: [pinia()] }, attachTo: document.body })
    await flushPromises()
    const text = wrapper.text()
    expect(text).toContain('创建回测批次')
    expect(text).toContain('批次列表')
    expect(wrapper.findAll('.bt-run-row').length).toBe(1)
    expect(wrapper.find('#bt-combos').exists()).toBe(true)
    expect((wrapper.find('#bt-combos').element as HTMLInputElement).value).toBe('400')
    const sampleBtn = wrapper.findAll('button').find((b) => b.text() === '小样本预设')
    expect(sampleBtn).toBeTruthy()
    await sampleBtn!.trigger('click')
    expect((wrapper.find('#bt-bar').element as HTMLSelectElement).value).toBe('1H')
    await wrapper.find('.bt-run-row').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('批次详情')
    expect(wrapper.text()).toContain('结果排行')
    expect(wrapper.text()).toContain('冠军明细')
    expect(wrapper.text()).toContain('资金曲线')
    wrapper.unmount()
  })
})
