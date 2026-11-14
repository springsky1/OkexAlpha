/* 导航分组回归：策略域与真实账户域分离（影子订单 / 回测 / 马丁策略归「策略」，账户只留真实账户与订单）。 */
import { describe, expect, it } from 'vitest'
import { ALL_ROUTES, MIGRATED_ROUTES, NAV_GROUPS, findEntry } from '../src/nav'

function groupOf(key: string) {
  const g = NAV_GROUPS.filter((x) => x.key === key)[0]
  expect(g).toBeTruthy()
  return g
}

function routesOf(key: string): string[] {
  return groupOf(key).items.map((i) => i.route)
}

describe('nav 分组', () => {
  it('「策略」包含回测 / 影子订单 / 马丁策略 / 健康度 / 实盘', () => {
    expect(routesOf('strategy')).toEqual(['strategy-backtest', 'shadow', 'strategies', 'health', 'live'])
  })

  it('「账户」只剩真实账户信息与真实订单', () => {
    expect(routesOf('account')).toEqual(['account', 'orders', 'order-center'])
  })

  it('马丁策略只在「策略」组出现一次（不再重复登记在账户组）', () => {
    const owners = NAV_GROUPS.filter((g) => g.items.some((i) => i.route === 'strategies')).map((g) => g.key)
    expect(owners).toEqual(['strategy'])
  })

  it('每组路由唯一，ALL_ROUTES 共 16 条且与 MIGRATED_ROUTES 对齐', () => {
    const all = NAV_GROUPS.reduce((acc: string[], g) => acc.concat(g.items.map((i) => i.route)), [])
    expect(new Set(all).size).toBe(all.length)
    expect(ALL_ROUTES.length).toBe(16)
    expect(ALL_ROUTES.slice().sort()).toEqual(MIGRATED_ROUTES.slice().sort())
  })

  it('findEntry 能按 route 取到登记项', () => {
    const e = findEntry('strategies')
    expect(e && e.label).toBe('马丁策略')
    expect(e && e.desc).toContain('停止')
  })
})
