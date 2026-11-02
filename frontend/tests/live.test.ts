import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import LiveView from '../src/views/LiveView.vue'
import {
  LIVE_CAP_LABELS,
  blockedInfo,
  buildOrderBody,
  confirmBodyHtml,
  liveAccountModel,
  liveBlockerHtml,
  liveCapEntries,
  liveCapValue,
  liveChecksMeta,
  liveDynamicCols,
  liveGateModel,
  orderErrorHtml,
  orderResultHtml,
  validateOrder
} from '../src/views/live/logic'
import type { LiveOrderInput } from '../src/views/live/logic'
import { apiGet, apiPost } from '../src/api/client'
import { EP } from '../src/api/endpoints'

vi.mock('../src/api/client', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiRawGet: vi.fn()
}))

const toast = vi.fn()
vi.mock('../src/stores/ui', () => ({
  useUiStore: () => ({ toast, confirmDialog: vi.fn().mockResolvedValue(true) })
}))

function input(over: Partial<LiveOrderInput> = {}): LiveOrderInput {
  return {
    instId: 'BTC-USDT-SWAP',
    side: 'buy',
    sz: '1',
    ordType: 'market',
    px: '',
    tdMode: 'isolated',
    reduceOnly: false,
    confirm: '',
    ...over
  }
}

const gate = {
  mode: 'SHADOW',
  liveAllowed: false,
  blockers: [
    'MODE: 当前模式 SHADOW，不是 REAL',
    'ENABLED: 实盘开关关闭',
    'CREDENTIALS: 未配置 API 密钥',
    'DAILY_NOTIONAL: 当日成交名义 3002.31 USD 已达上限 500.0 USD'
  ],
  checks: [
    { id: 'MODE', level: 'BLOCK', message: 'SHADOW' },
    { id: 'POSITION_LIMIT', level: 'PASS', message: '影子持仓 4/50' },
    { id: 'BACKFILL_LOAD', level: 'WARN', message: '补拉作业运行中' }
  ],
  caps: {
    maxPositions: 50,
    maxDailyNotionalUsd: 500.0,
    usedTodayUsd: 3002.31,
    capitalPerCoinUsd: 0.0,
    feeTaker: 5.0e-4,
    slippageBps: 2.0,
    evaluatedAt: 1700000000000
  }
}

