<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { findEntry, legacyHref } from '../nav'

const route = useRoute()
const name = computed(() => String(route.name || 'status'))
const entry = computed(() => findEntry(name.value))
const src = computed(() => legacyHref(name.value))
</script>

<template>
  <section class="page">
    <div class="page-head">
      <div>
        <h1>{{ entry ? entry.title : name }}</h1>
        <p class="muted">{{ entry ? entry.desc : '' }}（该页面尚未迁移到 Vue，暂由旧版界面提供）</p>
      </div>
      <div class="page-actions">
        <a class="btn" :href="src" target="_blank" rel="noopener">新窗口打开旧版</a>
      </div>
    </div>
    <iframe class="legacy-frame" :src="src" :key="name" title="legacy"></iframe>
  </section>
</template>
