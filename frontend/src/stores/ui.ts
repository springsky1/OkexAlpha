/* 全局交互：toast / 确认弹窗 / 最后刷新时间（legacy app.js 第 3 区等价实现） */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { fmtTime } from '../lib/format'

export interface ToastItem {
  id: number
  msg: string
  kind: string
}

export interface ModalState {
  visible: boolean
  title: string
  bodyHtml: string
  okText: string
  resolve: ((ok: boolean) => void) | null
}

const TOAST_TTL = 5600

export const useUiStore = defineStore('ui', () => {
  const toasts = ref<ToastItem[]>([])
  const lastRefresh = ref('—')
  const modal = ref<ModalState>({ visible: false, title: '确认操作', bodyHtml: '', okText: '确认', resolve: null })
  let seq = 0
  const timers = new Map<number, ReturnType<typeof setTimeout>>()

  function dismiss(id: number) {
    const t = timers.get(id)
    if (t) {
      clearTimeout(t)
      timers.delete(id)
    }
    toasts.value = toasts.value.filter((x) => x.id !== id)
  }

  function toast(msg: string, kind = 'info') {
    const id = ++seq
    toasts.value = toasts.value.concat([{ id, msg: String(msg), kind: String(kind) }])
    timers.set(
      id,
      setTimeout(() => dismiss(id), TOAST_TTL)
    )
  }

  function touchRefresh() {
    lastRefresh.value = fmtTime(Date.now())
  }

  function confirmDialog(title: string, bodyHtml: string, okText = '确认'): Promise<boolean> {
    return new Promise<boolean>((resolve) => {
      modal.value = { visible: true, title, bodyHtml, okText, resolve }
    })
  }

  function resolveModal(ok: boolean) {
    const current = modal.value
    modal.value = { visible: false, title: '确认操作', bodyHtml: '', okText: '确认', resolve: null }
    if (current.resolve) current.resolve(ok)
  }

  return { toasts, lastRefresh, modal, toast, dismiss, touchRefresh, confirmDialog, resolveModal }
})
