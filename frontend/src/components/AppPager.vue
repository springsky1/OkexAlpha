<script setup lang="ts">
import { computed } from 'vue'
import { pagerInfo } from '../lib/format'

const props = withDefaults(
  defineProps<{ offset: number; limit: number; total: number }>(),
  { offset: 0, limit: 20, total: 0 }
)
const emit = defineEmits<{ (e: 'change', offset: number): void }>()

const info = computed(() => pagerInfo(props.offset, Math.min(props.limit, Math.max(0, props.total - props.offset))))
const prevDisabled = computed(() => props.offset <= 0)
const nextDisabled = computed(() => props.offset + props.limit >= props.total)

function go(delta: number) {
  const next = props.offset + delta * props.limit
  if (next < 0) return
  emit('change', next)
}
</script>

<template>
  <div class="pager">
    <span class="pager-info muted">{{ info }}</span>
    <div class="btn-group">
      <button class="btn btn-sm" type="button" :disabled="prevDisabled" @click="go(-1)">上一页</button>
      <button class="btn btn-sm" type="button" :disabled="nextDisabled" @click="go(1)">下一页</button>
    </div>
  </div>
</template>
