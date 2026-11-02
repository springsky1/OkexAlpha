<script setup lang="ts">
/* 同步页（legacy data-page="sync" 1:1 移植）：两个同步阻塞调用 + 二次确认 + 等待框 + 结果框 */
import { onBeforeUnmount, reactive, ref } from 'vue'
import { apiPost } from '../api/client'
import { EP } from '../api/endpoints'
import { INST_TYPES } from '../lib/labels'
import { useUiStore } from '../stores/ui'
import {
  BASIC_DEFAULTS,
  DEFAULT_BASIC_INST_TYPE,
  SYNC_INST_TYPES,
  SYNC_KLINE_TYPES,
  SYNC_MAIN_INST_TYPES,
  SYNC_RATIO_TYPES,
  basicConfirmHtml,
  buildBasicBody,
  buildInstrumentBody,
  errorBoxHtml,
  instrumentConfirmHtml,
  renderSyncResultHtml
} from './sync/sync'

type SlotKind = 'idle' | 'wait' | 'ok' | 'err'

const ui = useUiStore()

function messageOf(err: unknown): string {
  const e = err as { message?: string } | null | undefined
  return e && e.message ? e.message : String(err)
}

/* 结果槽：waiting 时由 1 秒定时器刷新已耗时（legacy syncWaitBox 等价） */
function useSyncSlot() {
  const state = reactive({ kind: 'idle' as SlotKind, html: '', msg: '', seconds: 0, title: '' })
  let timer: ReturnType<typeof setInterval> | null = null
  let startAt = 0

  function stopTimer(): void {
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }

  function begin(title: string): void {
    stopTimer()
    state.kind = 'wait'
    state.title = title
    state.html = ''
    state.msg = ''
    state.seconds = 0
    startAt = Date.now()
    timer = setInterval(() => {
      state.seconds = Math.round((Date.now() - startAt) / 1000)
    }, 1000)
  }

  function done(title: string, data: unknown): void {
    stopTimer()
    state.kind = 'ok'
    state.html = renderSyncResultHtml(data, title, Date.now() - startAt)
  }

  function fail(message: string): void {
    stopTimer()
    state.kind = 'err'
    state.msg = '同步失败：' + message
    state.html = errorBoxHtml(state.msg)
  }

  onBeforeUnmount(stopTimer)
  return { state, begin, done, fail }
}

const inst = useSyncSlot()
const basic = useSyncSlot()

const busyInst = ref(false)
const busyBasic = ref(false)

/* ---- 表单 1：同步品种 ---- */
const instTypes = ref<string[]>([...SYNC_INST_TYPES])
const includeOptions = ref(true)
const families = ref('')

/* ---- 表单 2：组合同步 ---- */
const basicInstTypes = ref<string[]>(['SWAP'])
const basicIncludeOptions = ref(false)
const basicFamilies = ref('')
const basicInstType = ref(DEFAULT_BASIC_INST_TYPE)
const basicMaxInstruments = ref(BASIC_DEFAULTS.maxInstruments)
const basicMaxCcys = ref(BASIC_DEFAULTS.maxCcys)
const basicDays = ref(BASIC_DEFAULTS.days)
const basicBars = ref(BASIC_DEFAULTS.bars)
const basicKlineTypes = ref<string[]>(['CANDLE'])
const basicIncludeFunding = ref(true)
const basicIncludeRatios = ref(true)
const basicRatioTypes = ref<string[]>(['LSR_ACCOUNT_CCY', 'TOP_TRADER_POSITION'])

function currentInstrumentInput() {
  return { instTypes: instTypes.value, includeOptions: includeOptions.value, families: families.value }
}

function currentBasicInput() {
  return {
    instTypes: basicInstTypes.value,
    includeOptions: basicIncludeOptions.value,
    families: basicFamilies.value,
    instType: basicInstType.value,
    maxInstruments: basicMaxInstruments.value,
    maxCcys: basicMaxCcys.value,
    days: basicDays.value,
    bars: basicBars.value,
    klineTypes: basicKlineTypes.value,
    includeFunding: basicIncludeFunding.value,
    includeRatios: basicIncludeRatios.value,
    ratioTypes: basicRatioTypes.value
  }
}

