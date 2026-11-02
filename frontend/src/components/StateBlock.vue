<script setup lang="ts">
withDefaults(defineProps<{ kind?: 'loading' | 'empty' | 'error'; text?: string }>(), {
  kind: 'empty',
  text: '暂无数据'
})
</script>

<template>
  <div class="state" :class="kind === 'error' ? 'is-error' : ''">
    <span v-if="kind === 'loading'" class="spinner"></span>
    <span>{{ text }}</span>
  </div>
</template>
