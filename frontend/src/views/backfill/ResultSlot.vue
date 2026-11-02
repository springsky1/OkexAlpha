<script setup lang="ts">
/* 补拉页结果槽：legacy renderJobCreated / renderErrorBox / 「正在提交…」三态 */
import { fmtInt } from '../../lib/format'

withDefaults(
  defineProps<{
    kind?: 'idle' | 'loading' | 'ok' | 'err'
    title?: string
    jobId?: string | number
    taskTotal?: string | number
    msg?: string
  }>(),
  { kind: 'idle', title: '', jobId: '', taskTotal: '', msg: '' }
)
</script>

<template>
  <div v-if="kind === 'loading'" class="result-box"><span class="spinner"></span> 正在提交…</div>
  <div v-else-if="kind === 'ok'" class="result-box is-ok">
    <div><strong>{{ title }}</strong> 提交成功（HTTP 202）</div>
    <div>jobId：<strong>{{ jobId }}</strong></div>
    <div>taskTotal：<strong>{{ fmtInt(taskTotal) }}</strong></div>
    <div class="muted">可到 <a href="#/jobs">作业列表</a> 查看进度。</div>
  </div>
  <div v-else-if="kind === 'err'" class="result-box is-err">{{ msg }}</div>
</template>