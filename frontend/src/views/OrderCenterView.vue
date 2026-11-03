<script setup lang="ts">
/* 订单中心（需求 #4）：账户组的 7 tab 页面 —— 当前委托 / 历史委托 / 历史仓位 /
   交易账单 / 策略 / 借币数据 / 资金账单。

   交易所只读 tab 直接调 /api/v1/account/*；策略 tab 是本地聚合（资金、盈亏、收益率）
   并附带「停止 / 详情」两个操作。每个 tab 首次切换进入才请求一次，之后走缓存。 */
import { computed, reactive, ref } from 'vue'
import { apiGet, apiPost } from '../api/client'
import { EP } from '../api/endpoints'
import { esc, fmtTime, isNil } from '../lib/format'
import DataTable from '../components/DataTable.vue'
import StateBlock from '../components/StateBlock.vue'
import { useUiStore } from '../stores/ui'
import {
  ACC_NA,
  ORDER_EX_COLS,
  accMoney,
  accPnl,
  accIssueText,
  accNoticeText,
  strategyDetailPairs,
  strategyParamPairs,
  strategyUniverseOf,
  type KvPair,
  type Row,
  type StrategyDetail
} from './account/acc'
import {
  OC_BILLS_COLS,
  OC_BORROW_COLS,
  OC_EMPTY,
  OC_FILLS_COLS,
  OC_LIMIT,
  OC_POSITION_COLS,
  OC_STRATEGY_HEAD,
  OC_TABS,
  ocErrorText,
  ocMetaText,
  ocReturnPct,
  ocStrategyStatus,
  ocSummaryPairs,
  type OcTabName
} from './order-center/oc'

interface OcPayload {
  ok?: unknown
  items?: Row[]
  total?: unknown
  fetchedAt?: unknown
  error?: unknown
  notice?: unknown
}

interface TabState {
  /* 首次请求过（决定「尚未加载」还是「加载中…」） */
  requested: boolean
  /* 成功加载过（决定切回 tab 是否复用缓存） */
  loaded: boolean
  loading: boolean
  rows: Row[]
  issue: string
  wrapError: string
  meta: string
}

function blank(meta: string): TabState {
  return { requested: false, loaded: false, loading: false, rows: [], issue: '', wrapError: '', meta: meta }
}

const ui = useUiStore()

const active = ref<OcTabName>('pending')
const updated = ref(ACC_NA)
const topText = ref('')

const tabs = reactive<Record<OcTabName, TabState>>({
  pending: blank('加载中…'),
  history: blank('尚未加载'),
  positions: blank('尚未加载'),
  fills: blank('尚未加载'),
  strategy: blank('尚未加载'),
  borrow: blank('尚未加载'),
  bills: blank('尚未加载')
})

/* 策略 tab 的停止 / 详情状态 */
const stoppingId = ref(0)
const expandedId = ref(0)
const detail = ref<StrategyDetail | null>(null)
const detailLoading = ref(false)
const detailError = ref('')

const strategyRows = computed<Row[]>(() => tabs.strategy.rows)
const summaryPairs = computed<KvPair[]>(() => ocSummaryPairs(strategyRows.value))
const detailPairs = computed<KvPair[]>(() => strategyDetailPairs(detail.value, expandedId.value))
const detailParams = computed<KvPair[]>(() => strategyParamPairs(detail.value))
const detailUniverse = computed(() => strategyUniverseOf(detail.value))

function st(name: OcTabName): TabState {
  return tabs[name]
}

function colsOf(name: OcTabName): typeof ORDER_EX_COLS {
  if (name === 'positions') return OC_POSITION_COLS
  if (name === 'fills') return OC_FILLS_COLS
  if (name === 'bills') return OC_BILLS_COLS
  if (name === 'borrow') return OC_BORROW_COLS
  return ORDER_EX_COLS
}

function errMsg(err: unknown): string {
  const e = err as { message?: unknown } | null
  return e && e.message ? String(e.message) : String(err)
}

/* 每个 tab 一个 loader：参数与契约 A/B 完全对应 */
function loadOf(name: OcTabName): Promise<OcPayload> {
  switch (name) {
    case 'pending':
      return apiGet<OcPayload>(EP.accountOrders, { state: 'pending', instType: 'SWAP', limit: OC_LIMIT })
    case 'history':
      return apiGet<OcPayload>(EP.accountOrders, { state: 'history', instType: 'SWAP', limit: OC_LIMIT })
    case 'positions':
      return apiGet<OcPayload>(EP.accountPositionsHistory, { instType: 'SWAP', limit: OC_LIMIT })
    case 'fills':
      return apiGet<OcPayload>(EP.accountFills, { instType: 'SWAP', limit: OC_LIMIT })
    case 'borrow':
      return apiGet<OcPayload>(EP.accountBorrowRepay, { limit: OC_LIMIT })
    case 'bills':
      return apiGet<OcPayload>(EP.accountBills, { limit: OC_LIMIT })
    case 'strategy':
      return apiGet<OcPayload>(EP.accountStrategyCenter, { state: 'all', limit: OC_LIMIT, offset: 0 })
    default:
      return Promise.resolve({ items: [] })
  }
}

