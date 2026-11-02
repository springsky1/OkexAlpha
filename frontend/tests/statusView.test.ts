import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('../src/api/client', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiRawGet: vi.fn()
}))

import { apiGet } from '../src/api/client'
import StatusView from '../src/views/StatusView.vue'

const payload = {
  rows: { instruments: 4908, coins: 478, klines: 129077924 },
  instrumentsByType: [{ inst_type: 'SWAP', cnt: 532 }],
  backfillJobs: [{ data_type: 'KLINE', status: 'DONE', jobs: 17 }],
  cursors: { 'option_family:BTC-USD': 1793509629843 }
}

function mountView() {
  return mount(StatusView, { global: { plugins: [createPinia()] } })
}

describe('StatusView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
    ;(apiGet as unknown as { mockResolvedValue: (v: unknown) => void }).mockResolvedValue(payload)
  })

  it('挂载即请求 sync/status（不能等轮询首跳）', async () => {
    const w = mountView()
    await flushPromises()
    expect(apiGet).toHaveBeenCalledWith('/api/v1/sync/status')
    w.unmount()
  })

  it('数据回来后各表行数 kv-grid 渲染，不再是 loading', async () => {
    const w = mountView()
    await flushPromises()
    await flushPromises()
    const text = w.text()
    expect(text).toContain('4,908')
    expect(text).toContain('129,077,924')
    expect(w.findAll('.state .spinner').length).toBe(0)
    w.unmount()
  })

  it('接口失败时显示错误提示', async () => {
    ;(apiGet as unknown as { mockRejectedValue: (v: unknown) => void }).mockRejectedValue(new Error('boom'))
    const w = mountView()
    await flushPromises()
    expect(w.text()).toContain('加载运行状态失败：boom')
    w.unmount()
  })
})