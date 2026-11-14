<script setup lang="ts">
/* 订单中心：6 tab —— 当前委托 / 历史委托 / 历史仓位 / 交易账单 / 借币数据 / 资金账单。

   本页只放真实账户域：前 4 个 tab 读本地镜像库（okx_exchange_order / position / fill）：进入页面自动同步一轮，
   「强制刷新」再同步一次；借币与资金账单直连既有接口。
   4 个镜像 tab 支持勾选多行，汇总笔数 / 盈亏 / 手续费 / 资金费 / 净收益 / 胜率，并按合约与日期分组。
   影子策略的聚合与停止入口在「策略 → 马丁策略」页，不在本页。 */
import { computed, reactive, ref } from 'vue'
import { apiGet, apiPost } from '../api/client'
import { EP } from '../api/endpoints'
import { fmtTime } from '../lib/format'
import DataTable from '../components/DataTable.vue'
import StateBlock from '../components/StateBlock.vue'
import { useUiStore } from '../stores/ui'
import {
  ACC_NA,
  ORDER_EX_COLS,
  accInt,
  accPnl,
  accIssueText,
  accNoticeText,
  type KvPair,
  type Row
} from './account/acc'
import {
  OC_BILLS_COLS,
  OC_BORROW_COLS,
  OC_EMPTY,
  OC_FILLS_COLS,
  OC_LIMIT,
  OC_POSITION_COLS,
  OC_TABS,
  isMirrorTab,
  ocErrorText,
  ocMetaText,
  ocRowKey,
  ocSelectionPairs,
  ocSelectionSummary,
  type OcTabName
} from './order-center/oc'

