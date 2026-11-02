import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('../src/api/client', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiRawGet: vi.fn()
}))

import { apiGet } from '../src/api/client'
import { EP } from '../src/api/endpoints'
import AccountView from '../src/views/AccountView.vue'
import {
  ACC_NA,
  accBool,
  accIssueText,
  accMoney,
  accPct,
  accPnl,
  accQty,
  accTime,
  collectErrors
} from '../src/views/account/acc'

type Mocked = { mockResolvedValue: (v: unknown) => void; mockRejectedValue: (v: unknown) => void; mockImplementation: (f: (p: string, q?: unknown) => unknown) => void }
const mocked = apiGet as unknown as Mocked

const summary = {
  configured: true,
  mode: 'SHADOW',
  credentials: {
    label: 'OkexAlpha',
    apiKey: '3610****f233',
    apiKeyConfigured: true,
    secretKeyConfigured: true,
    passphraseConfigured: true,
    source: 'env:OKX_API_KEY/OKX_SECRET_KEY/OKX_PASSPHRASE'
  },
  account: {
    uid: '57***36',
    mainUid: '57***36',
    acctLv: '2',
    posMode: 'long_short_mode',
    acctStpMode: 'cancel_maker',
    autoLoan: 'false',
    perm: 'read_only,trade',
    kycLv: '3',
    level: 'Lv1'
  },
  ok: true,
  permissionNote: '当前 API Key 只有 read_only + trade 权限。',
  fetchedAt: '2026-11-02T04:30:00Z'
}

const balance = {
  totalEq: '1189.9876',
  isoEq: '0',
  adjEq: '',
  availEq: '',
  mgnRatio: '',
  imr: '',
  mmr: '',
  notionalUsd: '',
  uTime: '1793534041145',
  details: [
    {
      ccy: 'USDT',
      eq: '1189.9876',
      eqUsd: '1189.9876',
      cashBal: '1189.9876',
      availBal: '1189.98',
      availEq: '1189.98',
      frozenBal: '0',
      ordFrozen: '0',
      upl: '13.63',
      uplLiab: '0',
      disEq: '1189.98'
    }
  ],
  ok: true
}

const positions = { instType: 'SWAP', items: [], ok: true }

function mockAccount() {
  mocked.mockImplementation((path: string) => {
    if (path === EP.accountSummary) return Promise.resolve(summary)
    if (path === EP.accountBalance) return Promise.resolve(balance)
    if (path === EP.accountPositions) return Promise.resolve(positions)
    return Promise.reject(new Error('unexpected path ' + path))
  })
}

function mountView() {
  return mount(AccountView, { global: { plugins: [createPinia()] } })
}

describe('account 页格式化（legacy §16 1:1）', () => {
  it('金额固定 2 位小数且带千分位，空值显示 —', () => {
    expect(accMoney(1189.9876)).toBe('1,189.99')
    expect(accMoney(100)).toBe('100.00')
    expect(accMoney(0)).toBe('0.00')
    expect(accMoney(null)).toBe(ACC_NA)
    expect(accMoney('')).toBe(ACC_NA)
    expect(accMoney('abc')).toBe('abc')
  })

  it('币数量最多 6 位小数', () => {
    expect(accQty('0.12345678')).toBe('0.123457')
    expect(accQty(0.5)).toBe('0.5')
    expect(accQty(undefined)).toBe(ACC_NA)
  })

  it('比率转百分比 2 位，signed 时正数带 +', () => {
    expect(accPct(0.0158, true)).toBe('+1.58%')
    expect(accPct(-0.0158, true)).toBe('-1.58%')
    expect(accPct(0.0158)).toBe('1.58%')
    expect(accPct(null)).toBe(ACC_NA)
  })

  it('盈亏正绿负红', () => {
    expect(accPnl(13.63)).toBe('<span class="val-pos">+13.63</span>')
    expect(accPnl(-1.5)).toBe('<span class="val-neg">-1.50</span>')
    expect(accPnl(0)).toBe('0.00')
    expect(accPnl(null)).toBe(ACC_NA)
  })

  it('布尔字符串（autoLoan="false"）映射成 是/否', () => {
    expect(accBool('false')).toBe('否')
    expect(accBool(true)).toBe('是')
    expect(accBool(null)).toBe(ACC_NA)
  })

  it('毫秒与 ISO 时间都能显示成本地时间，空值 —', () => {
    expect(accTime(1793534041145)).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/)
    expect(accTime('2026-11-02T04:30:00Z')).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/)
    expect(accTime(null)).toBe(ACC_NA)
    expect(accTime('')).toBe(ACC_NA)
  })

  it('接口 error 汇总成一句话', () => {
    expect(accIssueText({ ok: true })).toBe('')
    expect(accIssueText({ ok: false })).toBe('接口返回 ok=false，未给出 error 详情')
    expect(accIssueText({ error: 'boom' })).toBe('boom')
    expect(accIssueText({ error: { code: 'E1', message: 'm', hint: 'h' } })).toBe('[E1] m 提示：h')
    expect(collectErrors([new Error('a'), 'x', new Error('b')])).toBe('a；b')
  })
})

