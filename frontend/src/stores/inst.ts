/* instId 候选缓存（legacy instCache / loadInstCache 等价实现） */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiGet } from '../api/client'
import { EP } from '../api/endpoints'

/* instId 第一段即 coin：BTC-USDT-SWAP → BTC */
export function coinOfInst(instId: unknown): string {
  const s = String(instId || '')
  const i = s.indexOf('-')
  return i > 0 ? s.slice(0, i) : s
}

export const useInstStore = defineStore('inst', () => {
  const list = ref<any[]>([])
  const loaded = ref(false)
  const ids = ref<Record<string, boolean>>({})
  let loading: Promise<any[]> | null = null

  /* 品种清单只拉一次（SWAP + FUTURES），按 instId 升序缓存 */
  async function load(): Promise<any[]> {
    if (loaded.value) return list.value
    if (loading) return loading
    loading = (async () => {
      const out: any[] = []
      const types = ['SWAP', 'FUTURES']
      for (let i = 0; i < types.length; i++) {
        try {
          const data = await apiGet<any[]>(EP.instruments, { instType: types[i], limit: 1000 })
          if (Array.isArray(data)) {
            for (let j = 0; j < data.length; j++) out.push(data[j])
          }
        } catch (err) {
          /* 单个类型失败不影响其它类型 */
        }
      }
      out.sort((a, b) => {
        const x = String(a.instId || '')
        const y = String(b.instId || '')
        return x < y ? -1 : x > y ? 1 : 0
      })
      const map: Record<string, boolean> = {}
      for (let k = 0; k < out.length; k++) map[String(out[k].instId || '').toUpperCase()] = true
      ids.value = map
      list.value = out
      loaded.value = out.length > 0
      return out
    })()
      .then(
        (l) => {
          loading = null
          return l
        },
        () => {
          loading = null
          return []
        }
      )
    return loading
  }

  function hasId(instId: string): boolean {
    return !!ids.value[String(instId || '').toUpperCase()]
  }

  function reload(): Promise<any[]> {
    loaded.value = false
    list.value = []
    ids.value = {}
    return load()
  }

  return { list, loaded, ids, load, hasId, reload }
})
