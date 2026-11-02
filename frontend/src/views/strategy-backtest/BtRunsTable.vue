<script setup lang="ts">
/* 批次列表：等价 legacy renderBtRuns（app.js 3630-3661） */
import StateBlock from '../../components/StateBlock.vue'
import { fmtInt, fmtShortTime, fmtTime, isNil, parseServerTime } from '../../lib/format'
import { btPhaseLabel, btProgressBarClass, btProgressPct, type BtRun } from './btLogic'

defineProps<{ runs: BtRun[]; loading: boolean; error: string; selectedId: number }>()
const emit = defineEmits<{ open: [id: number] }>()

function barWidth(done: unknown, total: unknown): string {
  return 'width:' + btProgressPct(done, total) + '%'
}

function progressText(done: unknown, total: unknown): string {
  return fmtInt(done) + '/' + fmtInt(total)
}

function rangeText(r: BtRun): string {
  return fmtShortTime(r.startMs) + ' → ' + fmtShortTime(r.endMs)
}
</script>

<template>
  <div class="card">
    <div class="card-head">
      <h2>批次列表</h2>
      <span class="muted">GET /api/v1/strategy/backtests?limit=20 · 有批次在跑时每 2 秒刷新</span>
    </div>
    <StateBlock v-if="loading && !runs.length" kind="loading" text="加载批次列表…" />
    <StateBlock v-else-if="error" kind="error" :text="error" />
    <StateBlock v-else-if="!runs.length" kind="empty" text="暂无回测批次，先在上方创建" />
    <div v-else class="table-wrap">
      <table class="table-jobs">
        <thead>
          <tr>
            <th>批次</th>
            <th>状态</th>
            <th>bar</th>
            <th>区间</th>
            <th>币进度</th>
            <th>参数进度</th>
            <th>phase</th>
            <th>最好结果</th>
            <th>开始时间</th>
            <th>结束时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="r in runs"
            :key="r.id"
            class="bt-run-row"
            :class="selectedId === r.id ? 'is-selected' : ''"
            @click="emit('open', r.id)"
          >
            <td class="mono">#{{ r.id }}</td>
            <td><span class="badge" :data-status="r.status">{{ r.status }}</span></td>
            <td class="mono">{{ r.bar }}</td>
            <td class="mono">{{ rangeText(r) }}</td>
            <td>
              <div class="progress-cell">
                <div class="progress">
                  <div class="bar" :class="btProgressBarClass(r.status)" :style="barWidth(r.coinDone, r.coinTotal)"></div>
                </div>
                <span class="progress-text">{{ progressText(r.coinDone, r.coinTotal) }}</span>
              </div>
            </td>
            <td>
              <div class="progress-cell">
                <div class="progress">
                  <div class="bar" :class="btProgressBarClass(r.status)" :style="barWidth(r.combosDone, r.combosTotal)"></div>
                </div>
                <span class="progress-text">{{ progressText(r.combosDone, r.combosTotal) }}</span>
              </div>
            </td>
            <td>{{ btPhaseLabel(r.phase) }}</td>
            <td class="mono">{{ isNil(r.bestResultId) ? '-' : '#' + r.bestResultId }}</td>
            <td class="mono">{{ fmtTime(parseServerTime(r.startedAt)) }}</td>
            <td class="mono">{{ fmtTime(parseServerTime(r.finishedAt)) }}</td>
            <td>
              <div class="row-actions">
                <button class="btn btn-xs" type="button" @click.stop="emit('open', r.id)">查看</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
