import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { usePolling } from '../src/composables/usePolling'

/* usePolling depends on onMounted / onBeforeUnmount, so a probe component drives the lifecycle */
function makeProbe(onTick: () => void, initialEnabled = false) {
  return defineComponent({
    setup() {
      const enabled = ref(initialEnabled)
      const { running, start, stop, restart } = usePolling(onTick, 5000, { enabled, immediate: false })
      return { running, start, stop, restart, enabled }
    },
    render() {
      return h('div')
    }
  })
}

describe('usePolling', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('does not run while disabled', async () => {
    const tick = vi.fn()
    const wrapper = mount(makeProbe(tick, false))
    await vi.advanceTimersByTimeAsync(20000)
    expect(tick).not.toHaveBeenCalled()
    expect(wrapper.vm.running).toBe(false)
    wrapper.unmount()
  })

  it('runs repeatedly on the interval once started', async () => {
    const tick = vi.fn()
    const wrapper = mount(makeProbe(tick))
    wrapper.vm.start()
    expect(wrapper.vm.running).toBe(true)
    await vi.advanceTimersByTimeAsync(5000)
    expect(tick).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(10000)
    expect(tick).toHaveBeenCalledTimes(3)
    wrapper.vm.stop()
    await vi.advanceTimersByTimeAsync(20000)
    expect(tick).toHaveBeenCalledTimes(3)
    expect(wrapper.vm.running).toBe(false)
    wrapper.unmount()
  })

  it('restart is idempotent for a running poller', async () => {
    const tick = vi.fn()
    /* enabled=true so that restart() actually re-arms the interval */
    const wrapper = mount(makeProbe(tick, true))
    wrapper.vm.restart()
    await vi.advanceTimersByTimeAsync(5000)
    /* stop() clears the old interval before starting a new one, so a restart
       must not double the tick rate: exactly one tick per interval window */
    expect(tick).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(5000)
    expect(tick).toHaveBeenCalledTimes(2)
    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(20000)
    expect(tick).toHaveBeenCalledTimes(2)
  })

  it('keeps polling after a throwing tick and stops on unmount', async () => {
    let calls = 0
    const Prode = defineComponent({
      setup() {
        const enabled = ref(true)
        const { start } = usePolling(
          () => {
            calls++
            throw new Error('boom')
          },
          1000,
          { enabled, immediate: false }
        )
        return { start }
      },
      render() {
        return h('div')
      }
    })
    const wrapper = mount(Prode)
    wrapper.vm.start()
    await vi.advanceTimersByTimeAsync(3000)
    expect(calls).toBe(3)
    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(5000)
    expect(calls).toBe(3)
  })

  it('starts on mount when already enabled', async () => {
    const tick = vi.fn()
    const wrapper = mount(makeProbe(tick, true))
    await vi.advanceTimersByTimeAsync(10000)
    expect(tick).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
})