<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { apiGet, apiPost } from '../api/client'
import { EP } from '../api/endpoints'
import { fmtInt, fmtMoney, fmtNum, fmtTime, isNil } from '../lib/format'
import { useUiStore } from '../stores/ui'
import { usePolling } from '../composables/usePolling'
import DataTable from '../components/DataTable.vue'
import StateBlock from '../components/StateBlock.vue'
import CopyOpenDialog from './shadow/CopyOpenDialog.vue'
import {
  SHADOW_ORDER_LIMIT,
  SHADOW_PF_LIMIT,
  SHADOW_PF_SHOW,
  SHADOW_POLL_MS,
  SHADOW_POS_LIMIT,
  collectErrors,
  drawEquityChart,
  shadowHealthCols,
  shadowHealthMeta,
  shadowOrderCols,
  shadowOrderInfo,
  shadowPfMeta,
  shadowPortfolioCols,
  shadowPortfolioKv,
  shadowPositionCols,
  shadowPosInfo,
  shadowStatsCards,
  shadowStatusKv,
  statusPortfolio
} from './shadow/logic'

const ui = useUiStore()
const auto = ref(true)
const refreshing = ref(false)
const ticking = ref(false)
const error = ref('')
const refreshAt = ref('')
const copyOpen = ref(false)

/* 引擎状态 */
const status = ref<any>(null)
const statusLoading = ref(true)
const statusFailed = ref(false)
const statusError = ref('')

/* 影子持仓 */
const posRows = ref<any[]>([])
const posLoading = ref(true)
const posFailed = ref(false)
const posError = ref('')
const posMeta = ref('GET /positions?status=OPEN · 每页 ' + SHADOW_POS_LIMIT + ' 条')
const posMore = ref(false)

/* 影子订单 */
const orderRows = ref<any[]>([])
const orderLoading = ref(true)
const orderFailed = ref(false)
const orderError = ref('')
const orderMeta = ref('GET /orders?limit=' + SHADOW_ORDER_LIMIT)
const orderMore = ref(false)

/* 组合快照 */
const pfRows = ref<any[]>([])
const pfLoading = ref(true)
const pfFailed = ref(false)
const pfError = ref('')
const canvasEl = ref<HTMLCanvasElement | null>(null)

const posCols = shadowPositionCols()
const orderCols = shadowOrderCols()
const pfCols = shadowPortfolioCols()
const hlCols = shadowHealthCols()

const statCards = computed(() => (status.value ? shadowStatsCards(status.value) : []))
const statusKv = computed(() => shadowStatusKv(status.value || {}))
const noteText = computed(() => {
  const s = status.value
  return s && !isNil(s.lastTickNote) ? String(s.lastTickNote) : '—'
})
const pf = computed<any>(() => statusPortfolio(status.value))
const pfKv = computed(() => shadowPortfolioKv(pf.value || {}))
const latestHealth = computed<any[]>(() => {
  const s = status.value
  return s && Array.isArray(s.latestHealth) ? s.latestHealth : []
})
const hlMeta = computed(() => shadowHealthMeta(latestHealth.value.length))
const posInfo = computed(() => shadowPosInfo(posRows.value.length))
const orderInfo = computed(() => shadowOrderInfo(orderRows.value.length))
const pfMetaText = computed(() => shadowPfMeta(pfRows.value))
const pfTableRows = computed(() => pfRows.value.slice(0, SHADOW_PF_SHOW))

function errText(err: any): string {
  return err && err.message ? String(err.message) : String(err)
}

async function loadStatus(): Promise<void> {
  if (!status.value) statusLoading.value = true
  try {
    const data = await apiGet<any>(EP.strategyShadowStatus)
    status.value = data && typeof data === 'object' ? data : {}
    statusFailed.value = false
  } catch (err: any) {
    statusFailed.value = true
    statusError.value = '引擎状态加载失败：' + errText(err)
    throw err
  } finally {
    statusLoading.value = false
  }
}

