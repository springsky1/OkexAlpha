<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { apiGet } from '../api/client'
import { EP } from '../api/endpoints'
import { esc, fmtNum, fmtRatio, fmtTime, isNil, toLocalInput } from '../lib/format'
import { PERIODS, RATIO_TYPES } from '../lib/labels'
import { useUiStore } from '../stores/ui'
import DataTable, { type TableCol } from '../components/DataTable.vue'
import StateBlock from '../components/StateBlock.vue'
import InstPicker from '../components/InstPicker.vue'
import {
  buildOiParams,
  buildOivParams,
  buildRatioParams,
  buildTakerParams,
  STATS_TABS,
  oiSummary,
  oivSummary,
  ratioHint,
  ratioSummary,
  takerSummary,
  type StatsTabKey
} from './stats/statsQuery'

const ui = useUiStore()

/* ---------- tab（默认多空比，与 legacy aria-selected 初始值一致） ---------- */
const activeTab = ref<StatsTabKey>('ratios')

/* ---------- 4 个表单（默认值与 legacy index.html 一致） ---------- */
const ratioForm = reactive({ ratioType: 'LSR_ACCOUNT_CCY', scope: 'BTC', period: '1H', start: '', end: '', limit: '500', order: 'desc' })
const oivForm = reactive({ ccy: 'BTC', period: '1H', start: '', end: '', limit: '500', order: 'desc' })
const takerForm = reactive({ scopeKind: 'CCY', scope: 'BTC', period: '1H', start: '', end: '', limit: '500', order: 'desc' })
const oiForm = reactive({ instId: 'BTC-USDT-SWAP', start: '', end: '', limit: '500', order: 'desc' })

const ratioOptions = RATIO_TYPES.map((x) => ({ value: x[0], label: x[0] + '（' + x[1] + '）' }))
const ratioHintText = computed(() => ratioHint(ratioForm.ratioType))

/* ---------- 每个面板独立的结果状态：idle -> loading -> ok/empty/error ---------- */
type Phase = 'idle' | 'loading' | 'ok' | 'empty' | 'error'
interface PanelState {
  phase: Phase
  fail: string
  summary: string
  rows: any[]
}

function freshPanel(): PanelState {
  return { phase: 'idle', fail: '', summary: '尚未查询', rows: [] }
}

const panels = reactive<Record<StatsTabKey, PanelState>>({
  ratios: freshPanel(),
  oiv: freshPanel(),
  taker: freshPanel(),
  oi: freshPanel()
})
const errs = reactive<Record<StatsTabKey, string>>({ ratios: '', oiv: '', taker: '', oi: '' })
const busy = reactive<Record<StatsTabKey, boolean>>({ ratios: false, oiv: false, taker: false, oi: false })

/* ---------- 列定义（与 legacy RATIO_COLS / OIV_COLS / TAKER_COLS / OI_COLS 逐列一致） ---------- */
const ratioCols: TableCol[] = [
  { title: '时间', render: (r: any) => '<span class="mono">' + esc(fmtTime(r.tsMs)) + '</span>' },
  { title: 'ratioType', render: (r: any) => esc(isNil(r.ratioType) ? '-' : r.ratioType) },
  { title: 'scopeKind', render: (r: any) => esc(isNil(r.scopeKind) ? '-' : r.scopeKind) },
  { title: 'scope', render: (r: any) => esc(isNil(r.scope) ? '-' : r.scope) },
  { title: 'period', render: (r: any) => esc(isNil(r.period) ? '-' : r.period) },
  { title: 'longShortRatio', num: true, render: (r: any) => fmtRatio(r.longShortRatio) }
]

const oivCols: TableCol[] = [
  { title: '时间', render: (r: any) => '<span class="mono">' + esc(fmtTime(r.tsMs)) + '</span>' },
  { title: 'ccy', render: (r: any) => esc(isNil(r.ccy) ? '-' : r.ccy) },
  { title: 'period', render: (r: any) => esc(isNil(r.period) ? '-' : r.period) },
  { title: '持仓量 oi', num: true, render: (r: any) => fmtNum(r.oi) },
  { title: '成交额 vol', num: true, render: (r: any) => fmtNum(r.vol) }
]

const takerCols: TableCol[] = [
  { title: '时间', render: (r: any) => '<span class="mono">' + esc(fmtTime(r.tsMs)) + '</span>' },
  { title: 'scopeKind', render: (r: any) => esc(isNil(r.scopeKind) ? '-' : r.scopeKind) },
  { title: 'scope', render: (r: any) => esc(isNil(r.scope) ? '-' : r.scope) },
  { title: 'marketType', render: (r: any) => esc(isNil(r.marketType) ? '-' : r.marketType) },
  { title: 'period', render: (r: any) => esc(isNil(r.period) ? '-' : r.period) },
  { title: '卖出量 sellVol', num: true, render: (r: any) => fmtNum(r.sellVol) },
  { title: '买入量 buyVol', num: true, render: (r: any) => fmtNum(r.buyVol) }
]