async function loadTab(name: OcTabName, force: boolean): Promise<void> {
  const s = tabs[name]
  if (s.loading) return
  if (!force && s.loaded) return
  s.loading = true
  s.requested = true
  if (!s.loaded) s.meta = '加载中…'
  let data: OcPayload
  try {
    data = await loadOf(name)
  } catch (err) {
    const msg = ocErrorText(name, err)
    s.loading = false
    s.wrapError = msg
    s.meta = '加载失败'
    topText.value = msg
    return
  }
  s.loading = false
  s.loaded = true
  s.wrapError = ''
  topText.value = ''
  s.issue = accIssueText(data)
  s.rows = data && Array.isArray(data.items) ? data.items : []
  /* 凭证没配时后端只给 notice，这里也要让用户看到原因（否则只剩空态文案） */
  if (!s.issue) topText.value = accNoticeText(data)
  s.meta = ocMetaText(name, s.rows.length)
  updated.value = '刷新于 ' + fmtTime(Date.now())
}

function switchTab(name: OcTabName): void {
  active.value = name
  collapseDetail()
  void loadTab(name, false)
}

function refreshNow(): void {
  void loadTab(active.value, true)
}

/* ---------------- 策略 tab：停止 / 详情 ---------------- */

function collapseDetail(): void {
  expandedId.value = 0
  detail.value = null
  detailError.value = ''
  detailLoading.value = false
}

async function toggleDetail(id: number): Promise<void> {
  const n = Number(id)
  if (!isFinite(n) || n <= 0) return
  if (Number(expandedId.value) === n) {
    collapseDetail()
    return
  }
  expandedId.value = n
  detail.value = null
  detailError.value = ''
  detailLoading.value = true
  try {
    const d = await apiGet<StrategyDetail>(EP.strategyStrategy(String(n)))
    if (Number(expandedId.value) !== n) return
    detail.value = d && typeof d === 'object' ? d : {}
  } catch (err) {
    if (Number(expandedId.value) === n) {
      detailError.value = '详情加载失败：' + errMsg(err)
      detailLoading.value = false
    }
    return
  }
  if (Number(expandedId.value) !== n) return
  detailLoading.value = false
}

async function stopStrategy(row: Row): Promise<void> {
  const n = Number(row.id)
  if (!isFinite(n) || n <= 0) return
  if (stoppingId.value > 0) return
  const name = isNil(row.name) ? '#' + n : String(row.name)
  const ok = await ui.confirmDialog(
    '停止策略',
    '确认停止策略 <b>' + esc(name) + '</b>（#' + n + '）？停止后不会被影子引擎继续调度，已有持仓由引擎平仓流程处理。',
    '停止策略'
  )
  if (!ok) return
  stoppingId.value = n
  try {
    const res = await apiPost<{ status?: unknown }>(EP.accountStrategyCenterStatus(String(n)), { status: 'STOPPED' })
    const status = res && !isNil(res.status) ? String(res.status) : 'STOPPED'
    ui.toast('策略 #' + n + ' 已停止（status=' + status + '）', 'ok')
  } catch (err) {
    ui.toast('停止失败：' + errMsg(err), 'err')
  }
  stoppingId.value = 0
  await loadTab('strategy', true)
}

/* 首屏只加载第一个 tab（当前委托），其余等用户切换 */
void loadTab('pending', false)
</script>

