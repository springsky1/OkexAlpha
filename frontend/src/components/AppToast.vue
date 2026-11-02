<script setup lang="ts">
import { useUiStore } from '../stores/ui'
const ui = useUiStore()
</script>

<template>
  <div class="toast-stack">
    <div v-for="t in ui.toasts" :key="t.id" class="toast" :class="'is-' + t.kind" role="status">
      {{ t.msg }}
    </div>
  </div>
</template>
