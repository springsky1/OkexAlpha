<script setup lang="ts">
/* 补拉页（legacy data-page="backfill" 1:1 移植）：四个表单提交后创建作业，结果槽显示 jobId/taskTotal */
import { reactive } from 'vue'
import { apiPost } from '../api/client'
import { EP } from '../api/endpoints'
import { BARS } from '../lib/labels'
import { useUiStore } from '../stores/ui'
import InstPicker from '../components/InstPicker.vue'
import ResultSlot from './backfill/ResultSlot.vue'
import {
  STATS_KIND_OPTIONS,
  buildFundingBody,
  buildKlineBody,
  buildRatioBody,
  buildStatsBody,
  clearedRange,
  quickRange,
  type SubmitOutcome
} from './backfill/forms'

interface ResState {
  kind: 'idle' | 'loading' | 'ok' | 'err'
  title: string
  jobId: string | number
  taskTotal: string | number
  msg: string
}

type FormKey = 'klines' | 'funding' | 'ratios' | 'stats'
type RangeState = { start: string; end: string }

function blankResult(): ResState {
  return { kind: 'idle', title: '', jobId: '', taskTotal: '', msg: '' }
}

const ui = useUiStore()

const kline = reactive({
  mode: 'ids',
  instIds: 'BTC-USDT-SWAP',
  instType: 'SWAP',
  baseCcy: '',
  bars: ['1H'] as string[],
  klineTypes: ['CANDLE'] as string[],
  start: '',
  end: '',
  pageLimit: ''
})

const funding = reactive({
  mode: 'ids',
  instIds: 'BTC-USDT-SWAP',
  instType: 'SWAP',
  start: '',
  end: ''
})

const ratio = reactive({
  ratioTypes: ['LSR_ACCOUNT_CCY'] as string[],
  ccys: 'BTC',
  scopes: 'BTC-USDT-SWAP',
  periods: ['1H'] as string[],
  start: '',
  end: ''
})

const stats = reactive({
  kinds: ['OPEN_INTEREST_VOLUME'] as string[],
  ccys: 'BTC',
  instIds: 'BTC-USDT-SWAP',
  periods: ['1H'] as string[],
  start: '',
  end: ''
})

const res = reactive<Record<FormKey, ResState>>({
  klines: blankResult(),
  funding: blankResult(),
  ratios: blankResult(),
  stats: blankResult()
})

const busy = reactive<Record<FormKey, boolean>>({ klines: false, funding: false, ratios: false, stats: false })

/* 快捷时间：近 N 天 / 清空（legacy setQuickRange / clearQuickRange） */
function applyQuick(st: RangeState, days: number | null) {
  if (days === null) {
    const c = clearedRange()
    st.start = c.start
    st.end = c.end
    return
  }
  const q = quickRange(days, Date.now())
  st.start = q.start
  st.end = q.end
  ui.toast(q.toast, 'info')
}

/* 提交公共流程：校验（toast + 可选错误盒）-> busy -> POST -> 结果盒 / 失败盒 */
async function submit(key: FormKey, outcome: SubmitOutcome, path: string, title: string) {
  if (!outcome.ok) {
    ui.toast(outcome.toast, 'err')
    if (outcome.box !== undefined) {
      res[key] = { ...blankResult(), kind: 'err', msg: outcome.box }
    }
    return
  }
  busy[key] = true
  res[key] = { ...blankResult(), kind: 'loading' }
  try {
    const data = await apiPost<any>(path, outcome.body)
    res[key] = {
      kind: 'ok',
      title,
      jobId: data && data.jobId ? data.jobId : '(未知)',
      taskTotal: data && data.taskTotal !== undefined ? data.taskTotal : '(未知)',
      msg: ''
    }
    ui.toast('已创建作业 jobId=' + (data && data.jobId) + '，任务数 ' + (data && data.taskTotal), 'ok')
    ui.touchRefresh()
  } catch (err: any) {
    const msg = err && err.message ? err.message : String(err)
    res[key] = { ...blankResult(), kind: 'err', msg: '提交失败：' + msg }
    ui.toast(title + '提交失败：' + msg, 'err')
  } finally {
    busy[key] = false
  }
}

function submitKlines() {
  void submit(
    'klines',
    buildKlineBody({
      mode: kline.mode,
      instIds: kline.instIds,
      instType: kline.instType,
      baseCcy: kline.baseCcy,
      bars: kline.bars,
      klineTypes: kline.klineTypes,
      start: kline.start,
      end: kline.end,
      pageLimit: kline.pageLimit
    }),
    EP.backfillKlines,
    'K 线补拉'
  )
}

