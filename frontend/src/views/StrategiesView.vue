<script setup lang="ts">
/* 马丁策略（legacy index.html §15 / app.js §16c 1:1 移植）
   运行中 / 历史两个 tab；点击表格行在下方展开详情，再次点击收起。 */
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { apiGet } from '../api/client'
import { EP } from '../api/endpoints'
import { badge, fmtTime, isNil } from '../lib/format'
import DataTable from '../components/DataTable.vue'
import StateBlock from '../components/StateBlock.vue'
import {
  ACC_NA,
  accInt,
  accMoney,
  accPnl,
  accTime,
  STRATEGIES_LIMIT,
  STRATEGY_EMPTY,
  STRATEGY_ORDER_COLS,
  STRATEGY_POSITION_COLS,
  STRATEGY_TABS,
  strategiesMetaText,
  strategyDetailPairs,
  strategyOrdersOf,
  strategyParamPairs,
  strategyPositionsOf,
  strategyUniverseOf,
  type KvPair,
  type Row,
  type StrategyDetail
} from './account/acc'

interface TabState {
  requested: boolean
  loaded: boolean
  loading: boolean
  rows: Row[]
  wrapError: string
  meta: string
}

function blank(meta: string): TabState {
  return { requested: false, loaded: false, loading: false, rows: [], wrapError: '', meta: meta }
}

const active = ref<'running' | 'history'>('running')
const updated = ref(ACC_NA)
const topText = ref('')
const runningDef = ref('口径：' + ACC_NA)

const tabs = reactive<Record<'running' | 'history', TabState>>({
  running: blank('加载中…'),
  history: blank('尚未加载')
})

const expandedId = ref(0)
const detail = ref<StrategyDetail | null>(null)
const detailLoading = ref(false)
const detailError = ref('')
const detailCard = ref<HTMLElement | null>(null)

const detailTitle = computed(() => '#' + expandedId.value)
const detailPath = computed(() => 'GET /api/v1/strategy/strategies/' + expandedId.value)
const detailPairs = computed<KvPair[]>(() => strategyDetailPairs(detail.value, expandedId.value))
const detailParams = computed<KvPair[]>(() => strategyParamPairs(detail.value))
const detailUniverse = computed(() => strategyUniverseOf(detail.value))
const detailPositions = computed(() => strategyPositionsOf(detail.value))
const detailOrders = computed(() => strategyOrdersOf(detail.value))

function st(name: 'running' | 'history'): TabState {
  return tabs[name]
}

function errMsg(err: unknown): string {
  const e = err as { message?: unknown } | null
  return e && e.message ? String(e.message) : String(err)
}

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
  await nextTick()
  const card = detailCard.value
  if (card && typeof card.scrollIntoView === 'function') {
    card.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  }
}

async function loadTab(name: 'running' | 'history', force: boolean): Promise<void> {
  const s = tabs[name]
  if (s.loading) return
  if (!force && s.loaded) return
  s.loading = true
  s.requested = true
  if (!s.loaded) s.meta = '加载中…'
  let data: { items?: Row[]; runningDefinition?: unknown }
  try {
    data = await apiGet<{ items?: Row[]; runningDefinition?: unknown }>(EP.strategyStrategies, {
      state: name,
      limit: STRATEGIES_LIMIT
    })
  } catch (err) {
    const msg = errMsg(err)
    s.loading = false
    s.wrapError = '策略加载失败：' + msg
    s.meta = '加载失败'
    topText.value = '策略加载失败（state=' + name + '）：' + msg
    return
  }
  s.loading = false
  s.loaded = true
  s.wrapError = ''
  topText.value = ''
  s.rows = data && Array.isArray(data.items) ? data.items : []
  if (name === 'running') {
    runningDef.value = '口径：' + (data && !isNil(data.runningDefinition) ? String(data.runningDefinition) : ACC_NA)
  }
  s.meta = strategiesMetaText(s.rows.length)
  updated.value = '刷新于 ' + fmtTime(Date.now())
}

function switchTab(name: 'running' | 'history'): void {
  active.value = name
  collapseDetail()
  void loadTab(name, false)
}

function refreshNow(): void {
  void loadTab(active.value, true)
}

function rowId(r: Row): number {
  return Number(r.id)
}

