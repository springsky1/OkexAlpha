/* 回测页的会话状态与接口调度（等价 legacy 里除 DOM 渲染外的全部逻辑）：
   批次列表 / 批次详情 / 逐币明细缓存 / 冠军资金曲线与逐笔 / 提交 / 取消 / 提升。
   组件只读这个 reactive 对象，渲染与事件绑定都极薄，保证切换路由后状态可保持。 */
import { computed, reactive, type ComputedRef } from 'vue'
import { ApiError, apiGet, apiPost } from '../../api/client'
import { EP } from '../../api/endpoints'
import { fmtInt } from '../../lib/format'
import { useUiStore } from '../../stores/ui'
import {
  BT_COIN_LIMIT,
  BT_EQUITY_LIMIT,
  BT_LIST_LIMIT,
  BT_TRADE_LIMIT,
  btBuildPayload,
  btCanPromote,
  btPromoteName,
  btStatusActive,
  BT_FORM_DEFAULTS,
  type BtEquityPoint,
  type BtResult,
  type BtRun,
  type BtRunDetailPayload,
  type BtRunsPayload,
  type BtSubmitFormState,
  type BtTrade
} from './btLogic'
import { emptyRangesForm } from './btRanges'

export function btErrText(err: unknown): string {
  if (err instanceof ApiError) return err.message
  if (err && typeof err === 'object' && 'message' in err) return String((err as { message: unknown }).message)
  return String(err)
}

export function btErrConflict(err: unknown): boolean {
  if (!err || typeof err !== 'object') return false
  const e = err as { status?: number; code?: string | number }
  return Number(e.status) === 409 || String(e.code) === 'CONFLICT'
}

export interface BtSession {
  state: {
    runs: BtRun[]
    runsLoading: boolean
    runsError: string
    runsTotal: number
    runsLoaded: boolean
    selectedId: number
    detail: BtRunDetailPayload | null
    detailLoading: boolean
    detailError: string
    expanded: Record<number, boolean>
    coins: Record<number, BtResult[]>
    coinsLoading: Record<number, boolean>
    champAggId: number
    champCoinId: number
    equity: BtEquityPoint[]
    equityLoading: boolean
    equityError: string
    trades: BtTrade[]
    tradesLoading: boolean
    tradesError: string
    error: string
    submitBusy: boolean
    cancelBusy: boolean
    promoteBusy: boolean
  }
  active: ComputedRef<boolean>
  champAgg: ComputedRef<BtResult | null>
  champCoin: ComputedRef<BtResult | null>
  champCoins: ComputedRef<BtResult[] | undefined>
  loadRuns: (silent?: boolean) => Promise<void>
  loadDetail: (id: number, silent?: boolean) => Promise<void>
  openRun: (id: number) => Promise<void>
  toggleResult: (row: BtResult) => Promise<void>
  loadCoins: (aggId: number, force?: boolean) => Promise<BtResult[] | null>
  loadEquity: (resultId: number) => Promise<void>
  loadTrades: (resultId: number) => Promise<void>
  loadChampion: () => Promise<void>
  pickCoin: (aggId: number, coinId: number) => Promise<void>
  submit: (form: BtSubmitFormState) => Promise<number>
  cancelRun: () => Promise<void>
  promote: () => Promise<void>
  setError: (msg: string) => void
  resetForm: () => BtSubmitFormState
}

export function newBtForm(): BtSubmitFormState {
  const next = Object.assign({}, BT_FORM_DEFAULTS)
  // ranges 是嵌套对象：浅拷贝会与 BT_FORM_DEFAULTS 共享，必须换成独立实例
  next.ranges = emptyRangesForm()
  return next
}