async function loadPositions(append: boolean): Promise<void> {
  const offset = append ? posRows.value.length : 0
  if (!posRows.value.length) posLoading.value = true
  try {
    const data = await apiGet<any[]>(EP.strategyShadowPositions, {
      status: 'OPEN',
      limit: SHADOW_POS_LIMIT,
      offset
    })
    const rows = Array.isArray(data) ? data : []
    posRows.value = append ? posRows.value.concat(rows) : rows
    posMore.value = rows.length >= SHADOW_POS_LIMIT
    posFailed.value = false
    posMeta.value = 'GET /positions?status=OPEN · 每页 ' + SHADOW_POS_LIMIT + ' 条'
  } catch (err: any) {
    if (!append) {
      posFailed.value = true
      posError.value = '影子持仓加载失败：' + errText(err)
    }
    posMeta.value = '加载失败'
    throw err
  } finally {
    posLoading.value = false
  }
}

async function loadOrders(append: boolean): Promise<void> {
  const offset = append ? orderRows.value.length : 0
  if (!orderRows.value.length) orderLoading.value = true
  try {
    const data = await apiGet<any[]>(EP.strategyShadowOrders, { limit: SHADOW_ORDER_LIMIT, offset })
    const rows = Array.isArray(data) ? data : []
    orderRows.value = append ? orderRows.value.concat(rows) : rows
    orderMore.value = rows.length >= SHADOW_ORDER_LIMIT
    orderFailed.value = false
    orderMeta.value = 'GET /orders?limit=' + SHADOW_ORDER_LIMIT
  } catch (err: any) {
    if (!append) {
      orderFailed.value = true
      orderError.value = '影子订单加载失败：' + errText(err)
    }
    orderMeta.value = '加载失败'
    throw err
  } finally {
    orderLoading.value = false
  }
}

async function loadPortfolio(): Promise<void> {
  if (!pfRows.value.length) pfLoading.value = true
  try {
    const data = await apiGet<any[]>(EP.strategyShadowPortfolio, { limit: SHADOW_PF_LIMIT })
    pfRows.value = Array.isArray(data) ? data : []
    pfFailed.value = false
  } catch (err: any) {
    if (!pfRows.value.length) {
      pfFailed.value = true
      pfError.value = '组合快照加载失败：' + errText(err)
    }
    pfRows.value = []
    throw err
  } finally {
    pfLoading.value = false
    await nextTick()
    drawEquityChart(canvasEl.value, pfRows.value)
  }
}

async function loadAll(manual: boolean): Promise<void> {
  error.value = ''
  if (manual) refreshing.value = true
  const results = await Promise.all([
    loadStatus().catch((e: unknown) => e),
    loadPositions(false).catch((e: unknown) => e),
    loadOrders(false).catch((e: unknown) => e),
    loadPortfolio().catch((e: unknown) => e)
  ])
  if (manual) refreshing.value = false
  const msg = collectErrors(results)
  if (msg) error.value = '部分数据加载失败：' + msg
  refreshAt.value = '刷新于 ' + fmtTime(Date.now())
  if (manual) ui.toast(msg ? '刷新完成，但有接口失败' : '影子数据已刷新', msg ? 'err' : 'ok')
}

function refreshNow(): void {
  void loadAll(true)
}

function loadMorePositions(): void {
  void loadPositions(true).catch(() => undefined)
}

function loadMoreOrders(): void {
  void loadOrders(true).catch(() => undefined)
}

async function runTick(): Promise<void> {
  if (ticking.value) return
  ticking.value = true
  try {
    const r = await apiPost<any>(EP.strategyShadowTick)
    const res = r || {}
    ui.toast(
      '已推进一轮：opened=' + fmtInt(res.opened) + ' closed=' + fmtInt(res.closed) +
        ' marked=' + fmtInt(res.marked) + '，耗时 ' + fmtInt(res.durationMs) + ' ms',
      'ok'
    )
    await loadAll(false)
  } catch (err: any) {
    ui.toast('推进失败：' + errText(err), 'err')
  } finally {
    ticking.value = false
  }
}