onMounted(() => {
  void loadTab('running', false)
})
</script>

<template>
  <section class="page" data-page="strategies">
    <div class="page-head">
      <div>
        <h1>马丁策略</h1>
        <p class="muted">影子引擎里的马丁策略实例：参数、自选合约、持仓与最近订单。点击表格行展开详情，再次点击收起。</p>
      </div>
      <div class="page-actions">
        <span class="hint" id="strategies-updated">{{ updated }}</span>
        <button class="btn btn-sm" type="button" id="strategies-refresh" @click="refreshNow">刷新</button>
      </div>
    </div>

    <div v-if="topText" class="alert danger" id="strategies-error">{{ topText }}</div>

    <div class="tabs" id="strategies-tabs" role="tablist">
      <button
        v-for="t in STRATEGY_TABS"
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
      v-for="t in STRATEGY_TABS"
      :key="t.name"
      class="tab-panel"
      :class="{ 'is-active': active === t.name }"
      :data-panel="t.name"
    >
      <div class="card">
        <div class="card-head">
          <h2>{{ t.title }}</h2>
          <span class="muted" :id="'strategies-' + t.name + '-meta'">{{ st(t.name).meta }}</span>
        </div>
        <p v-if="t.name === 'running'" class="hint" id="strategies-running-def">{{ runningDef }}</p>
        <p v-else class="hint">GET /api/v1/strategy/strategies?state=history&amp;limit=50</p>
        <div class="table-wrap" :id="'strategies-' + t.name + '-wrap'">
          <StateBlock v-if="st(t.name).wrapError" kind="error" :text="st(t.name).wrapError" />
          <StateBlock v-else-if="!st(t.name).requested" kind="empty" text="尚未加载" />
          <StateBlock v-else-if="st(t.name).loading && !st(t.name).loaded" kind="loading" text="加载中…" />
          <StateBlock v-else-if="!st(t.name).rows.length" kind="empty" :text="STRATEGY_EMPTY[t.name]" />
          <table v-else>
            <thead>
              <tr>
                <th>id</th>
                <th>name</th>
                <th>status</th>
                <th>mode</th>
                <th class="num">持仓（开/平）</th>
                <th class="num">持仓名义 USD</th>
                <th class="num">未实现盈亏 USD</th>
                <th class="num">订单数</th>
                <th>最近下单</th>
                <th>创建时间</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(r, i) in st(t.name).rows"
                :key="i"
                class="row-link"
                :class="{ 'is-selected': rowId(r) === Number(expandedId) }"
                :data-strategy-id="r.id"
                @click="toggleDetail(rowId(r))"
              >
                <td class="mono">{{ isNil(r.id) ? ACC_NA : r.id }}</td>
                <td>{{ isNil(r.name) ? ACC_NA : r.name }}</td>
                <td v-html="badge(r.status)"></td>
                <td>{{ isNil(r.mode) ? ACC_NA : r.mode }}</td>
                <td class="num">{{ accInt(r.openPositions) }} / {{ accInt(r.closedPositions) }}</td>
                <td class="num">{{ accMoney(r.openNotionalUsd) }}</td>
                <td class="num" v-html="accPnl(r.unrealizedPnlUsd)"></td>
                <td class="num">{{ accInt(r.orderCount) }}</td>
                <td class="mono">{{ accTime(r.lastOrderAt) }}</td>
                <td class="mono">{{ accTime(r.createdAt) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <div id="strategies-detail-card" ref="detailCard" v-show="expandedId > 0" class="card">
      <div class="card-head">
        <h2>策略详情 <span class="mono">{{ detailTitle }}</span></h2>
        <span class="muted">{{ detailPath }}</span>
      </div>
      <div id="strategies-detail">
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

          <div class="detail-block">
            <h4>当前持仓 positions（{{ detailPositions.length }}）</h4>
            <DataTable :cols="STRATEGY_POSITION_COLS" :rows="detailPositions" empty-text="无持仓" />
          </div>

          <div class="detail-block">
            <h4>最近订单 recentOrders（{{ detailOrders.length }}）</h4>
            <DataTable :cols="STRATEGY_ORDER_COLS" :rows="detailOrders" empty-text="该策略暂无订单" />
          </div>
        </template>
      </div>
    </div>
  </section>
</template>