export function createBtSession(): BtSession {
  const ui = useUiStore()

  const state = reactive({
    runs: [] as BtRun[],
    runsLoading: false,
    runsError: '',
    runsTotal: 0,
    runsLoaded: false,
    selectedId: 0,
    detail: null as BtRunDetailPayload | null,
    detailLoading: false,
    detailError: '',
    expanded: {} as Record<number, boolean>,
    coins: {} as Record<number, BtResult[]>,
    coinsLoading: {} as Record<number, boolean>,
    champAggId: 0,
    champCoinId: 0,
    equity: [] as BtEquityPoint[],
    equityLoading: false,
    equityError: '',
    trades: [] as BtTrade[],
    tradesLoading: false,
    tradesError: '',
    error: '',
    submitBusy: false,
    cancelBusy: false,
    promoteBusy: false
  })

  /* 并发保护：详情/曲线的请求序号，只接受最后一次发起的响应 */
  let detailSeq = 0
  let championSeq = 0

  const active = computed(function () {
    const run = state.detail ? state.detail.run : null
    return !!run && btStatusActive(run.status)
  })

  const champAgg = computed(function (): BtResult | null {
    const d = state.detail
    if (!d || !state.champAggId) return null
    const list = d.results || []
    for (let i = 0; i < list.length; i++) {
      if (Number(list[i].id) === state.champAggId) return list[i]
    }
    return null
  })

  const champCoin = computed(function (): BtResult | null {
    const list = state.coins[state.champAggId]
    if (!list || !state.champCoinId) return null
    for (let i = 0; i < list.length; i++) {
      if (Number(list[i].id) === state.champCoinId) return list[i]
    }
    return null
  })

  const champCoins = computed(function (): BtResult[] | undefined {
    return state.coins[state.champAggId]
  })

  function setError(msg: string): void {
    state.error = msg || ''
  }

  async function loadRuns(silent?: boolean): Promise<void> {
    if (!silent) {
      state.runsLoading = true
      state.runsError = ''
    }
    try {
      const data = await apiGet<BtRunsPayload>(EP.strategyBacktests, { limit: BT_LIST_LIMIT })
      const items = data && Array.isArray(data.items) ? data.items : []
      state.runs = items
      state.runsTotal = Number(data && data.total) || items.length
      state.runsLoaded = true
      state.runsError = ''
    } catch (err) {
      const msg = btErrText(err)
      state.runsError = '加载批次列表失败：' + msg
      ui.toast('加载回测批次失败：' + msg, 'err')
    } finally {
      state.runsLoading = false
    }
  }

  function resetChampion(): void {
    state.champAggId = 0
    state.champCoinId = 0
    state.equity = []
    state.equityError = ''
    state.trades = []
    state.tradesError = ''
  }

  async function loadDetail(id: number, silent?: boolean): Promise<void> {
    if (!id) return
    const seq = ++detailSeq
    if (!silent) {
      state.detailLoading = true
      state.detailError = ''
    }
    try {
      const data = await apiGet<BtRunDetailPayload>(EP.strategyBacktest(String(id)))
      if (seq !== detailSeq) return
      const results = data && Array.isArray(data.results) ? data.results : []
      const payload: BtRunDetailPayload = {
        run: data ? (data.run || null) : null,
        results: results,
        resultTotal: Number(data && data.resultTotal) || results.length,
        best: data ? (data.best || null) : null
      }
      state.detail = payload
      state.detailError = ''
      const nextBest = payload.best && payload.best.id ? Number(payload.best.id) : results.length ? Number(results[0].id) : 0
      if (nextBest && nextBest !== state.champAggId) {
        state.champAggId = nextBest
        state.champCoinId = 0
      }
      if (state.champAggId) await loadChampion()
      else resetChampion()
    } catch (err) {
      if (seq !== detailSeq) return
      const msg = btErrText(err)
      state.detailError = '加载失败：' + msg
      state.detail = null
      resetChampion()
      ui.toast('加载批次详情失败：' + msg, 'err')
    } finally {
      if (seq === detailSeq) state.detailLoading = false
    }
  }

  async function openRun(id: number): Promise<void> {
    const next = Number(id)
    if (!next) return
    if (next !== state.selectedId) {
      state.expanded = {}
      state.coins = {}
      state.coinsLoading = {}
      resetChampion()
    }
    state.selectedId = next
    await loadDetail(next)
  }

  async function loadCoins(aggId: number, force?: boolean): Promise<BtResult[] | null> {
    const id = Number(aggId)
    if (!id) return null
    if (!force && state.coins[id]) return state.coins[id]
    state.coinsLoading = Object.assign({}, state.coinsLoading, { [id]: true })
    try {
      const rows = await apiGet<BtResult[]>(EP.strategyBacktestResults(String(state.selectedId), String(id)), {
        limit: BT_COIN_LIMIT
      })
      const list = Array.isArray(rows) ? rows : []
      state.coins[id] = list
      return list
    } finally {
      const next = Object.assign({}, state.coinsLoading)
      delete next[id]
      state.coinsLoading = next
    }
  }

  async function loadEquity(resultId: number): Promise<void> {
    const id = Number(resultId)
    const runId = state.selectedId
    if (!id || !runId) {
      state.equity = []
      return
    }
    state.equityLoading = true
    state.equityError = ''
    try {
      const rows = await apiGet<BtEquityPoint[]>(EP.strategyBacktestEquity(String(runId), String(id)), {
        limit: BT_EQUITY_LIMIT
      })
      state.equity = Array.isArray(rows) ? rows : []
    } catch (err) {
      state.equity = []
      state.equityError = '加载失败：' + btErrText(err)
    } finally {
      state.equityLoading = false
    }
  }

  async function loadTrades(resultId: number): Promise<void> {
    const id = Number(resultId)
    const runId = state.selectedId
    if (!runId) {
      state.trades = []
      return
    }
    state.tradesLoading = true
    state.tradesError = ''
    try {
      const rows = await apiGet<BtTrade[]>(EP.strategyBacktestTrades(String(runId)), {
        resultId: id || undefined,
        limit: BT_TRADE_LIMIT
      })
      state.trades = Array.isArray(rows) ? rows : []
    } catch (err) {
      state.trades = []
      state.tradesError = '加载失败：' + btErrText(err)
    } finally {
      state.tradesLoading = false
    }
  }

  async function loadChampion(): Promise<void> {
    const aggId = state.champAggId
    const runId = state.selectedId
    if (!aggId || !runId) {
      resetChampion()
      return
    }
    const seq = ++championSeq
    try {
      await loadCoins(aggId)
    } catch (err) {
      ui.toast('加载冠军逐币明细失败：' + btErrText(err), 'err')
    }
    if (seq !== championSeq) return
    const viewId = state.champCoinId || aggId
    await Promise.all([loadEquity(viewId), loadTrades(viewId)])
  }

  async function pickCoin(aggId: number, coinId: number): Promise<void> {
    state.champAggId = Number(aggId) || 0
    state.champCoinId = Number(coinId) && Number(coinId) !== state.champAggId ? Number(coinId) : 0
    await loadChampion()
  }

  async function toggleResult(row: BtResult): Promise<void> {
    const id = Number(row.id)
    if (!id) return
    const open = !!state.expanded[id]
    const next = Object.assign({}, state.expanded)
    if (open) delete next[id]
    else next[id] = true
    state.expanded = next
    if (open) return
    if (state.coins[id]) return
    try {
      await loadCoins(id)
    } catch (err) {
      const rollback = Object.assign({}, state.expanded)
      delete rollback[id]
      state.expanded = rollback
      ui.toast('加载逐币明细失败：' + btErrText(err), 'err')
    }
  }

  async function submit(form: BtSubmitFormState): Promise<number> {
    const built = btBuildPayload(form)
    if (!built.ok) {
      if (built.box) state.error = built.message
      ui.toast(built.message, 'err')
      return 0
    }
    state.submitBusy = true
    state.error = ''
    try {
      const data = await apiPost<{ runId: number; coinTotal: number; combosTotal: number; status: string }>(
        EP.strategyBacktests,
        built.body
      )
      ui.toast(
        '已创建回测 #' + data.runId + '，' + fmtInt(data.coinTotal) + ' 个币 × ' + fmtInt(data.combosTotal) + ' 个参数组合',
        'ok'
      )
      await openRun(Number(data.runId))
      await loadRuns(true)
      return Number(data.runId) || 0
    } catch (err) {
      const msg = btErrConflict(err)
        ? '已有批次在跑，先取消或等它结束（' + btErrText(err) + '）'
        : '创建回测失败：' + btErrText(err)
      state.error = msg
      ui.toast(msg, 'err')
      return 0
    } finally {
      state.submitBusy = false
    }
  }

  async function cancelRun(): Promise<void> {
    const id = state.selectedId
    if (!id) return
    const ok = await ui.confirmDialog(
      '取消回测批次',
      '<p>确认取消回测 <span class="mono">#' + id + '</span>？</p>' +
        '<p class="muted">已完成的中间结果会保留，未跑完的部分标记为已取消。</p>',
      '确认取消'
    )
    if (!ok) return
    state.cancelBusy = true
    try {
      const data = await apiPost<{ runId: number; canceled: boolean; status: string }>(EP.strategyBacktestCancel(String(id)))
      const status = data && data.status ? data.status : ''
      ui.toast('已取消回测 #' + id + '（' + status + '）', 'ok')
      await loadRuns(true)
      await loadDetail(id, true)
    } catch (err) {
      ui.toast('取消回测失败：' + btErrText(err), 'err')
    } finally {
      state.cancelBusy = false
    }
  }

  async function promote(): Promise<void> {
    const row = champAgg.value
    const runId = state.selectedId
    if (!runId) return
    if (!btCanPromote(row)) {
      ui.toast('只有聚合结果（segment=ALL）可以提升为候选策略', 'err')
      return
    }
    const target = row as BtResult
    const name = btPromoteName(runId, target.rank, Number(target.id))
    state.promoteBusy = true
    try {
      const data = await apiPost<{ id: number; name: string }>(EP.strategyBacktestPromote(String(runId)), null, {
        resultId: target.id,
        name: name
      })
      ui.toast('已提升为候选策略 #' + data.id + '（' + data.name + '）', 'ok')
      ui.touchRefresh()
    } catch (err) {
      ui.toast('提升为候选策略失败：' + btErrText(err), 'err')
    } finally {
      state.promoteBusy = false
    }
  }

  return {
    state,
    active,
    champAgg,
    champCoin,
    champCoins,
    loadRuns,
    loadDetail,
    openRun,
    toggleResult,
    loadCoins,
    loadEquity,
    loadTrades,
    loadChampion,
    pickCoin,
    submit,
    cancelRun,
    promote,
    setError,
    resetForm: newBtForm
  }
}

/* 页面级单例：legacy 的 ui.bt 是模块级状态，切路由再回到本页仍显示上次的批次与展开项 */
let pageSession: BtSession | null = null

export function btPageSession(): BtSession {
  if (!pageSession) pageSession = createBtSession()
  return pageSession
}
