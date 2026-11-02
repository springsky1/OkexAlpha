/* 顶栏健康徽标（legacy checkHealth 等价实现） */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiRawGet } from '../api/client'
import { EP } from '../api/endpoints'
import { useUiStore } from './ui'

export type HealthState = 'unknown' | 'up' | 'down'

export const useHealthStore = defineStore('health', () => {
  const state = ref<HealthState>('unknown')
  const text = ref('检测中…')
  const detail = ref('—')
  const cardValue = ref('—')
  const cardSub = ref('—')
  const ui = useUiStore()

  async function check(showToast: boolean): Promise<boolean> {
    state.value = 'unknown'
    text.value = '检测中…'
    try {
      const health = await apiRawGet<any>(EP.health)
      const status = health && health.status ? String(health.status) : 'UNKNOWN'
      const up = status === 'UP'
      state.value = up ? 'up' : 'down'
      text.value = up ? '服务正常' : '服务 ' + status
      const comps = health && health.components ? health.components : {}
      const keys = Object.keys(comps)
      const parts: string[] = []
      for (let i = 0; i < keys.length; i++) {
        const c = comps[keys[i]]
        parts.push(keys[i] + '=' + (c && c.status ? c.status : '?'))
      }
      if (!parts.length) parts.push('status=' + status)
      const line = parts.join(' · ')
      detail.value = line
      cardValue.value = up ? 'UP' : status
      cardSub.value = line
      if (showToast) ui.toast(up ? '健康检查通过' : '健康检查异常：' + status, up ? 'ok' : 'err')
      return up
    } catch (err: any) {
      state.value = 'down'
      text.value = '服务不可用'
      detail.value = err && err.message ? err.message : String(err)
      cardValue.value = 'DOWN'
      cardSub.value = detail.value
      if (showToast) ui.toast('健康检查失败：' + detail.value, 'err')
      return false
    }
  }

  return { state, text, detail, cardValue, cardSub, check }
})
