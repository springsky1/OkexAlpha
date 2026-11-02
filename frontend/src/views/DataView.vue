<script setup lang="ts">
import { computed, reactive } from 'vue'
import { apiGet } from '../api/client'
import { EP } from '../api/endpoints'
import { useUiStore } from '../stores/ui'
import DataTable from '../components/DataTable.vue'
import StateBlock from '../components/StateBlock.vue'
import AppPager from '../components/AppPager.vue'
import {
  COIN_COLS,
  INST_COLS,
  INST_STATE_OPTIONS,
  INST_TYPE_OPTIONS,
  LIMIT_OPTIONS,
  buildCoinsParams,
  buildInstParams,
  pageSummary,
  pagerTotal
} from './data/dataQuery'

const ui = useUiStore()

/* channel 下拉（legacy index.html #coins-channel） */
const COIN_CHANNELS = ['SPOT', 'MARGIN', 'SWAP', 'FUTURES', 'OPTION']

/* ---------- 两个表格各自的表单 + 结果状态 ---------- */
type Phase = 'idle' | 'loading' | 'ok' | 'empty' | 'error'
interface TableState {
  phase: Phase
  fail: string
  summary: string
  rows: any[]
  offset: number
  busy: boolean
  error: string
}

function freshTable(): TableState {
  return { phase: 'idle', fail: '', summary: '尚未查询', rows: [], offset: 0, busy: false, error: '' }
}

const coinsForm = reactive({ channel: '', q: '', limit: 50 })
const instForm = reactive({ instType: '', baseCcy: '', state: '', limit: 50 })
const coinsState = reactive<TableState>(freshTable())
const instState = reactive<TableState>(freshTable())

const coinsTotal = computed(() => pagerTotal(coinsState.offset, coinsState.rows.length, coinsForm.limit))
const instTotal = computed(() => pagerTotal(instState.offset, instState.rows.length, instForm.limit))

/* ---------- 币种 ---------- */
async function loadCoins() {
  coinsState.error = ''
  coinsState.busy = true
  coinsState.phase = 'loading'
  try {
    const data = await apiGet<any[]>(
      EP.coins,
      buildCoinsParams({ channel: coinsForm.channel, q: coinsForm.q, limit: coinsForm.limit, offset: coinsState.offset })
    )
    const list = Array.isArray(data) ? data : []
    coinsState.rows = list
    coinsState.phase = list.length ? 'ok' : 'empty'
    coinsState.summary = pageSummary(list.length, coinsForm.limit)
    ui.touchRefresh()
  } catch (err: any) {
    const msg = err && err.message ? err.message : String(err)
    coinsState.fail = msg
    coinsState.phase = 'error'
    coinsState.error = msg
    ui.toast('加载币种失败：' + msg, 'err')
  } finally {
    coinsState.busy = false
  }
}

/* 提交 / 换每页条数 / 翻页都要重新查询（legacy initDataPage 的三个监听器） */
function onCoinsSubmit() {
  coinsState.offset = 0
  void loadCoins()
}

function onCoinsLimitChange(e: Event) {
  coinsForm.limit = Number((e.target as HTMLSelectElement).value) || 50
  coinsState.offset = 0
  void loadCoins()
}

function onCoinsPage(offset: number) {
  coinsState.offset = offset
  void loadCoins()
}

/* ---------- 品种 ---------- */
async function loadInstruments() {
  instState.error = ''
  instState.busy = true
  instState.phase = 'loading'
  try {
    const data = await apiGet<any[]>(
      EP.instruments,
      buildInstParams({
        instType: instForm.instType,
        baseCcy: instForm.baseCcy,
        state: instForm.state,
        limit: instForm.limit,
        offset: instState.offset
      })
    )
    const list = Array.isArray(data) ? data : []
    instState.rows = list
    instState.phase = list.length ? 'ok' : 'empty'
    instState.summary = pageSummary(list.length, instForm.limit)
    ui.touchRefresh()
  } catch (err: any) {
    const msg = err && err.message ? err.message : String(err)
    instState.fail = msg
    instState.phase = 'error'
    instState.error = msg
    ui.toast('加载品种失败：' + msg, 'err')
  } finally {
    instState.busy = false
  }
}

function onInstSubmit() {
  instState.offset = 0
  void loadInstruments()
}

function onInstLimitChange(e: Event) {
  instForm.limit = Number((e.target as HTMLSelectElement).value) || 50
  instState.offset = 0
  void loadInstruments()
}

function onInstPage(offset: number) {
  instState.offset = offset
  void loadInstruments()
}
</script>

