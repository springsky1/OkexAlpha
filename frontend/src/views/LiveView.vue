<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { apiGet, apiPost } from '../api/client'
import { EP } from '../api/endpoints'
import { esc, fmtInt, fmtTime, isNil } from '../lib/format'
import { useUiStore } from '../stores/ui'
import { usePolling } from '../composables/usePolling'
import DataTable from '../components/DataTable.vue'
import StateBlock from '../components/StateBlock.vue'
import InstPicker from '../components/InstPicker.vue'
import type { Col } from './shadow/logic'
import { liveBlockerHtml } from './live/logic'
import {
  LIVE_POLL_MS,
  buildOrderBody,
  confirmBodyHtml,
  liveAccountModel,
  liveCapEntries,
  liveChecksCols,
  liveChecksMeta,
  liveDynamicCols,
  liveGateModel,
  orderErrorHtml,
  orderResultHtml,
  validateOrder
} from './live/logic'
import type { LiveOrderInput, ValidateField } from './live/logic'

const ui = useUiStore()
const auto = ref(true)
const refreshing = ref(false)
const submitting = ref(false)
const error = ref('')
const updatedAt = ref('')

/* 门禁 */
const gateData = ref<any>(null)
const gateLoading = ref(true)
const gateFailed = ref(false)
const gateError = ref('')

/* 账户 */
const account = ref<any>(null)
const accountLoading = ref(true)
const accountFailed = ref(false)
const accountError = ref('')

/* 下单结果 */
const resultHtml = ref('')

const checkCols = liveChecksCols()
const form = reactive<LiveOrderInput>({
  instId: '',
  side: 'buy',
  sz: '1',
  ordType: 'market',
  px: '',
  tdMode: 'isolated',
  reduceOnly: false,
  confirm: ''
})

const instBox = ref<HTMLElement | null>(null)
const szInput = ref<HTMLInputElement | null>(null)
const pxInput = ref<HTMLInputElement | null>(null)

const gate = computed(() => liveGateModel(gateData.value))
const gateHtml = computed(() => {
  if (!gateData.value) return ''
  const g = gateData.value || {}
  const model = gate.value
  const blockers = Array.isArray(g.blockers) ? g.blockers : []
  let html = '<div class="live-gate">'
  html +=
    '<div class="live-gate-cell"><div class="stat-label">当前模式 mode</div>' +
    '<div class="live-gate-mode">' + esc(model.mode) + '</div>' +
    '<div class="stat-sub">' + model.modeSub + '</div></div>'
  html +=
    '<div class="live-gate-cell"><div class="stat-label">放行结果 liveAllowed</div>' +
    '<div class="live-gate-flag ' + (model.allowed ? 'is-allow' : 'is-block') + '">' + model.flagText + '</div>' +
    '<div class="stat-sub">' + model.flagSub + '</div></div>'
  html +=
    '<div class="live-gate-cell"><div class="stat-label">判定</div>' +
    '<div class="live-gate-verdict"><span class="badge" data-status="' +
    (model.allowed ? 'PASS' : 'BLOCK') + '">' + (model.allowed ? 'PASS' : 'BLOCK') + '</span>' +
    '<span class="live-gate-verdict-text">' + model.verdictText + '</span></div>' +
    '<div class="stat-sub">' + model.verdictSub + '</div></div>'
  html += '</div>'
  if (blockers.length) {
    html +=
      '<div class="live-blockers-box"><div class="live-blockers-title">拦截原因 blockers（' +
      fmtInt(blockers.length) + ' 项）</div><ul class="live-blockers">'
    for (let i = 0; i < blockers.length; i++) html += '<li>' + liveBlockerHtml(blockers[i]) + '</li>'
    html += '</ul></div>'
  } else if (model.allowed) {
    html += '<div class="live-gate-ok">没有拦截项：模式、开关、密钥、风控上限都已通过。</div>'
  }
  if (!model.allowed && model.isShadow) {
    html +=
      '<p class="hint live-gate-hint">提示：把环境切到 DEMO/REAL 并打开实盘开关、配置 API 密钥后，' +
      '这里才可能变成 ALLOW；在 SHADOW 下它永远是 BLOCK，下单接口的 422 是预期结果，不是故障。</p>'
  }
  return html
})

const checks = computed<any[]>(() => {
  const g = gateData.value
  return g && Array.isArray(g.checks) ? g.checks : []
})
const checksMeta = computed(() => liveChecksMeta(checks.value))
const capEntries = computed(() => liveCapEntries(gateData.value ? gateData.value.caps : null))

const acct = computed(() => liveAccountModel(account.value))
const balance = computed<any[]>(() => {
  const a = account.value
  return a && Array.isArray(a.balance) ? a.balance : []
})
const positions = computed<any[]>(() => {
  const a = account.value
  return a && Array.isArray(a.positions) ? a.positions : []
})
const balanceCols = computed<Col[]>(() => liveDynamicCols(balance.value))
const positionCols = computed<Col[]>(() => liveDynamicCols(positions.value))

function errText(err: any): string {
  return err && err.message ? String(err.message) : String(err)
}

