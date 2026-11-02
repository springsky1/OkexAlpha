<script setup lang="ts">
/* 我的订单（legacy index.html §14 / app.js §16b 1:1 移植）
   local 是本服务自己记录的影子订单，pending / history 走 OKX；每个 tab 首次进入才请求一次。 */
import { onMounted, reactive, ref } from 'vue'
import { apiGet } from '../api/client'
import { EP } from '../api/endpoints'
import { fmtTime } from '../lib/format'
import DataTable from '../components/DataTable.vue'
import StateBlock from '../components/StateBlock.vue'
import {
  ACC_NA,
  accIssueText,
  accNoticeText,
  ORDER_EX_COLS,
  ORDER_LOCAL_COLS,
  ORDERS_EMPTY,
  ORDERS_LIMIT,
  ORDERS_TABS,
  ordersMetaText,
  type OrdersPayload,
  type OrdersState,
  type Row
} from './account/acc'

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

const active = ref<OrdersState>('local')
const updated = ref(ACC_NA)
const topText = ref('')

const tabs = reactive<Record<OrdersState, TabState>>({
  local: blank('加载中…'),
  pending: blank('尚未加载'),
  history: blank('尚未加载')
})

function st(name: OrdersState): TabState {
  return tabs[name]
}

function errMsg(err: unknown): string {
  const e = err as { message?: unknown } | null
  return e && e.message ? String(e.message) : String(err)
}

async function loadTab(name: OrdersState, force: boolean): Promise<void> {
  const s = tabs[name]
  if (s.loading) return
  if (!force && s.loaded) return
  s.loading = true
  s.requested = true
  if (!s.loaded) s.meta = '加载中…'
  let data: OrdersPayload
  try {
    data = await apiGet<OrdersPayload>(EP.accountOrders, { state: name, instType: 'SWAP', limit: ORDERS_LIMIT })
  } catch (err) {
    const msg = errMsg(err)
    s.loading = false
    s.wrapError = '订单加载失败：' + msg
    s.meta = '加载失败'
    topText.value = '订单加载失败（state=' + name + '）：' + msg
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
  s.meta = ordersMetaText(name, s.rows.length)
  updated.value = '刷新于 ' + fmtTime(Date.now())
}

function switchTab(name: OrdersState): void {
  active.value = name
  void loadTab(name, false)
}

function refreshNow(): void {
  void loadTab(active.value, true)
}

onMounted(() => {
  void loadTab('local', false)
})
</script>

<template>
  <section class="page" data-page="orders">
    <div class="page-head">
      <div>
        <h1>我的订单</h1>
        <p class="muted">本地影子订单与交易所真实订单分开拉取：local 是本服务自己记录的影子订单，pending / history 走 OKX 订单接口。每个 tab 首次进入才请求一次。</p>
      </div>
      <div class="page-actions">
        <span class="hint" id="orders-updated">{{ updated }}</span>
        <button class="btn btn-sm" type="button" id="orders-refresh" @click="refreshNow">刷新</button>
      </div>
    </div>

    <div v-if="topText" class="alert danger" id="orders-error">{{ topText }}</div>

    <div class="tabs" id="orders-tabs" role="tablist">
      <button
        v-for="t in ORDERS_TABS"
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
      v-for="t in ORDERS_TABS"
      :key="t.name"
      class="card tab-panel"
      :class="{ 'is-active': active === t.name }"
      :data-panel="t.name"
    >
      <div class="card-head">
        <h2>{{ t.title }}</h2>
        <span class="muted" :id="'orders-' + t.name + '-meta'">{{ st(t.name).meta }}</span>
      </div>
      <p class="hint">GET /api/v1/account/orders?state={{ t.name }}&amp;instType=SWAP&amp;limit=50</p>
      <div class="table-wrap" :id="'orders-' + t.name + '-wrap'">
        <StateBlock v-if="st(t.name).wrapError" kind="error" :text="st(t.name).wrapError" />
        <StateBlock v-else-if="!st(t.name).requested" kind="empty" text="尚未加载" />
        <StateBlock v-else-if="st(t.name).loading && !st(t.name).loaded" kind="loading" text="加载中…" />
        <div v-else-if="st(t.name).issue" class="alert danger">{{ st(t.name).issue }}</div>
        <DataTable
          v-else
          :cols="t.name === 'local' ? ORDER_LOCAL_COLS : ORDER_EX_COLS"
          :rows="st(t.name).rows"
          :empty-text="ORDERS_EMPTY[t.name]"
        />
      </div>
    </div>
  </section>
</template>