async function closePosition(id: number): Promise<void> {
  const ok = await ui.confirmDialog(
    '确认平掉影子持仓 #' + id + '？',
    '<p>将按最新标记价市价平掉该影子持仓，并在影子订单表里记一笔 MANUAL_CLOSE。这是本地影子账本操作，不会向交易所发真实订单。</p>',
    '确认平仓'
  )
  if (!ok) return
  try {
    const r = await apiPost<any>(EP.strategyShadowClose(String(id)))
    const res = r || {}
    ui.toast(
      '影子持仓 #' + id + ' 已平仓：exitPx=' + fmtNum(res.exitPx) + '，已实现盈亏 ' +
        fmtMoney(res.unrealizedPnl) + ' USD',
      'ok'
    )
  } catch (err: any) {
    if (err && err.status === 400) {
      ui.toast('平仓失败（' + err.status + '）：' + errText(err), 'err')
    } else if (err && err.status === 404) {
      ui.toast('平仓失败（404）：该影子持仓不存在，可能已被平掉', 'err')
    } else {
      ui.toast('平仓失败：' + errText(err), 'err')
    }
  }
  await loadAll(false)
}

/* 复制开仓：弹窗把一笔真实开仓复制进影子账本，成功后刷新持仓与订单 */
async function onCopyOpened(res: any): Promise<void> {
  const pos = res && res.position ? res.position : {}
  const dir = pos.side === 'short' ? '做空' : '做多'
  ui.toast(
    '已复制开仓：' + (pos.instId || '') + ' ' + dir + ' ' + fmtNum(pos.sz) + ' 张 @ ' + fmtNum(pos.entryPx) +
      '（orderId=' + fmtInt(res && res.orderId) + '，仅影子账本）',
    'ok'
  )
  await Promise.all([loadPositions(false), loadOrders(false)])
}

/* 平仓按钮走事件委托（表格是 v-html 渲染的） */
function onSectionClick(e: MouseEvent): void {
  const target = e.target as Element | null
  const node = target && typeof target.closest === 'function' ? target.closest('[data-shadow-close]') : null
  if (!node) return
  e.preventDefault()
  const id = Number(node.getAttribute('data-shadow-close'))
  if (!isFinite(id) || id <= 0) return
  void closePosition(id)
}

const polling = usePolling(() => loadAll(false), SHADOW_POLL_MS, { enabled: auto, immediate: false })
watch(auto, () => polling.restart())

onMounted(() => {
  void loadAll(false)
})
</script>

