<script setup lang="ts">
import StateBlock from './StateBlock.vue'

export interface TableCol {
  title: string
  key?: string
  num?: boolean
  render?: (row: any) => string
}

withDefaults(
  defineProps<{ cols: TableCol[]; rows: any[]; emptyText?: string }>(),
  { emptyText: '暂无数据' }
)

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
        <th v-for="(c, i) in cols" :key="i" :class="c.num ? 'num' : ''">{{ c.title }}</th>
      </tr>
    </thead>
    <tbody>
      <tr v-for="(row, r) in rows" :key="r">
        <td v-for="(c, i) in cols" :key="i" :class="c.num ? 'num' : ''" v-html="cell(c, row)"></td>
      </tr>
    </tbody>
  </table>
</template>
