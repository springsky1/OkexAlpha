<script setup lang="ts">
/* 提交区：1:1 复刻 legacy index.html#form-backtest + app.js 3544-3630 */
import InstPicker from '../../components/InstPicker.vue'
import { BARS } from '../../lib/labels'
import type { BtSubmitFormState } from './btLogic'

defineProps<{ form: BtSubmitFormState; busy: boolean }>()
const emit = defineEmits<{ submit: []; sample: []; quick: [days: number]; clear: [] }>()
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
