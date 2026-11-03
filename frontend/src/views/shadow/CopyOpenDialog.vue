<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { apiGet, apiPost } from '../../api/client'
import { EP } from '../../api/endpoints'
import { fmtMoney, fmtNum, isNil } from '../../lib/format'

/*
 * 复制开仓弹窗：把一笔真实（或手工填写）的开仓意图复制进本地影子账本。
 * 默认张数/保证金取该合约的平台最小下单量（open-preflight），用户可在弹窗里改参数；
 * 提交只会写 okx_shadow_position / okx_shadow_order，不会向 OKX 发单。
 */
const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'opened', payload: any): void }>()

/* 表单 */
const instId = ref('')
const side = ref<'long' | 'short'>('long')
const szText = ref('')
const pxText = ref('')
const leverage = ref(5)
const takeProfitPct = ref(2)
const stopLossPct = ref(25)
const maxLayers = ref(5)
const szEdited = ref(false)
const pxEdited = ref(false)

/* 最小下单量预检 */
const preflight = ref<any>(null)
const pfLoading = ref(false)
const pfError = ref('')
const notice = computed(() => (preflight.value && preflight.value.notice ? String(preflight.value.notice) : ''))
const minSzText = computed(() => (preflight.value && !isNil(preflight.value.minSz) ? fmtNum(preflight.value.minSz) : '—'))
const marginText = computed(() =>
  preflight.value && !isNil(preflight.value.suggestedMarginUsd) ? fmtMoney(preflight.value.suggestedMarginUsd) : '—'
)
const leverageMax = computed(() => (preflight.value && !isNil(preflight.value.leverageMax) ? Number(preflight.value.leverageMax) : 125))

/* 最近真实订单 */
const recent = ref<any[]>([])
const recentLoading = ref(false)
const recentError = ref('')
const recentPick = ref('')

const submitting = ref(false)
const error = ref('')

const canSubmit = computed(() => {
  if (submitting.value) return false
  if (instId.value.trim() === '') return false
  if (preflight.value && preflight.value.tradable === false) return false
  return true
})

function errText(err: any): string {
  if (!err) return '未知错误'
  if (typeof err === 'string') return err
  return err.message ? String(err.message) : '未知错误'
}

function resetForm(): void {
  instId.value = ''
  side.value = 'long'
  szText.value = ''
  pxText.value = ''
  leverage.value = 5
  takeProfitPct.value = 2
  stopLossPct.value = 25
  maxLayers.value = 5
  szEdited.value = false
  pxEdited.value = false
  preflight.value = null
  pfError.value = ''
  recentPick.value = ''
  error.value = ''
}

async function loadPreflight(): Promise<void> {
  const id = instId.value.trim()
  if (!id) {
    preflight.value = null
    pfError.value = ''
    return
  }
  pfLoading.value = true
  pfError.value = ''
  try {
    const pf = await apiGet<any>(EP.strategyShadowOpenPreflight, { instId: id })
    // 期间合约被改掉就丢弃这次结果，避免串数据
    if (instId.value.trim() !== id) return
    preflight.value = pf || null
    if (pf && !szEdited.value && !isNil(pf.suggestedSz)) szText.value = String(pf.suggestedSz)
    if (pf && !pxEdited.value && !isNil(pf.lastPx)) pxText.value = String(pf.lastPx)
  } catch (err: any) {
    preflight.value = null
    pfError.value = '最小下单量查询失败：' + errText(err)
  } finally {
    pfLoading.value = false
  }
}

async function loadRecent(): Promise<void> {
  if (recentLoading.value) return
  if (recent.value.length) return
  recentLoading.value = true
  recentError.value = ''
  try {
    const data = await apiGet<any>(EP.accountOrders, { state: 'history', instType: 'SWAP', limit: 100 })
    const items = data && Array.isArray(data.items) ? data.items : []
    recent.value = items.filter((it: any) => it && it.instId)
  } catch (err: any) {
    recentError.value = '最近真实订单加载失败：' + errText(err)
  } finally {
    recentLoading.value = false
  }
}

/* 选中历史订单后回填合约/方向/杠杆，再按所选合约重查最小下单量（价格与张数由 preflight 用最新价与最小张数覆盖） */
function onRecentPick(): void {
  const idx = Number(recentPick.value)
  if (!isFinite(idx) || idx < 0 || idx >= recent.value.length) return
  const it = recent.value[idx] || {}
  if (it.instId) instId.value = String(it.instId)
  const ps = String(it.posSide || '').toLowerCase()
  if (ps === 'long' || ps === 'short') {
    side.value = ps
  } else {
    side.value = String(it.side || '').toLowerCase() === 'sell' ? 'short' : 'long'
  }
  const px = Number(!isNil(it.avgPx) && Number(it.avgPx) > 0 ? it.avgPx : it.px)
  if (isFinite(px) && px > 0) {
    pxText.value = String(px)
    pxEdited.value = false
  }
  const lv = Number(it.lever)
  if (isFinite(lv) && lv >= 1) leverage.value = Math.min(125, Math.round(lv))
  void loadPreflight()
}

async function submit(): Promise<void> {
  if (!canSubmit.value) return
  error.value = ''
  submitting.value = true
  const body: any = {
    instId: instId.value.trim(),
    side: side.value,
    leverage: Number(leverage.value),
    params: {
      takeProfitPct: Number(takeProfitPct.value),
      stopLossPct: Number(stopLossPct.value),
      maxLayers: Number(maxLayers.value)
    }
  }
  // 留空代表用平台最小张数 / 最新价，由后端补齐
  if (szText.value.trim() !== '') body.sz = Number(szText.value)
  if (pxText.value.trim() !== '') body.px = Number(pxText.value)
  try {
    const res = await apiPost<any>(EP.strategyShadowOpen, body)
    emit('opened', res || {})
    emit('close')
  } catch (err: any) {
    error.value = '复制开仓失败：' + errText(err)
  } finally {
    submitting.value = false
  }
}

