<script setup lang="ts">
/* 账户概览（legacy index.html §13 / app.js §16a 1:1 移植）
   只调只读接口：summary / balance / positions；三块各自独立加载，互不阻塞。 */
import { computed, onMounted, reactive, ref } from 'vue'
import { apiGet } from '../api/client'
import { EP } from '../api/endpoints'
import { esc, fmtTime, isNil } from '../lib/format'
import { useUiStore } from '../stores/ui'
import DataTable from '../components/DataTable.vue'
import StateBlock from '../components/StateBlock.vue'
import {
  ACC_BALANCE_COLS,
  ACC_NA,
  ACC_POSITION_COLS,
  accBool,
  accInt,
  accIssueText,
  accMoney,
  accNum,
  accNoticeText,
  accTime,
  collectErrors,
  kv,
  kvHtml,
  type AccountBalance,
  type AccountPositions,
  type AccountSummary,
  type KvPair
} from './account/acc'

type Block = 'summary' | 'balance' | 'positions'

const ui = useUiStore()

const summary = ref<AccountSummary | null>(null)
const balance = ref<AccountBalance | null>(null)
const positions = ref<AccountPositions | null>(null)

const loading = reactive<Record<Block, boolean>>({ summary: true, balance: true, positions: true })
const loadError = reactive<Record<Block, string>>({ summary: '', balance: '', positions: '' })

const topError = ref('')
const updated = ref(ACC_NA)
const busy = ref(false)

const summaryIssue = computed(() => (summary.value ? accIssueText(summary.value) : ''))
const balanceIssue = computed(() => (balance.value ? accIssueText(balance.value) : ''))
const positionsIssue = computed(() => (positions.value ? accIssueText(positions.value) : ''))

/* 模式 / 凭证 / 抓取时间一行摘要 */
const summaryHead = computed(() => {
  const s = summary.value
  if (!s) return ''
  return (
    '模式 ' + (isNil(s.mode) ? ACC_NA : String(s.mode)) +
    ' · 凭证已配置 ' + (s.configured ? '是' : '否') +
    ' · 抓取时间 ' + accTime(s.fetchedAt)
  )
})

const summaryNotice = computed(() => accNoticeText(summary.value))

const summaryPairs = computed<KvPair[]>(() => {
  const s = summary.value || {}
  const cred = s.credentials || {}
  const acc = s.account || {}
  return [
    kv('凭证标签 label', cred.label),
    kvHtml('apiKey（脱敏）', '<span class="mono">' + esc(isNil(cred.apiKey) ? ACC_NA : cred.apiKey) + '</span>'),
    kv('apiKey 已配置', cred.apiKeyConfigured),
    kv('secretKey 已配置', cred.secretKeyConfigured),
    kv('passphrase 已配置', cred.passphraseConfigured),
    kv('凭证来源 source', cred.source),
    kv('权限 perm', acc.perm),
    kv('账户模式 acctLv', acc.acctLv),
    kv('持仓模式 posMode', acc.posMode),
    kv('自成交保护 acctStpMode', acc.acctStpMode),
    kvHtml('自动借币 autoLoan', '<span>' + esc(accBool(acc.autoLoan)) + '</span>'),
    kvHtml('uid（脱敏）', '<span class="mono">' + esc(isNil(acc.uid) ? ACC_NA : acc.uid) + '</span>'),
    kvHtml('主账户 mainUid（脱敏）', '<span class="mono">' + esc(isNil(acc.mainUid) ? ACC_NA : acc.mainUid) + '</span>'),
    kv('KYC 等级 kycLv', acc.kycLv),
    kv('等级 level', acc.level)
  ]
})

const permissionNote = computed(() => {
  const s = summary.value
  return s && !isNil(s.permissionNote) ? String(s.permissionNote) : ''
})

const balanceDetails = computed(() => (balance.value && Array.isArray(balance.value.details) ? balance.value.details : []))

const balancePairs = computed<KvPair[]>(() => {
  const d = balance.value || {}
  return [
    kv('总权益 totalEq', accMoney(d.totalEq)),
    kv('isoEq', accMoney(d.isoEq)),
    kv('adjEq', accMoney(d.adjEq)),
    kv('availEq', accMoney(d.availEq)),
    kv('保证金率 mgnRatio', accNum(d.mgnRatio)),
    kv('初始保证金 imr', accMoney(d.imr)),
    kv('维持保证金 mmr', accMoney(d.mmr)),
    kv('名义 notionalUsd', accMoney(d.notionalUsd)),
    kv('更新时间 uTime', accTime(d.uTime)),
    kv('币种数 details', accInt(balanceDetails.value.length))
  ]
})

const positionRows = computed(() => (positions.value && Array.isArray(positions.value.items) ? positions.value.items : []))

function errMsg(err: unknown): string {
  const e = err as { message?: unknown } | null
  return e && e.message ? String(e.message) : String(err)
}

async function loadSummary(): Promise<void> {
  loadError.summary = ''
  try {
    const d = await apiGet<AccountSummary>(EP.accountSummary)
    summary.value = d && typeof d === 'object' ? d : {}
  } catch (err) {
    if (!summary.value) loadError.summary = '凭证信息加载失败：' + errMsg(err)
    throw err
  } finally {
    loading.summary = false
  }
}

