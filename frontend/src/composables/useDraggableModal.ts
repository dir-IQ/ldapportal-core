// SPDX-License-Identifier: Apache-2.0
import { ref, watch, type Ref } from 'vue'

/**
 * Makes a modal panel draggable by a handle (its header). Tracks a translate
 * offset applied to the panel and clamps it so the panel always stays within
 * the viewport (the header — and its close button — can never be dragged out of
 * reach). Resets to centered every time the modal opens.
 *
 * Pointer-events based, so it covers mouse + touch + pen with one path. A no-op
 * unless `enabled()` (gate on a `movable` prop and, if you like, a min viewport
 * width so phones keep modals put).
 */
export function useDraggableModal(opts: {
  panelRef: Ref<HTMLElement | null>
  enabled: () => boolean
  isOpen: () => boolean
}) {
  const offset = ref({ x: 0, y: 0 })

  let dragging = false
  let startX = 0
  let startY = 0          // pointer position at drag start
  let originX = 0
  let originY = 0         // offset at drag start
  let baseLeft = 0
  let baseTop = 0         // panel's untranslated top-left (offset removed)
  let panelW = 0
  let panelH = 0

  // Clamp that tolerates an inverted range (panel larger than the viewport):
  // Math.min/max swap keeps it from snapping to a nonsensical bound.
  function clamp(v: number, a: number, b: number): number {
    return Math.min(Math.max(v, Math.min(a, b)), Math.max(a, b))
  }

  function onMove(e: PointerEvent): void {
    if (!dragging) return
    const m = 8
    const nx = originX + (e.clientX - startX)
    const ny = originY + (e.clientY - startY)
    offset.value = {
      x: clamp(nx, m - baseLeft, window.innerWidth - m - panelW - baseLeft),
      y: clamp(ny, m - baseTop, window.innerHeight - m - panelH - baseTop),
    }
  }

  function onUp(): void {
    dragging = false
    document.body.style.removeProperty('user-select')
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerup', onUp)
  }

  function onHandlePointerDown(e: PointerEvent): void {
    if (!opts.enabled() || e.button !== 0) return
    // Never start a drag from an interactive control in the header (the ×).
    if ((e.target as HTMLElement | null)?.closest('button, a, input, select, textarea')) return
    const panel = opts.panelRef.value
    if (!panel) return

    const rect = panel.getBoundingClientRect()
    baseLeft = rect.left - offset.value.x
    baseTop = rect.top - offset.value.y
    panelW = rect.width
    panelH = rect.height
    startX = e.clientX
    startY = e.clientY
    originX = offset.value.x
    originY = offset.value.y
    dragging = true

    // Suppress text selection while dragging the header.
    document.body.style.userSelect = 'none'
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
    e.preventDefault()
  }

  // Re-center each time the modal opens so it never reappears displaced.
  watch(opts.isOpen, (open) => { if (open) offset.value = { x: 0, y: 0 } })

  return { offset, onHandlePointerDown }
}