async function submitInstruments(): Promise<void> {
  if (busyInst.value) return
  const ok = await ui.confirmDialog('确认全量同步品种', instrumentConfirmHtml(currentInstrumentInput()), '确认开始')
  if (!ok) return
  busyInst.value = true
  inst.begin('品种同步')
  try {
    const data = await apiPost<unknown>(EP.syncInstruments, buildInstrumentBody(currentInstrumentInput()))
    inst.done('品种同步', data)
    ui.toast('品种同步完成', 'ok')
    ui.touchRefresh()
  } catch (err: unknown) {
    inst.fail(messageOf(err))
    ui.toast('品种同步失败：' + messageOf(err), 'err')
  } finally {
    busyInst.value = false
  }
}

async function submitBasic(): Promise<void> {
  if (busyBasic.value) return
  const input = currentBasicInput()
  const outcome = buildBasicBody(input)
  if (!outcome.ok) {
    ui.toast(outcome.toast, 'err')
    return
  }
  const ok = await ui.confirmDialog('确认组合同步', basicConfirmHtml(input), '确认开始')
  if (!ok) return
  busyBasic.value = true
  basic.begin('组合同步')
  try {
    const data = await apiPost<unknown>(EP.syncBasic, outcome.body)
    basic.done('组合同步', data)
    ui.toast('组合同步完成', 'ok')
    ui.touchRefresh()
  } catch (err: unknown) {
    basic.fail(messageOf(err))
    ui.toast('组合同步失败：' + messageOf(err), 'err')
  } finally {
    busyBasic.value = false
  }
}
</script>

