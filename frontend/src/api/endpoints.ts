/* 全部 /api/v1 路径（与 legacy app.js 实际调用逐条一致，新增页面从这里取） */

export const EP = {
  health: '/actuator/health',

  syncStatus: '/api/v1/sync/status',
  syncBasic: '/api/v1/sync/basic',
  syncInstruments: '/api/v1/sync/instruments',

  instruments: '/api/v1/instruments',
  coins: '/api/v1/coins',
  klines: '/api/v1/klines',
  fundingRates: '/api/v1/funding-rates',

  ratios: '/api/v1/ratios/long-short',
  openInterest: '/api/v1/stats/open-interest',
  openInterestVolume: '/api/v1/stats/open-interest-volume',
  takerVolume: '/api/v1/stats/taker-volume',

  backfillKlines: '/api/v1/backfill/klines',
  backfillFundingRates: '/api/v1/backfill/funding-rates',
  backfillRatios: '/api/v1/backfill/ratios',
  backfillMarketStats: '/api/v1/backfill/market-stats',
  backfillJobs: '/api/v1/backfill/jobs',
  backfillJob: (id: string) => '/api/v1/backfill/jobs/' + encodeURIComponent(id),
  backfillJobAction: (id: string, action: string) =>
    '/api/v1/backfill/jobs/' + encodeURIComponent(id) + '/' + action,

  strategyBacktests: '/api/v1/strategy/backtests',
  strategyBacktest: (runId: string) => '/api/v1/strategy/backtests/' + encodeURIComponent(runId),
  strategyBacktestCancel: (runId: string) => '/api/v1/strategy/backtests/' + encodeURIComponent(runId) + '/cancel',
  strategyBacktestPromote: (runId: string) => '/api/v1/strategy/backtests/' + encodeURIComponent(runId) + '/promote',
  strategyBacktestResults: (runId: string, resultId: string) =>
    '/api/v1/strategy/backtests/' + encodeURIComponent(runId) + '/results/' + encodeURIComponent(resultId) + '/coins',
  strategyBacktestEquity: (runId: string, resultId: string) =>
    '/api/v1/strategy/backtests/' + encodeURIComponent(runId) + '/results/' + encodeURIComponent(resultId) + '/equity',
  strategyBacktestTrades: (runId: string) => '/api/v1/strategy/backtests/' + encodeURIComponent(runId) + '/trades',

  strategyHealthChecks: '/api/v1/strategy/health/checks',
  strategyHealthLatest: '/api/v1/strategy/health/latest',
  strategyHealthCheck: '/api/v1/strategy/health/check',

  strategyLiveAccount: '/api/v1/strategy/live/account',
  strategyLiveGate: '/api/v1/strategy/live/gate',
  strategyLiveOrders: '/api/v1/strategy/live/orders',

  strategyShadowStatus: '/api/v1/strategy/shadow/status',
  strategyShadowOrders: '/api/v1/strategy/shadow/orders',
  strategyShadowPositions: '/api/v1/strategy/shadow/positions',
  strategyShadowPortfolio: '/api/v1/strategy/shadow/portfolio',
  strategyShadowTick: '/api/v1/strategy/shadow/tick',
  strategyShadowClose: (id: string) =>
    '/api/v1/strategy/shadow/positions/' + encodeURIComponent(id) + '/close',

  strategyStrategies: '/api/v1/strategy/strategies',
  strategyStrategy: (id: string) => '/api/v1/strategy/strategies/' + encodeURIComponent(id),

  accountBalance: '/api/v1/account/balance',
  accountPositions: '/api/v1/account/positions',
  accountOrders: '/api/v1/account/orders',
  accountSummary: '/api/v1/account/summary'
} as const
