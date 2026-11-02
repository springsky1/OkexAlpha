<script setup lang="ts">
/* 冠军明细 + 币种选择器：等价 legacy renderBtChampionHead / renderBtCoinPicker（app.js 3982-4095） */
import { computed } from 'vue'
import { esc, fmtInt, fmtNum, fmtPct, isNil } from '../../lib/format'
import {
  btChampionMeta,
  btCanPromote,
  btMetrics,
  btParseJson,
  btPctCell,
  btSame,
  btVerdictBadge,
  prettyJson,
  type BtEquityPoint,
  type BtResult,
  type BtTrade,
  type BtMetricsSample
} from './btLogic'
import BtEquityChart from './BtEquityChart.vue'
import BtTradesTable from './BtTradesTable.vue'

const props = defineProps<{
  agg: BtResult | null
  coin: BtResult | null
  bestId: number
  coins: BtResult[] | undefined
  equity: BtEquityPoint[]
  equityLoading: boolean
  equityError: string
  trades: BtTrade[]
  tradesLoading: boolean
  tradesError: string
  promoteBusy: boolean
}>()

const emit = defineEmits<{ pick: [aggId: number, coinId: number]; promote: [] }>()

const aggId = computed(() => (props.agg ? Number(props.agg.id) : 0))
const isChampion = computed(() => !!props.agg && Number(props.agg.id) === Number(props.bestId))
const metaText = computed(() => (props.agg ? btChampionMeta(aggId.value, props.agg, isChampion.value, props.coin) : ''))

const metrics = computed(() => btMetrics(props.agg))
const inSample = computed((): BtMetricsSample | null => {
  const m = metrics.value
  return m && m.inSample ? m.inSample : null
})
/* 聚合行 metrics.inSample 与整体同值（后端未区分样本内外）时省略样本内分组，避免误导 */
const showInSample = computed(() => {
  const ins = inSample.value
  const agg = props.agg
  if (!ins || !agg) return false
  return !btSame(ins.returnPct, agg.netPnlPct) || !btSame(ins.trades, agg.tradeCount)
})
const outOfSample = computed((): BtMetricsSample | null => {
  const m = metrics.value
  return m && m.outOfSample ? m.outOfSample : null
})
const score = computed(() => (metrics.value ? metrics.value.score : undefined))

const params = computed(() => {
  const parsed = btParseJson(props.agg ? props.agg.params : null)
  return parsed ? prettyJson(parsed) : ''
})

const promoteDisabled = computed(() => props.promoteBusy || !btCanPromote(props.agg))

function winRateText(v: unknown): string {
  return isNil(v) ? '-' : Number(v).toFixed(2) + '%'
}

function pickCoin(coinId: number) {
  emit('pick', aggId.value, coinId)
}
</script>

