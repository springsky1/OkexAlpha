<script setup lang="ts">
/* K 线查询页：legacy index.html data-page="klines" + app.js 第 7 区（1367~2118）1:1 迁移 */
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { apiGet } from '../api/client'
import { EP } from '../api/endpoints'
import { BARS, KLINE_TYPES } from '../lib/labels'
import { useUiStore } from '../stores/ui'
import InstPicker from '../components/InstPicker.vue'
import DataTable from '../components/DataTable.vue'
import StateBlock from '../components/StateBlock.vue'
import { KLINE_COLS } from './klines/columns'
import { chartLineColor, chartMetaText, klineSummaryText } from './klines/chartModel'
import { anyRatioEnabled, drawChart } from './klines/chartDraw'
import { ensureRatio, loadChartSeries } from './klines/data'
import { buildKlineQuery, klineLimitSuffix, klineMeta, quickRangeValues } from './klines/form'
import type { ChartInfo, ChartOpts, KlineRow, LastQuery, LineKey, RatioCache, RatioKey } from './klines/types'

const ui = useUiStore()

/* ---------- 表单状态（默认值与 legacy index.html 的默认值一致） ---------- */
const instId = ref('BTC-USDT-SWAP')
const bar = ref('1H')
const klineType = ref('CANDLE')
const order = ref('desc')
const limitRaw = ref('500')
const startRaw = ref('')
const endRaw = ref('')

/* ---------- 查询结果 / 图表状态（对应 legacy ui.klines） ---------- */
const rows = ref<KlineRow[]>([])
const chartRows = ref<KlineRow[]>([])
const chartInfo = ref<ChartInfo | null>(null)
const chartRatio = ref<RatioCache | null>(null)
const lastQuery = ref<LastQuery | null>(null)
const busy = ref(false)
const error = ref('')
const summary = ref('尚未查询')
const chartMeta = ref('尚未查询')
const chartHint = ref(
  '勾选后叠加折线；多空比走右侧独立刻度（5m/1H/1D 由 bar 推导）；图表按所选时间范围读取整段数据'
)
const tableState = ref<'idle' | 'loading' | 'error' | 'done'>('idle')
const tableError = ref('')

const opts = reactive<ChartOpts>({
  close: true,
  ma5: false,
  ma20: false,
  ma60: false,
  ratioPos: false,
  ratioAcc: false,
  ratioLsr: false
})

const canvasEl = ref<HTMLCanvasElement | null>(null)

function redraw() {
  drawChart(
    canvasEl.value,
    { rows: chartRows.value, info: chartInfo.value, opts: opts, ratio: chartRatio.value },
    (text) => {
      chartHint.value = text
    }
  )
}

/* ---------- 表单动作 ---------- */
function quick(days: number) {
  const r = quickRangeValues(days, Date.now())
  startRaw.value = r.start
  endRaw.value = r.end
  ui.toast('已填充最近 ' + days + ' 天的时间范围', 'info')
}

function clearTimes() {
  startRaw.value = ''
  endRaw.value = ''
}

function reset() {
  instId.value = 'BTC-USDT-SWAP'
  bar.value = '1H'
  klineType.value = 'CANDLE'
  order.value = 'desc'
  limitRaw.value = '500'
  startRaw.value = ''
  endRaw.value = ''
  error.value = ''
  tableState.value = 'idle'
  tableError.value = ''
  rows.value = []
  summary.value = '尚未查询'
  chartRows.value = []
  chartInfo.value = null
  lastQuery.value = null
  chartRatio.value = null
  opts.ratioPos = false
  opts.ratioAcc = false
  opts.ratioLsr = false
  chartMeta.value = '尚未查询'
  chartHint.value = ''
  redraw()
  ui.toast('已重置 K 线查询条件', 'info')
}