const oiCols: TableCol[] = [
  { title: '时间', render: (r: any) => '<span class="mono">' + esc(fmtTime(r.tsMs)) + '</span>' },
  { title: 'instId', render: (r: any) => esc(isNil(r.instId) ? '-' : r.instId) },
  { title: 'instType', render: (r: any) => esc(isNil(r.instType) ? '-' : r.instType) },
  { title: '持仓量 oi', num: true, render: (r: any) => fmtNum(r.oi) },
  { title: 'oiCcy', num: true, render: (r: any) => fmtNum(r.oiCcy) },
  { title: 'oiUsd', num: true, render: (r: any) => fmtNum(r.oiUsd) }
]

/* ---------- 快捷时间（legacy setQuickRange / clearQuickRange） ---------- */
function quickRange(target: { start: string; end: string }, days: number) {
  const now = Date.now()
  target.end = toLocalInput(now)
  target.start = toLocalInput(now - days * 86400000)
  ui.toast('已填充最近 ' + days + ' 天的时间范围', 'info')
}

function clearRange(target: { start: string; end: string }) {
  target.start = ''
  target.end = ''
}

/* ---------- 统一执行：setLoading -> apiGet -> 表格/概要，失败写错误条 + toast ---------- */
const TOAST_PREFIX: Record<StatsTabKey, string> = {
  ratios: '多空比查询失败',
  oiv: '持仓量-成交额查询失败',
  taker: '主动买卖量查询失败',
  oi: '持仓量快照查询失败'
}

async function run(key: StatsTabKey, path: string, params: Record<string, unknown>, summarize: (list: any[]) => string) {
  const st = panels[key]
  busy[key] = true
  st.phase = 'loading'
  try {
    const data = await apiGet<any[]>(path, params)
    const list = Array.isArray(data) ? data : []
    st.rows = list
    st.phase = list.length ? 'ok' : 'empty'
    st.summary = summarize(list)
    ui.touchRefresh()
  } catch (err: any) {
    const msg = err && err.message ? err.message : String(err)
    st.fail = msg
    st.phase = 'error'
    errs[key] = msg
    ui.toast(TOAST_PREFIX[key] + '：' + msg, 'err')
  } finally {
    busy[key] = false
  }
}

async function loadRatios() {
  errs.ratios = ''
  const built = buildRatioParams({
    ratioType: ratioForm.ratioType,
    scope: ratioForm.scope,
    period: ratioForm.period,
    order: ratioForm.order,
    limitRaw: ratioForm.limit,
    startRaw: ratioForm.start,
    endRaw: ratioForm.end
  })
  if (!built.ok) {
    errs.ratios = built.error
    ui.toast(built.toast, 'err')
    return
  }
  const ratioType = String(built.params.ratioType)
  const scope = String(built.params.scope)
  await run('ratios', EP.ratios, built.params, (list) => ratioSummary(list.length, ratioType, scope))
}

async function loadOiv() {
  errs.oiv = ''
  const built = buildOivParams({
    ccy: oivForm.ccy,
    period: oivForm.period,
    order: oivForm.order,
    limitRaw: oivForm.limit,
    startRaw: oivForm.start,
    endRaw: oivForm.end
  })
  if (!built.ok) {
    errs.oiv = built.error
    ui.toast(built.toast, 'err')
    return
  }
  const ccy = String(built.params.ccy)
  const period = String(built.params.period)
  await run('oiv', EP.openInterestVolume, built.params, (list) => oivSummary(list.length, ccy, period))
}

async function loadTaker() {
  errs.taker = ''
  const built = buildTakerParams({
    scopeKind: takerForm.scopeKind,
    scope: takerForm.scope,
    period: takerForm.period,
    order: takerForm.order,
    limitRaw: takerForm.limit,
    startRaw: takerForm.start,
    endRaw: takerForm.end
  })
  if (!built.ok) {
    errs.taker = built.error
    ui.toast(built.toast, 'err')
    return
  }
  const scopeKind = String(built.params.scopeKind)
  const scope = String(built.params.scope)
  await run('taker', EP.takerVolume, built.params, (list) => takerSummary(list.length, scopeKind, scope))
}

