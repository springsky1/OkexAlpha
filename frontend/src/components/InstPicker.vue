<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { parseList } from '../lib/format'
import { NL, TAB } from '../lib/labels'
import { coinOfInst, useInstStore } from '../stores/inst'

const props = withDefaults(
  defineProps<{
    modelValue: string
    multiple?: boolean
    instTypes?: string[]
    maxRows?: number
    placeholder?: string
  }>(),
  {
    multiple: false,
    instTypes: () => ['SWAP', 'FUTURES'],
    maxRows: 300,
    placeholder: ''
  }
)

const emit = defineEmits<{ (e: 'update:modelValue', v: string): void }>()

const inst = useInstStore()
const panelOpen = ref(false)
const head = ref('')
const rows = ref<any[]>([])
const active = ref(-1)
let blurTimer: ReturnType<typeof setTimeout> | null = null

const value = () => props.modelValue || ''

/* 多选时只看最后一个逗号后的输入作为过滤词 */
function queryOf(): string {
  const v = value()
  if (!props.multiple) return v.trim().toUpperCase()
  const i = v.lastIndexOf(',')
  return v.slice(i + 1).trim().toUpperCase()
}

function allowed(it: any): boolean {
  return props.instTypes.indexOf(String(it.instType || '')) >= 0
}

async function markActive() {
  await nextTick()
  const node = document.querySelector('.inst-combo .inst-opt.is-active')
  if (node && node.scrollIntoView) node.scrollIntoView({ block: 'nearest' })
}

function refresh() {
  const picked = props.multiple ? parseList(value(), NL, TAB) : []
  let q = queryOf()
  /* 框里已经是一个完整 instId（刚选中/初始值）时不当作过滤词，直接列全部候选 */
  if (q && inst.hasId(q)) q = ''
  const pool = inst.list.filter(allowed)
  const next: any[] = []
  let matched = 0
  for (let i = 0; i < pool.length; i++) {
    const it = pool[i]
    if (!it || !it.instId) continue
    if (picked.indexOf(it.instId) >= 0) continue
    const id = String(it.instId).toUpperCase()
    const coin = coinOfInst(it.instId).toUpperCase()
    if (q && id.indexOf(q) < 0 && coin.indexOf(q) < 0) continue
    matched++
    if (next.length < props.maxRows) next.push(it)
  }
  rows.value = next
  active.value = next.length ? 0 : -1
  if (!inst.list.length) {
    head.value = '库内暂无品种'
    return
  }
  const scope = props.instTypes.join('+')
  head.value = q
    ? scope + ' 匹配 ' + matched + ' 个' + (matched > next.length ? '（仅显示前 ' + next.length + '）' : '') + ' · coin/instId 过滤'
    : scope + ' 共 ' + pool.length + ' 个 · 输入 coin/instId 过滤'
  if (next.length) void markActive()
}

function close() {
  panelOpen.value = false
  rows.value = []
  active.value = -1
}

function apply(it: any) {
  if (!it) return
  if (!props.multiple) {
    emit('update:modelValue', it.instId)
  } else {
    /* 只保留已存在的合法 instId，丢掉刚打的过滤片段，避免把 "BTC" 这类半截词当成合约 */
    let tokens = parseList(value(), NL, TAB)
    if (inst.loaded) tokens = tokens.filter((t) => inst.hasId(t))
    if (tokens.indexOf(it.instId) < 0) tokens.push(it.instId)
    emit('update:modelValue', tokens.join(', '))
  }
  /* 多选模式保持面板打开，便于连续挑多个 */
  if (props.multiple) refresh()
  else close()
}

async function open() {
  if (blurTimer) {
    clearTimeout(blurTimer)
    blurTimer = null
  }
  if (panelOpen.value) return
  panelOpen.value = true
  head.value = '加载中…'
  rows.value = []
  await inst.load()
  if (panelOpen.value) refresh()
}

function move(step: number) {
  if (!rows.value.length) return
  active.value = (active.value + step + rows.value.length) % rows.value.length
  void markActive()
}

function onFocus() {
  if (blurTimer) {
    clearTimeout(blurTimer)
    blurTimer = null
  }
  void open()
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    if (!panelOpen.value) {
      void open()
      e.preventDefault()
      return
    }
    move(e.key === 'ArrowDown' ? 1 : -1)
    e.preventDefault()
    return
  }
  if (e.key === 'Enter') {
    if (panelOpen.value) {
      /* 框里已经是合法 instId（含多选时最后一个完整 instId）→ 回车只收起，不覆盖用户输入 */
      const tokens = parseList(value(), NL, TAB)
      const tail = String(tokens.length ? tokens[tokens.length - 1] : '').toUpperCase()
      if (tail && inst.hasId(tail)) {
        close()
        e.preventDefault()
        return
      }
      if (rows.value.length) {
        apply(rows.value[active.value < 0 ? 0 : active.value])
        e.preventDefault()
      }
    }
    return
  }
  if (e.key === 'Escape') close()
}

function onBlur() {
  if (blurTimer) clearTimeout(blurTimer)
  blurTimer = setTimeout(() => {
    blurTimer = null
    close()
  }, 140)
}

function onPanelMousedown(e: MouseEvent) {
  e.preventDefault()
}

function onOptionMousedown(e: MouseEvent) {
  e.preventDefault()
}

function toggleCaret() {
  if (panelOpen.value) close()
  else void open()
}

watch(
  () => props.modelValue,
  () => {
    if (panelOpen.value) refresh()
  }
)

onBeforeUnmount(() => {
  if (blurTimer) clearTimeout(blurTimer)
})
</script>

<template>
  <div class="inst-combo">
    <textarea
      v-if="multiple"
      class="inst-input"
      rows="2"
      :value="modelValue"
      :placeholder="placeholder"
      autocomplete="off"
      spellcheck="false"
      @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"
      @focus="onFocus"
      @blur="onBlur"
      @keydown="onKeydown"
    ></textarea>
    <input
      v-else
      class="inst-input"
      type="text"
      :value="modelValue"
      :placeholder="placeholder"
      autocomplete="off"
      spellcheck="false"
      @input="emit('update:modelValue', ($event.target as HTMLInputElement).value)"
      @focus="onFocus"
      @blur="onBlur"
      @keydown="onKeydown"
    />
    <button class="inst-caret" type="button" tabindex="-1" aria-label="展开 instId 候选" @mousedown="onPanelMousedown" @click="toggleCaret">▾</button>
    <div v-if="panelOpen" class="inst-panel" @mousedown="onPanelMousedown">
      <div class="inst-panel-head">{{ head }}</div>
      <div class="inst-panel-list" role="listbox">
        <div v-if="!inst.list.length" class="inst-empty">库内还没有品种数据，请先到「同步」页执行 POST /api/v1/sync/instruments</div>
        <div v-else-if="!rows.length" class="inst-empty">没有匹配的 instId</div>
        <button
          v-for="(it, i) in rows"
          :key="it.instId"
          type="button"
          class="inst-opt"
          role="option"
          :class="{ 'is-active': i === active }"
          @mousedown="onOptionMousedown"
          @mouseenter="active = i"
          @click="apply(it)"
        >
          <span class="inst-opt-id">{{ it.instId }}</span>
          <span class="inst-opt-meta">{{ coinOfInst(it.instId) }} · {{ it.instType || '' }}{{ it.state ? ' · ' + it.state : '' }}</span>
        </button>
      </div>
    </div>
  </div>
</template>