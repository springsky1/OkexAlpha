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
import InstPicker from '../src/components/InstPicker.vue'

const swapList = [
  { instId: 'BTC-USDT-SWAP', instType: 'SWAP', state: 'live' },
  { instId: 'ETH-USDT-SWAP', instType: 'SWAP', state: 'live' },
  { instId: 'SOL-USDT-SWAP', instType: 'SWAP', state: 'live' }
]

type Mocked = {
  mockImplementation: (f: (p: string, q?: unknown) => unknown) => void
}
const mocked = apiGet as unknown as Mocked

function mockInstruments() {
  mocked.mockImplementation((path: string, params?: unknown) => {
    if (path !== EP.instruments) return Promise.reject(new Error('unexpected path ' + path))
    const type = String((params as { instType?: string } | undefined)?.instType || '')
    return Promise.resolve(type === 'SWAP' ? swapList : [])
  })
}

async function openPanel(w: ReturnType<typeof mount>) {
  await w.find('.inst-input').trigger('focus')
  await flushPromises()
  return w
}

function lastEmit(w: ReturnType<typeof mount>): string {
  const events = w.emitted('update:modelValue') as unknown[][] | undefined
  expect(events && events.length).toBeTruthy()
  return String(events![events!.length - 1][0])
}

describe('InstPicker 多选：checkbox + 全选/反选/清空', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
    mockInstruments()
  })

  it('多选面板渲染候选、勾选框与已选计数', async () => {
    const w = mount(InstPicker, {
      props: { modelValue: 'BTC-USDT-SWAP, ETH-USDT-SWAP', multiple: true, instTypes: ['SWAP'] },
      global: { plugins: [createPinia()] }
    })
    await openPanel(w)
    expect(w.find('.inst-bulk').exists()).toBe(true)
    expect(w.find('.inst-bulk-count').text()).toBe('已选 2 个')
    expect(w.findAll('.inst-opt').length).toBe(3)
    const btc = w.findAll('.inst-opt')[0]
    expect(btc.classes()).toContain('is-checked')
    expect(btc.attributes('aria-selected')).toBe('true')
    expect(btc.find('.inst-check').text()).toBe('✓')
    const sol = w.findAll('.inst-opt')[2]
    expect(sol.classes()).not.toContain('is-checked')
    expect(sol.find('.inst-check').text()).toBe('')
    w.unmount()
  })

  it('点击候选切换勾选，面板保持打开，已选串按逗号回写', async () => {
    const w = mount(InstPicker, {
      props: { modelValue: '', multiple: true, instTypes: ['SWAP'] },
      global: { plugins: [createPinia()] }
    })
    await openPanel(w)
    await w.findAll('.inst-opt')[1].trigger('click')
    expect(lastEmit(w)).toBe('ETH-USDT-SWAP')
    expect(w.find('.inst-panel').exists()).toBe(true)
    w.unmount()
  })

  it('再次点击已选项即取消选择', async () => {
    const w = mount(InstPicker, {
      props: { modelValue: 'BTC-USDT-SWAP, ETH-USDT-SWAP', multiple: true, instTypes: ['SWAP'] },
      global: { plugins: [createPinia()] }
    })
    await openPanel(w)
    await w.findAll('.inst-opt')[0].trigger('click')
    expect(lastEmit(w)).toBe('ETH-USDT-SWAP')
    w.unmount()
  })

  it('全选写入全部过滤结果，反选取补集，清空写空串', async () => {
    const all = mount(InstPicker, {
      props: { modelValue: '', multiple: true, instTypes: ['SWAP'] },
      global: { plugins: [createPinia()] }
    })
    await openPanel(all)
    const bulk = all.findAll('.inst-bulk-btn')
    await bulk[0].trigger('click')
    expect(lastEmit(all)).toBe('BTC-USDT-SWAP, ETH-USDT-SWAP, SOL-USDT-SWAP')
    all.unmount()

    const inv = mount(InstPicker, {
      props: { modelValue: 'BTC-USDT-SWAP', multiple: true, instTypes: ['SWAP'] },
      global: { plugins: [createPinia()] }
    })
    await openPanel(inv)
    await inv.findAll('.inst-bulk-btn')[1].trigger('click')
    expect(lastEmit(inv)).toBe('ETH-USDT-SWAP, SOL-USDT-SWAP')
    inv.unmount()

    const clr = mount(InstPicker, {
      props: { modelValue: 'BTC-USDT-SWAP', multiple: true, instTypes: ['SWAP'] },
      global: { plugins: [createPinia()] }
    })
    await openPanel(clr)
    await clr.findAll('.inst-bulk-btn')[2].trigger('click')
    expect(lastEmit(clr)).toBe('')
    clr.unmount()
  })

  it('全选按过滤词收窄，不含被过滤掉的候选', async () => {
    const w = mount(InstPicker, {
      props: { modelValue: '', multiple: true, instTypes: ['SWAP'] },
      global: { plugins: [createPinia()] }
    })
    await openPanel(w)
    /* 多选过滤词 = 最后一个逗号后的片段；已选 BTC 再打 "ET" 只应看到 ETH */
    await w.find('.inst-input').setValue('BTC-USDT-SWAP, ET')
    /* 真实用法是 v-model 回填，这里手动模拟父组件更新 prop，触发候选重算 */
    await w.setProps({ modelValue: 'BTC-USDT-SWAP, ET' })
    await flushPromises()
    expect(w.findAll('.inst-opt').length).toBe(1)
    await w.findAll('.inst-bulk-btn')[0].trigger('click')
    /* 全选只吞掉过滤结果，并把半截词 "ET" 丢掉 */
    expect(lastEmit(w)).toBe('BTC-USDT-SWAP, ETH-USDT-SWAP')
    w.unmount()
  })

  it('手动输入的半截词不会写进已选集合，也不会被全选污染', async () => {
    const w = mount(InstPicker, {
      props: { modelValue: '', multiple: true, instTypes: ['SWAP'] },
      global: { plugins: [createPinia()] }
    })
    await openPanel(w)
    await w.find('.inst-input').setValue('BT')
    await w.setProps({ modelValue: 'BT' })
    await flushPromises()
    await w.findAll('.inst-bulk-btn')[0].trigger('click')
    expect(lastEmit(w)).toBe('BTC-USDT-SWAP')
    w.unmount()
  })

  it('单选模式不渲染批量操作条，点击后回写并收起面板', async () => {
    const w = mount(InstPicker, {
      props: { modelValue: '', instTypes: ['SWAP'] },
      global: { plugins: [createPinia()] }
    })
    await openPanel(w)
    expect(w.find('.inst-bulk').exists()).toBe(false)
    expect(w.find('.inst-check').exists()).toBe(false)
    await w.findAll('.inst-opt')[0].trigger('click')
    expect(lastEmit(w)).toBe('BTC-USDT-SWAP')
    await flushPromises()
    expect(w.find('.inst-panel').exists()).toBe(false)
    w.unmount()
  })
})