<template>
  <section class="page" data-page="sync">
    <div class="page-head">
      <div>
        <h1>同步</h1>
        <p class="muted">从 OKX 上游拉取品种并重建币种表，或执行组合同步（品种 + 首批补拉作业）。均为同步阻塞调用，可能需要数分钟。</p>
      </div>
    </div>

    <form class="card" id="form-sync-instruments" novalidate @submit.prevent="submitInstruments">
      <div class="card-head"><h2>同步品种</h2><span class="muted">POST /api/v1/sync/instruments</span></div>
      <div class="check-group">
        <span class="check-group-title">instTypes</span>
        <div class="check-grid" data-group="sync-inst-types">
          <label v-for="t in SYNC_INST_TYPES" :key="t" class="check">
            <input v-model="instTypes" type="checkbox" :value="t"><span>{{ t }}</span>
          </label>
        </div>
        <p class="hint">全不选 = 后端默认同步全部 5 类。</p>
      </div>
      <div class="form-grid">
        <div class="field">
          <label class="check inline"><input v-model="includeOptions" type="checkbox"><span>includeOptions（展开期权）</span></label>
          <p class="hint">期权按家族逐个拉取，耗时最久</p>
        </div>
        <div class="field">
          <label for="sync-families">families（可选）</label>
          <input id="sync-families" v-model="families" type="text" placeholder="BTC-USD, ETH-USD" autocomplete="off">
          <p class="hint">逗号分隔；留空则自动推断</p>
        </div>
      </div>
      <div class="form-actions">
        <div class="quick-group"><span class="hint danger-text">提交前会二次确认：全量同步可能跑几分钟。</span></div>
        <div class="btn-group">
          <button class="btn btn-primary" type="submit" id="sync-instruments-submit" :disabled="busyInst">开始同步</button>
        </div>
      </div>
      <div class="result-slot" id="sync-instruments-result">
        <div v-if="inst.state.kind === 'wait'" class="result-box">
          <span class="spinner"></span> {{ inst.state.title }}正在执行，已耗时 <strong>{{ inst.state.seconds }} 秒</strong>（阻塞调用，请勿关闭页面）
        </div>
        <div v-else-if="inst.state.kind === 'ok' || inst.state.kind === 'err'" v-html="inst.state.html"></div>
      </div>
    </form>

    <form class="card" id="form-sync-basic" novalidate @submit.prevent="submitBasic">
      <div class="card-head"><h2>组合同步（basic）</h2><span class="muted">POST /api/v1/sync/basic</span></div>
      <div class="check-group">
        <span class="check-group-title">instTypes（透传给品种同步）</span>
        <div class="check-grid" data-group="sync-basic-inst-types">
          <label v-for="t in SYNC_MAIN_INST_TYPES" :key="t" class="check">
            <input v-model="basicInstTypes" type="checkbox" :value="t"><span>{{ t }}</span>
          </label>
        </div>
      </div>
      <div class="form-grid">
        <div class="field">
          <label class="check inline"><input v-model="basicIncludeOptions" type="checkbox"><span>includeOptions</span></label>
        </div>
        <div class="field">
          <label for="sync-basic-families">families</label>
          <input id="sync-basic-families" v-model="basicFamilies" type="text" placeholder="BTC-USD" autocomplete="off">
        </div>
        <div class="field">
          <label for="sync-basic-inst-type">instType（补拉标的类型）</label>
          <select id="sync-basic-inst-type" v-model="basicInstType">
            <option v-for="it in INST_TYPES" :key="it[0]" :value="it[0]">{{ it[1] }}</option>
          </select>
        </div>
        <div class="field">
          <label for="sync-basic-max-instruments">maxInstruments</label>
          <input id="sync-basic-max-instruments" v-model="basicMaxInstruments" type="number" min="1" max="500" step="1">
          <p class="hint">夹到 [1, 500]</p>
        </div>
        <div class="field">
          <label for="sync-basic-max-ccys">maxCcys</label>
          <input id="sync-basic-max-ccys" v-model="basicMaxCcys" type="number" min="1" max="100" step="1">
          <p class="hint">夹到 [1, 100]</p>
        </div>
        <div class="field">
          <label for="sync-basic-days">days</label>
          <input id="sync-basic-days" v-model="basicDays" type="number" min="1" max="365" step="1">
          <p class="hint">补拉区间 [now-days, now]</p>
        </div>
        <div class="field">
          <label for="sync-basic-bars">bars</label>
          <input id="sync-basic-bars" v-model="basicBars" type="text" placeholder="1H, 1D" autocomplete="off">
          <p class="hint">逗号分隔；留空 = 1H</p>
        </div>
        <div class="field">
          <label class="check inline"><input v-model="basicIncludeFunding" type="checkbox"><span>includeFunding</span></label>
        </div>
        <div class="field">
          <label class="check inline"><input v-model="basicIncludeRatios" type="checkbox"><span>includeRatios</span></label>
        </div>
      </div>
      <div class="check-group">
        <span class="check-group-title">klineTypes</span>
        <div class="check-grid" data-group="sync-basic-kline-types">
          <label v-for="t in SYNC_KLINE_TYPES" :key="t" class="check">
            <input v-model="basicKlineTypes" type="checkbox" :value="t"><span>{{ t }}</span>
          </label>
        </div>
      </div>
      <div class="check-group">
        <span class="check-group-title">ratioTypes</span>
        <div class="check-grid" data-group="sync-basic-ratio-types">
          <label v-for="t in SYNC_RATIO_TYPES" :key="t" class="check">
            <input v-model="basicRatioTypes" type="checkbox" :value="t"><span>{{ t }}</span>
          </label>
        </div>
      </div>
      <div class="form-actions">
        <div class="quick-group"><span class="hint danger-text">提交前会二次确认：会先同步品种，再新建补拉作业。</span></div>
        <div class="btn-group">
          <button class="btn btn-primary" type="submit" id="sync-basic-submit" :disabled="busyBasic">开始组合同步</button>
        </div>
      </div>
      <div class="result-slot" id="sync-basic-result">
        <div v-if="basic.state.kind === 'wait'" class="result-box">
          <span class="spinner"></span> {{ basic.state.title }}正在执行，已耗时 <strong>{{ basic.state.seconds }} 秒</strong>（阻塞调用，请勿关闭页面）
        </div>
        <div v-else-if="basic.state.kind === 'ok' || basic.state.kind === 'err'" v-html="basic.state.html"></div>
      </div>
    </form>
  </section>
</template>