describe('AccountView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
    mockAccount()
  })

  it('挂载即并发请求 summary / balance / positions 三个只读接口', async () => {
    const w = mountView()
    await flushPromises()
    expect(apiGet).toHaveBeenCalledWith(EP.accountSummary)
    expect(apiGet).toHaveBeenCalledWith(EP.accountBalance)
    expect(apiGet).toHaveBeenCalledWith(EP.accountPositions, { instType: 'SWAP' })
    w.unmount()
  })

  it('渲染脱敏凭证、账户模式与权限说明（不出现任何真实密钥）', async () => {
    const w = mountView()
    await flushPromises()
    const text = w.text()
    expect(text).toContain('3610****f233')
    expect(text).toContain('57***36')
    expect(text).toContain('read_only,trade')
    expect(text).toContain('long_short_mode')
    expect(text).toContain('当前 API Key 只有 read_only + trade 权限。')
    expect(text).toContain('模式 SHADOW')
    w.unmount()
  })

  it('余额卡渲染 10 项指标与币种明细表，空值显示 —', async () => {
    const w = mountView()
    await flushPromises()
    const text = w.text()
    expect(text).toContain('1,189.99')
    expect(text).toContain('USDT')
    expect(text).toContain('币种数 details')
    expect(w.findAll('#account-balance-table tbody tr').length).toBe(1)
    /* render 返回的 HTML 必须真的经 v-html 落到 DOM（正数绿） */
    expect(w.find('#account-balance-table .val-pos').text()).toBe('+13.63')
    expect(w.text()).toContain('—')
    w.unmount()
  })

  it('交易所无持仓时显示「当前无持仓」', async () => {
    const w = mountView()
    await flushPromises()
    expect(w.find('#account-positions').text()).toContain('当前无持仓')
    w.unmount()
  })

  it('data.ok=false 时卡片显示 error 详情而不是崩溃', async () => {
    mocked.mockImplementation((path: string) => {
      if (path === EP.accountSummary) {
        return Promise.resolve({ ok: false, error: { code: 'AUTH', message: 'API key 无效' } })
      }
      return Promise.resolve({ ok: true, items: [], details: [] })
    })
    const w = mountView()
    await flushPromises()
    expect(w.find('#account-summary').text()).toContain('[AUTH] API key 无效')
    w.unmount()
  })

  it('某个接口失败时顶部汇总告警，其余卡片照常渲染', async () => {
    mocked.mockImplementation((path: string) => {
      if (path === EP.accountBalance) return Promise.reject(new Error('boom'))
      if (path === EP.accountSummary) return Promise.resolve(summary)
      return Promise.resolve(positions)
    })
    const w = mountView()
    await flushPromises()
    expect(w.find('.alert.danger').text()).toContain('部分数据加载失败：boom')
    expect(w.text()).toContain('3610****f233')
    expect(w.find('#account-balance').text()).toContain('余额加载失败：boom')
    w.unmount()
  })
})