describe('live/logic 纯函数', () => {
  it('validateOrder 依次校验 instId / sz / 限价 px', () => {
    expect(validateOrder(input({ instId: '  ' }))).toEqual({
      ok: false,
      field: 'instId',
      msg: '请先填 instId（可聚焦输入框从下拉里选）'
    })
    expect(validateOrder(input({ sz: '0' }))).toMatchObject({ ok: false, field: 'sz' })
    expect(validateOrder(input({ sz: 'abc' }))).toMatchObject({ ok: false, field: 'sz' })
    expect(validateOrder(input({ ordType: 'limit', px: '' }))).toMatchObject({ ok: false, field: 'px' })
    expect(validateOrder(input({ ordType: 'limit', px: '1.5' }))).toEqual({ ok: true })
    expect(validateOrder(input({ ordType: 'market', px: '' }))).toEqual({ ok: true })
  })

  it('buildOrderBody 市价不带 px，限价带数字 px，confirm 非空才带', () => {
    expect(buildOrderBody(input())).toEqual({
      instId: 'BTC-USDT-SWAP',
      side: 'buy',
      sz: 1,
      ordType: 'market',
      tdMode: 'isolated',
      reduceOnly: false
    })
    const limit = buildOrderBody(input({ ordType: 'limit', px: '100.5', confirm: 'CONFIRM-REAL-TRADING' }))
    expect(limit.px).toBe(100.5)
    expect(limit.confirm).toBe('CONFIRM-REAL-TRADING')
    expect(buildOrderBody(input({ instId: ' DOGE-USDT-SWAP ' })).instId).toBe('DOGE-USDT-SWAP')
  })

  it('confirmBodyHtml 列出 6 项且在 BLOCK 时说明 422 属预期', () => {
    const html = confirmBodyHtml(input({ ordType: 'limit', px: '100' }), false, 'SHADOW')
    expect(html).toContain('live-confirm-list')
    expect(html).toContain('BTC-USDT-SWAP')
    expect(html).toContain('100')
    expect(html).toContain('liveAllowed=false')
    expect(html).toContain('HTTP 422')
    const allow = confirmBodyHtml(input(), true, 'REAL')
    expect(allow).toContain('会真的发往交易所')
  })

  it('liveBlockerHtml 把 CODE 前缀加粗', () => {
    expect(liveBlockerHtml('MODE: 当前模式 SHADOW')).toBe('<strong>MODE</strong><span>当前模式 SHADOW</span>')
    expect(liveBlockerHtml('no-prefix')).toBe('no-prefix')
    expect(liveBlockerHtml(null)).toBe('')
  })

  it('liveGateModel 在 SHADOW 下判定 BLOCK 并提示预期行为', () => {
    const m = liveGateModel(gate)
    expect(m).toMatchObject({ mode: 'SHADOW', allowed: false, isShadow: true, flagText: 'BLOCK' })
    expect(m.verdictText).toBe('拦截 4 项')
    expect(m.verdictSub).toBe('SHADOW 下 BLOCK 属预期行为')
    expect(liveGateModel({ mode: 'REAL', liveAllowed: true }).flagText).toBe('ALLOW')
  })

  it('liveChecksMeta 只统计 PASS/WARN/BLOCK', () => {
    expect(liveChecksMeta(gate.checks)).toBe('PASS 1 · WARN 1 · BLOCK 1')
    expect(liveChecksMeta([])).toBe('PASS 0 · WARN 0 · BLOCK 0')
  })

  it('caps 按固定键序输出中文名与格式化值', () => {
    const entries = liveCapEntries(gate.caps)
    expect(entries.map((e) => e[0])).toEqual(Object.values(LIVE_CAP_LABELS))
    expect(entries[0]).toEqual(['最大持仓数', '50'])
    expect(entries[1][1]).toBe('500')
    expect(entries[2][1]).toBe('3,002.31')
    expect(entries[4][1]).toBe('0.0005')
    expect(entries[6][1]).toMatch(/^\d{4}-\d{2}-\d{2} /)
    // legacy app.js:5404-5405：数字且键不含 Usd/Bps 才走 fmtInt，其余走 fmtNum（不补小数位）
    expect(entries[3][1]).toBe('0') // capitalPerCoinUsd 数字 0
    expect(entries[5][1]).toBe('2') // slippageBps 数字 2
    expect(liveCapEntries(null)).toEqual([])
    expect(liveCapValue('capitalPerCoinUsd', '0.0')).toBe('0')
    expect(liveCapValue('capitalPerCoinUsd', 0)).toBe('0')
    expect(liveCapValue('maxDailyNotionalUsd', 500)).toBe('500')
    expect(liveCapValue('maxPositions', 50)).toBe('50')
    expect(liveCapValue('feeTaker', 5.0e-4)).toBe('0.0005')
    expect(liveCapValue('unknownKey', '')).toBe('-') // 空字符串按 isNil 处理
    expect(liveCapValue('unknownKey', null)).toBe('-')
  })

  it('liveAccountModel 未配置密钥时给出原因与条数', () => {
    const m = liveAccountModel({
      configured: false,
      mode: 'SHADOW',
      reason: '未配置 API 密钥',
      balance: [],
      positions: []
    })
    expect(m.configured).toBe(false)
    expect(m.title).toBe('未配置 API 密钥（configured=false）')
    expect(m.reason).toBe('未配置 API 密钥')
    expect(m.meta).toBe('mode=SHADOW · balance=0 条 · positions=0 条')
    const on = liveAccountModel({ configured: true, mode: 'REAL', balance: [{}], positions: [{}, {}] })
    expect(on.title).toBe('已配置 API 密钥')
    expect(on.balanceCount).toBe(1)
    expect(on.positionCount).toBe(2)
  })

  it('liveDynamicCols 取首行最多 10 列，对象值 JSON 化', () => {
    const rows = [{ a: 1, b: { c: 2 }, d: null }]
    const cols = liveDynamicCols(rows)
    expect(cols).toHaveLength(3)
    expect(cols[1].render!(rows[0])).toContain('{&quot;c&quot;:2}')
    expect(cols[2].render!(rows[0])).toBe('-')
    const wide = liveDynamicCols([
      { a: 1, b: 2, c: 3, d: 4, e: 5, f: 6, g: 7, h: 8, i: 9, j: 10, k: 11 }
    ])
    expect(wide).toHaveLength(10)
    expect(liveDynamicCols([])).toEqual([])
  })

  it('blockedInfo 拆出 " | " 分隔的拦截原因', () => {
    const info = blockedInfo('live trading blocked -> MODE: a | ENABLED: b')
    expect(info.detail).toBe('MODE: a | ENABLED: b')
    expect(info.parts).toEqual(['MODE: a', 'ENABLED: b'])
    expect(blockedInfo('other').parts).toEqual(['other'])
  })

  it('422 渲染成「被门禁拦截（符合预期）」，其它错误是普通失败', () => {
    const html = orderErrorHtml({
      status: 422,
      code: 'INVALID_PARAM',
      message: 'live trading blocked -> MODE: a | ENABLED: b'
    })
    expect(html).toContain('被门禁拦截（符合预期）')
    expect(html).toContain('HTTP 422 · code=INVALID_PARAM')
    expect(html).toContain('<strong>MODE</strong>')
    expect(html).toContain('<strong>ENABLED</strong>')
    expect(html).not.toContain('is-err')
    const fail = orderErrorHtml({ status: 500, message: 'boom' })
    expect(fail).toContain('下单失败（HTTP 500）：boom')
  })

  it('成功结果面板展示 accepted / ordId / clOrdId', () => {
    expect(orderResultHtml({ accepted: true, ordId: '1', clOrdId: 'c' })).toContain('accepted=true')
    expect(orderResultHtml({ accepted: false })).toContain('ordId (空)')
    expect(orderResultHtml({})).toContain('clOrdId -')
  })
})

