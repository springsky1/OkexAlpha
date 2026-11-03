<script setup lang="ts">
/* 提交区：1:1 复刻 legacy index.html#form-backtest + app.js 3544-3630；
   「参数范围（可选）」为需求 #1 新增，留空时完全等价旧行为（内置 648 组网格）。 */
import { computed } from 'vue'
import InstPicker from '../../components/InstPicker.vue'
import { BARS } from '../../lib/labels'
import type { BtSubmitFormState } from './btLogic'
import {
  BT_DEFAULT_SAMPLE_SIZE,
  BT_MAX_COMBOS,
  BT_PRESETS,
  BT_RANGE_META,
  BT_SIDE_LONG,
  BT_SIDE_SHORT,
  emptyRangesForm,
  estimateCombos,
  rangesFromPreset,
  type BtRangeDimMeta
} from './btRanges'

const { form, busy } = defineProps<{ form: BtSubmitFormState; busy: boolean }>()
const emit = defineEmits<{ submit: []; sample: []; quick: [days: number]; clear: [] }>()

/* 预计组合数：按当前 6 维 + 方向实时估算，留空的维度按内置网格取值个数计 */
const rangeCombos = computed(() => estimateCombos(form.ranges))
const rangeOverLimit = computed(() => rangeCombos.value > BT_MAX_COMBOS)

/* 预设只覆盖范围与方向，币种/时间/抽样等其余字段保持不动 */
function applyPreset(key: string): void {
  form.rangesPreset = key
  Object.assign(form.ranges, rangesFromPreset(key))
}

/* 「默认」= 清空全部维度，回到内置 648 组网格 */
function clearPreset(): void {
  form.rangesPreset = null
  Object.assign(form.ranges, emptyRangesForm())
}

/* 输入框 placeholder 按内置网格的典型写法拆分（如 '3 / 10 / 1'） */
function ph(meta: BtRangeDimMeta, index: number): string {
  return meta.placeholder.split(' / ')[index] || ''
}
</script>