async function loadGate(): Promise<void> {
  if (!gateData.value) gateLoading.value = true
  try {
    const data = await apiGet<any>(EP.strategyLiveGate)
    gateData.value = data && typeof data === 'object' ? data : {}
    gateFailed.value = false
  } catch (err: any) {
    gateFailed.value = true
    gateError.value = '门禁加载失败：' + errText(err)
    throw err
  } finally {
    gateLoading.value = false
  }
}

async function loadAccount(): Promise<void> {
  if (!account.value) accountLoading.value = true
  try {
    const data = await apiGet<any>(EP.strategyLiveAccount)
    account.value = data && typeof data === 'object' ? data : {}
    accountFailed.value = false
  } catch (err: any) {
    accountFailed.value = true
    accountError.value = '账户加载失败：' + errText(err)
    throw err
  } finally {
    accountLoading.value = false
  }
}

async function loadAll(manual: boolean): Promise<void> {
  error.value = ''
  if (manual) refreshing.value = true
  const results = await Promise.all([
    loadGate().catch((e: unknown) => e),
    loadAccount().catch((e: unknown) => e)
  ])
  if (manual) refreshing.value = false
  const parts: string[] = []
  for (let i = 0; i < results.length; i++) {
    const v = results[i]
    if (v instanceof Error && v.message) parts.push(v.message)
  }
  const msg = parts.join('；')
  if (msg) error.value = '部分数据加载失败：' + msg
  updatedAt.value = '刷新于 ' + fmtTime(Date.now())
  if (manual) ui.toast(msg ? '刷新完成，但有接口失败' : '门禁数据已刷新', msg ? 'err' : 'ok')
}

function refreshNow(): void {
  void loadAll(true)
}

function focusField(field: ValidateField): void {
  if (field === 'instId') {
    const el = instBox.value ? (instBox.value.querySelector('input,textarea') as HTMLElement | null) : null
    if (el) el.focus()
  } else if (field === 'sz') {
    if (szInput.value) szInput.value.focus()
  } else if (pxInput.value) {
    pxInput.value.focus()
  }
}

async function submitOrder(): Promise<void> {
  if (submitting.value) return
  const input: LiveOrderInput = {
    instId: form.instId,
    side: form.side,
    sz: form.sz,
    ordType: form.ordType,
    px: form.px,
    tdMode: form.tdMode,
    reduceOnly: form.reduceOnly,
    confirm: form.confirm
  }
  const v = validateOrder(input)
  if (!v.ok) {
    ui.toast(v.msg, 'err')
    focusField(v.field)
    return
  }
  const g = gateData.value || {}
  const mode = isNil(g.mode) ? '-' : String(g.mode)
  const allowed = !!g.liveAllowed
  const ok = await ui.confirmDialog('确认提交下单请求？', confirmBodyHtml(input, allowed, mode), '确认提交')
  if (!ok) return
  submitting.value = true
  try {
    const res = await apiPost<any>(EP.strategyLiveOrders, buildOrderBody(input))
    resultHtml.value = orderResultHtml(res || {})
    ui.toast('下单请求已被接受', 'ok')
    void loadGate().catch(() => undefined)
  } catch (err: any) {
    resultHtml.value = orderErrorHtml(err)
    if (err && err.status === 422) ui.toast('被门禁拦截（符合预期）：HTTP 422', 'info')
    else ui.toast('下单失败：' + errText(err), 'err')
  } finally {
    submitting.value = false
  }
}

const polling = usePolling(() => loadAll(false), LIVE_POLL_MS, { enabled: auto, immediate: false })
watch(auto, () => polling.restart())

onMounted(() => {
  void loadAll(false)
})
</script>

