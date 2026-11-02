<script setup lang="ts">
/* 结果排行 + 逐币明细：等价 legacy renderBtResults / renderBtCoinsInner（app.js 3842-3914） */
import { fmtInt, fmtNum, isNil } from '../../lib/format'
import { btPctCell, btVerdictBadge, type BtResult } from './btLogic'

defineProps<{
  rows: BtResult[]
  total: number
  active: boolean
  expanded: Record<number, boolean>
  coins: Record<number, BtResult[]>
  champAggId: number
  champCoinId: number
}>()
const emit = defineEmits<{ toggle: [row: BtResult]; pick: [aggId: number, coinId: number] }>()

function winRateText(v: unknown): string {
  return isNil(v) ? '-' : Number(v).toFixed(2) + '%'
}

function summaryText(shown: number, total: number): string {
  return shown ? '展示前 ' + shown + ' 个 / 共 ' + fmtInt(total) + ' 个参数组合 · 点击行展开逐币明细' : ''
}
</script>

<template>
  <div class="card">
    <div class="card-head">
      <h2>结果排行</h2>
      <span class="muted">{{ summaryText(rows.length, total) }}</span>
    </div>
    <div v-if="!rows.length" class="state">
      {{ active ? '回测进行中，暂无可排行的参数组合（粗筛完成后出现）' : '暂无结果' }}
    </div>
    <div v-else class="table-wrap">
      <table class="table-jobs">
        <thead>
          <tr>
            <th class="num">rank</th>
            <th>paramKey</th>
            <th class="num">净收益率</th>
            <th class="num">最大回撤</th>
            <th class="num">Sharpe</th>
            <th class="num">胜率</th>
            <th class="num">交易数</th>
            <th class="num">样本外收益</th>
            <th>verdict</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="r in rows" :key="r.id">
            <tr :class="champAggId === r.id ? 'is-selected' : ''" @click="emit('toggle', r)">
              <td class="num">{{ isNil(r.rank) ? '-' : r.rank }}</td>
              <td class="mono">{{ isNil(r.paramKey) ? '-' : r.paramKey }}</td>
              <td class="num" v-html="btPctCell(r.netPnlPct)"></td>
              <td class="num">{{ fmtNum(r.maxDrawdownPct) }}%</td>
              <td class="num">{{ fmtNum(r.sharpe) }}</td>
              <td class="num">{{ winRateText(r.winRate) }}</td>
              <td class="num">{{ fmtInt(r.tradeCount) }}</td>
              <td class="num" v-html="btPctCell(r.oosNetPnlPct)"></td>
              <td v-html="btVerdictBadge(r.verdict)"></td>
              <td>
                <div class="row-actions">
                  <button class="btn btn-xs" type="button" @click.stop="emit('toggle', r)">
                    {{ expanded[r.id] ? '收起' : '逐币' }}
                  </button>
                </div>
              </td>
            </tr>
            <tr v-if="expanded[r.id]" class="table-detail">
              <td colspan="10">
                <div v-if="!coins[r.id]" class="state">
                  <span class="spinner"></span><span>加载逐币明细…</span>
                </div>
                <div v-else-if="!coins[r.id].length" class="state">该组合暂无逐币明细</div>
                <div v-else class="table-detail-inner">
                  <table>
                    <thead>
                      <tr>
                        <th>instId</th>
                        <th class="num">交易数</th>
                        <th class="num">胜率</th>
                        <th class="num">净收益率</th>
                        <th class="num">最大回撤</th>
                        <th class="num">Sharpe</th>
                        <th>verdict</th>
                        <th>资金曲线</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="c in coins[r.id]" :key="c.id" :class="champCoinId === c.id ? 'is-selected' : ''">
                        <td class="mono">{{ c.instId }}</td>
                        <td class="num">{{ fmtInt(c.tradeCount) }}</td>
                        <td class="num">{{ winRateText(c.winRate) }}</td>
                        <td class="num" v-html="btPctCell(c.netPnlPct)"></td>
                        <td class="num">{{ fmtNum(c.maxDrawdownPct) }}%</td>
                        <td class="num">{{ fmtNum(c.sharpe) }}</td>
                        <td v-html="btVerdictBadge(c.verdict)"></td>
                        <td>
                          <button class="btn btn-xs" type="button" @click.stop="emit('pick', r.id, c.id)">看曲线</button>
                        </td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>
  </div>
</template>
