<script setup lang="ts">
/* 策略回测页：legacy index.html data-page="strategy-backtest" + app.js 第 14b 区（约 3400~4340）1:1 迁移。
   四块：提交区 / 批次列表 / 批次详情 + 结果排行 / 冠军明细（资金曲线 + 逐笔）。
   状态全在 btSession 的单例里，组件只做渲染与事件转发，切路由不会重复挂监听或定时器。 */
import { computed, nextTick, onMounted, reactive, watch } from 'vue'
import { usePolling } from '../composables/usePolling'
import { BT_POLL_MS, btHasActiveRun, btQuickRange, btRunsSummary, btSampleForm, type BtSubmitFormState } from './strategy-backtest/btLogic'
import { btPageSession, newBtForm } from './strategy-backtest/btSession'
import BtSubmitForm from './strategy-backtest/BtSubmitForm.vue'
import BtRunsTable from './strategy-backtest/BtRunsTable.vue'
import BtRunDetail from './strategy-backtest/BtRunDetail.vue'
import BtResultsTable from './strategy-backtest/BtResultsTable.vue'
import BtChampion from './strategy-backtest/BtChampion.vue'

const session = btPageSession()
const { state, active, champAgg, champCoin, champCoins } = session

/* 表单不与 state 混放：提交成功后保留用户填的值（legacy 行为） */
const form = reactive<BtSubmitFormState>(newBtForm())

const hasActive = computed(() => btHasActiveRun(state.runs) || active.value)
const runsSummary = computed(() => btRunsSummary(state.runsTotal, state.runs.length, hasActive.value))

/* ---------- 表单动作 ---------- */
function onSubmit(): void {
  void session.submit(form)
}

function onSample(): void {
  Object.assign(form, btSampleForm(Date.now()))
}

function onQuick(days: number): void {
  Object.assign(form, btQuickRange(days, Date.now()))
}

function onClear(): void {
  form.start = ''
  form.end = ''
}

/* ---------- 列表 / 详情 ---------- */
async function openRun(id: number): Promise<void> {
  await session.openRun(id)
  await nextTick()
  const node = document.getElementById('bt-detail')
  if (node && typeof node.scrollIntoView === 'function') node.scrollIntoView({ block: 'start', behavior: 'smooth' })
}

function refreshNow(): void {
  void session.loadRuns(false)
  if (state.selectedId) void session.loadDetail(state.selectedId, false)
}

/* ---------- 轮询：列表有 PENDING/RUNNING（或当前详情仍在跑）时每 2 秒刷新 ---------- */
async function tick(): Promise<void> {
  await session.loadRuns(true)
  if (state.selectedId && active.value) await session.loadDetail(state.selectedId, true)
}

const polling = usePolling(() => tick(), BT_POLL_MS, { enabled: hasActive, immediate: false })

watch(hasActive, () => {
  polling.restart()
})

onMounted(() => {
  void session.loadRuns(false)
  if (state.selectedId && !state.detail) void session.openRun(state.selectedId)
})
</script>

<template>
  <section class="page" data-page="strategy-backtest">
    <div class="page-head">
      <div>
        <h1>策略回测</h1>
        <p class="muted">
          POST /api/v1/strategy/backtests 创建批次（202），GET /api/v1/strategy/backtests 查看进度与结果排行；
          有批次在跑时每 2 秒自动刷新。
        </p>
      </div>
      <div class="page-actions">
        <span class="muted">{{ runsSummary }}</span>
        <button class="btn btn-primary" type="button" @click="refreshNow">刷新列表</button>
      </div>
    </div>

    <BtSubmitForm :form="form" :busy="state.submitBusy" @submit="onSubmit" @sample="onSample" @quick="onQuick" @clear="onClear" />

    <div v-if="state.error" class="alert danger">{{ state.error }}</div>

    <BtRunsTable
      :runs="state.runs"
      :loading="state.runsLoading"
      :error="state.runsError"
      :selected-id="state.selectedId"
      @open="openRun"
    />

    <div v-if="state.selectedId" id="bt-detail">
      <BtRunDetail
        :run="state.detail ? state.detail.run : null"
        :result-total="state.detail ? state.detail.resultTotal : 0"
        :cancel-busy="state.cancelBusy"
        @cancel="session.cancelRun()"
      />

      <div v-if="state.detailError" class="alert danger">{{ state.detailError }}</div>

      <BtResultsTable
        v-if="state.detail"
        :rows="state.detail.results"
        :total="state.detail.resultTotal"
        :active="active"
        :expanded="state.expanded"
        :coins="state.coins"
        :champ-agg-id="state.champAggId"
        :champ-coin-id="state.champCoinId"
        @toggle="session.toggleResult"
        @pick="session.pickCoin"
      />

      <BtChampion
        v-if="state.detail && champAgg"
        :agg="champAgg"
        :coin="champCoin"
        :best-id="state.detail.best ? Number(state.detail.best.id) : 0"
        :coins="champCoins"
        :equity="state.equity"
        :equity-loading="state.equityLoading"
        :equity-error="state.equityError"
        :trades="state.trades"
        :trades-loading="state.tradesLoading"
        :trades-error="state.tradesError"
        :promote-busy="state.promoteBusy"
        @pick="session.pickCoin"
        @promote="session.promote()"
      />
    </div>
  </section>
</template>