<template>
  <section class="page" data-page="live">
    <div class="page-head">
      <div>
        <h1>实盘门禁</h1>
        <p class="muted">
          实盘下单前必须过门禁（模式 / 开关 / 密钥 / 持仓上限 / 当日名义 / 健康度 / 补拉负载 / 行情新鲜度 / 接口错误率）。当前为 SHADOW 环境，门禁必然 BLOCK、下单接口以 HTTP 422 拒绝——这是预期行为，不是故障。
        </p>
      </div>
      <div class="page-actions">
        <span class="hint">{{ updatedAt || '—' }}</span>
        <label class="switch"><input v-model="auto" type="checkbox" /><span>每 10 秒自动刷新</span></label>
        <button class="btn btn-sm" type="button" :disabled="refreshing" :aria-busy="refreshing ? 'true' : 'false'" @click="refreshNow">
          刷新
        </button>
      </div>
    </div>

    <div v-if="error" class="alert danger">{{ error }}</div>

    <div class="card">
      <div class="card-head">
        <h2>门禁总览</h2>
        <span class="muted">GET /api/v1/strategy/live/gate</span>
      </div>
      <StateBlock v-if="gateLoading && !gateData" kind="loading" text="加载中…" />
      <StateBlock v-else-if="gateFailed && !gateData" kind="error" :text="gateError" />
      <div v-else-if="gateData" v-html="gateHtml"></div>
    </div>

    <div class="card">
      <div class="card-head">
        <h2>门禁检查明细</h2>
        <span class="muted">{{ checksMeta }}</span>
      </div>
      <div class="table-wrap">
        <StateBlock v-if="gateLoading && !gateData" kind="loading" text="加载中…" />
        <StateBlock v-else-if="gateFailed && !gateData" kind="error" :text="gateError" />
        <DataTable v-else :cols="checkCols" :rows="checks" empty-text="暂无数据" />
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <h2>风控上限 caps</h2>
        <span class="muted">gate.caps</span>
      </div>
      <div v-if="capEntries.length" class="kv-grid live-caps-grid">
        <div v-for="row in capEntries" :key="row[0]" class="kv">
          <span class="kv-key">{{ row[0] }}</span>
          <span class="kv-val">{{ row[1] }}</span>
        </div>
      </div>
      <StateBlock v-else-if="!gateData" kind="loading" text="加载中…" />
      <div v-else class="state">暂无数据（gate 未返回 caps）</div>
    </div>

    <div class="card">
      <div class="card-head">
        <h2>交易所账户</h2>
        <span class="muted">GET /api/v1/strategy/live/account</span>
      </div>
      <StateBlock v-if="accountLoading && !account" kind="loading" text="加载中…" />
      <StateBlock v-else-if="accountFailed && !account" kind="error" :text="accountError" />
      <div v-else-if="account">
        <div v-if="acct.configured" class="live-account-on">
          已配置 API 密钥（mode={{ acct.mode }}）：余额 {{ fmtInt(acct.balanceCount) }} 条 · 持仓
          {{ fmtInt(acct.positionCount) }} 条，数据来自交易所。
        </div>
        <div v-else class="live-account-off">
          <div class="live-account-off-title">未配置 API 密钥（configured=false）</div>
          <div class="live-account-off-reason">{{ acct.reason }}</div>
          <div class="live-account-off-meta mono">{{ acct.meta }}</div>
        </div>
        <div class="card-head live-account-head">
          <h2>余额 balance</h2>
          <span class="muted">{{ fmtInt(acct.balanceCount) }} 条</span>
        </div>
        <div class="table-wrap">
          <DataTable :cols="balanceCols" :rows="balance" empty-text="暂无数据" />
        </div>
        <div class="card-head live-account-head">
          <h2>持仓 positions</h2>
          <span class="muted">{{ fmtInt(acct.positionCount) }} 条</span>
        </div>
        <div class="table-wrap">
          <DataTable :cols="positionCols" :rows="positions" empty-text="暂无数据" />
        </div>
      </div>
    </div>

    <form class="card" novalidate @submit.prevent="submitOrder">
      <div class="card-head">
        <h2>受控下单</h2>
        <span class="muted">POST /api/v1/strategy/live/orders（门禁不通过返回 422）</span>
      </div>
      <div class="form-grid">
        <div ref="instBox" class="field">
          <label>instId</label>
          <InstPicker id="live-inst" v-model="form.instId" placeholder="BTC-USDT-SWAP" />
          <p class="hint">SWAP / FUTURES，聚焦展开库内合约</p>
        </div>
        <div class="field">
          <label for="live-side">side</label>
          <select id="live-side" v-model="form.side">
            <option value="buy">buy 买入</option>
            <option value="sell">sell 卖出</option>
          </select>
        </div>
        <div class="field">
          <label for="live-sz">sz 张数</label>
          <input id="live-sz" ref="szInput" v-model="form.sz" type="number" min="1" step="1" placeholder="1" />
          <p class="hint">按合约张数，不是 USD</p>
        </div>
        <div class="field">
          <label for="live-ordtype">ordType</label>
          <select id="live-ordtype" v-model="form.ordType">
            <option value="market">market</option>
            <option value="limit">limit</option>
          </select>
        </div>
        <div class="field">
          <label for="live-px">px 限价</label>
          <input id="live-px" ref="pxInput" v-model="form.px" type="number" min="0" step="any" placeholder="限价单必填" />
        </div>
        <div class="field">
          <label for="live-tdmode">tdMode</label>
          <select id="live-tdmode" v-model="form.tdMode">
            <option value="isolated">isolated 逐仓</option>
            <option value="cross">cross 全仓</option>
          </select>
        </div>
        <div class="field">
          <label for="live-confirm">confirm 二次确认口令</label>
          <input id="live-confirm" v-model="form.confirm" type="text" placeholder="REAL 模式填 CONFIRM-REAL-TRADING" autocomplete="off" />
          <p class="hint">只有 mode=REAL 才校验</p>
        </div>
        <div class="field">
          <label>reduceOnly</label>
          <label class="check inline"><input v-model="form.reduceOnly" type="checkbox" /><span>只减仓</span></label>
        </div>
      </div>
      <div class="form-actions">
        <span class="hint">提交前会弹二次确认；SHADOW 环境预期被 422 拦截。</span>
        <div class="btn-group">
          <button class="btn btn-primary" type="submit" :disabled="submitting" :aria-busy="submitting ? 'true' : 'false'">
            提交下单
          </button>
        </div>
      </div>
      <div class="result-slot" v-html="resultHtml"></div>
    </form>
  </section>
</template>
