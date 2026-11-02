<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import AppToast from './components/AppToast.vue'
import AppConfirm from './components/AppConfirm.vue'
import { NAV_COLLAPSE_KEY, NAV_GROUPS, isMigrated } from './nav'
import { useHealthStore } from './stores/health'
import { useUiStore } from './stores/ui'

const route = useRoute()
const health = useHealthStore()
const ui = useUiStore()

/* 分组折叠状态：键 okx.nav.<group>，"1" 表示折叠，缺省为展开（与 legacy 一致） */
function readCollapse(): Record<string, boolean> {
  const out: Record<string, boolean> = {}
  for (const g of NAV_GROUPS) {
    let collapsed = false
    try {
      const s = localStorage.getItem(NAV_COLLAPSE_KEY + g.key)
      if (s !== null) collapsed = s === '1'
    } catch (e) {
      /* 隐私模式下 localStorage 不可用，按展开处理 */
    }
    out[g.key] = collapsed
  }
  return out
}

const collapsed = ref<Record<string, boolean>>(readCollapse())

function toggleGroup(key: string) {
  collapsed.value[key] = !collapsed.value[key]
  try {
    localStorage.setItem(NAV_COLLAPSE_KEY + key, collapsed.value[key] ? '1' : '0')
  } catch (e) {
    /* 忽略写入失败 */
  }
}

const svcOrigin = computed(() => {
  const o = window.location.origin
  return o && o !== 'null' ? o : 'file://（需用 http 服务访问）'
})

onMounted(() => {
  void health.check(false)
})
</script>

<template>
  <div class="app">
    <!-- ============ 左侧导航 ============ -->
    <aside class="sidebar">
      <div class="brand">
        <span class="brand-mark">OK</span>
        <span class="brand-text">
          <strong>OkexAlpha</strong>
          <small>数据下载 / 补拉后台</small>
        </span>
      </div>
      <div class="nav">
        <nav v-for="g in NAV_GROUPS" :key="g.key" class="nav-group" :data-group="g.key" :aria-label="g.label">
          <button
            class="nav-group-toggle"
            type="button"
            :aria-expanded="collapsed[g.key] ? 'false' : 'true'"
            :aria-controls="'nav-items-' + g.key"
            @click="toggleGroup(g.key)"
          >
            <span class="nav-group-title">{{ g.label }}</span>
            <span class="nav-group-arrow" aria-hidden="true">▾</span>
          </button>
          <div v-show="!collapsed[g.key]" class="nav-items" :id="'nav-items-' + g.key">
            <router-link
              v-for="it in g.items"
              :key="it.route"
              class="nav-item"
              :to="'/' + it.route"
              :class="{ 'is-active': route.name === it.route }"
            >
              {{ it.label }}
              <span v-if="!isMigrated(it.route)" class="nav-flag" title="该页面暂由旧版界面提供">旧</span>
            </router-link>
          </div>
        </nav>
      </div>
      <div class="sidebar-foot">
        <div class="foot-label">服务地址</div>
        <div class="mono">{{ svcOrigin }}</div>
      </div>
    </aside>

    <div class="main">
      <!-- ============ 顶部固定栏 ============ -->
      <header class="topbar">
        <div class="topbar-group">
          <button
            class="health-badge"
            type="button"
            :data-state="health.state"
            title="点击重新检查健康状态"
            @click="health.check(true)"
          >
            <span class="dot"></span><span>{{ health.text }}</span>
          </button>
          <span class="topbar-meta mono">{{ health.detail }}</span>
        </div>
        <div class="topbar-group">
          <span class="topbar-meta">最后刷新：<span>{{ ui.lastRefresh }}</span></span>
          <button class="btn btn-sm" type="button" @click="health.check(true)">检查健康</button>
        </div>
      </header>

      <main class="content">
        <router-view v-slot="{ Component }">
          <component :is="Component" />
        </router-view>
      </main>
    </div>

    <AppToast />
    <AppConfirm />
  </div>
</template>