describe('LiveView 组件', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(apiGet).mockImplementation((path: string) => {
      if (path === EP.strategyLiveGate) return Promise.resolve(gate as never)
      if (path === EP.strategyLiveAccount) {
        return Promise.resolve({
          configured: false,
          mode: 'SHADOW',
          reason: '未配置 API 密钥（...），当前只有本地影子账本可用',
          balance: [],
          positions: []
        } as never)
      }
      return Promise.resolve([] as never)
    })
  })

  it('渲染门禁 BLOCK、caps 与未配置账户', async () => {
    const w = mount(LiveView, { global: { plugins: [createPinia()] } })
    await flushPromises()
    const text = w.text()
    expect(text).toContain('实盘门禁')
    expect(text).toContain('BLOCK')
    expect(text).toContain('拦截原因 blockers（4 项）')
    expect(text).toContain('MODE')
    expect(text).toContain('最大持仓数')
    expect(text).toContain('未配置 API 密钥（configured=false）')
    expect(text).toContain('PASS 1 · WARN 1 · BLOCK 1')
    w.unmount()
  })

  it('下单被 422 拦截时展示为「符合预期」', async () => {
    vi.mocked(apiPost).mockRejectedValueOnce({
      status: 422,
      code: 'INVALID_PARAM',
      message: 'live trading blocked -> MODE: a | ENABLED: b'
    })
    const w = mount(LiveView, { global: { plugins: [createPinia()] } })
    await flushPromises()
    await w.find('input.inst-input').setValue('BTC-USDT-SWAP')
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(apiPost).toHaveBeenCalledWith(EP.strategyLiveOrders, {
      instId: 'BTC-USDT-SWAP',
      side: 'buy',
      sz: 1,
      ordType: 'market',
      tdMode: 'isolated',
      reduceOnly: false
    })
    const slot = w.find('.result-slot')
    expect(slot.text()).toContain('被门禁拦截（符合预期）')
    expect(slot.text()).toContain('HTTP 422')
    expect(toast).toHaveBeenCalledWith('被门禁拦截（符合预期）：HTTP 422', 'info')
    w.unmount()
  })
})