<template>
  <section class="page" data-page="data">
    <div class="page-head">
      <div>
        <h1>数据浏览</h1>
        <p class="muted">币种表与品种表分页浏览（后端不返回总条数，按「本页是否满页」判断是否还有下一页）。</p>
      </div>
    </div>

    <!-- ============ 币种 ============ -->
    <form class="card" novalidate @submit.prevent="onCoinsSubmit">
      <div class="card-head"><h2>币种 coins</h2><span class="muted">GET /api/v1/coins</span></div>
      <div class="form-grid form-grid-tight">
        <div class="field">
          <label for="coins-channel">channel</label>
          <select id="coins-channel" v-model="coinsForm.channel">
            <option value="">全部</option>
            <option v-for="c in COIN_CHANNELS" :key="c" :value="c">{{ c }}</option>
          </select>
        </div>
        <div class="field">
          <label for="coins-q">q（币种模糊匹配）</label>
          <input id="coins-q" v-model="coinsForm.q" type="text" placeholder="BTC" autocomplete="off">
        </div>
        <div class="field">
          <label for="coins-limit">每页条数</label>
          <select id="coins-limit" :value="String(coinsForm.limit)" @change="onCoinsLimitChange">
            <option v-for="n in LIMIT_OPTIONS" :key="n" :value="String(n)">{{ n }}</option>
          </select>
        </div>
      </div>
      <div class="form-actions">
        <div class="quick-group"></div>
        <div class="btn-group">
          <button class="btn btn-primary" type="submit" :disabled="coinsState.busy" :aria-busy="coinsState.busy ? 'true' : 'false'">查询</button>
        </div>
      </div>
    </form>

    <div v-if="coinsState.error" class="alert danger">{{ coinsState.error }}</div>

    <div class="card">
      <div class="card-head"><h2>币种列表</h2><span class="muted">{{ coinsState.summary }}</span></div>
      <StateBlock v-if="coinsState.phase === 'loading'" kind="loading" text="加载中…" />
      <StateBlock v-else-if="coinsState.phase === 'error'" kind="error" :text="'加载失败：' + coinsState.fail" />
      <StateBlock v-else-if="coinsState.phase === 'idle'" kind="empty" text="尚未查询" />
      <template v-else>
        <div class="table-wrap">
          <DataTable :cols="COIN_COLS" :rows="coinsState.rows" empty-text="暂无数据" />
        </div>
      </template>
      <AppPager :offset="coinsState.offset" :limit="coinsForm.limit" :total="coinsTotal" @change="onCoinsPage" />
    </div>

    <!-- ============ 品种 ============ -->
    <form class="card" novalidate @submit.prevent="onInstSubmit">
      <div class="card-head"><h2>品种 instruments</h2><span class="muted">GET /api/v1/instruments</span></div>
      <div class="form-grid form-grid-tight">
        <div class="field">
          <label for="inst-type">instType</label>
          <select id="inst-type" v-model="instForm.instType">
            <option value="">全部</option>
            <option v-for="o in INST_TYPE_OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</option>
          </select>
        </div>
        <div class="field">
          <label for="inst-base">baseCcy</label>
          <input id="inst-base" v-model="instForm.baseCcy" type="text" placeholder="BTC" autocomplete="off">
        </div>
        <div class="field">
          <label for="inst-state">state</label>
          <select id="inst-state" v-model="instForm.state">
            <option value="">全部</option>
            <option v-for="s in INST_STATE_OPTIONS" :key="s" :value="s">{{ s }}</option>
          </select>
        </div>
        <div class="field">
          <label for="inst-limit">每页条数</label>
          <select id="inst-limit" :value="String(instForm.limit)" @change="onInstLimitChange">
            <option v-for="n in LIMIT_OPTIONS" :key="n" :value="String(n)">{{ n }}</option>
          </select>
        </div>
      </div>
      <div class="form-actions">
        <div class="quick-group"></div>
        <div class="btn-group">
          <button class="btn btn-primary" type="submit" :disabled="instState.busy" :aria-busy="instState.busy ? 'true' : 'false'">查询</button>
        </div>
      </div>
    </form>

    <div v-if="instState.error" class="alert danger">{{ instState.error }}</div>

    <div class="card">
      <div class="card-head"><h2>品种列表</h2><span class="muted">{{ instState.summary }}</span></div>
      <StateBlock v-if="instState.phase === 'loading'" kind="loading" text="加载中…" />
      <StateBlock v-else-if="instState.phase === 'error'" kind="error" :text="'加载失败：' + instState.fail" />
      <StateBlock v-else-if="instState.phase === 'idle'" kind="empty" text="尚未查询" />
      <template v-else>
        <div class="table-wrap">
          <DataTable :cols="INST_COLS" :rows="instState.rows" empty-text="暂无数据" />
        </div>
      </template>
      <AppPager :offset="instState.offset" :limit="instForm.limit" :total="instTotal" @change="onInstPage" />
    </div>
  </section>
</template>