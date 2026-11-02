<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import { useUiStore } from '../stores/ui'

const ui = useUiStore()

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape' && ui.modal.visible) ui.resolveModal(false)
}

onMounted(() => document.addEventListener('keydown', onKey))
onBeforeUnmount(() => document.removeEventListener('keydown', onKey))
</script>

<template>
  <div v-if="ui.modal.visible" class="modal-backdrop" @click.self="ui.resolveModal(false)">
    <div class="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title">
      <h3 id="modal-title">{{ ui.modal.title }}</h3>
      <div class="modal-body" v-html="ui.modal.bodyHtml"></div>
      <div class="modal-actions">
        <button class="btn" type="button" @click="ui.resolveModal(false)">取消</button>
        <button class="btn btn-primary" type="button" @click="ui.resolveModal(true)">{{ ui.modal.okText }}</button>
      </div>
    </div>
  </div>
</template>
