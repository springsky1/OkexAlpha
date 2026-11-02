<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { apiGet } from '../api/client'
import { EP } from '../api/endpoints'
import { badge, esc, fmtInt, fmtTime, isNil } from '../lib/format'
import { useHealthStore } from '../stores/health'
import { useUiStore } from '../stores/ui'
import { usePolling } from '../composables/usePolling'
import DataTable, { type TableCol } from '../components/DataTable.vue'
import StateBlock from '../components/StateBlock.vue'

interface StatusData {
  rows: Record<string, unknown>
  instrumentsByType: any[]
  backfillJobs: any[]
  cursors: Record<string, unknown>
}

const ui = useUiStore()
const health = useHealthStore()
const auto = ref(true)
const busy = ref(false)
const loading = ref(true)
const failed = ref(false)
const error = ref('')
const data = reactive<StatusData>({ rows: {}, instrumentsByType: [], backfillJobs: [], cursors: {} })

const instTypeRows = computed(() =>
  data.instrumentsByType.map((r) => ({
    type: r.inst_type !== undefined ? r.inst_type : r.instType !== undefined ? r.instType : r.type,
    cnt: r.cnt !== undefined ? r.cnt : r.count
  }))
)

const instTypeCols: TableCol[] = [
  { title: 'instType', render: (r: any) => esc(isNil(r.type) ? '-' : r.type) },
  { title: '数量', num: true, render: (r: any) => fmtInt(r.cnt) }
]

const jobCols: TableCol[] = [
  {
    title: 'dataType',
    render: (r: any) => esc(isNil(r.data_type !== undefined ? r.data_type : r.dataType) ? '-' : r.data_type !== undefined ? r.data_type : r.dataType)
  },
  { title: 'status', render: (r: any) => badge(r.status) },
  { title: '作业数', num: true, render: (r: any) => fmtInt(r.jobs) }
]

const rowKeys = computed(() => Object.keys(data.rows))
const cursorKeys = computed(() => Object.keys(data.cursors))

function cursorText(v: unknown): string {
  if (isNil(v)) return '-'
  return fmtTime(v) + '（' + fmtInt(v) + '）'
}

/* 5 秒轮询 + 后端慢查询时，重叠的请求会把 Hikari 连接堆满，前端先做单飞（single-flight）保护 */
async function load(firstLoad: boolean) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  if (firstLoad) loading.value = true
  try {
    const d = await apiGet<any>(EP.syncStatus)
    data.rows = d && d.rows ? d.rows : {}
    data.instrumentsByType = d && Array.isArray(d.instrumentsByType) ? d.instrumentsByType : []
    data.backfillJobs = d && Array.isArray(d.backfillJobs) ? d.backfillJobs : []
    data.cursors = d && d.cursors ? d.cursors : {}
    failed.value = false
    ui.touchRefresh()
  } catch (err: any) {
    const msg = err && err.message ? err.message : String(err)
    error.value = '加载运行状态失败：' + msg
    if (firstLoad) failed.value = true
  } finally {
    busy.value = false
    if (firstLoad) loading.value = false
  }
}

function refreshNow() {
  void load(true)
}

/* 首屏加载（loading=true），随后交给 5 秒轮询（load(false) 不翻转 loading） */
onMounted(() => {
  void load(true)
})

const polling = usePolling(() => load(false), 5000, { enabled: auto, immediate: false })

watch(auto, () => {
  polling.restart()
})

function kvLoading() {
  return loading.value && !failed.value
}
</script>

<template>
  <section class="page" data-page="status">
    <div class="page-head">
      <div>
        <h1>运行状态</h1>
        <p class="muted">健康检查与数据面概览（行数 / 品种分布 / 补拉作业 / 同步游标）。</p>
      </div>
      <div class="page-actions">
        <label class="switch"><input v-model="auto" type="checkbox"><span>每 5 秒自动刷新</span></label>
        <button class="btn btn-primary" type="button" @click="refreshNow">立即刷新</button>
      </div>
    </div>

    <div v-if="error" class="alert danger">{{ error }}</div>

    <div class="card-row">
      <div class="card stat-card">
        <div class="stat-label">健康检查</div>
        <div class="stat-value">{{ health.cardValue }}</div>
        <div class="stat-sub mono">{{ health.cardSub }}</div>
      </div>
      <div class="card stat-card">
        <div class="stat-label">品种总数</div>
        <div class="stat-value">{{ fmtInt(data.rows.instruments) }}</div>
        <div class="stat-sub">okx_instrument</div>
      </div>
      <div class="card stat-card">
        <div class="stat-label">币种总数</div>
        <div class="stat-value">{{ fmtInt(data.rows.coins) }}</div>
        <div class="stat-sub">okx_coin</div>
      </div>
      <div class="card stat-card">
        <div class="stat-label">K 线总行数</div>
        <div class="stat-value">{{ fmtInt(data.rows.klines) }}</div>
        <div class="stat-sub">okx_kline</div>
      </div>
    </div>

    <div class="grid-2">
      <div class="card">
        <div class="card-head"><h2>各表行数</h2><span class="muted">GET /api/v1/sync/status</span></div>
        <StateBlock v-if="failed && !rowKeys.length" kind="error" text="加载失败" />
        <StateBlock v-else-if="kvLoading()" kind="loading" />
        <StateBlock v-else-if="!rowKeys.length" kind="empty" text="暂无数据" />
        <div v-else class="kv-grid">
          <div v-for="k in rowKeys" :key="k" class="kv">
            <span class="kv-key">{{ k }}</span>
            <span class="kv-val">{{ fmtInt(data.rows[k]) }}</span>
          </div>
        </div>
      </div>
      <div class="card">
        <div class="card-head"><h2>品种分布</h2><span class="muted">instrumentsByType</span></div>
        <StateBlock v-if="failed && !instTypeRows.length" kind="error" text="加载失败" />
        <StateBlock v-else-if="loading" kind="loading" />
        <DataTable v-else :cols="instTypeCols" :rows="instTypeRows" empty-text="暂无数据" />
      </div>
    </div>

    <div class="card">
      <div class="card-head"><h2>补拉作业概览</h2><span class="muted">backfillJobs（按类型 + 状态聚合）</span></div>
      <StateBlock v-if="failed && !data.backfillJobs.length" kind="error" text="加载失败" />
      <StateBlock v-else-if="loading" kind="loading" />
      <DataTable v-else :cols="jobCols" :rows="data.backfillJobs" empty-text="暂无补拉作业" />
    </div>

    <div class="card">
      <div class="card-head"><h2>同步游标</h2><span class="muted">okx_sync_cursor</span></div>
      <StateBlock v-if="failed && !cursorKeys.length" kind="error" text="加载失败" />
      <StateBlock v-else-if="kvLoading()" kind="empty" text="暂无数据" />
      <div v-else-if="cursorKeys.length" class="kv-grid">
        <div v-for="k in cursorKeys" :key="k" class="kv">
          <span class="kv-key">{{ k }}</span>
          <span class="kv-val">{{ cursorText(data.cursors[k]) }}</span>
        </div>
      </div>
      <StateBlock v-else kind="empty" text="暂无数据" />
    </div>
  </section>
</template>