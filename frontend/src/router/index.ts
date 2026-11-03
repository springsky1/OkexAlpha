import { createRouter, createWebHashHistory } from 'vue-router'
import { ALL_ROUTES, MIGRATED_ROUTES, findEntry } from '../nav'

/* 每个路由一个懒加载页面组件（vue-router 自行处理 () => import() 的懒加载，
   不要再包 defineAsyncComponent()，否则 dev 模式报 VUE_ROUTER_R0029）；
   未迁移（或尚无 Vue 页面）的路由统一落 LegacyFrame（iframe 旧静态页） */
const VIEW_LOADERS: Record<string, () => Promise<unknown>> = {
  status: () => import('../views/StatusView.vue'),
  klines: () => import('../views/KlinesView.vue'),
  funding: () => import('../views/FundingView.vue'),
  stats: () => import('../views/StatsView.vue'),
  backfill: () => import('../views/BackfillView.vue'),
  jobs: () => import('../views/JobsView.vue'),
  sync: () => import('../views/SyncView.vue'),
  data: () => import('../views/DataView.vue'),
  'strategy-backtest': () => import('../views/StrategyBacktestView.vue'),
  shadow: () => import('../views/ShadowView.vue'),
  health: () => import('../views/HealthView.vue'),
  live: () => import('../views/LiveView.vue'),
  account: () => import('../views/AccountView.vue'),
  orders: () => import('../views/OrdersView.vue'),
  'order-center': () => import('../views/OrderCenterView.vue'),
  strategies: () => import('../views/StrategiesView.vue')
}

const LEGACY_LOADER = () => import('../views/LegacyFrame.vue')

function componentFor(route: string): () => Promise<unknown> {
  if (MIGRATED_ROUTES.indexOf(route) < 0) return LEGACY_LOADER
  return VIEW_LOADERS[route] || LEGACY_LOADER
}

/* hash 模式：URL 形态与 legacy 完全一致（#/status），迁移期用户无感 */
export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    /* 空 hash（#/ 与无 hash）落到状态页，与 legacy 默认路由一致 */
    { path: '/', redirect: '/status' },
    { path: '/:pathMatch(.*)*', redirect: '/status' },
    ...ALL_ROUTES.map((route) => ({
      path: '/' + route,
      name: route,
      component: componentFor(route),
      props: true
    }))
  ]
})

router.afterEach((to) => {
  const route = String(to.name || 'status')
  const entry = findEntry(route)
  document.title = (entry ? entry.title : route) + ' · OkexAlpha'
})

router.onError((err) => {
  console.error('router error', err)
})
