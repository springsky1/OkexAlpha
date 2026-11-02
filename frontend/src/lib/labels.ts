/* 与 legacy app.js 第 0 区逐行等价的常量/枚举（1:1 移植） */

export const NL = String.fromCharCode(10)
export const TAB = String.fromCharCode(9)

export type OptionPair = [string, string]

export const BARS: OptionPair[] = [
  ['1m', '1m（1 分钟）'],
  ['3m', '3m（3 分钟）'],
  ['5m', '5m（5 分钟）'],
  ['15m', '15m（15 分钟）'],
  ['30m', '30m（30 分钟）'],
  ['1H', '1H（1 小时）'],
  ['2H', '2H（2 小时）'],
  ['4H', '4H（4 小时）'],
  ['6H', '6H（6 小时）'],
  ['12H', '12H（12 小时）'],
  ['1D', '1D（1 天）'],
  ['2D', '2D（2 天）'],
  ['3D', '3D（3 天）'],
  ['1W', '1W（1 周）'],
  ['1M', '1M（1 月）'],
  ['3M', '3M（3 月）'],
  ['6Hutc', '6Hutc（6 小时 · UTC）'],
  ['12Hutc', '12Hutc（12 小时 · UTC）'],
  ['1Dutc', '1Dutc（1 天 · UTC）'],
  ['2Dutc', '2Dutc（2 天 · UTC）'],
  ['3Dutc', '3Dutc（3 天 · UTC）'],
  ['1Wutc', '1Wutc（1 周 · UTC）'],
  ['1Mutc', '1Mutc（1 月 · UTC）'],
  ['3Mutc', '3Mutc（3 月 · UTC）']
]

export const KLINE_TYPES: OptionPair[] = [
  ['CANDLE', 'CANDLE（蜡烛 K 线，含成交量）'],
  ['MARK', 'MARK（标记价格）']
]

export const INST_TYPES: OptionPair[] = [
  ['SPOT', 'SPOT（现货）'],
  ['MARGIN', 'MARGIN（杠杆）'],
  ['SWAP', 'SWAP（永续）'],
  ['FUTURES', 'FUTURES（交割）'],
  ['OPTION', 'OPTION（期权）']
]

export const PERIODS: OptionPair[] = [
  ['5m', '5m'],
  ['1H', '1H'],
  ['1D', '1D']
]

/* RatioType：[code, 名称, scopeKind] */
export const RATIO_TYPES: [string, string, string][] = [
  ['LSR_ACCOUNT_CCY', '多空人数比（按币种）', 'CCY'],
  ['LSR_ACCOUNT_CONTRACT', '多空人数比（按合约）', 'INST'],
  ['TOP_TRADER_ACCOUNT', '精英账户多空比', 'INST'],
  ['TOP_TRADER_POSITION', '精英持仓多空比', 'INST']
]

export const DATA_TYPES = ['KLINE', 'FUNDING_RATE', 'LONG_SHORT_RATIO', 'OPEN_INTEREST_VOLUME', 'TAKER_VOLUME', 'OPEN_INTEREST']

export const DATA_TYPE_LABELS: Record<string, string> = {
  KLINE: 'K 线',
  FUNDING_RATE: '资金费率',
  LONG_SHORT_RATIO: '多空比',
  OPEN_INTEREST_VOLUME: '持仓量-成交额',
  TAKER_VOLUME: '主动买卖量',
  OPEN_INTEREST: '持仓量快照'
}

export const JOB_STATUSES = ['PENDING', 'RUNNING', 'PAUSED', 'DONE', 'FAILED', 'CANCELED']

export const JOB_STATUS_LABELS: Record<string, string> = {
  PENDING: '待执行',
  RUNNING: '执行中',
  PAUSED: '已暂停',
  DONE: '已完成',
  FAILED: '失败',
  CANCELED: '已取消'
}

export const TASK_STATUS_LABELS: Record<string, string> = {
  PENDING: '待执行',
  RUNNING: '执行中',
  DONE: '已完成',
  FAILED: '失败',
  SKIPPED: '已跳过'
}

export const STAT_KINDS = ['OPEN_INTEREST_VOLUME', 'TAKER_VOLUME', 'OPEN_INTEREST']

export const PAGE_TITLES: Record<string, string> = {
  status: '运行状态',
  klines: 'K 线查询',
  funding: '资金费率',
  stats: '统计查询',
  backfill: '补拉作业',
  jobs: '作业列表',
  sync: '同步',
  data: '数据浏览',
  'strategy-backtest': '策略回测',
  shadow: '影子订单',
  health: '健康度',
  live: '实盘',
  account: '账户概览',
  orders: '我的订单',
  strategies: '马丁策略'
}

/* 图表字体与配色（与 styles.css 的 --up / --down 保持一致） */
export const CHART_FONT = '11px ui-monospace, Menlo, Consolas, monospace'
export const COLOR_UP = '#12a45c'
export const COLOR_DOWN = '#e0533c'
export const COLOR_GRID = '#e4e8ef'
export const COLOR_AXIS = '#98a2b3'