<template>
  <section class="page" data-page="shadow" @click="onSectionClick">
    <div class="page-head">
      <div>
        <h1>影子交易</h1>
        <p class="muted">
          策略在本地影子账本里并行记账：按最新行情开仓、分层、标记与退出，全程不发真实订单。可手动推进一轮，也可平掉单笔影子持仓。
        </p>
      </div>
      <div class="page-actions">
        <span class="hint">{{ refreshAt || '—' }}</span>
        <label class="switch"><input v-model="auto" type="checkbox" /><span>每 5 秒自动刷新</span></label>
        <button class="btn btn-sm" type="button" :disabled="refreshing" :aria-busy="refreshing ? 'true' : 'false'" @click="refreshNow">
          刷新
        </button>
        <button class="btn btn-sm btn-primary" type="button" :disabled="ticking" :aria-busy="ticking ? 'true' : 'false'" @click="runTick">
          手动推进一轮
        </button>
      </div>
    </div>

    <div v-if="error" class="alert danger">{{ error }}</div>

    <div v-if="status" class="card-row">
      <div v-for="c in statCards" :key="c.label" class="card stat-card">
        <div class="stat-label">{{ c.label }}</div>
        <div class="stat-value" :class="{ 'is-time': c.time }">{{ c.value }}</div>
        <div class="stat-sub">{{ c.sub }}</div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <h2>引擎状态</h2>
        <span class="muted">GET /api/v1/strategy/shadow/status</span>
      </div>
      <StateBlock v-if="statusLoading && !status" kind="loading" text="加载中…" />
      <StateBlock v-else-if="statusFailed && !status" kind="error" :text="statusError" />
      <div v-else-if="status">
        <div class="kv-grid">
          <div v-for="row in statusKv" :key="row[0]" class="kv">
            <span class="kv-key">{{ row[0] }}</span>
            <span class="kv-val">{{ row[1] }}</span>
          </div>
        </div>
        <div class="shadow-note">
          <span class="shadow-note-key">上一轮 note</span>
          <span class="shadow-note-val mono">{{ noteText }}</span>
        </div>
        <div class="shadow-subhead">{{ pf ? '最新组合快照（已随 status 一并返回）' : '最新组合快照' }}</div>
        <div v-if="pf" class="kv-grid shadow-pf-kv">
          <div v-for="row in pfKv" :key="row[0]" class="kv">
            <span class="kv-key">{{ row[0] }}</span>
            <span class="kv-val">{{ row[1] }}</span>
          </div>
        </div>
        <div v-else class="state">暂无数据（status 未返回 latestPortfolio）</div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <h2>影子持仓</h2>
        <span class="muted">{{ posMeta }}</span>
        <button class="btn btn-sm" type="button" data-action="copy-open" @click="copyOpen = true">复制开仓</button>
      </div>
      <div class="table-wrap">
        <StateBlock v-if="posLoading && !posRows.length" kind="loading" text="加载中…" />
        <StateBlock v-else-if="posFailed && !posRows.length" kind="error" :text="posError" />
        <DataTable v-else :cols="posCols" :rows="posRows" empty-text="暂无数据" />
      </div>
      <div class="pager">
        <span class="pager-info">{{ posInfo }}</span>
        <button v-if="posMore" class="btn btn-sm" type="button" @click="loadMorePositions">加载更多</button>
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <h2>影子订单</h2>
        <span class="muted">{{ orderMeta }}</span>
      </div>
      <div class="table-wrap">
        <StateBlock v-if="orderLoading && !orderRows.length" kind="loading" text="加载中…" />
        <StateBlock v-else-if="orderFailed && !orderRows.length" kind="error" :text="orderError" />
        <DataTable v-else :cols="orderCols" :rows="orderRows" empty-text="暂无数据" />
      </div>
      <div class="pager">
        <span class="pager-info">{{ orderInfo }}</span>
        <button v-if="orderMore" class="btn btn-sm" type="button" @click="loadMoreOrders">加载更多</button>
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <h2>组合快照</h2>
        <span class="muted">{{ pfMetaText }}</span>
      </div>
      <div class="chart-wrap shadow-chart"><canvas ref="canvasEl"></canvas></div>
      <div class="table-wrap" style="margin-top: 12px">
        <StateBlock v-if="pfLoading && !pfRows.length" kind="loading" text="加载中…" />
        <StateBlock v-else-if="pfFailed && !pfRows.length" kind="error" :text="pfError" />
        <DataTable v-else :cols="pfCols" :rows="pfTableRows" empty-text="暂无数据" />
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <h2>最近一轮体检</h2>
        <span class="muted">{{ hlMeta }}</span>
      </div>
      <div class="table-wrap">
        <StateBlock v-if="!status" kind="loading" text="加载中…" />
        <DataTable v-else :cols="hlCols" :rows="latestHealth" empty-text="暂无数据" />
      </div>
    </div>

    <CopyOpenDialog :open="copyOpen" @close="copyOpen = false" @opened="onCopyOpened" />
  </section>
</template>