async function submit() {
  error.value = ''
  const q = buildKlineQuery({
    instId: instId.value,
    bar: bar.value,
    klineType: klineType.value,
    order: order.value,
    limitRaw: limitRaw.value,
    startRaw: startRaw.value,
    endRaw: endRaw.value
  })
  if (!q.ok) {
    if (q.alert) error.value = q.alert
    ui.toast(q.toast, 'err')
    return
  }
  lastQuery.value = { instId: q.instId, bar: q.bar, startMs: q.startMs, endMs: q.endMs }

  busy.value = true
  tableState.value = 'loading'
  tableError.value = ''
  try {
    const data = await apiGet<KlineRow[]>(EP.klines, q.params)
    const list: KlineRow[] = Array.isArray(data) ? data : []
    const meta = klineMeta(q.instId, q.bar, q.klineType)
    /* 表格按 limit 取一页；返回条数等于 limit 时提示一次，避免误以为区间数据缺失 */
    const suffix = klineLimitSuffix(q.limit, list.length)
    rows.value = list
    summary.value = klineSummaryText(list, meta, suffix)
    chartMeta.value = chartMetaText(list.length, q.klineType)
    tableState.value = 'done'
    if (!list.length) ui.toast('查询成功，但没有匹配的数据', 'info')

    /* 图表按"选中时间范围"整段取数：表格只按 limit 取一页，图表不受 limit 限制 */
    if (q.startMs !== null) chartHint.value = '正在加载 ' + q.bar + ' 区间数据…'
    try {
      const series = await loadChartSeries(
        { instId: q.instId, bar: q.bar, klineType: q.klineType, startMs: q.startMs, endMs: q.endMs },
        list
      )
      chartRows.value = series.rows
      chartInfo.value = { truncated: series.truncated }
      chartRatio.value = await ensureRatio(q.instId, q.bar, q.startMs, q.endMs, {
        enabled: anyRatioEnabled(opts),
        cached: chartRatio.value,
        onLoading: () => {
          chartHint.value = '正在加载多空比数据…'
        }
      })
      redraw()
      if (series.truncated) ui.toast('区间数据超过 30000 根，图表只显示最近的部分', 'info')
    } catch (chartErr) {
      chartInfo.value = null
      redraw()
      const msg = (chartErr as { message?: string }).message || String(chartErr)
      ui.toast('图表数据加载失败：' + msg, 'err')
    }
    ui.touchRefresh()
  } catch (err) {
    const msg = (err as { message?: string }).message || String(err)
    tableState.value = 'error'
    tableError.value = '查询失败：' + msg
    error.value = msg
    ui.toast('K 线查询失败：' + msg, 'err')
  } finally {
    busy.value = false
  }
}

/* 价格折线勾选：先同步模型再重绘（legacy change 监听同一语义） */
function onOptToggle(key: LineKey, e: Event) {
  opts[key] = (e.target as HTMLInputElement).checked
  redraw()
}

/* 多空比勾选：首次勾选时按当前查询条件拉取，之后走缓存 */
async function onRatioToggle(key: RatioKey, e: Event) {
  const q = lastQuery.value
  opts[key] = (e.target as HTMLInputElement).checked
  if (!q) {
    /* 还没查询过就没有区间可拉：模型与 DOM 一起复位（legacy: this.checked = false） */
    opts[key] = false
    ;(e.target as HTMLInputElement).checked = false
    ui.toast('请先查询 K 线，再叠加多空比', 'info')
    return
  }
  try {
    chartRatio.value = await ensureRatio(q.instId, q.bar, q.startMs, q.endMs, {
      enabled: anyRatioEnabled(opts),
      cached: chartRatio.value,
      onLoading: () => {
        chartHint.value = '正在加载多空比数据…'
      }
    })
  } catch (err) {
    const msg = (err as { message?: string }).message || String(err)
    ui.toast('多空比加载失败：' + msg, 'err')
  }
  redraw()
}

/* 图例色块统一走颜色表（与 legacy .line-swatch 上色一致） */
function lineColor(key: string): string {
  return chartLineColor(key)
}

/* ---------- 画布尺寸：等价 legacy initResizeHandler 的 150ms 防抖重绘 ---------- */
let ro: ResizeObserver | null = null
let resizeTimer: ReturnType<typeof setTimeout> | null = null
let firstObserve = true

onMounted(() => {
  const wrap = canvasEl.value ? canvasEl.value.parentElement : null
  if (typeof ResizeObserver !== 'undefined' && wrap) {
    ro = new ResizeObserver(() => {
      /* 首次回调只是 observe 时的初始尺寸通知：legacy 首屏不画图，这里同样跳过 */
      if (firstObserve) {
        firstObserve = false
        return
      }
      if (resizeTimer) clearTimeout(resizeTimer)
      resizeTimer = setTimeout(() => {
        resizeTimer = null
        redraw()
      }, 150)
    })
    ro.observe(wrap)
  }
})

onBeforeUnmount(() => {
  if (ro) ro.disconnect()
  ro = null
  if (resizeTimer) clearTimeout(resizeTimer)
  resizeTimer = null
})
</script>

