<script setup lang="ts">
import StateBlock from './StateBlock.vue'

export interface TableCol {
  title: string
  key?: string
  num?: boolean
  render?: (row: any) => string
}

const props = withDefaults(
  defineProps<{
    cols: TableCol[]
    rows: any[]
    emptyText?: string
    /* 勾选模式：首列放 checkbox，选中项由父组件用 key 维护 */
    selectable?: boolean
    selected?: string[]
    rowKey?: (row: any) => string
  }>(),
  { emptyText: '暂无数据', selectable: false, selected: () => [], rowKey: undefined }
)

const emit = defineEmits<{
  (e: 'toggle', key: string): void
  (e: 'toggle-all', keys: string[]): void
}>()

function keyOf(row: any, index: number): string {
  if (typeof props.rowKey === 'function') return props.rowKey(row)
  return String(index)
}

function isChecked(row: any, index: number): boolean {
  return props.selected.indexOf(keyOf(row, index)) >= 0
}

function allChecked(): boolean {
  return props.rows.length > 0 && props.selected.length >= props.rows.length
}

function toggleAll(): void {
  emit(
    'toggle-all',
    allChecked() ? [] : props.rows.map((row, i) => keyOf(row, i))
  )
}

function cell(col: TableCol, row: any): string {
  if (typeof col.render === 'function') return col.render(row)
  const v = col.key ? row[col.key] : ''
  return v === null || v === undefined || v === '' ? '-' : String(v)
}
</script>

<template>
  <StateBlock v-if="!rows || !rows.length" kind="empty" :text="emptyText" />
  <table v-else>
    <thead>
      <tr>
        <th v-if="selectable" class="oc-check">
          <input
            type="checkbox"
            :checked="allChecked()"
            aria-label="全选"
            @change="toggleAll"
          />
        </th>
        <th v-for="(c, i) in cols" :key="i" :class="c.num ? 'num' : ''">{{ c.title }}</th>
      </tr>
    </thead>
    <tbody>
      <tr v-for="(row, r) in rows" :key="r" :class="{ 'is-selected': selectable && isChecked(row, r) }">
        <td v-if="selectable" class="oc-check">
          <input
            type="checkbox"
            :checked="isChecked(row, r)"
            :aria-label="'选择第 ' + (r + 1) + ' 行'"
            @change="emit('toggle', keyOf(row, r))"
          />
        </td>
        <td v-for="(c, i) in cols" :key="i" :class="c.num ? 'num' : ''" v-html="cell(c, row)"></td>
      </tr>
    </tbody>
  </table>
</template>