<template>
  <div class="card">
    <div class="card-head">
      <h2>冠军明细</h2>
      <div class="btn-group">
        <span class="muted">{{ metaText || '—' }}</span>
        <button class="btn btn-sm" type="button" :disabled="promoteDisabled" @click="emit('promote')">提升为候选策略</button>
      </div>
    </div>

    <div v-if="!agg" class="state">组合明细加载中…</div>
    <div v-else>
      <div class="kv-grid">
        <div class="kv"><span class="kv-key">paramKey</span><span class="kv-val mono">{{ agg.paramKey || '-' }}</span></div>
        <div class="kv"><span class="kv-key">verdict</span><span class="kv-val" v-html="btVerdictBadge(agg.verdict)"></span></div>
        <div class="kv"><span class="kv-key">净收益率</span><span class="kv-val" v-html="btPctCell(agg.netPnlPct)"></span></div>
        <div class="kv"><span class="kv-key">最大回撤</span><span class="kv-val">{{ fmtNum(agg.maxDrawdownPct) }}%</span></div>
        <div class="kv"><span class="kv-key">Sharpe</span><span class="kv-val">{{ fmtNum(agg.sharpe) }}</span></div>
        <div class="kv"><span class="kv-key">胜率</span><span class="kv-val">{{ winRateText(agg.winRate) }}</span></div>
        <div class="kv"><span class="kv-key">交易数</span><span class="kv-val">{{ fmtInt(agg.tradeCount) }}</span></div>
        <div class="kv"><span class="kv-key">样本外收益</span><span class="kv-val" v-html="btPctCell(agg.oosNetPnlPct)"></span></div>
        <div class="kv"><span class="kv-key">覆盖币数</span><span class="kv-val">{{ fmtInt(agg.coinCount) }}</span></div>
        <div class="kv"><span class="kv-key">强平次数</span><span class="kv-val">{{ fmtInt(agg.liquidationCount) }}</span></div>
      </div>

      <template v-if="metrics">
        <div v-if="showInSample && inSample" class="kv-grid" style="margin-top: 10px">
          <div class="kv"><span class="kv-key">样本内收益</span><span class="kv-val" v-html="btPctCell(inSample.returnPct)"></span></div>
          <div class="kv"><span class="kv-key">样本内回撤</span><span class="kv-val">{{ fmtNum(inSample.maxDrawdownPct) }}%</span></div>
          <div class="kv"><span class="kv-key">样本内交易</span><span class="kv-val">{{ fmtInt(inSample.trades) }}</span></div>
          <div class="kv"><span class="kv-key">样本内 Sharpe</span><span class="kv-val">{{ fmtNum(inSample.sharpe) }}</span></div>
        </div>
        <div v-if="outOfSample" class="kv-grid" style="margin-top: 10px">
          <div class="kv"><span class="kv-key">样本外回撤</span><span class="kv-val">{{ fmtNum(outOfSample.maxDrawdownPct) }}%</span></div>
          <div class="kv"><span class="kv-key">样本外交易</span><span class="kv-val">{{ fmtInt(outOfSample.trades) }}</span></div>
          <div v-if="!isNil(score)" class="kv"><span class="kv-key">score</span><span class="kv-val">{{ fmtNum(score) }}</span></div>
        </div>
        <p v-if="inSample && !showInSample" class="hint" style="margin-top: 8px">
          接口 metrics.inSample 与整体指标同值（后端未区分样本内外），已省略样本内分组。
        </p>
      </template>

      <div v-if="coin" class="kv-grid" style="margin-top: 10px">
        <div class="kv"><span class="kv-key">当前币</span><span class="kv-val mono">{{ coin.instId }}</span></div>
        <div class="kv"><span class="kv-key">币净收益率</span><span class="kv-val" v-html="btPctCell(coin.netPnlPct)"></span></div>
        <div class="kv"><span class="kv-key">币最大回撤</span><span class="kv-val">{{ fmtNum(coin.maxDrawdownPct) }}%</span></div>
        <div class="kv"><span class="kv-key">币 Sharpe</span><span class="kv-val">{{ fmtNum(coin.sharpe) }}</span></div>
        <div class="kv"><span class="kv-key">币胜率</span><span class="kv-val">{{ winRateText(coin.winRate) }}</span></div>
        <div class="kv"><span class="kv-key">币交易数</span><span class="kv-val">{{ fmtInt(coin.tradeCount) }}</span></div>
      </div>
      <p v-else class="hint" style="margin-top: 10px">
        聚合行（segment=ALL）没有资金曲线数据；用下方「查看某个币的资金曲线」选择单个币，或在结果排行里展开「逐币」后点某个币的「看曲线」。
      </p>

      <details v-if="params" class="bt-req">
        <summary class="hint">组合参数 params</summary>
        <pre class="json-out" v-html="esc(params)"></pre>
      </details>
    </div>

    <BtEquityChart
      :points="equity"
      :coin-selected="!!coin"
      :loading="equityLoading"
      :error="equityError"
    >
      <template #picker>
        <div class="bt-picker">
          <template v-if="!coins"><span class="hint">正在加载币种…</span></template>
          <template v-else-if="!coins.length"><span class="hint">该组合没有逐币明细</span></template>
          <template v-else>
            <span class="hint">查看某个币的资金曲线：</span>
            <button
              v-for="c in coins"
              :key="c.id"
              class="bt-pick"
              :class="coin !== null && Number(coin.id) === Number(c.id) ? 'is-on' : ''"
              type="button"
              @click="pickCoin(Number(c.id))"
            >
              {{ c.instId }} · {{ fmtPct(c.netPnlPct) }}
            </button>
            <button class="bt-pick" :class="!coin ? 'is-on' : ''" type="button" @click="pickCoin(0)">聚合行</button>
          </template>
        </div>
      </template>
    </BtEquityChart>

    <BtTradesTable
      :trades="trades"
      :coin-selected="!!coin"
      :loading="tradesLoading"
      :error="tradesError"
    />
  </div>
</template>
