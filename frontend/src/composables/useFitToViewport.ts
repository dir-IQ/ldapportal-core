// SPDX-License-Identifier: Apache-2.0
import { ref, onMounted, onBeforeUnmount, type Ref } from 'vue'

/**
 * Sizes a scroll container to the space that is actually left between its top
 * edge and the bottom of the viewport, so the surrounding form fits in one
 * screen and only the container scrolls internally (no page + inner
 * double-scrollbar).
 *
 * `reserveBelow` leaves room for whatever sits beneath the container (e.g. the
 * confirm controls); `minHeight` is a floor so a short viewport still shows a
 * usable slice of rows (the page may then scroll, which is unavoidable).
 *
 * Re-measures on mount, window resize, and any layout shift reported by a
 * body-level ResizeObserver (e.g. a warning banner appearing above the table).
 * Layout APIs are guarded so the composable is a safe no-op under jsdom.
 */
export function useFitToViewport(
  el: Ref<HTMLElement | null>,
  opts: { reserveBelow?: number; minHeight?: number } = {},
) {
  const reserveBelow = opts.reserveBelow ?? 120
  const minHeight = opts.minHeight ?? 160
  const height = ref<number | null>(null)

  let frame = 0
  let ro: ResizeObserver | null = null

  function measure() {
    const node = el.value
    if (!node || typeof window === 'undefined') return
    const top = node.getBoundingClientRect().top
    const available = window.innerHeight - top - reserveBelow
    height.value = Math.max(minHeight, Math.round(available))
  }

  function remeasure() {
    if (typeof window === 'undefined' || typeof requestAnimationFrame === 'undefined') {
      measure()
      return
    }
    cancelAnimationFrame(frame)
    frame = requestAnimationFrame(measure)
  }

  onMounted(() => {
    remeasure()
    window.addEventListener('resize', remeasure)
    if (typeof ResizeObserver !== 'undefined' && typeof document !== 'undefined') {
      ro = new ResizeObserver(remeasure)
      ro.observe(document.body)
    }
  })

  onBeforeUnmount(() => {
    if (typeof cancelAnimationFrame !== 'undefined') cancelAnimationFrame(frame)
    if (typeof window !== 'undefined') window.removeEventListener('resize', remeasure)
    ro?.disconnect()
  })

  return { height, remeasure }
}