<template>
  <section class="page" data-page="klines">
    <div class="page-head">
      <div>
        <h1>K 线查询</h1>
        <p class="muted">查询已入库的 K 线。CANDLE 带成交量，MARK 为标记价格；只支持永续合约（<code>-SWAP</code>），指数 K 线已下线。</p>
      </div>
    </div>

    <form class="card" novalidate @submit.prevent="submit">
      <div class="form-grid">
        <div class="field">
          <label for="kline-inst-id">instId <span class="req">*</span></label>
          <InstPicker
            id="kline-inst-id"
            v-model="instId"
            :inst-types="['SWAP']"
            placeholder="点击或输入 coin 筛选，如 BTC"
          />
          <p class="hint">下拉可选，支持按 coin 或 instId 过滤；只支持永续合约 -SWAP</p>
        </div>
        <div class="field">
          <label for="kline-bar">bar <span class="req">*</span></label>
          <select id="kline-bar" v-model="bar">
            <option v-for="b in BARS" :key="b[0]" :value="b[0]">{{ b[1] }}</option>
          </select>
          <p class="hint">24 个周期（含 utc 变体）</p>
        </div>
        <div class="field">
          <label for="kline-type">klineType</label>
          <select id="kline-type" v-model="klineType">
            <option v-for="t in KLINE_TYPES" :key="t[0]" :value="t[0]">{{ t[1] }}</option>
          </select>
          <p class="hint">只有 CANDLE 带成交量</p>
        </div>
        <div class="field">
          <label for="kline-start">开始时间</label>
          <input id="kline-start" v-model="startRaw" type="datetime-local" data-role="start">
        </div>
        <div class="field">
          <label for="kline-end">结束时间</label>
          <input id="kline-end" v-model="endRaw" type="datetime-local" data-role="end">
        </div>
        <div class="field">
          <label for="kline-limit">limit</label>
          <input id="kline-limit" v-model="limitRaw" type="number" min="1" max="5000" step="1">
          <p class="hint">默认 500，上限 5000</p>
        </div>
        <div class="field">
          <label for="kline-order">order</label>
          <select id="kline-order" v-model="order">
            <option value="desc">desc（新 → 旧）</option>
            <option value="asc">asc（旧 → 新）</option>
          </select>
        </div>
      </div>
      <div class="form-actions">
        <div class="quick-group">
          <span class="hint">快捷时间：</span>
          <button class="chip" type="button" @click="quick(1)">近 1 天</button>
          <button class="chip" type="button" @click="quick(7)">近 7 天</button>
          <button class="chip" type="button" @click="quick(30)">近 30 天</button>
          <button class="chip" type="button" @click="clearTimes">清空时间</button>
        </div>
        <div class="btn-group">
          <button class="btn" type="button" @click="reset">重置</button>
          <button class="btn btn-primary" type="submit" :disabled="busy" :aria-busy="busy ? 'true' : undefined">查询</button>
        </div>
      </div>
    </form>

    <div v-if="error" class="alert danger">{{ error }}</div>

    <div class="card">
      <div class="card-head">
        <h2>蜡烛图</h2>
        <div class="legend">
          <span class="legend-item"><i class="swatch up"></i>涨</span>
          <span class="legend-item"><i class="swatch down"></i>跌</span>
          <span class="legend-item muted">{{ chartMeta }}</span>
        </div>
      </div>
      <div class="chart-toolbar">
        <label class="chart-toggle">
          <input :checked="opts.close" type="checkbox" @change="onOptToggle('close', $event)">
          <i class="line-swatch" :style="{ background: lineColor('close') }"></i>收盘价折线
        </label>
        <label class="chart-toggle">
          <input :checked="opts.ma5" type="checkbox" @change="onOptToggle('ma5', $event)">
          <i class="line-swatch" :style="{ background: lineColor('ma5') }"></i>MA5
        </label>
        <label class="chart-toggle">
          <input :checked="opts.ma20" type="checkbox" @change="onOptToggle('ma20', $event)">
          <i class="line-swatch" :style="{ background: lineColor('ma20') }"></i>MA20
        </label>
        <label class="chart-toggle">
          <input :checked="opts.ma60" type="checkbox" @change="onOptToggle('ma60', $event)">
          <i class="line-swatch" :style="{ background: lineColor('ma60') }"></i>MA60
        </label>
        <span class="chart-sep"></span>
        <label class="chart-toggle">
          <input :checked="opts.ratioPos" type="checkbox" @change="onRatioToggle('ratioPos', $event)">
          <i class="line-swatch" :style="{ background: lineColor('ratioPos') }"></i>精英持仓多空比
        </label>
        <label class="chart-toggle">
          <input :checked="opts.ratioAcc" type="checkbox" @change="onRatioToggle('ratioAcc', $event)">
          <i class="line-swatch" :style="{ background: lineColor('ratioAcc') }"></i>大户账户多空比
        </label>
        <label class="chart-toggle">
          <input :checked="opts.ratioLsr" type="checkbox" @change="onRatioToggle('ratioLsr', $event)">
          <i class="line-swatch" :style="{ background: lineColor('ratioLsr') }"></i>全账户多空比
        </label>
        <span class="hint">{{ chartHint }}</span>
      </div>
      <div class="chart-wrap"><canvas ref="canvasEl"></canvas></div>
    </div>

    <div class="card">
      <div class="card-head"><h2>查询结果</h2><span class="muted">{{ summary }}</span></div>
      <div>
        <StateBlock v-if="tableState === 'loading'" kind="loading" text="查询中…" />
        <StateBlock v-else-if="tableState === 'error'" kind="error" :text="tableError" />
        <DataTable v-else-if="rows.length" :cols="KLINE_COLS" :rows="rows" />
        <StateBlock v-else kind="empty" :text="tableState === 'done' ? '暂无数据' : '尚未查询'" />
      </div>
    </div>
  </section>
</template>
