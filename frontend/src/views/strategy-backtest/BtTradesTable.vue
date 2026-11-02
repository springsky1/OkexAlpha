<script setup lang="ts">
/* 逐笔成交：等价 legacy loadBtTrades + renderBtTrades（app.js 4258-4314） */
import { computed } from 'vue'
import { esc, fmtInt, fmtMoney, fmtNum, fmtTime } from '../../lib/format'
import { btMoneyCell, btPctCell, btSortedTrades, btTradesSummary, type BtTrade } from './btLogic'

const props = defineProps<{
  trades: BtTrade[]
  coinSelected: boolean
  loading: boolean
  error: string
}>()

const rows = computed(() => btSortedTrades(props.trades))

const meta = computed(() => {
  if (props.loading) return '加载中…'
  if (props.error) return props.error
  return rows.value.length ? btTradesSummary(rows.value.length, props.coinSelected) : ''
})
</script>

<template>
  <div class="card-head" style="margin-top: 16px">
    <h2>逐笔成交</h2>
    <span class="muted">{{ meta }}</span>
  </div>
  <div v-if="!rows.length" class="state">暂无逐笔成交</div>
  <div v-else class="table-wrap">
    <table class="table-jobs">
      <thead>
        <tr>
          <th>instId</th>
          <th>side</th>
          <th class="num">layers</th>
          <th class="num">开仓价</th>
          <th class="num">平仓价</th>
          <th class="num">名义USD</th>
          <th class="num">pnl</th>
          <th class="num">pnl%</th>
          <th class="num">fee</th>
          <th class="num">funding</th>
          <th>开仓时间</th>
          <th>平仓时间</th>
          <th>exit_reason</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="t in rows" :key="t.id">
          <td class="mono">{{ esc(t.inst_id) }}</td>
          <td>{{ esc(t.side) }}</td>
          <td class="num">{{ fmtInt(t.layers) }}</td>
          <td class="num">{{ fmtNum(t.entry_px) }}</td>
          <td class="num">{{ fmtNum(t.exit_px) }}</td>
          <td class="num">{{ fmtMoney(t.notional_usd) }}</td>
          <td class="num" v-html="btMoneyCell(t.pnl)"></td>
          <td class="num" v-html="btPctCell(t.pnl_pct)"></td>
          <td class="num">{{ fmtNum(t.fee) }}</td>
          <td class="num">{{ fmtNum(t.funding_paid) }}</td>
          <td class="mono">{{ fmtTime(Number(t.entry_ts_ms)) }}</td>
          <td class="mono">{{ fmtTime(Number(t.exit_ts_ms)) }}</td>
          <td>{{ esc(t.exit_reason) }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