function submitFunding() {
  void submit(
    'funding',
    buildFundingBody({
      mode: funding.mode,
      instIds: funding.instIds,
      instType: funding.instType,
      start: funding.start,
      end: funding.end
    }),
    EP.backfillFundingRates,
    '资金费率补拉'
  )
}

function submitRatios() {
  void submit(
    'ratios',
    buildRatioBody({
      ratioTypes: ratio.ratioTypes,
      ccys: ratio.ccys,
      scopes: ratio.scopes,
      periods: ratio.periods,
      start: ratio.start,
      end: ratio.end
    }),
    EP.backfillRatios,
    '多空比补拉'
  )
}

function submitStats() {
  void submit(
    'stats',
    buildStatsBody({
      kinds: stats.kinds,
      ccys: stats.ccys,
      instIds: stats.instIds,
      periods: stats.periods,
      start: stats.start,
      end: stats.end
    }),
    EP.backfillMarketStats,
    '行情统计补拉'
  )
}
</script>

<template>
  <section class="page" data-page="backfill">
    <div class="page-head">
      <div>
        <h1>补拉（Backfill）</h1>
        <p class="muted">
          提交补拉作业后立即返回 <code>{jobId, taskTotal}</code>（HTTP 202），作业由后端异步执行；可在「作业列表」里查看进度。
        </p>
      </div>
    </div>

    <!-- 5.1 K 线 -->
    <form class="card" novalidate @submit.prevent="submitKlines">
      <div class="card-head"><h2>K 线补拉</h2><span class="muted">POST /api/v1/backfill/klines</span></div>
      <div class="mode-switch">
        <label class="radio"><input v-model="kline.mode" type="radio" name="bf-kline-mode" value="ids"><span>按 instIds</span></label>
        <label class="radio"><input v-model="kline.mode" type="radio" name="bf-kline-mode" value="type"><span>按 instType 展开</span></label>
      </div>
      <div class="form-grid">
        <div class="field mode-block" :class="{ 'is-off': kline.mode !== 'ids' }">
          <label>instIds</label>
          <InstPicker v-model="kline.instIds" multiple :inst-types="['SWAP']" placeholder="BTC-USDT-SWAP, ETH-USDT-SWAP" />
          <p class="hint">逗号 / 空格 / 换行分隔</p>
        </div>
        <div class="field mode-block" :class="{ 'is-off': kline.mode !== 'type' }">
          <label>instType</label>
          <select v-model="kline.instType"><option value="SWAP">SWAP（永续）</option></select>
          <p class="hint">K 线只支持 SWAP（永续）</p>
        </div>
        <div class="field mode-block" :class="{ 'is-off': kline.mode !== 'type' }">
          <label>baseCcy</label>
          <input v-model="kline.baseCcy" type="text" placeholder="可留空，如 BTC" autocomplete="off">
        </div>
        <div class="field">
          <label>startMs（开始时间）</label>
          <input v-model="kline.start" type="datetime-local">
        </div>
        <div class="field">
          <label>endMs（结束时间）</label>
          <input v-model="kline.end" type="datetime-local">
        </div>
        <div class="field">
          <label>pageLimit</label>
          <input v-model="kline.pageLimit" type="number" min="1" max="300" step="1" placeholder="300（默认）">
          <p class="hint">每页条数，≤ 300</p>
        </div>
      </div>
      <div class="check-group">
        <span class="check-group-title">bars <span class="req">*</span></span>
        <div class="check-grid">
          <label v-for="b in BARS" :key="b[0]" class="check">
            <input v-model="kline.bars" type="checkbox" :value="b[0]"><span>{{ b[0] }}</span>
          </label>
        </div>
      </div>
      <div class="check-group">
        <span class="check-group-title">klineTypes <span class="req">*</span></span>
        <div class="check-grid">
          <label class="check"><input v-model="kline.klineTypes" type="checkbox" value="CANDLE"><span>CANDLE</span></label>
          <label class="check"><input v-model="kline.klineTypes" type="checkbox" value="MARK"><span>MARK</span></label>
        </div>
      </div>
      <div class="form-actions">
        <div class="quick-group">
          <span class="hint">快捷时间：</span>
          <button class="chip" type="button" @click="applyQuick(kline, 1)">近 1 天</button>
          <button class="chip" type="button" @click="applyQuick(kline, 7)">近 7 天</button>
          <button class="chip" type="button" @click="applyQuick(kline, 30)">近 30 天</button>
          <button class="chip" type="button" @click="applyQuick(kline, null)">清空时间</button>
        </div>
        <div class="btn-group">
          <button class="btn btn-primary" type="submit" :disabled="busy.klines" :aria-busy="busy.klines ? 'true' : undefined">提交补拉</button>
        </div>
      </div>
      <div class="result-slot"><ResultSlot v-bind="res.klines" /></div>
    </form>

    <!-- 5.2 资金费率 -->
    <form class="card" novalidate @submit.prevent="submitFunding">
      <div class="card-head"><h2>资金费率补拉</h2><span class="muted">POST /api/v1/backfill/funding-rates</span></div>
      <div class="mode-switch">
        <label class="radio"><input v-model="funding.mode" type="radio" name="bf-funding-mode" value="ids"><span>按 instIds</span></label>
        <label class="radio"><input v-model="funding.mode" type="radio" name="bf-funding-mode" value="type"><span>按 instType 展开</span></label>
      </div>
      <div class="form-grid">
        <div class="field mode-block" :class="{ 'is-off': funding.mode !== 'ids' }">
          <label>instIds</label>
          <InstPicker v-model="funding.instIds" multiple :inst-types="['SWAP', 'FUTURES']" placeholder="BTC-USDT-SWAP, ETH-USDT-SWAP" />
          <p class="hint">逗号 / 空格 / 换行分隔</p>
        </div>
        <div class="field mode-block" :class="{ 'is-off': funding.mode !== 'type' }">
          <label>instType</label>
          <select v-model="funding.instType">
            <option value="SWAP">SWAP（永续）</option>
            <option value="FUTURES">FUTURES（交割）</option>
          </select>
        </div>
        <div class="field">
          <label>startMs（开始时间）</label>
          <input v-model="funding.start" type="datetime-local">
        </div>
        <div class="field">
          <label>endMs（结束时间）</label>
          <input v-model="funding.end" type="datetime-local">
        </div>
      </div>
      <div class="form-actions">
        <div class="quick-group">
          <span class="hint">快捷时间：</span>
          <button class="chip" type="button" @click="applyQuick(funding, 7)">近 7 天</button>
          <button class="chip" type="button" @click="applyQuick(funding, 30)">近 30 天</button>
          <button class="chip" type="button" @click="applyQuick(funding, 90)">近 90 天</button>
          <button class="chip" type="button" @click="applyQuick(funding, null)">清空时间</button>
        </div>
        <div class="btn-group">
          <button class="btn btn-primary" type="submit" :disabled="busy.funding" :aria-busy="busy.funding ? 'true' : undefined">提交补拉</button>
        </div>
      </div>
      <div class="result-slot"><ResultSlot v-bind="res.funding" /></div>
    </form>

    <!-- 5.3 多空比 -->
    <form class="card" novalidate @submit.prevent="submitRatios">
      <div class="card-head"><h2>多空比补拉</h2><span class="muted">POST /api/v1/backfill/ratios</span></div>
      <div class="check-group">
        <span class="check-group-title">ratioTypes <span class="req">*</span></span>
        <div class="check-grid">
          <label class="check"><input v-model="ratio.ratioTypes" type="checkbox" value="LSR_ACCOUNT_CCY"><span>LSR_ACCOUNT_CCY</span></label>
          <label class="check"><input v-model="ratio.ratioTypes" type="checkbox" value="LSR_ACCOUNT_CONTRACT"><span>LSR_ACCOUNT_CONTRACT</span></label>
          <label class="check"><input v-model="ratio.ratioTypes" type="checkbox" value="TOP_TRADER_ACCOUNT"><span>TOP_TRADER_ACCOUNT</span></label>
          <label class="check"><input v-model="ratio.ratioTypes" type="checkbox" value="TOP_TRADER_POSITION"><span>TOP_TRADER_POSITION</span></label>
        </div>
        <p class="hint">CCY 类型用 ccys，INST 类型用 scopes；两类都要填才能同时覆盖。</p>
      </div>
      <div class="form-grid">
        <div class="field">
          <label>ccys</label>
          <textarea v-model="ratio.ccys" rows="2" placeholder="BTC, ETH"></textarea>
          <p class="hint">CCY 类型必填</p>
        </div>
        <div class="field">
          <label>scopes</label>
          <textarea v-model="ratio.scopes" rows="2" placeholder="BTC-USDT-SWAP"></textarea>
          <p class="hint">INST 类型必填（instId）</p>
        </div>
        <div class="field">
          <label>startMs（开始时间）</label>
          <input v-model="ratio.start" type="datetime-local">
        </div>
        <div class="field">
          <label>endMs（结束时间）</label>
          <input v-model="ratio.end" type="datetime-local">
        </div>
      </div>
      <div class="check-group">
        <span class="check-group-title">periods <span class="req">*</span></span>
        <div class="check-grid">
          <label class="check"><input v-model="ratio.periods" type="checkbox" value="5m"><span>5m</span></label>
          <label class="check"><input v-model="ratio.periods" type="checkbox" value="1H"><span>1H</span></label>
          <label class="check"><input v-model="ratio.periods" type="checkbox" value="1D"><span>1D</span></label>
        </div>
      </div>
      <div class="form-actions">
        <div class="quick-group">
          <span class="hint">快捷时间：</span>
          <button class="chip" type="button" @click="applyQuick(ratio, 1)">近 1 天</button>
          <button class="chip" type="button" @click="applyQuick(ratio, 7)">近 7 天</button>
          <button class="chip" type="button" @click="applyQuick(ratio, 30)">近 30 天</button>
          <button class="chip" type="button" @click="applyQuick(ratio, null)">清空时间</button>
        </div>
        <div class="btn-group">
          <button class="btn btn-primary" type="submit" :disabled="busy.ratios" :aria-busy="busy.ratios ? 'true' : undefined">提交补拉</button>
        </div>
      </div>
      <div class="result-slot"><ResultSlot v-bind="res.ratios" /></div>
    </form>

    <!-- 5.4 市场统计 -->
    <form class="card" novalidate @submit.prevent="submitStats">
      <div class="card-head"><h2>持仓量 / 成交额 / 主动买卖补拉</h2><span class="muted">POST /api/v1/backfill/market-stats</span></div>
      <div class="check-group">
        <span class="check-group-title">kinds</span>
        <div class="check-grid">
          <label v-for="k in STATS_KIND_OPTIONS" :key="k[0]" class="check">
            <input v-model="stats.kinds" type="checkbox" :value="k[0]"><span>{{ k[1] }}</span>
          </label>
        </div>
        <p class="hint">留空 = 三种全跑。OPEN_INTEREST_VOLUME 需要 ccys；TAKER_VOLUME 的 INST 维度用 instIds；OPEN_INTEREST 留空 instIds 时取库内全部 SWAP。</p>
      </div>
      <div class="form-grid">
        <div class="field">
          <label>ccys</label>
          <textarea v-model="stats.ccys" rows="2" placeholder="BTC, ETH"></textarea>
        </div>
        <div class="field">
          <label>instIds</label>
          <InstPicker v-model="stats.instIds" multiple :inst-types="['SWAP', 'FUTURES']" placeholder="BTC-USDT-SWAP" />
        </div>
        <div class="field">
          <label>startMs（开始时间）</label>
          <input v-model="stats.start" type="datetime-local">
        </div>
        <div class="field">
          <label>endMs（结束时间）</label>
          <input v-model="stats.end" type="datetime-local">
        </div>
      </div>
      <div class="check-group">
        <span class="check-group-title">periods</span>
        <div class="check-grid">
          <label class="check"><input v-model="stats.periods" type="checkbox" value="5m"><span>5m</span></label>
          <label class="check"><input v-model="stats.periods" type="checkbox" value="1H"><span>1H</span></label>
          <label class="check"><input v-model="stats.periods" type="checkbox" value="1D"><span>1D</span></label>
        </div>
      </div>
      <div class="form-actions">
        <div class="quick-group">
          <span class="hint">快捷时间：</span>
          <button class="chip" type="button" @click="applyQuick(stats, 1)">近 1 天</button>
          <button class="chip" type="button" @click="applyQuick(stats, 7)">近 7 天</button>
          <button class="chip" type="button" @click="applyQuick(stats, null)">清空时间</button>
        </div>
        <div class="btn-group">
          <button class="btn btn-primary" type="submit" :disabled="busy.stats" :aria-busy="busy.stats ? 'true' : undefined">提交补拉</button>
        </div>
      </div>
      <div class="result-slot"><ResultSlot v-bind="res.stats" /></div>
    </form>
  </section>
</template>