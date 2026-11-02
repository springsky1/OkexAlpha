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
import DataView from '../src/views/DataView.vue'
import { buildCoinsParams, buildInstParams, joinList, pageSummary, pagerTotal } from '../src/views/data/dataQuery'
import { useUiStore } from '../src/stores/ui'

const api = apiGet as unknown as {
  mockResolvedValue: (v: unknown) => void
  mockRejectedValue: (v: unknown) => void
  mockImplementation: (fn: (path: any) => Promise<unknown>) => void
  mock: { calls: any[][] }
}

function coinRows(n: number) {
  const out: any[] = []
  for (let i = 0; i < n; i++) {
    out.push({
      coin: 'C' + i,
      channels: ['SPOT', 'SWAP'],
      roles: [],
      quoteCcys: ['USDT'],
      instCount: 12,
      spotInstCount: 1,
      swapInstCount: 2,
      futuresInstCount: 3,
      optionInstCount: 4,
      marginInstCount: 5
    })
  }
  return out
}

const instRows = [
  { instId: 'BTC-USDT-SWAP', instType: 'SWAP', baseCcy: '', quoteCcy: 'USDT', state: 'live', ctVal: 0.01, ctMult: 1, lever: 100, tickSz: 0.1, lotSz: 1, listTimeMs: 1600000000000 }
]

function mountView() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const w = mount(DataView, { global: { plugins: [pinia] } })
  return { w, ui: useUiStore() }
}

function routeByPath(n: number) {
  api.mockImplementation((path: any) => Promise.resolve(path === EP.coins ? coinRows(n) : instRows))
}

describe('dataQuery（纯逻辑）', () => {
  it('joinList：数组用「、」拼接，空数组与空值是 -', () => {
    expect(joinList(['SPOT', 'SWAP'])).toBe('SPOT、SWAP')
    expect(joinList([])).toBe('-')
    expect(joinList(null)).toBe('-')
    expect(joinList(0)).toBe('0')
  })

  it('币种参数：q 去空格，offset/limit 原样', () => {
    expect(buildCoinsParams({ channel: 'SWAP', q: ' btc ', limit: 50, offset: 100 })).toEqual({
      channel: 'SWAP',
      q: 'btc',
      limit: 50,
      offset: 100
    })
  })

  it('品种参数：baseCcy 去空格，instType/state 空串由请求层跳过', () => {
    expect(buildInstParams({ instType: '', baseCcy: ' BTC ', state: 'live', limit: 20, offset: 0 })).toEqual({
      instType: '',
      baseCcy: 'BTC',
      state: 'live',
      limit: 20,
      offset: 0
    })
  })

  it('结果概要：条数为 0 时「本页无数据」', () => {
    expect(pageSummary(0, 50)).toBe('本页无数据')
    expect(pageSummary(20, 20)).toBe('本页 20 条 · 每页 20')
  })

  it('pagerTotal：满页 +1 让「下一页」可用，非满页即停', () => {
    expect(pagerTotal(0, 20, 20)).toBe(21)
    expect(pagerTotal(0, 7, 20)).toBe(7)
    expect(pagerTotal(100, 0, 50)).toBe(100)
  })
})

describe('DataView（组件）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    routeByPath(20)
  })

  it('挂载不发请求，两个结果区都是「尚未查询」', () => {
    const { w } = mountView()
    expect(api.mock.calls.length).toBe(0)
    expect(w.findAll('.muted').filter((x) => x.text() === '尚未查询').length).toBe(2)
    w.unmount()
  })

  it('提交币种表单按 legacy 参数请求并渲染表格 + 概要', async () => {
    const { w } = mountView()
    await w.find('#coins-q').setValue('BTC')
    await w.findAll('form')[0].trigger('submit')
    await flushPromises()
    expect(api.mock.calls[0][0]).toBe(EP.coins)
    expect(api.mock.calls[0][1]).toEqual({ channel: '', q: 'BTC', limit: 50, offset: 0 })
    expect(w.text()).toContain('本页 20 条 · 每页 50')
    expect(w.text()).toContain('品种数')
    w.unmount()
  })

  it('换每页条数会重置 offset=0 并立即查询', async () => {
    const { w } = mountView()
    await w.findAll('form')[0].trigger('submit')
    await flushPromises()
    await w.find('#coins-limit').setValue('20')
    await flushPromises()
    expect(api.mock.calls[1][1]).toEqual({ channel: '', q: '', limit: 20, offset: 0 })
    w.unmount()
  })

  it('满页时「下一页」可用，翻页把 offset 推进一个 limit', async () => {
    const { w } = mountView()
    await w.find('#coins-limit').setValue('20')
    await flushPromises()
    const pager = w.findAll('.pager')[0]
    const buttons = pager.findAll('button')
    expect(buttons[0].attributes('disabled')).toBeDefined()
    expect(buttons[1].attributes('disabled')).toBeUndefined()
    await buttons[1].trigger('click')
    await flushPromises()
    expect(api.mock.calls[api.mock.calls.length - 1][1]).toEqual({ channel: '', q: '', limit: 20, offset: 20 })
    w.unmount()
  })

  it('非满页时「下一页」禁用', async () => {
    routeByPath(7)
    const { w } = mountView()
    await w.findAll('form')[0].trigger('submit')
    await flushPromises()
    const buttons = w.findAll('.pager')[0].findAll('button')
    expect(buttons[1].attributes('disabled')).toBeDefined()
    expect(w.text()).toContain('本页 7 条 · 每页 50')
    w.unmount()
  })

  it('品种表单按 instType/baseCcy/state 过滤', async () => {
    const { w } = mountView()
    await w.find('#inst-type').setValue('SWAP')
    await w.find('#inst-base').setValue('BTC')
    await w.find('#inst-state').setValue('live')
    await w.findAll('form')[1].trigger('submit')
    await flushPromises()
    expect(api.mock.calls[0][0]).toBe(EP.instruments)
    expect(api.mock.calls[0][1]).toEqual({ instType: 'SWAP', baseCcy: 'BTC', state: 'live', limit: 50, offset: 0 })
    expect(w.text()).toContain('BTC-USDT-SWAP')
    w.unmount()
  })

  it('接口失败时错误条 + 加载失败态 + toast', async () => {
    api.mockRejectedValue(new Error('boom'))
    const { w, ui } = mountView()
    await w.findAll('form')[0].trigger('submit')
    await flushPromises()
    expect(w.find('.alert.danger').text()).toBe('boom')
    expect(w.text()).toContain('加载失败：boom')
    expect(ui.toasts.map((x) => x.msg)).toContain('加载币种失败：boom')
    w.unmount()
  })
})
