<script setup lang="ts">
/* 同步页（legacy data-page="sync" 1:1 移植）：两个同步阻塞调用 + 二次确认 + 等待框 + 结果框 */
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { apiGet, apiPost } from '../api/client'
import { EP } from '../api/endpoints'
import { fmtInt, fmtTime } from '../lib/format'
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

/* ---- 卡片 3：定时增量补数据（GET /api/v1/sync/incremental + POST .../run） ---- */
interface IncrementalCursor {
  key?: string
  lastTsMs?: number
}

interface IncrementalStatus {
  enabled?: boolean
  intervalMs?: number
  lookbackMs?: number
  lagMs?: number
  bars?: string[]
  klineTypes?: string[]
  maxInstIds?: number
  lastRunAtMs?: number | null
  lastNote?: string
  lastJobId?: number | null
  cursorCount?: number
  cursors?: IncrementalCursor[]
  runningKlineJobs?: number
  /* 已提交、等作业 DONE 后才推进水位的登记数 */
  pendingJobs?: number
}

interface IncrementalJob {
  jobId?: number
  taskTotal?: number
  bar?: string
  klineType?: string
}

interface IncrementalRunResult {
  submitted?: boolean
  skippedReason?: string | null
  jobId?: number | null
  jobs?: IncrementalJob[]
  cursorAdvanced?: number
  seeded?: number
  note?: string
}

/* 后端 skippedReason 的中文说明（未列举的码原样回显） */
const SKIP_LABELS: Record<string, string> = {
  disabled: '增量补数据已关闭（okx.sync.incremental.enabled=false）',
  running: '上一轮增量补数据还在执行',
  'kline-job-running': '已有 K 线补拉作业在跑，本轮跳过',
  'no-instruments': '库内没有 live SWAP 品种'
}

const incrStatus = ref<IncrementalStatus | null>(null)
const incrStatusError = ref('')
const busyIncr = ref(false)
const incr = useSyncSlot()

function intervalText(ms: unknown): string {
  const n = Number(ms)
  if (!isFinite(n) || n <= 0) return '-'
  if (n < 60_000) return Math.round(n / 1000) + ' 秒'
  const minutes = n / 60_000
  return (minutes % 1 === 0 ? String(minutes) : minutes.toFixed(1)) + ' 分钟'
}

function runResultText(data: IncrementalRunResult | null | undefined): string {
  if (!data) return '增量补数据完成'
  if (data.submitted) {
    const count = Array.isArray(data.jobs) ? data.jobs.length : 0
    /* 提交只是入队：水位要等作业 DONE 后由下一轮对账推进 */
    const reconcileTail = data.cursorAdvanced ? '，本轮对账推进 ' + fmtInt(data.cursorAdvanced) + ' 个游标' : ''
    return '已提交 ' + count + ' 个增量作业（完成后自动推进水位）' + reconcileTail
  }
  const reason = data.skippedReason || ''
  if (!reason && data.seeded) {
    return '已播种 ' + fmtInt(data.seeded) + ' 个游标（本轮无缺口，下一轮开始补）'
  }
  if (!reason && data.cursorAdvanced) {
    return '本轮未提交作业，对账推进 ' + fmtInt(data.cursorAdvanced) + ' 个游标'
  }
  const label = SKIP_LABELS[reason] || (reason ? '本轮跳过（' + reason + '）' : '本轮跳过')
  return '未提交作业：' + label
}

/* silent=true 时不弹错误 toast（首次进入 / 手动触发后的刷新） */
async function loadIncremental(silent: boolean): Promise<void> {
  try {
    const data = await apiGet<IncrementalStatus | null>(EP.syncIncremental)
    incrStatus.value = data && typeof data === 'object' ? data : null
    incrStatusError.value = ''
  } catch (err: unknown) {
    incrStatusError.value = messageOf(err)
    if (!silent) ui.toast('加载增量状态失败：' + messageOf(err), 'err')
  }
}

async function runIncremental(): Promise<void> {
  if (busyIncr.value) return
  busyIncr.value = true
  incr.begin('增量补数据')
  try {
    const data = await apiPost<IncrementalRunResult>(EP.syncIncrementalRun, null)
    incr.done('增量补数据', data)
    ui.toast(runResultText(data), data && data.submitted ? 'ok' : 'info')
    ui.touchRefresh()
    void loadIncremental(true)
  } catch (err: unknown) {
    const msg = messageOf(err)
    incr.fail(msg)
    ui.toast('增量补数据失败：' + msg, 'err')
  } finally {
    busyIncr.value = false
  }
}

onMounted(() => {
  void loadIncremental(true)
})

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

    <div class="card" id="card-sync-incremental">
      <div class="card-head">
        <h2>定时增量补数据</h2>
        <span class="muted">GET /api/v1/sync/incremental · POST /api/v1/sync/incremental/run</span>
      </div>
      <div v-if="incrStatus" class="kv-grid" id="sync-incremental-status">
        <div class="kv"><span class="kv-key">开关</span><span class="kv-val">{{ incrStatus.enabled ? '已启用' : '已关闭' }}</span></div>
        <div class="kv"><span class="kv-key">间隔</span><span class="kv-val">{{ intervalText(incrStatus.intervalMs) }}</span></div>
        <div class="kv"><span class="kv-key">最近运行</span><span class="kv-val">{{ fmtTime(incrStatus.lastRunAtMs) }}</span></div>
        <div class="kv"><span class="kv-key">游标数</span><span class="kv-val">{{ fmtInt(incrStatus.cursorCount) }}</span></div>
        <div class="kv"><span class="kv-key">RUNNING 作业数</span><span class="kv-val">{{ fmtInt(incrStatus.runningKlineJobs) }}</span></div>
        <div class="kv"><span class="kv-key">待推进作业数</span><span class="kv-val" id="sync-incremental-pending">{{ fmtInt(incrStatus.pendingJobs) }}</span></div>
      </div>
      <p v-else class="hint" id="sync-incremental-empty">
        {{ incrStatusError ? '加载增量状态失败：' + incrStatusError : '加载增量状态…' }}
      </p>
      <p v-if="incrStatus && incrStatus.lastNote" class="hint" id="sync-incremental-note">最近结果：{{ incrStatus.lastNote }}</p>
      <p class="hint">
        按 okx_sync_cursor 里 kline|klineType|instId|bar 的水位，把 K 线补到 now - lag；已有 K 线补拉作业在跑时本轮跳过。
        水位只在作业跑成功后推进（失败/取消会按旧水位重提，不会静默丢数据）。
        开关 / 间隔 / 回看窗口在 application.yml 的 okx.sync.incremental 下配置。
      </p>
      <div class="form-actions">
        <div class="quick-group"><span class="hint">手动触发与定时任务共用同一套防重入与跳过规则。</span></div>
        <div class="btn-group">
          <button class="btn btn-primary" type="button" id="sync-incremental-run" :disabled="busyIncr" @click="runIncremental">
            {{ busyIncr ? '补数据中…' : '立即补增量' }}
          </button>
        </div>
      </div>
      <div class="result-slot" id="sync-incremental-result">
        <div v-if="incr.state.kind === 'wait'" class="result-box">
          <span class="spinner"></span> {{ incr.state.title }}正在执行，已耗时 <strong>{{ incr.state.seconds }} 秒</strong>
        </div>
        <div v-else-if="incr.state.kind === 'ok' || incr.state.kind === 'err'" v-html="incr.state.html"></div>
      </div>
    </div>
  </section>
</template>