function onKeydown(e: KeyboardEvent): void {
  if (!props.open) return
  if (e.key === 'Escape') emit('close')
}

watch(
  () => props.open,
  (v) => {
    if (!v) return
    resetForm()
    void loadRecent()
  }
)

onMounted(() => document.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => document.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div v-if="open" class="modal-backdrop" data-dialog="shadow-copy-open">
    <div class="modal" role="dialog" aria-modal="true" aria-labelledby="copy-open-title">
      <h3 id="copy-open-title">复制开仓（影子账本）</h3>
      <div class="modal-body">
        <div class="alert ok">只写入影子账本，不会向 OKX 发单。默认张数按该合约的平台最小下单量计算。</div>

        <div class="form-grid form-grid-tight">
          <div class="field">
            <label for="copy-open-inst">合约 instId</label>
            <input
              id="copy-open-inst"
              v-model="instId"
              type="text"
              placeholder="例如 BTC-USDT-SWAP"
              data-field="instId"
              @change="loadPreflight"
            />
            <p class="hint">
              <template v-if="pfLoading">正在查询最小下单量…</template>
              <template v-else-if="preflight">建议保证金 {{ marginText }} USD · 杠杆上限 {{ leverageMax }}</template>
              <template v-else>填写后自动查询平台最小下单量</template>
            </p>
          </div>
          <div class="field">
            <label for="copy-open-recent">从最近真实订单选择</label>
            <select id="copy-open-recent" v-model="recentPick" data-field="recent" @change="onRecentPick">
              <option value="">（可选）最近 100 笔历史订单</option>
              <option v-for="(it, i) in recent" :key="i" :value="String(i)">
                {{ it.instId }} · {{ it.posSide || it.side }} · {{ it.sz }} 张 · {{ it.avgPx || it.px || '—' }}
              </option>
            </select>
            <p v-if="recentError" class="hint">{{ recentError }}</p>
            <p v-else-if="recentLoading" class="hint">正在加载最近真实订单…</p>
            <p v-else class="hint">选中后会回填合约/方向/杠杆，价格与张数按该合约的最新价与平台最小张数刷新</p>
          </div>
        </div>

        <div v-if="notice" class="alert" data-role="preflight-notice">{{ notice }}</div>
        <div v-else-if="pfError" class="alert danger">{{ pfError }}</div>

        <div class="form-grid form-grid-tight">
          <div class="field">
            <label>方向</label>
            <div class="btn-group">
              <label class="chip">
                <input v-model="side" type="radio" value="long" data-field="side-long" />
                <span>做多</span>
              </label>
              <label class="chip">
                <input v-model="side" type="radio" value="short" data-field="side-short" />
                <span>做空</span>
              </label>
            </div>
          </div>
          <div class="field">
            <label for="copy-open-sz">张数</label>
            <input
              id="copy-open-sz"
              v-model="szText"
              type="text"
              inputmode="decimal"
              :placeholder="preflight && !isNil(preflight.suggestedSz) ? String(preflight.suggestedSz) : '留空=平台最小张数'"
              data-field="sz"
              @input="szEdited = true"
            />
            <p class="hint">平台最小张数 minSz：{{ minSzText }}</p>
          </div>
          <div class="field">
            <label for="copy-open-px">开仓价格</label>
            <input
              id="copy-open-px"
              v-model="pxText"
              type="text"
              inputmode="decimal"
              placeholder="留空=最新价"
              data-field="px"
              @input="pxEdited = true"
            />
            <p class="hint">留空则用最新标记价</p>
          </div>
          <div class="field">
            <label for="copy-open-lev">杠杆</label>
            <input id="copy-open-lev" v-model.number="leverage" type="number" min="1" max="125" data-field="leverage" />
            <p class="hint">1 ~ 125 倍</p>
          </div>
          <div class="field">
            <label for="copy-open-tp">止盈 %</label>
            <input id="copy-open-tp" v-model.number="takeProfitPct" type="number" step="0.1" data-field="takeProfitPct" />
            <p class="hint">相对保证金的收益率</p>
          </div>
          <div class="field">
            <label for="copy-open-sl">止损 %</label>
            <input id="copy-open-sl" v-model.number="stopLossPct" type="number" step="0.1" data-field="stopLossPct" />
            <p class="hint">相对保证金的亏损率</p>
          </div>
          <div class="field">
            <label for="copy-open-layers">最大层数</label>
            <input id="copy-open-layers" v-model.number="maxLayers" type="number" min="1" data-field="maxLayers" />
            <p class="hint">逆势加仓的最大层数</p>
          </div>
        </div>

        <div v-if="error" class="alert danger" data-role="open-error">{{ error }}</div>
      </div>
      <div class="modal-actions">
        <button class="btn" type="button" :disabled="submitting" @click="emit('close')">取消</button>
        <button
          class="btn btn-primary"
          type="button"
          :disabled="!canSubmit"
          :aria-busy="submitting ? 'true' : 'false'"
          data-action="submit"
          @click="submit"
        >
          {{ submitting ? '提交中…' : '写入影子账本' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.modal-body .form-grid {
  margin-bottom: 12px;
}
.modal-body .alert {
  margin-bottom: 12px;
}
.modal-body .chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.modal-body .chip input {
  margin: 0;
}
</style>
