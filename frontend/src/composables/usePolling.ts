/* 轮询：与 legacy startStatusTimer / startJobsTimer 同语义（5s 间隔、可开关） */
import { onBeforeUnmount, onMounted, ref, type Ref } from 'vue'

export interface PollingOptions {
  enabled?: Ref<boolean> | boolean
  immediate?: boolean
}

export function usePolling(fn: () => void | Promise<void>, intervalMs: number, options: PollingOptions = {}) {
  const running = ref(false)
  let timer: ReturnType<typeof setInterval> | null = null

  const enabled = (): boolean => {
    const e = options.enabled
    if (e === undefined || e === null) return true
    return typeof e === 'boolean' ? e : !!e.value
  }

  async function tick() {
    if (!running.value) return
    try {
      await fn()
    } catch (err) {
      /* 轮询失败由页面自身处理，这里不中断定时器 */
      console.error('polling tick failed', err)
    }
  }

  function start() {
    if (timer !== null) return
    running.value = true
    if (options.immediate !== false) void tick()
    timer = setInterval(() => void tick(), intervalMs)
  }

  function stop() {
    running.value = false
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }

  function restart() {
    stop()
    if (enabled()) start()
  }

  onMounted(() => {
    if (enabled()) start()
  })
  onBeforeUnmount(stop)

  return { running, start, stop, restart }
}