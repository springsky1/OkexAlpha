<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { apiGet, apiPost } from '../api/client'
import { EP } from '../api/endpoints'
import { fmtInt } from '../lib/format'
import { useUiStore } from '../stores/ui'
import { usePolling } from '../composables/usePolling'
import DataTable from '../components/DataTable.vue'
import StateBlock from '../components/StateBlock.vue'
import {
  HEALTH_LEVELS,
  HEALTH_PAGE_LIMIT,
  HEALTH_POLL_MS,
  healthChecksCols,
  healthChecksInfo,
  healthCheckedAt,
  healthListMeta,
  healthSummaryHtml,
  healthSummaryMeta,
  summarizeHealth
} from './health/logic'

const ui = useUiStore()
const auto = ref(true)
const refreshing = ref(false)
const checking = ref(false)
const error = ref('')

/* 最近一轮体检 */
const latestRows = ref<any[]>([])
const latestLoading = ref(true)
const latestFailed = ref(false)
const latestError = ref('')

/* 体检明细 */
const level = ref('')
const offset = ref(0)
const checks = ref<any[]>([])
const checksLoading = ref(true)
const checksFailed = ref(false)
const checksError = ref('')
const checksInfoText = ref('')

const cols = healthChecksCols()
const summary = computed(() => summarizeHealth(latestRows.value))
const summaryHtml = computed(() => healthSummaryHtml(summary.value))
const summaryMeta = computed(() => healthSummaryMeta(summary.value))
const checkedAt = computed(() => healthCheckedAt(summary.value))
const listMeta = computed(() => healthListMeta(level.value))
const prevDisabled = computed(() => offset.value <= 0)
const nextDisabled = computed(() => checks.value.length < HEALTH_PAGE_LIMIT)

function errText(err: any): string {
  return err && err.message ? String(err.message) : String(err)
}

async function loadLatest(): Promise<void> {
  if (!latestRows.value.length) latestLoading.value = true
  try {
    const data = await apiGet<any[]>(EP.strategyHealthLatest)
    latestRows.value = Array.isArray(data) ? data : []
    latestFailed.value = false
  } catch (err: any) {
    latestFailed.value = true
    latestError.value = '最近体检加载失败：' + errText(err)
    throw err
  } finally {
    latestLoading.value = false
  }
}

async function loadChecks(): Promise<void> {
  checksLoading.value = true
  const params: Record<string, unknown> = { limit: HEALTH_PAGE_LIMIT, offset: offset.value }
  if (level.value) params.level = level.value
  try {
    const data = await apiGet<any[]>(EP.strategyHealthChecks, params)
    const rows = Array.isArray(data) ? data : []
    checks.value = rows
    checksFailed.value = false
    checksInfoText.value = healthChecksInfo(offset.value, rows.length)
    if (offset.value > 0 && rows.length === 0) {
      offset.value = Math.max(0, offset.value - HEALTH_PAGE_LIMIT)
      checksInfoText.value = '已到末页'
    }
  } catch (err: any) {
    checksFailed.value = true
    checksError.value = '体检明细加载失败：' + errText(err)
    checksInfoText.value = '加载失败'
    throw err
  } finally {
    checksLoading.value = false
  }
}

async function loadAll(manual: boolean): Promise<void> {
  error.value = ''
  if (manual) refreshing.value = true
  const results = await Promise.all([
    loadLatest().catch((e: unknown) => e),
    loadChecks().catch((e: unknown) => e)
  ])
  if (manual) refreshing.value = false
  const parts: string[] = []
  for (let i = 0; i < results.length; i++) {
    const v = results[i]
    if (v instanceof Error && v.message) parts.push(v.message)
  }
  const msg = parts.join('；')
  if (msg) error.value = '部分数据加载失败：' + msg
  if (manual) ui.toast(msg ? '刷新完成，但有接口失败' : '健康度已刷新', msg ? 'err' : 'ok')
}

function refreshNow(): void {
  void loadAll(true)
}

