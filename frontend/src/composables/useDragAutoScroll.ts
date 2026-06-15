// SPDX-License-Identifier: Apache-2.0
import { onBeforeUnmount } from 'vue'

/**
 * Smooth edge auto-scroll for native HTML5 drag-and-drop.
 *
 * The browser's own autoscroll during an HTML5 drag is jerky or absent inside a
 * scrollable container (e.g. a modal body), which makes reordering a long list
 * by drag painful. This drives a {@link requestAnimationFrame} loop that scrolls
 * the nearest scrollable ancestor whenever the drag pointer enters a band near
 * its top or bottom edge, at a speed that ramps with how deep into the band the
 * pointer is.
 *
 * Usage: call {@link start} from your `dragstart` handler (passing the
 * component's root element) and {@link stop} from `dragend`. The loop also tears
 * down on unmount.
 */
export interface DragAutoScrollOptions {
  /** Height (px) of the edge band that triggers scrolling. Default 72. */
  edge?: number
  /** Maximum scroll speed (px per frame) at the very edge. Default 18. */
  maxSpeed?: number
}

export interface DragAutoScroll {
  start: (rootEl: HTMLElement | null | undefined) => void
  stop: () => void
}

export function useDragAutoScroll(options: DragAutoScrollOptions = {}): DragAutoScroll {
  const edge = options.edge ?? 72
  const maxSpeed = options.maxSpeed ?? 18

  let container: HTMLElement | null = null
  let pointerY = 0
  let hasPointer = false
  let raf: number | null = null

  function findScrollParent(el: HTMLElement | null): HTMLElement | null {
    let node: HTMLElement | null = el
    while (node) {
      const overflowY = getComputedStyle(node).overflowY
      if ((overflowY === 'auto' || overflowY === 'scroll')
          && node.scrollHeight > node.clientHeight) {
        return node
      }
      node = node.parentElement
    }
    return null
  }

  // Native drag doesn't fire mousemove, so the pointer position is read off the
  // continuous `dragover` stream instead.
  function onDragOver(e: DragEvent): void {
    pointerY = e.clientY
    hasPointer = true
  }

  function step(): void {
    if (!container || !hasPointer) { raf = null; return }
    const rect = container.getBoundingClientRect()
    const topDist = pointerY - rect.top
    const bottomDist = rect.bottom - pointerY
    let delta = 0
    if (topDist < edge && topDist > -edge) {
      delta = -maxSpeed * Math.min(1, (edge - topDist) / edge)
    } else if (bottomDist < edge && bottomDist > -edge) {
      delta = maxSpeed * Math.min(1, (edge - bottomDist) / edge)
    }
    if (delta !== 0) container.scrollTop += delta
    raf = requestAnimationFrame(step)
  }

  function start(rootEl: HTMLElement | null | undefined): void {
    container = findScrollParent(rootEl ?? null)
    if (!container) return
    pointerY = 0
    hasPointer = false
    document.addEventListener('dragover', onDragOver, { passive: true })
    if (raf === null) raf = requestAnimationFrame(step)
  }

  function stop(): void {
    document.removeEventListener('dragover', onDragOver)
    if (raf !== null) { cancelAnimationFrame(raf); raf = null }
    container = null
    hasPointer = false
  }

  onBeforeUnmount(stop)

  return { start, stop }
}