async function loadOi() {
  errs.oi = ''
  const built = buildOiParams({
    instId: oiForm.instId,
    order: oiForm.order,
    limitRaw: oiForm.limit,
    startRaw: oiForm.start,
    endRaw: oiForm.end
  })
  if (!built.ok) {
    errs.oi = built.error
    ui.toast(built.toast, 'err')
    return
  }
  const instId = String(built.params.instId)
  await run('oi', EP.openInterest, built.params, (list) => oiSummary(list.length, instId))
}

/* 切 tab 即查询该 tab（与 legacy switchStatsTab -> loadStatsTab 一致） */
async function switchTab(name: StatsTabKey) {
  activeTab.value = name
  if (name === 'ratios') await loadRatios()
  else if (name === 'oiv') await loadOiv()
  else if (name === 'taker') await loadTaker()
  else await loadOi()
}
</script>

<template>
  <section class="page" data-page="stats">
    <div class="page-head">
      <div>
        <h1>统计查询</h1>
        <p class="muted">rubik 统计与持仓量快照。period 只支持 5m / 1H / 1D（各有上游保留期）。</p>
      </div>
    </div>

    <div class="tabs" role="tablist">
      <button
        v-for="tab in STATS_TABS"
        :key="tab.key"
        class="tab"
        :class="{ 'is-active': activeTab === tab.key }"
        type="button"
        role="tab"
        :aria-selected="activeTab === tab.key ? 'true' : 'false'"
        @click="switchTab(tab.key)"
      >
        {{ tab.label }}
      </button>
    </div>

    <!-- ============ 4.1 多空比 ============ -->
    <div class="tab-panel" :class="{ 'is-active': activeTab === 'ratios' }" data-panel="ratios">
      <form class="card" novalidate @submit.prevent="loadRatios">
        <div class="form-grid">
          <div class="field">
            <label for="ratio-type">ratioType <span class="req">*</span></label>
            <select id="ratio-type" v-model="ratioForm.ratioType">
              <option v-for="o in ratioOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
            </select>
            <p class="hint">{{ ratioHintText }}</p>
          </div>
          <div class="field">
            <label for="ratio-scope">scope <span class="req">*</span></label>
            <input id="ratio-scope" v-model="ratioForm.scope" type="text" placeholder="BTC 或 BTC-USDT-SWAP" autocomplete="off">
            <p class="hint">CCY 类型填币种，INST 类型填 instId</p>
          </div>
          <div class="field">
            <label for="ratio-period">period <span class="req">*</span></label>
            <select id="ratio-period" v-model="ratioForm.period">
              <option v-for="o in PERIODS" :key="o[0]" :value="o[0]">{{ o[1] }}</option>
            </select>
          </div>
          <div class="field">
            <label for="ratio-start">开始时间</label>
            <input id="ratio-start" v-model="ratioForm.start" type="datetime-local">
          </div>
          <div class="field">
            <label for="ratio-end">结束时间</label>
            <input id="ratio-end" v-model="ratioForm.end" type="datetime-local">
          </div>
          <div class="field">
            <label for="ratio-limit">limit</label>
            <input id="ratio-limit" v-model="ratioForm.limit" type="number" min="1" max="1000" step="1">
          </div>
          <div class="field">
            <label for="ratio-order">order</label>
            <select id="ratio-order" v-model="ratioForm.order">
              <option value="desc">desc（新 → 旧）</option>
              <option value="asc">asc（旧 → 新）</option>
            </select>
          </div>
        </div>
        <div class="form-actions">
          <div class="quick-group">
            <span class="hint">快捷时间：</span>
            <button class="chip" type="button" @click="quickRange(ratioForm, 1)">近 1 天</button>
            <button class="chip" type="button" @click="quickRange(ratioForm, 7)">近 7 天</button>
            <button class="chip" type="button" @click="quickRange(ratioForm, 30)">近 30 天</button>
            <button class="chip" type="button" @click="clearRange(ratioForm)">清空时间</button>
          </div>
          <div class="btn-group">
            <button class="btn btn-primary" type="submit" :disabled="busy.ratios" :aria-busy="busy.ratios ? 'true' : 'false'">查询</button>
          </div>
        </div>
      </form>
      <div v-if="errs.ratios" class="alert danger">{{ errs.ratios }}</div>
      <div class="card">
        <div class="card-head"><h2>查询结果</h2><span class="muted">{{ panels.ratios.summary }}</span></div>
        <StateBlock v-if="panels.ratios.phase === 'loading'" kind="loading" text="查询中…" />
        <StateBlock v-else-if="panels.ratios.phase === 'error'" kind="error" :text="'查询失败：' + panels.ratios.fail" />
        <StateBlock v-else-if="panels.ratios.phase === 'idle'" kind="empty" text="尚未查询" />
        <template v-else>
          <div class="table-wrap">
            <DataTable :cols="ratioCols" :rows="panels.ratios.rows" empty-text="暂无数据" />
          </div>
        </template>
      </div>
    </div>

    <!-- ============ 4.2 持仓量-成交额 ============ -->
    <div class="tab-panel" :class="{ 'is-active': activeTab === 'oiv' }" data-panel="oiv">
      <form class="card" novalidate @submit.prevent="loadOiv">
        <div class="form-grid">
          <div class="field">
            <label for="oiv-ccy">ccy <span class="req">*</span></label>
            <input id="oiv-ccy" v-model="oivForm.ccy" type="text" placeholder="BTC" autocomplete="off">
          </div>
          <div class="field">
            <label for="oiv-period">period <span class="req">*</span></label>
            <select id="oiv-period" v-model="oivForm.period">
              <option v-for="o in PERIODS" :key="o[0]" :value="o[0]">{{ o[1] }}</option>
            </select>
          </div>
          <div class="field">
            <label for="oiv-start">开始时间</label>
            <input id="oiv-start" v-model="oivForm.start" type="datetime-local">
          </div>
          <div class="field">
            <label for="oiv-end">结束时间</label>
            <input id="oiv-end" v-model="oivForm.end" type="datetime-local">
          </div>
          <div class="field">
            <label for="oiv-limit">limit</label>
            <input id="oiv-limit" v-model="oivForm.limit" type="number" min="1" max="1000" step="1">
          </div>
          <div class="field">
            <label for="oiv-order">order</label>
            <select id="oiv-order" v-model="oivForm.order">
              <option value="desc">desc（新 → 旧）</option>
              <option value="asc">asc（旧 → 新）</option>
            </select>
          </div>
        </div>
        <div class="form-actions">
          <div class="quick-group">
            <span class="hint">快捷时间：</span>
            <button class="chip" type="button" @click="quickRange(oivForm, 1)">近 1 天</button>
            <button class="chip" type="button" @click="quickRange(oivForm, 7)">近 7 天</button>
            <button class="chip" type="button" @click="clearRange(oivForm)">清空时间</button>
          </div>
          <div class="btn-group">
            <button class="btn btn-primary" type="submit" :disabled="busy.oiv" :aria-busy="busy.oiv ? 'true' : 'false'">查询</button>
          </div>
        </div>
      </form>
      <div v-if="errs.oiv" class="alert danger">{{ errs.oiv }}</div>
      <div class="card">
        <div class="card-head"><h2>查询结果</h2><span class="muted">{{ panels.oiv.summary }}</span></div>
        <StateBlock v-if="panels.oiv.phase === 'loading'" kind="loading" text="查询中…" />
        <StateBlock v-else-if="panels.oiv.phase === 'error'" kind="error" :text="'查询失败：' + panels.oiv.fail" />
        <StateBlock v-else-if="panels.oiv.phase === 'idle'" kind="empty" text="尚未查询" />
        <template v-else>
          <div class="table-wrap">
            <DataTable :cols="oivCols" :rows="panels.oiv.rows" empty-text="暂无数据" />
          </div>
        </template>
      </div>
    </div>

    <!-- ============ 4.3 主动买卖量 ============ -->
    <div class="tab-panel" :class="{ 'is-active': activeTab === 'taker' }" data-panel="taker">
      <form class="card" novalidate @submit.prevent="loadTaker">
        <div class="form-grid">
          <div class="field">
            <label for="taker-scope-kind">scopeKind</label>
            <select id="taker-scope-kind" v-model="takerForm.scopeKind">
              <option value="CCY">CCY（按币种）</option>
              <option value="INST">INST（按合约）</option>
            </select>
          </div>
          <div class="field">
            <label for="taker-scope">scope <span class="req">*</span></label>
            <input id="taker-scope" v-model="takerForm.scope" type="text" placeholder="BTC 或 BTC-USDT-SWAP" autocomplete="off">
            <p class="hint">CCY 填币种，INST 填 instId</p>
          </div>
          <div class="field">
            <label for="taker-period">period <span class="req">*</span></label>
            <select id="taker-period" v-model="takerForm.period">
              <option v-for="o in PERIODS" :key="o[0]" :value="o[0]">{{ o[1] }}</option>
            </select>
          </div>
          <div class="field">
            <label for="taker-start">开始时间</label>
            <input id="taker-start" v-model="takerForm.start" type="datetime-local">
          </div>
          <div class="field">
            <label for="taker-end">结束时间</label>
            <input id="taker-end" v-model="takerForm.end" type="datetime-local">
          </div>
          <div class="field">
            <label for="taker-limit">limit</label>
            <input id="taker-limit" v-model="takerForm.limit" type="number" min="1" max="1000" step="1">
          </div>
          <div class="field">
            <label for="taker-order">order</label>
            <select id="taker-order" v-model="takerForm.order">
              <option value="desc">desc（新 → 旧）</option>
              <option value="asc">asc（旧 → 新）</option>
            </select>
          </div>
        </div>
        <div class="form-actions">
          <div class="quick-group">
            <span class="hint">快捷时间：</span>
            <button class="chip" type="button" @click="quickRange(takerForm, 1)">近 1 天</button>
            <button class="chip" type="button" @click="quickRange(takerForm, 7)">近 7 天</button>
            <button class="chip" type="button" @click="clearRange(takerForm)">清空时间</button>
          </div>
          <div class="btn-group">
            <button class="btn btn-primary" type="submit" :disabled="busy.taker" :aria-busy="busy.taker ? 'true' : 'false'">查询</button>
          </div>
        </div>
      </form>
      <div v-if="errs.taker" class="alert danger">{{ errs.taker }}</div>
      <div class="card">
        <div class="card-head"><h2>查询结果</h2><span class="muted">{{ panels.taker.summary }}</span></div>
        <StateBlock v-if="panels.taker.phase === 'loading'" kind="loading" text="查询中…" />
        <StateBlock v-else-if="panels.taker.phase === 'error'" kind="error" :text="'查询失败：' + panels.taker.fail" />
        <StateBlock v-else-if="panels.taker.phase === 'idle'" kind="empty" text="尚未查询" />
        <template v-else>
          <div class="table-wrap">
            <DataTable :cols="takerCols" :rows="panels.taker.rows" empty-text="暂无数据" />
          </div>
        </template>
      </div>
    </div>

    <!-- ============ 4.4 持仓量快照 ============ -->
    <div class="tab-panel" :class="{ 'is-active': activeTab === 'oi' }" data-panel="oi">
      <form class="card" novalidate @submit.prevent="loadOi">
        <div class="form-grid">
          <div class="field">
            <label>instId <span class="req">*</span></label>
            <InstPicker v-model="oiForm.instId" :inst-types="['SWAP', 'FUTURES']" placeholder="点击或输入 coin 筛选，如 BTC" />
            <p class="hint">下拉可选 SWAP + FUTURES；持仓量快照按采集时间记录，无历史回填</p>
          </div>
          <div class="field">
            <label for="oi-start">开始时间</label>
            <input id="oi-start" v-model="oiForm.start" type="datetime-local">
          </div>
          <div class="field">
            <label for="oi-end">结束时间</label>
            <input id="oi-end" v-model="oiForm.end" type="datetime-local">
          </div>
          <div class="field">
            <label for="oi-limit">limit</label>
            <input id="oi-limit" v-model="oiForm.limit" type="number" min="1" max="1000" step="1">
          </div>
          <div class="field">
            <label for="oi-order">order</label>
            <select id="oi-order" v-model="oiForm.order">
              <option value="desc">desc（新 → 旧）</option>
              <option value="asc">asc（旧 → 新）</option>
            </select>
          </div>
        </div>
        <div class="form-actions">
          <div class="quick-group">
            <span class="hint">快捷时间：</span>
            <button class="chip" type="button" @click="quickRange(oiForm, 1)">近 1 天</button>
            <button class="chip" type="button" @click="quickRange(oiForm, 7)">近 7 天</button>
            <button class="chip" type="button" @click="clearRange(oiForm)">清空时间</button>
          </div>
          <div class="btn-group">
            <button class="btn btn-primary" type="submit" :disabled="busy.oi" :aria-busy="busy.oi ? 'true' : 'false'">查询</button>
          </div>
        </div>
      </form>
      <div v-if="errs.oi" class="alert danger">{{ errs.oi }}</div>
      <div class="card">
        <div class="card-head"><h2>查询结果</h2><span class="muted">{{ panels.oi.summary }}</span></div>
        <StateBlock v-if="panels.oi.phase === 'loading'" kind="loading" text="查询中…" />
        <StateBlock v-else-if="panels.oi.phase === 'error'" kind="error" :text="'查询失败：' + panels.oi.fail" />
        <StateBlock v-else-if="panels.oi.phase === 'idle'" kind="empty" text="尚未查询" />
        <template v-else>
          <div class="table-wrap">
            <DataTable :cols="oiCols" :rows="panels.oi.rows" empty-text="暂无数据" />
          </div>
        </template>
      </div>
    </div>
  </section>
</template>