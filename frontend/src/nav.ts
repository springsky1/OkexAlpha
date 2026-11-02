/* 导航注册表：与 legacy index.html 的 15 个 hash 页面 / 3 个分组完全一致。
   migrated=true 的页面走 Vue 组件；false 的页面用 iframe 挂 /legacy/（过渡期双轨）。 */

export interface NavEntry {
  route: string
  label: string
  title: string
  desc: string
}

export const NAV_GROUPS: { key: string; label: string; items: NavEntry[] }[] = [
  {
    key: 'basic',
    label: '基础数据',
    items: [
      { route: 'status', label: '运行状态', title: '运行状态', desc: '健康检查与数据面概览（行数 / 品种分布 / 补拉作业 / 同步游标）。' },
      { route: 'klines', label: 'K 线查询', title: 'K 线查询', desc: '查询已入库的 K 线。CANDLE 带成交量，MARK 为标记价格；只支持永续合约（-SWAP），指数 K 线已下线。' },
      { route: 'funding', label: '资金费率', title: '资金费率', desc: '按 instId 与时间范围查询资金费率记录（衍生品才有资金费率）。' },
      { route: 'stats', label: '统计查询', title: '统计查询', desc: '多空比、持仓量、主动买卖量等统计指标查询。' },
      { route: 'backfill', label: '补拉作业', title: '补拉作业', desc: '按数据类型创建补拉作业，异步回填历史数据。' },
      { route: 'jobs', label: '作业列表', title: '作业列表', desc: '补拉作业与任务执行明细、进度与断点续跑。' },
      { route: 'sync', label: '同步', title: '同步', desc: '同步品种、币种等基础数据与 K 线增量。' },
      { route: 'data', label: '数据浏览', title: '数据浏览', desc: '直接浏览各表数据行。' }
    ]
  },
  {
    key: 'strategy',
    label: '策略',
    items: [
      { route: 'strategy-backtest', label: '策略回测', title: '策略回测', desc: '马丁策略参数寻优与回测结果。' },
      { route: 'shadow', label: '影子订单', title: '影子订单', desc: '影子盘订单、持仓与权益曲线。' },
      { route: 'health', label: '健康度', title: '健康度', desc: '策略健康检查规则与检查结果。' },
      { route: 'live', label: '实盘', title: '实盘', desc: '实盘闸门、下单与持仓。' }
    ]
  },
  {
    key: 'account',
    label: '账户',
    items: [
      { route: 'account', label: '账户概览', title: '账户概览', desc: 'OKX 只读账户信息：凭证脱敏展示、账户模式、余额明细与交易所持仓。本页只调用只读接口，不会下单 / 撤单 / 划转。' },
      { route: 'orders', label: '我的订单', title: '我的订单', desc: '交易所订单查询与状态。' },
      { route: 'strategies', label: '马丁策略', title: '马丁策略', desc: '策略实例管理与参数。' }
    ]
  }
]

export const NAV_GROUP_LABELS: Record<string, string> = {
  basic: '基础数据',
  strategy: '策略',
  account: '账户'
}

export const NAV_COLLAPSE_KEY = 'okx.nav.'

export const ALL_ROUTES: string[] = NAV_GROUPS.reduce(
  (acc: string[], g) => acc.concat(g.items.map((i) => i.route)),
  []
)

/* 已迁移到 Vue 的页面（P2 起 15 个页面全部完成迁移，仍保留该表以便随时回退某个页面） */
export const MIGRATED_ROUTES: string[] = [
  'status',
  'klines',
  'funding',
  'stats',
  'backfill',
  'jobs',
  'sync',
  'data',
  'strategy-backtest',
  'shadow',
  'health',
  'live',
  'account',
  'orders',
  'strategies'
]

export function isMigrated(route: string): boolean {
  return MIGRATED_ROUTES.indexOf(route) >= 0
}

export function legacyHref(route: string): string {
  return '/legacy/index.html#/' + route
}

export function findEntry(route: string): NavEntry | undefined {
  for (const g of NAV_GROUPS) {
    for (const it of g.items) {
      if (it.route === route) return it
    }
  }
  return undefined
}
