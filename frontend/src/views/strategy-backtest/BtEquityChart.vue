<script setup lang="ts">
/* 资金曲线：等价 legacy loadBtEquity + drawBtEquity（app.js 4097-4256） */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { drawBtEquity } from './btDraw'
import { btEquityEmptyText, btEquitySummary, type BtEquityPoint } from './btLogic'

const props = defineProps<{
  points: BtEquityPoint[]
  coinSelected: boolean
  loading: boolean
  error: string
}>()

const canvasEl = ref<HTMLCanvasElement | null>(null)
let ro: ResizeObserver | null = null

const meta = computed(() => {
  if (props.loading) return '加载中…'
  if (props.error) return props.error
  if (props.points.length) return btEquitySummary(props.points)
  return btEquityEmptyText(!props.coinSelected)
})

function redraw() {
  drawBtEquity(canvasEl.value, props.points)
}

watch(() => props.points, redraw, { deep: false })

onMounted(() => {
  redraw()
  if (typeof ResizeObserver !== 'undefined' && canvasEl.value && canvasEl.value.parentElement) {
    ro = new ResizeObserver(() => redraw())
    ro.observe(canvasEl.value.parentElement)
  }
})

onBeforeUnmount(() => {
  if (ro) ro.disconnect()
  ro = null
})
</script>

<template>
  <div class="card-head" style="margin-top: 16px">
    <h2>资金曲线</h2>
    <span class="muted">{{ meta }}</span>
  </div>
  <slot name="picker" />
  <div class="chart-wrap"><canvas ref="canvasEl"></canvas></div>
</template>
