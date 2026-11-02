<script setup lang="ts">
import { reactive, ref } from 'vue'
import { apiGet } from '../api/client'
import { EP } from '../api/endpoints'
import { esc, fmtNum, fmtTime, isNil, toLocalInput } from '../lib/format'
import { useUiStore } from '../stores/ui'
import DataTable, { type TableCol } from '../components/DataTable.vue'
import StateBlock from '../components/StateBlock.vue'
import InstPicker from '../components/InstPicker.vue'
import { buildFundingParams, fundingSummary } from './funding/fundingQuery'

const ui = useUiStore()

/* ---------- 表单状态（默认值与 legacy index.html 一致） ---------- */
const form = reactive({
  instId: 'BTC-USDT-SWAP',
  start: '',
  end: '',
  limit: '500',
  order: 'desc'
})

/* ---------- 结果区状态：idle -> loading -> ok/empty/error ---------- */
type Phase = 'idle' | 'loading' | 'ok' | 'empty' | 'error'
const phase = ref<Phase>('idle')
const rows = ref<any[]>([])
const summary = ref('尚未查询')
const fail = ref('')
const error = ref('')
const busy = ref(false)

/* ---------- 列定义（与 legacy FUNDING_COLS 逐列一致） ---------- */
const cols: TableCol[] = [
  { title: '资金费率时间', render: (r: any) => '<span class="mono">' + esc(fmtTime(r.fundingTimeMs)) + '</span>' },
  { title: 'instId', render: (r: any) => esc(isNil(r.instId) ? '-' : r.instId) },
  { title: '资金费率', num: true, render: (r: any) => fmtNum(r.fundingRate) },
  { title: '实际费率', num: true, render: (r: any) => fmtNum(r.realizedRate) },
  { title: '溢价 premium', num: true, render: (r: any) => fmtNum(r.premium) },
  { title: '利率 interestRate', num: true, render: (r: any) => fmtNum(r.interestRate) },
  { title: '下次结算', render: (r: any) => '<span class="mono">' + esc(fmtTime(r.nextFundingTimeMs)) + '</span>' },
  { title: 'settled', num: true, render: (r: any) => esc(isNil(r.settled) ? '-' : r.settled ? '是' : '否') },
  { title: 'method', render: (r: any) => esc(isNil(r.method) ? '-' : r.method) },
  { title: 'formulaType', render: (r: any) => esc(isNil(r.formulaType) ? '-' : r.formulaType) }
]

/* ---------- 快捷时间（legacy setQuickRange / clearQuickRange） ---------- */
function quickRange(days: number) {
  const now = Date.now()
  form.end = toLocalInput(now)
  form.start = toLocalInput(now - days * 86400000)
  ui.toast('已填充最近 ' + days + ' 天的时间范围', 'info')
}

function clearRange() {
  form.start = ''
  form.end = ''
}

/* ---------- 查询 ---------- */
async function submit() {
  error.value = ''
  const built = buildFundingParams({
    instId: form.instId,
    order: form.order,
    limitRaw: form.limit,
    startRaw: form.start,
    endRaw: form.end
  })
  if (!built.ok) {
    error.value = built.error
    ui.toast(built.toast, 'err')
    return
  }
  busy.value = true
  phase.value = 'loading'
  try {
    const data = await apiGet<any[]>(EP.fundingRates, built.params)
    const list = Array.isArray(data) ? data : []
    rows.value = list
    phase.value = list.length ? 'ok' : 'empty'
    summary.value = fundingSummary(list.length, String(form.instId || '').trim())
    ui.touchRefresh()
  } catch (err: any) {
    const msg = err && err.message ? err.message : String(err)
    fail.value = msg
    phase.value = 'error'
    error.value = msg
    ui.toast('资金费率查询失败：' + msg, 'err')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="page" data-page="funding">
    <div class="page-head">
      <div>
        <h1>资金费率</h1>
        <p class="muted">按 instId 与时间范围查询资金费率记录（衍生品才有资金费率）。</p>
      </div>
    </div>

    <form class="card" novalidate @submit.prevent="submit">
      <div class="form-grid">
        <div class="field">
          <label>instId <span class="req">*</span></label>
          <InstPicker v-model="form.instId" :inst-types="['SWAP', 'FUTURES']" placeholder="点击或输入 coin 筛选，如 BTC" />
          <p class="hint">下拉可选 SWAP + FUTURES，支持按 coin 过滤</p>
        </div>
        <div class="field">
          <label for="funding-start">开始时间</label>
          <input id="funding-start" v-model="form.start" type="datetime-local">
        </div>
        <div class="field">
          <label for="funding-end">结束时间</label>
          <input id="funding-end" v-model="form.end" type="datetime-local">
        </div>
        <div class="field">
          <label for="funding-limit">limit</label>
          <input id="funding-limit" v-model="form.limit" type="number" min="1" max="1000" step="1">
          <p class="hint">默认 500，上限 1000</p>
        </div>
        <div class="field">
          <label for="funding-order">order</label>
          <select id="funding-order" v-model="form.order">
            <option value="desc">desc（新 → 旧）</option>
            <option value="asc">asc（旧 → 新）</option>
          </select>
        </div>
      </div>
      <div class="form-actions">
        <div class="quick-group">
          <span class="hint">快捷时间：</span>
          <button class="chip" type="button" @click="quickRange(7)">近 7 天</button>
          <button class="chip" type="button" @click="quickRange(30)">近 30 天</button>
          <button class="chip" type="button" @click="quickRange(90)">近 90 天</button>
          <button class="chip" type="button" @click="clearRange">清空时间</button>
        </div>
        <div class="btn-group">
          <button class="btn btn-primary" type="submit" :disabled="busy" :aria-busy="busy ? 'true' : 'false'">查询</button>
        </div>
      </div>
    </form>

    <div v-if="error" class="alert danger">{{ error }}</div>

    <div class="card">
      <div class="card-head"><h2>查询结果</h2><span class="muted">{{ summary }}</span></div>
      <StateBlock v-if="phase === 'loading'" kind="loading" text="查询中…" />
      <StateBlock v-else-if="phase === 'error'" kind="error" :text="'查询失败：' + fail" />
      <StateBlock v-else-if="phase === 'idle'" kind="empty" text="尚未查询" />
      <template v-else>
        <div class="table-wrap">
          <DataTable :cols="cols" :rows="rows" empty-text="暂无数据" />
        </div>
      </template>
    </div>
  </section>
</template>