<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
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
/* 多选面板里与当前过滤词匹配的全部 instId（不受 maxRows 截断，供全选/反选使用） */
const matchedIds = ref<string[]>([])
let blurTimer: ReturnType<typeof setTimeout> | null = null

const value = () => props.modelValue || ''

/* 已选中的合法 instId；品种库未加载时按原始逗号串理解 */
function pickedTokens(): string[] {
  const toks = parseList(value(), NL, TAB)
  return inst.loaded ? toks.filter((t) => inst.hasId(t)) : toks
}

const selectedCount = computed(() => (props.multiple ? pickedTokens().length : 0))
/* 已选集合（随 modelValue 缓存，避免每行重复解析逗号串） */
const pickedMap = computed<Record<string, boolean>>(() => {
  const map: Record<string, boolean> = {}
  const list = pickedTokens()
  for (let i = 0; i < list.length; i++) map[list[i]] = true
  return map
})

function isPicked(id: string): boolean {
  return !!pickedMap.value[id]
}

function setTokens(list: string[]) {
  emit('update:modelValue', list.join(', '))
}

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
  let q = queryOf()
  /* 框里已经是一个完整 instId（刚选中/初始值）时不当作过滤词，直接列全部候选 */
  if (q && inst.hasId(q)) q = ''
  const pool = inst.list.filter(allowed)
  const next: any[] = []
  const matchedList: string[] = []
  let matched = 0
  for (let i = 0; i < pool.length; i++) {
    const it = pool[i]
    if (!it || !it.instId) continue
    /* 单选模式把已选项从候选里剔除；多选用 checkbox 常驻展示，勾选态由 isPicked 决定 */
    if (!props.multiple && isPicked(it.instId)) continue
    const id = String(it.instId).toUpperCase()
    const coin = coinOfInst(it.instId).toUpperCase()
    if (q && id.indexOf(q) < 0 && coin.indexOf(q) < 0) continue
    matched++
    matchedList.push(it.instId)
    if (next.length < props.maxRows) next.push(it)
  }
  matchedIds.value = matchedList
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

/* 多选：勾选/取消一个 instId（只保留合法 instId，丢掉刚打的过滤片段） */
function toggle(it: any) {
  if (!it || !it.instId) return
  const tokens = pickedTokens()
  const i = tokens.indexOf(it.instId)
  if (i >= 0) tokens.splice(i, 1)
  else tokens.push(it.instId)
  setTokens(tokens)
  refresh()
}

function apply(it: any) {
  if (!it) return
  if (!props.multiple) {
    emit('update:modelValue', it.instId)
    close()
    return
  }
  /* 多选模式保持面板打开，便于连续挑多个 */
  toggle(it)
}

/* 全选当前过滤结果（含被 maxRows 截断、未渲染的候选） */
function selectAll() {
  const tokens = pickedTokens()
  for (let i = 0; i < matchedIds.value.length; i++) {
    const id = matchedIds.value[i]
    if (tokens.indexOf(id) < 0) tokens.push(id)
  }
  setTokens(tokens)
  refresh()
}

/* 反选当前过滤结果 */
function invertAll() {
  const tokens = pickedTokens()
  for (let i = 0; i < matchedIds.value.length; i++) {
    const id = matchedIds.value[i]
    const at = tokens.indexOf(id)
    if (at >= 0) tokens.splice(at, 1)
    else tokens.push(id)
  }
  setTokens(tokens)
  refresh()
}

function clearAll() {
  setTokens([])
  refresh()
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
      <div v-if="multiple" class="inst-bulk">
        <span class="inst-bulk-count">已选 {{ selectedCount }} 个</span>
        <button type="button" class="inst-bulk-btn" @click="selectAll">全选</button>
        <button type="button" class="inst-bulk-btn" @click="invertAll">反选</button>
        <button type="button" class="inst-bulk-btn" @click="clearAll">清空</button>
      </div>
      <div class="inst-panel-list" role="listbox">
        <div v-if="!inst.list.length" class="inst-empty">库内还没有品种数据，请先到「同步」页执行 POST /api/v1/sync/instruments</div>
        <div v-else-if="!rows.length" class="inst-empty">没有匹配的 instId</div>
        <button
          v-for="(it, i) in rows"
          :key="it.instId"
          type="button"
          class="inst-opt"
          role="option"
          :aria-selected="multiple ? isPicked(it.instId) : i === active"
          :class="{ 'is-active': i === active, 'is-checked': multiple && isPicked(it.instId) }"
          @mousedown="onOptionMousedown"
          @mouseenter="active = i"
          @click="apply(it)"
        >
          <span v-if="multiple" class="inst-check" :class="{ 'is-on': isPicked(it.instId) }" aria-hidden="true">{{ isPicked(it.instId) ? '✓' : '' }}</span>
          <span class="inst-opt-id">{{ it.instId }}</span>
          <span class="inst-opt-meta">{{ coinOfInst(it.instId) }} · {{ it.instType || '' }}{{ it.state ? ' · ' + it.state : '' }}</span>
        </button>
      </div>
    </div>
  </div>
</template>