<template>
  <form class="card" novalidate @submit.prevent="emit('submit')">
    <div class="card-head">
      <h2>创建回测批次</h2>
      <span class="muted">POST /api/v1/strategy/backtests（202）</span>
    </div>
    <div class="form-grid">
      <div class="field">
        <label for="bt-inst-ids">币种 instIds</label>
        <InstPicker
          id="bt-inst-ids"
          v-model="form.instIds"
          multiple
          :inst-types="['SWAP']"
          placeholder="BTC-USDT-SWAP, ETH-USDT-SWAP"
        />
        <p class="hint">逗号 / 空格 / 换行分隔；留空 = 库内前 N 个 live SWAP</p>
      </div>
      <div class="field">
        <label for="bt-bar">bar</label>
        <select id="bt-bar" v-model="form.bar">
          <option v-for="b in BARS" :key="b[0]" :value="b[0]">{{ b[1] }}</option>
        </select>
        <p class="hint">默认 1m；跑长区间建议 1H / 1D</p>
      </div>
      <div class="field">
        <label for="bt-start">开始时间</label>
        <input id="bt-start" v-model="form.start" type="datetime-local">
      </div>
      <div class="field">
        <label for="bt-end">结束时间</label>
        <input id="bt-end" v-model="form.end" type="datetime-local">
      </div>
      <div class="field">
        <label for="bt-combos">combos 抽样组合数</label>
        <input id="bt-combos" v-model="form.combos" type="number" min="1" max="2000" step="1">
        <p class="hint">默认 400，上限 2000</p>
      </div>
      <div class="field">
        <label for="bt-topn">topN 精调保留数</label>
        <input id="bt-topn" v-model="form.topN" type="number" min="1" max="20" step="1">
        <p class="hint">默认 5，上限 20</p>
      </div>
      <div class="field">
        <label for="bt-margin">marginUsd 单币保证金</label>
        <input id="bt-margin" v-model="form.margin" type="number" min="1" step="1">
        <p class="hint">默认 100（缺省取配置 capital-per-coin）</p>
      </div>
      <div class="field">
        <label for="bt-maxhold">maxHoldHours 最长持仓</label>
        <input id="bt-maxhold" v-model="form.maxHold" type="number" min="1" max="8760" step="1">
        <p class="hint">默认 48 小时</p>
      </div>
      <div class="field">
        <label for="bt-coinlimit">coinLimit</label>
        <input id="bt-coinlimit" v-model="form.coinLimit" type="number" min="1" max="50" step="1">
        <p class="hint">instIds 留空时取前 N 个 live SWAP，上限 50</p>
      </div>
    </div>

    <section class="range-block">
      <div class="range-head">
        <h3>参数范围（可选）</h3>
        <span class="hint">全留空 = 内置 648 组网格；只填部分维度时，其余维度仍用内置取值</span>
      </div>
      <div class="range-presets">
        <span class="hint">预设：</span>
        <button
          type="button"
          class="chip"
          :class="{ 'is-active': !form.rangesPreset }"
          @click="clearPreset"
        >
          默认
        </button>
        <button
          v-for="p in BT_PRESETS"
          :key="p.key"
          type="button"
          class="chip"
          :class="{ 'is-active': form.rangesPreset === p.key }"
          :title="p.note + '（约 ' + p.combos + ' 组）'"
          @click="applyPreset(p.key)"
        >
          {{ p.label }}
        </button>
      </div>
      <div class="range-grid">
        <div v-for="meta in BT_RANGE_META" :key="meta.key" class="range-dim">
          <label :for="'bt-range-' + meta.key + '-min'">{{ meta.label }}</label>
          <div class="range-inputs">
            <input
              :id="'bt-range-' + meta.key + '-min'"
              v-model="form.ranges[meta.key].min"
              type="number"
              :step="meta.integral ? 1 : 'any'"
              :placeholder="'min ' + ph(meta, 0)"
            >
            <input
              :id="'bt-range-' + meta.key + '-max'"
              v-model="form.ranges[meta.key].max"
              type="number"
              :step="meta.integral ? 1 : 'any'"
              :placeholder="'max ' + ph(meta, 1)"
            >
            <input
              :id="'bt-range-' + meta.key + '-step'"
              v-model="form.ranges[meta.key].step"
              type="number"
              :step="meta.integral ? 1 : 'any'"
              :placeholder="'步长 ' + ph(meta, 2)"
            >
          </div>
        </div>
      </div>
      <div class="range-sides">
        <span class="hint">方向：</span>
        <label class="range-side">
          <input v-model="form.ranges.sides" type="checkbox" :value="BT_SIDE_LONG">
          做多
        </label>
        <label class="range-side">
          <input v-model="form.ranges.sides" type="checkbox" :value="BT_SIDE_SHORT">
          做空
        </label>
        <span class="hint">只勾一个 = 单向；两个都勾或都不勾 = 双向</span>
      </div>
      <p class="range-estimate" :class="{ 'is-over': rangeOverLimit }">
        预计组合数：{{ rangeCombos }}（上限 {{ BT_MAX_COMBOS }}，默认抽样 {{ BT_DEFAULT_SAMPLE_SIZE }}）
        <template v-if="rangeOverLimit">；超出上限会按上限抽样，并在批次 request 里标记 rangeTruncated</template>
      </p>
    </section>

    <div class="form-actions">
      <div class="quick-group">
        <span class="hint">快捷区间：</span>
        <button class="chip" type="button" @click="emit('quick', 7)">近 7 天</button>
        <button class="chip" type="button" @click="emit('quick', 30)">近 30 天</button>
        <button class="chip" type="button" @click="emit('quick', 90)">近 90 天</button>
        <button class="chip" type="button" @click="emit('clear')">清空时间</button>
      </div>
      <div class="btn-group">
        <button class="btn" type="button" @click="emit('sample')">小样本预设</button>
        <button class="btn btn-primary" type="submit" :disabled="busy" :aria-busy="busy ? 'true' : 'false'">创建回测</button>
      </div>
    </div>
    <p class="hint" style="margin-top: 8px">
      窗口最短 7 天；起止时间留空 = 结束于 now、开始于 now-90 天。已有 PENDING/RUNNING 批次时提交返回 409。
    </p>
  </form>
</template>

<style scoped>
/* 参数范围区块：复用全局 .chip/.hint/.field 风格，只补布局 */
.range-block {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed var(--border);
}

.range-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}

.range-head h3 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-soft);
}

.range-presets {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin: 8px 0 10px;
}

.chip.is-active {
  border-style: solid;
  border-color: var(--brand);
  background: var(--brand-soft);
  color: var(--brand);
}

.range-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 10px 14px;
}

.range-dim {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.range-dim > label {
  font-size: 12.5px;
  color: var(--text-soft);
  font-weight: 500;
}

.range-inputs {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 6px;
}

.range-inputs input {
  padding: 5px 7px;
  font-size: 12px;
}

.range-sides {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 10px;
}

.range-side {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12.5px;
  color: var(--text-soft);
  cursor: pointer;
}

.range-side input {
  width: auto;
  margin: 0;
}

.range-estimate {
  margin: 8px 0 0;
  font-size: 11.5px;
  color: var(--muted);
}

.range-estimate.is-over {
  color: var(--danger, #d9534f);
}
</style>
