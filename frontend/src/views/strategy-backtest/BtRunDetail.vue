<script setup lang="ts">
/* 批次详情：等价 legacy renderBtDetail（app.js 3749-3797） */
import { computed } from 'vue'
import { esc, fmtDur, fmtInt, fmtTime, isNil, parseServerTime } from '../../lib/format'
import { btParseJson, btPhaseLabel, btProgressBarClass, btProgressPct, btStatusActive, prettyJson, type BtRun } from './btLogic'

const props = defineProps<{ run: BtRun | null; resultTotal: number; cancelBusy: boolean }>()
const emit = defineEmits<{ cancel: [] }>()

const active = computed(() => !!props.run && btStatusActive(props.run.status))

const elapsed = computed(() => {
  const run = props.run
  if (!run) return NaN
  const started = parseServerTime(run.startedAt)
  if (!isFinite(started)) return NaN
  const finished = parseServerTime(run.finishedAt)
  return (isFinite(finished) ? finished : Date.now()) - started
})

const req = computed(() => {
  const parsed = btParseJson(props.run ? props.run.request : null)
  if (!parsed || typeof parsed !== 'object') return null
  const obj = parsed as { instIds?: unknown; combos?: unknown; topN?: unknown }
  const ids = Array.isArray(obj.instIds) ? obj.instIds.join(', ') : ''
  return {
    ids: ids || '库内默认币种',
    combos: String(obj.combos),
    topN: String(obj.topN),
    json: prettyJson(parsed)
  }
})

function barWidth(done: unknown, total: unknown): string {
  return 'width:' + btProgressPct(done, total) + '%'
}

function progressText(done: unknown, total: unknown): string {
  return fmtInt(done) + '/' + fmtInt(total)
}

function kvTime(v: unknown): string {
  return fmtTime(parseServerTime(v))
}
</script>

<template>
  <div class="card">
    <div class="card-head">
      <h2>批次详情</h2>
      <div class="btn-group">
        <button class="btn btn-sm btn-danger" type="button" :disabled="!active || cancelBusy" @click="emit('cancel')">
          取消批次
        </button>
      </div>
    </div>
    <div v-if="!run" class="state">暂无批次数据</div>
    <div v-else>
      <div class="kv-grid">
        <div class="kv"><span class="kv-key">批次</span><span class="kv-val mono">#{{ run.id }}</span></div>
        <div class="kv"><span class="kv-key">状态</span><span class="kv-val"><span class="badge" :data-status="run.status">{{ run.status }}</span></span></div>
        <div class="kv"><span class="kv-key">bar</span><span class="kv-val mono">{{ run.bar }}</span></div>
        <div class="kv"><span class="kv-key">phase</span><span class="kv-val">{{ btPhaseLabel(run.phase) }}</span></div>
        <div class="kv">
          <span class="kv-key">区间</span>
          <span class="kv-val mono">{{ fmtTime(run.startMs) }} → {{ fmtTime(run.endMs) }}</span>
        </div>
        <div class="kv"><span class="kv-key">币进度</span><span class="kv-val">{{ fmtInt(run.coinDone) }} / {{ fmtInt(run.coinTotal) }}</span></div>
        <div class="kv">
          <span class="kv-key">参数组合进度</span>
          <span class="kv-val">{{ fmtInt(run.combosDone) }} / {{ fmtInt(run.combosTotal) }}</span>
        </div>
        <div class="kv">
          <span class="kv-key">冠军结果</span>
          <span class="kv-val mono">{{ isNil(run.bestResultId) ? '-' : '#' + run.bestResultId }}</span>
        </div>
        <div class="kv"><span class="kv-key">结果总数</span><span class="kv-val">{{ fmtInt(resultTotal) }}</span></div>
        <div class="kv"><span class="kv-key">创建时间</span><span class="kv-val">{{ kvTime(run.createdAt) }}</span></div>
        <div class="kv"><span class="kv-key">开始时间</span><span class="kv-val">{{ kvTime(run.startedAt) }}</span></div>
        <div class="kv"><span class="kv-key">结束时间</span><span class="kv-val">{{ kvTime(run.finishedAt) }}</span></div>
        <div class="kv"><span class="kv-key">耗时</span><span class="kv-val">{{ isFinite(elapsed) ? fmtDur(elapsed) : '-' }}</span></div>
        <div v-if="run.label" class="kv"><span class="kv-key">备注</span><span class="kv-val">{{ run.label }}</span></div>
      </div>

      <div class="bt-bars">
        <div>
          <div class="hint">币进度</div>
          <div class="progress-cell">
            <div class="progress">
              <div class="bar" :class="btProgressBarClass(run.status)" :style="barWidth(run.coinDone, run.coinTotal)"></div>
            </div>
            <span class="progress-text">{{ progressText(run.coinDone, run.coinTotal) }}</span>
          </div>
        </div>
        <div>
          <div class="hint">参数组合进度</div>
          <div class="progress-cell">
            <div class="progress">
              <div class="bar" :class="btProgressBarClass(run.status)" :style="barWidth(run.combosDone, run.combosTotal)"></div>
            </div>
            <span class="progress-text">{{ progressText(run.combosDone, run.combosTotal) }}</span>
          </div>
        </div>
      </div>

      <div v-if="run.error" class="alert danger" style="margin-top: 12px">{{ run.error }}</div>

      <details v-if="req" class="bt-req">
        <summary class="hint">
          request 参数（{{ req.ids }}，combos={{ req.combos }}，topN={{ req.topN }}）
        </summary>
        <pre class="json-out" v-html="esc(req.json)"></pre>
      </details>
    </div>
  </div>
</template>