async function loadBalance(): Promise<void> {
  loadError.balance = ''
  try {
    const d = await apiGet<AccountBalance>(EP.accountBalance)
    balance.value = d && typeof d === 'object' ? d : {}
  } catch (err) {
    if (!balance.value) loadError.balance = '余额加载失败：' + errMsg(err)
    throw err
  } finally {
    loading.balance = false
  }
}

async function loadPositions(): Promise<void> {
  loadError.positions = ''
  try {
    const d = await apiGet<AccountPositions>(EP.accountPositions, { instType: 'SWAP' })
    positions.value = d && typeof d === 'object' ? d : {}
  } catch (err) {
    if (!positions.value) loadError.positions = '持仓加载失败：' + errMsg(err)
    throw err
  } finally {
    loading.positions = false
  }
}

/* 三个接口并发，单个失败不拖垮其它两块；失败信息汇总到顶部告警 */
async function loadAll(manual: boolean): Promise<void> {
  topError.value = ''
  if (manual) busy.value = true
  const results = await Promise.all([
    loadSummary().catch((e) => e),
    loadBalance().catch((e) => e),
    loadPositions().catch((e) => e)
  ])
  if (manual) busy.value = false
  const msg = collectErrors(results)
  if (msg) topError.value = '部分数据加载失败：' + msg
  updated.value = '刷新于 ' + fmtTime(Date.now())
  if (manual) ui.toast(msg ? '刷新完成，但有接口失败' : '账户数据已刷新', msg ? 'err' : 'ok')
}

function refreshNow(): void {
  void loadAll(true)
}

onMounted(() => {
  void loadAll(false)
})
</script>

<template>
  <section class="page" data-page="account">
    <div class="page-head">
      <div>
        <h1>账户概览</h1>
        <p class="muted">OKX 只读账户信息：凭证脱敏展示、账户模式、余额明细与交易所持仓。本页只调用只读接口，不会下单 / 撤单 / 划转。</p>
      </div>
      <div class="page-actions">
        <span class="hint" id="account-updated">{{ updated }}</span>
        <button class="btn btn-sm" type="button" id="account-refresh" :aria-busy="busy ? 'true' : undefined" :disabled="busy" @click="refreshNow">刷新</button>
      </div>
    </div>

    <div v-if="topError" class="alert danger" id="account-error">{{ topError }}</div>

    <div class="card">
      <div class="card-head">
        <h2>凭证与账户</h2>
        <span class="muted">GET /api/v1/account/summary</span>
      </div>
      <div id="account-summary">
        <StateBlock v-if="loadError.summary && !summary" kind="error" :text="loadError.summary" />
        <StateBlock v-else-if="!summary" kind="loading" text="加载中…" />
        <div v-else-if="summaryIssue" class="alert danger">{{ summaryIssue }}</div>
        <template v-else>
          <p class="hint">{{ summaryHead }}</p>
          <div v-if="summaryNotice" class="alert danger">{{ summaryNotice }}</div>
          <div class="kv-grid">
            <div v-for="p in summaryPairs" :key="p.key" class="kv">
              <span class="kv-key">{{ p.key }}</span>
              <span class="kv-val" v-html="p.html"></span>
            </div>
          </div>
          <p v-if="permissionNote" class="hint">{{ permissionNote }}</p>
        </template>
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <h2>账户余额</h2>
        <span class="muted">GET /api/v1/account/balance</span>
      </div>
      <div id="account-balance">
        <StateBlock v-if="loadError.balance && !balance" kind="error" :text="loadError.balance" />
        <StateBlock v-else-if="!balance" kind="loading" text="加载中…" />
        <div v-else-if="balanceIssue" class="alert danger">{{ balanceIssue }}</div>
        <div v-else class="kv-grid">
          <div v-for="p in balancePairs" :key="p.key" class="kv">
            <span class="kv-key">{{ p.key }}</span>
            <span class="kv-val" v-html="p.html"></span>
          </div>
        </div>
      </div>
      <div id="account-balance-table" class="table-wrap">
        <StateBlock v-if="loadError.balance && !balance" kind="empty" text="余额明细不可用" />
        <StateBlock v-else-if="!balance" kind="loading" text="加载中…" />
        <StateBlock v-else-if="balanceIssue" kind="empty" text="余额明细不可用" />
        <DataTable v-else :cols="ACC_BALANCE_COLS" :rows="balanceDetails" empty-text="账户下暂无币种余额" />
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <h2>交易所持仓</h2>
        <span class="muted">GET /api/v1/account/positions?instType=SWAP</span>
      </div>
      <div id="account-positions" class="table-wrap">
        <StateBlock v-if="loadError.positions && !positions" kind="error" :text="loadError.positions" />
        <StateBlock v-else-if="!positions" kind="loading" text="加载中…" />
        <div v-else-if="positionsIssue" class="alert danger">{{ positionsIssue }}</div>
        <DataTable v-else :cols="ACC_POSITION_COLS" :rows="positionRows" empty-text="当前无持仓" />
      </div>
    </div>
  </section>
</template>
