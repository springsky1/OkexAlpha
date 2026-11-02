import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import HealthView from '../src/views/HealthView.vue'
import {
  HEALTH_RULES,
  healthChecksInfo,
  healthChecksCols,
  healthCheckedAt,
  healthListMeta,
  healthSummaryHtml,
  healthSummaryMeta,
  summarizeHealth
} from '../src/views/health/logic'
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

const rows = [
  { id: 1, checkedAt: '2024-01-02T03:04:05Z', rule: 'SHADOW_SCHEDULER', level: 'WARN', scope: 'engine', message: 'w' },
  { id: 2, checkedAt: '2024-01-02T03:04:06Z', rule: 'MARKET_DATA_FRESH', level: 'OK', scope: 'market' },
  { id: 3, checkedAt: '2024-01-02T03:04:07Z', rule: 'BACKFILL_LOAD', level: 'FAIL', scope: 'backfill' },
  { id: 4, checkedAt: '2024-01-02T03:04:08Z', rule: 'CUSTOM_RULE', level: 'OTHER', scope: 'x' }
]

describe('health/logic 纯函数', () => {
  it('summarizeHealth 空数组返回 null', () => {
    expect(summarizeHealth([])).toBeNull()
    expect(summarizeHealth(null as never)).toBeNull()
  })

  it('summarizeHealth 统计各 level 并给出整体判定', () => {
    const s = summarizeHealth(rows)!
    expect(s).toMatchObject({ ok: 1, warn: 1, fail: 1, other: 1, total: 4, verdict: 'FAIL' })
    expect(s.latestMs).toBeGreaterThan(0)
  })

  it('summarizeHealth 无 FAIL 时按 WARN 判定，全 OK 则 OK', () => {
    expect(summarizeHealth([{ level: 'OK' }, { level: 'WARN' }])!.verdict).toBe('WARN')
    expect(summarizeHealth([{ level: 'OK' }])!.verdict).toBe('OK')
  })

  it('summarizeHealth 规则按出现顺序收集并补齐 9 类', () => {
    const s = summarizeHealth(rows)!
    expect(s.rules[0]).toBe('SHADOW_SCHEDULER')
    expect(s.rules[1]).toBe('MARKET_DATA_FRESH')
    expect(s.rules).toHaveLength(HEALTH_RULES.length + 1)
    expect(s.rules).toContain('UTILIZATION')
    expect(s.rules).toContain('CUSTOM_RULE')
  })

  it('healthSummaryHtml 输出三档徽章与判定文案', () => {
    const html = healthSummaryHtml(summarizeHealth(rows))
    expect(html).toContain('data-level="OK"')
    expect(html).toContain('data-level="WARN"')
    expect(html).toContain('data-level="FAIL"')
    expect(html).toContain('data-level="OTHER"')
    expect(html).toContain('共 4 条检查')
    expect(html).toContain('data-verdict="FAIL"')
    expect(html).toContain('有 FAIL 即 FAIL')
    expect(healthSummaryHtml(null)).toBe('')
  })

  it('汇总 meta 与最近体检文案', () => {
    expect(healthSummaryMeta(null)).toBe('暂无体检记录')
    expect(healthSummaryMeta(summarizeHealth(rows)!)).toContain('最近一轮 4 条')
    expect(healthCheckedAt(null)).toBe('最近体检 -')
    expect(healthCheckedAt(summarizeHealth(rows)!)).toMatch(/^最近体检 \d{4}-\d{2}-\d{2} /)
  })

  it('healthListMeta 带 level 过滤与每页条数', () => {
    expect(healthListMeta('')).toBe('全部 level · 每页 100 条')
    expect(healthListMeta('WARN')).toBe('level=WARN · 每页 100 条')
  })

  it('healthChecksInfo 走 pagerInfo', () => {
    expect(healthChecksInfo(0, 0)).toBe('暂无数据')
    expect(healthChecksInfo(0, 3)).toBe('第 1–3 条')
  })

  it('体检明细 7 列且 metric 可折叠', () => {
    const cols = healthChecksCols()
    expect(cols).toHaveLength(7)
    expect(cols[0].render!({ checkedAt: '2024-01-02T03:04:05Z' })).toMatch(/mono/)
    expect(cols[6].render!({ metric: '{"a":1}' })).toContain('metric-fold')
    expect(cols[6].render!({ metric: null })).toContain('muted')
  })
})

describe('HealthView 组件', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(apiGet).mockImplementation((path: string) => {
      if (path === EP.strategyHealthLatest) return Promise.resolve(rows as never)
      if (path === EP.strategyHealthChecks) return Promise.resolve(rows as never)
      return Promise.resolve(null as never)
    })
    vi.mocked(apiPost).mockResolvedValue({
      ok: 11,
      warn: 4,
      fail: 0,
      autoExited: 0,
      durationMs: 394,
      summary: 'ok=11 warn=4 fail=0 autoExited=0'
    } as never)
  })

  it('渲染汇总徽章、明细表并支持立即体检', async () => {
    const w = mount(HealthView, { global: { plugins: [createPinia()] } })
    await flushPromises()
    expect(apiGet).toHaveBeenCalledWith(EP.strategyHealthLatest)
    expect(apiGet).toHaveBeenCalledWith(EP.strategyHealthChecks, { limit: 100, offset: 0 })
    const text = w.text()
    expect(text).toContain('健康度')
    expect(text).toContain('共 4 条检查')
    expect(text).toContain('整体判定')
    expect(text).toContain('SHADOW_SCHEDULER')
    expect(w.findAll('.health-pill')).toHaveLength(4)
    const runBtn = w.findAll('button').find((b) => b.text().includes('立即体检'))
    expect(runBtn).toBeTruthy()
    await runBtn!.trigger('click')
    await flushPromises()
    expect(apiPost).toHaveBeenCalledWith(EP.strategyHealthCheck)
    expect(toast).toHaveBeenCalledWith('体检完成：ok=11 warn=4 fail=0 autoExited=0，耗时 394 ms', 'ok')
    w.unmount()
  })

  it('level 过滤与翻页参数', async () => {
    const w = mount(HealthView, { global: { plugins: [createPinia()] } })
    await flushPromises()
    await w.find('select#health-level').setValue('WARN')
    await flushPromises()
    expect(apiGet).toHaveBeenCalledWith(EP.strategyHealthChecks, { limit: 100, offset: 0, level: 'WARN' })
    const pagerInfo = w.find('.pager-info')
    expect(pagerInfo.exists()).toBe(true)
    expect(pagerInfo.text()).toBe('第 1–4 条')
    w.unmount()
  })
})