function onLevelChange(): void {
  offset.value = 0
  void loadChecks().catch((err: any) => ui.toast('明细加载失败：' + errText(err), 'err'))
}

function prevPage(): void {
  offset.value = Math.max(0, offset.value - HEALTH_PAGE_LIMIT)
  void loadChecks().catch(() => undefined)
}

function nextPage(): void {
  offset.value = offset.value + HEALTH_PAGE_LIMIT
  void loadChecks().catch(() => undefined)
}

async function runCheck(): Promise<void> {
  if (checking.value) return
  checking.value = true
  try {
    const r = await apiPost<any>(EP.strategyHealthCheck)
    const res = r || {}
    const text =
      res.summary ||
      'ok=' + fmtInt(res.ok) + ' warn=' + fmtInt(res.warn) + ' fail=' + fmtInt(res.fail)
    ui.toast('体检完成：' + text + '，耗时 ' + fmtInt(res.durationMs) + ' ms', 'ok')
    offset.value = 0
    await loadAll(false)
  } catch (err: any) {
    ui.toast('体检失败：' + errText(err), 'err')
  } finally {
    checking.value = false
  }
}

const polling = usePolling(() => loadAll(false), HEALTH_POLL_MS, { enabled: auto, immediate: false })
watch(auto, () => polling.restart())

onMounted(() => {
  void loadAll(false)
})
</script>

<template>
  <section class="page" data-page="health">
    <div class="page-head">
      <div>
        <h1>健康度</h1>
        <p class="muted">
          每轮影子 tick 后跑 9 类规则体检：调度、行情新鲜度、资金利用率、仓位退出、资金费率、空仓、实盘门禁、接口错误率、补拉负载。这里看最近一轮结论与历史明细。
        </p>
      </div>
      <div class="page-actions">
        <span class="hint">{{ checkedAt }}</span>
        <label class="switch"><input v-model="auto" type="checkbox" /><span>每 15 秒自动刷新</span></label>
        <button class="btn btn-sm" type="button" :disabled="refreshing" :aria-busy="refreshing ? 'true' : 'false'" @click="refreshNow">
          刷新
        </button>
        <button class="btn btn-sm btn-primary" type="button" :disabled="checking" :aria-busy="checking ? 'true' : 'false'" @click="runCheck">
          立即体检
        </button>
      </div>
    </div>

    <div v-if="error" class="alert danger">{{ error }}</div>

    <div class="card">
      <div class="card-head">
        <h2>最近一轮体检</h2>
        <span class="muted">{{ summaryMeta }}</span>
      </div>
      <StateBlock v-if="latestLoading && !latestRows.length" kind="loading" text="加载中…" />
      <StateBlock v-else-if="latestFailed && !latestRows.length" kind="error" :text="latestError" />
      <div v-else-if="summary" v-html="summaryHtml"></div>
      <StateBlock v-else kind="empty" text="暂无体检记录，点右上角「立即体检」跑一轮" />
    </div>

    <div class="card">
      <div class="card-head">
        <h2>体检明细</h2>
        <div class="page-actions">
          <label class="hint" for="health-level">level</label>
          <select id="health-level" v-model="level" class="health-level" @change="onLevelChange">
            <option value="">全部</option>
            <option v-for="lv in HEALTH_LEVELS" :key="lv" :value="lv">{{ lv }}</option>
          </select>
          <span class="muted">{{ listMeta }}</span>
        </div>
      </div>
      <div class="table-wrap">
        <StateBlock v-if="checksLoading && !checks.length" kind="loading" text="加载中…" />
        <StateBlock v-else-if="checksFailed && !checks.length" kind="error" :text="checksError" />
        <DataTable v-else :cols="cols" :rows="checks" empty-text="暂无数据" />
      </div>
      <div class="pager">
        <button class="btn btn-sm" type="button" :disabled="prevDisabled" @click="prevPage">上一页</button>
        <span class="pager-info">{{ checksInfoText }}</span>
        <button class="btn btn-sm" type="button" :disabled="nextDisabled" @click="nextPage">下一页</button>
      </div>
    </div>
  </section>
</template>