interface OcPayload {
  ok?: unknown
  items?: Row[]
  total?: unknown
  lastSyncedAt?: unknown
  fetchedAt?: unknown
  fetched?: unknown
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
const syncing = ref(false)

const tabs = reactive<Record<OcTabName, TabState>>({
  pending: blank('加载中…'),
  history: blank('尚未加载'),
  positions: blank('尚未加载'),
  fills: blank('尚未加载'),
  borrow: blank('尚未加载'),
  bills: blank('尚未加载')
})

/* 勾选状态：按 tab 分开，切换 tab 不会把别的 tab 的选择带过去 */
const selected = reactive<Record<OcTabName, string[]>>({
  pending: [],
  history: [],
  positions: [],
  fills: [],
  borrow: [],
  bills: []
})

/* 当前 tab 里实际被勾选的行（用 rowKey 对齐，重新加载后自动丢弃已消失的行） */
const selectedRows = computed<Row[]>(() => {
  const keys = selected[active.value]
  if (!keys.length) return []
  return tabs[active.value].rows.filter((row) => keys.indexOf(ocRowKey(active.value, row)) >= 0)
})

const selectionSummary = computed(() => ocSelectionSummary(active.value, selectedRows.value))
const selectionPairs = computed<KvPair[]>(() => ocSelectionPairs(selectionSummary.value))

/* 按日柱：高度按当日净收益绝对值归一，负值用红色 */
const dayBars = computed(() => {
  const days = selectionSummary.value.days
  let max = 0
  for (let i = 0; i < days.length; i++) max = Math.max(max, Math.abs(days[i].netPnl))
  return days.map((d) => ({
    day: d.day,
    netPnl: d.netPnl,
    neg: d.netPnl < 0,
    height: (max > 0 ? Math.max(4, Math.round((Math.abs(d.netPnl) / max) * 56)) : 4) + 'px'
  }))
})

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

function toggleRow(name: OcTabName, key: string): void {
  const keys = selected[name]
  const i = keys.indexOf(key)
  if (i >= 0) keys.splice(i, 1)
  else keys.push(key)
}

function toggleAll(name: OcTabName, keys: string[]): void {
  selected[name] = keys.slice()
}

function clearSelection(): void {
  selected[active.value] = []
}

/* 每个 tab 一个 loader：镜像 tab 走本地库，借币与资金账单走原接口 */
function loadOf(name: OcTabName): Promise<OcPayload> {
  switch (name) {
    case 'pending':
      return apiGet<OcPayload>(EP.accountExchangeOrders, {
        state: 'pending',
        instType: 'SWAP',
        limit: OC_LIMIT,
        offset: 0
      })
    case 'history':
      return apiGet<OcPayload>(EP.accountExchangeOrders, {
        state: 'history',
        instType: 'SWAP',
        limit: OC_LIMIT,
        offset: 0
      })
    case 'positions':
      return apiGet<OcPayload>(EP.accountExchangePositions, { instType: 'SWAP', limit: OC_LIMIT, offset: 0 })
    case 'fills':
      return apiGet<OcPayload>(EP.accountExchangeFills, { instType: 'SWAP', limit: OC_LIMIT, offset: 0 })
    case 'borrow':
      return apiGet<OcPayload>(EP.accountBorrowRepay, { limit: OC_LIMIT })
    case 'bills':
      return apiGet<OcPayload>(EP.accountBills, { limit: OC_LIMIT })
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
  /* 重新加载后行可能已变，清掉本 tab 的勾选，避免汇总里出现「看不见的行」 */
  selected[name] = []
  /* 凭证没配时后端只给 notice，这里也要让用户看到原因（否则只剩空态文案） */
  if (!s.issue) topText.value = accNoticeText(data)
  s.meta = ocMetaText(name, s.rows.length, data && data.total, data && data.lastSyncedAt)
  updated.value = '刷新于 ' + fmtTime(Date.now())
}

function switchTab(name: OcTabName): void {
  active.value = name
  void loadTab(name, false)
}

/* ---------------- 交易所同步 ---------------- */

function syncCounts(res: OcPayload): string {
  const fetched = res && res.fetched && typeof res.fetched === 'object' ? (res.fetched as Record<string, unknown>) : null
  if (!fetched) return ''
  const orders = Number(fetched.pending || 0) + Number(fetched.history || 0)
  return '委托 ' + orders + ' · 仓位 ' + Number(fetched.positions || 0) + ' · 成交 ' + Number(fetched.fills || 0)
}

async function syncMirror(force: boolean): Promise<boolean> {
  if (syncing.value) return false
  syncing.value = true
  try {
    const res = await apiPost<OcPayload>(EP.accountExchangeSync, undefined, {
      instType: 'SWAP',
      limit: OC_LIMIT,
      force: force
    })
    if (res && res.ok === false) {
      const error = res.error as { message?: unknown } | null
      ui.toast('同步失败：' + (error && error.message ? String(error.message) : '交易所未返回数据'), 'err')
      return false
    }
    if (force) ui.toast('同步完成：' + (syncCounts(res) || '本地镜像已更新'), 'ok')
    return true
  } catch (err) {
    ui.toast('同步失败：' + errMsg(err), 'err')
    return false
  } finally {
    syncing.value = false
  }
}

async function refreshNow(): Promise<void> {
  await syncMirror(true)
  await loadTab(active.value, true)
}

/* 首屏：先自动同步一次本地镜像（失败只 toast、不阻塞），再读当前委托 */
async function bootstrap(): Promise<void> {
  await syncMirror(false)
  await loadTab(active.value, false)
}
void bootstrap()
</script>

<template>
  <section class="page" data-page="order-center">
    <div class="page-head">
      <div>
        <h1>订单中心</h1>
        <p class="muted">
          委托 / 仓位 / 成交来自交易所并落成本地镜像库：进入页面自动同步一次，「强制刷新」再拉一轮；勾选多行可汇总营收。
          借币与资金账单仍是原有数据源；影子策略请到「策略 → 马丁策略」。
        </p>
      </div>
      <div class="page-actions">
        <span class="hint" id="oc-updated">{{ updated }}</span>
        <button class="btn btn-sm" type="button" id="oc-refresh" :disabled="syncing" @click="refreshNow">
          {{ syncing ? '同步中…' : '强制刷新' }}
        </button>
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

        <DataTable
          v-else
          :cols="colsOf(t.name)"
          :rows="st(t.name).rows"
          :empty-text="OC_EMPTY[t.name]"
          :selectable="isMirrorTab(t.name)"
          :selected="selected[t.name]"
          :row-key="(row: Row) => ocRowKey(t.name, row)"
          @toggle="(key: string) => toggleRow(t.name, key)"
          @toggle-all="(keys: string[]) => toggleAll(t.name, keys)"
        />
      </div>
    </div>

    <div v-if="selectedRows.length" id="oc-selection" class="card">
      <div class="card-head">
        <h2>已选 {{ selectedRows.length }} 笔 · 营收汇总</h2>
        <span class="page-actions">
          <button class="btn btn-xs" type="button" id="oc-selection-clear" @click="clearSelection">清空勾选</button>
        </span>
      </div>
      <div class="oc-selection-grid">
        <div class="kv-grid" id="oc-selection-kv">
          <div v-for="p in selectionPairs" :key="p.key" class="kv">
            <span class="kv-key">{{ p.key }}</span>
            <span class="kv-val" v-html="p.html"></span>
          </div>
        </div>
        <div>
          <div class="oc-selection-sub">按合约分组（{{ selectionSummary.byInst.length }}）</div>
          <table id="oc-selection-inst">
            <thead>
              <tr>
                <th>合约 instId</th>
                <th class="num">笔数</th>
                <th class="num">净收益</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in selectionSummary.byInst" :key="row.instId">
                <td><span class="mono">{{ row.instId }}</span></td>
                <td class="num">{{ accInt(row.count) }}</td>
                <td class="num" v-html="accPnl(row.netPnl)"></td>
              </tr>
            </tbody>
          </table>
          <div class="oc-selection-sub">按日净收益（{{ dayBars.length }}）</div>
          <div class="oc-days" id="oc-selection-days">
            <div v-for="bar in dayBars" :key="bar.day" class="oc-day">
              <span class="oc-day-bar" :class="{ 'val-neg': bar.neg }" :style="{ height: bar.height }"></span>
              <span class="mono">{{ bar.day.slice(5) }}</span>
              <span v-html="accPnl(bar.netPnl)"></span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>