<template>
  <section class="page" data-page="order-center">
    <div class="page-head">
      <div>
        <h1>订单中心</h1>
        <p class="muted">账户的委托、成交、仓位、账单与策略汇总集中在一页：交易所只读数据直接来自 OKX，策略 tab 是本服务的影子账本聚合。每个 tab 首次进入才请求一次。</p>
      </div>
      <div class="page-actions">
        <span class="hint" id="oc-updated">{{ updated }}</span>
        <button class="btn btn-sm" type="button" id="oc-refresh" @click="refreshNow">刷新</button>
      </div>
    </div>

    <div v-if="topText" class="alert danger" id="oc-error">{{ topText }}</div>

    <div class="tabs" id="oc-tabs" role="tablist">
      <button
        v-for="t in OC_TABS"
        :key="t.name"
        class="tab"
        :class="{ 'is-active': active === t.name }"
        type="button"
        role="tab"
        :aria-selected="active === t.name ? 'true' : 'false'"
        :data-tab="t.name"
        @click="switchTab(t.name)"
      >
        {{ t.label }}
      </button>
    </div>

    <div
      v-for="t in OC_TABS"
      :key="t.name"
      class="card tab-panel"
      :class="{ 'is-active': active === t.name }"
      :data-panel="t.name"
    >
      <div class="card-head">
        <h2>{{ t.title }}</h2>
        <span class="muted" :id="'oc-' + t.name + '-meta'">{{ st(t.name).meta }}</span>
      </div>
      <p class="hint">{{ t.desc }} <span class="mono">{{ t.hint }}</span></p>

      <div class="table-wrap" :id="'oc-' + t.name + '-wrap'">
        <StateBlock v-if="st(t.name).wrapError" kind="error" :text="st(t.name).wrapError" />
        <StateBlock v-else-if="!st(t.name).requested" kind="empty" text="尚未加载" />
        <StateBlock v-else-if="st(t.name).loading && !st(t.name).loaded" kind="loading" text="加载中…" />
        <div v-else-if="st(t.name).issue" class="alert danger">{{ st(t.name).issue }}</div>

        <template v-else-if="t.name === 'strategy'">
          <div class="kv-grid" id="oc-strategy-summary">
            <div v-for="p in summaryPairs" :key="p.key" class="kv">
              <span class="kv-key">{{ p.key }}</span>
              <span class="kv-val" v-html="p.html"></span>
            </div>
          </div>
          <StateBlock v-if="!strategyRows.length" kind="empty" :text="OC_EMPTY.strategy" />
          <table v-else id="oc-strategy-table">
            <thead>
              <tr>
                <th v-for="(h, i) in OC_STRATEGY_HEAD" :key="i">{{ h }}</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="r in strategyRows"
                :key="String(r.id)"
                :class="{ 'is-selected': Number(r.id) === Number(expandedId) }"
                :data-strategy-id="r.id"
              >
                <td>
                  {{ isNil(r.name) ? ACC_NA : r.name }}
                  <span class="mono muted">#{{ r.id }}</span>
                </td>
                <td class="num">{{ accMoney(r.investedUsd) }}</td>
                <td class="num" v-html="accPnl(r.totalPnlUsd)"></td>
                <td class="num">{{ ocReturnPct(r.returnPct) }}</td>
                <td class="num" v-html="accPnl(r.realizedPnlUsd)"></td>
                <td class="num" v-html="accPnl(r.unrealizedPnlUsd)"></td>
                <td v-html="ocStrategyStatus(r)"></td>
                <td class="oc-actions">
                  <button
                    class="btn btn-xs btn-danger"
                    type="button"
                    data-action="stop"
                    :data-id="r.id"
                    :disabled="stoppingId === Number(r.id) || r.running !== true"
                    :title="r.running === true ? '停止该策略' : '该策略未处于运行中'"
                    @click="stopStrategy(r)"
                  >
                    停止
                  </button>
                  <button
                    class="btn btn-xs"
                    type="button"
                    data-action="detail"
                    :data-id="r.id"
                    @click="toggleDetail(Number(r.id))"
                  >
                    详情
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </template>

        <DataTable
          v-else
          :cols="colsOf(t.name)"
          :rows="st(t.name).rows"
          :empty-text="OC_EMPTY[t.name]"
        />
      </div>
    </div>

    <div id="oc-strategy-detail" v-show="expandedId > 0" class="card">
      <div class="card-head">
        <h2>策略详情 <span class="mono">#{{ expandedId }}</span></h2>
        <span class="muted mono">GET /api/v1/strategy/strategies/{{ expandedId }}</span>
      </div>
      <StateBlock v-if="detailLoading" kind="loading" text="加载中…" />
      <StateBlock v-else-if="detailError" kind="error" :text="detailError" />
      <template v-else-if="detail">
        <div class="kv-grid">
          <div v-for="p in detailPairs" :key="p.key" class="kv">
            <span class="kv-key">{{ p.key }}</span>
            <span class="kv-val" v-html="p.html"></span>
          </div>
        </div>
        <div class="detail-block">
          <h4>参数 params（{{ detailParams.length }}）</h4>
          <p v-if="!detailParams.length" class="hint">{{ ACC_NA }}</p>
          <div v-else class="kv-grid">
            <div v-for="p in detailParams" :key="p.key" class="kv">
              <span class="kv-key">{{ p.key }}</span>
              <span class="kv-val" v-html="p.html"></span>
            </div>
          </div>
        </div>
        <div class="detail-block">
          <h4>自选合约 universe（{{ detailUniverse.length }}）</h4>
          <p v-if="!detailUniverse.length" class="hint">{{ ACC_NA }}</p>
          <div v-else class="chip-row">
            <span v-for="(u, i) in detailUniverse" :key="i" class="chip-static mono">{{ u }}</span>
          </div>
        </div>
      </template>
    </div>
  </section>
</template>
