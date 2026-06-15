// SPDX-License-Identifier: Apache-2.0
import { ref, watch, type Ref } from 'vue'

const MIN_W = 280
const MIN_H = 160
const MARGIN = 8   // keep this much of the panel inside the viewport

/**
 * Drag-to-move and drag-to-resize behaviour for a flex-centered modal panel.
 *
 * The panel is centered by its parent flex container; we layer a translate
 * `offset` (for move) and an explicit `size` (for resize) on top. Resize is
 * SE-corner only and anchors the top-left: because changing the width re-centers
 * the panel, we compensate `offset` by half the size delta so the top-left edge
 * stays put and the corner follows the pointer.
 *
 * Pointer-events based (mouse/touch/pen). Both gestures clamp to the viewport
 * and reset on open. No-ops unless the matching getter (`movable`/`resizable`)
 * returns true — gate those on the props plus a min viewport width.
 */
export function useDraggableModal(opts: {
  panelRef: Ref<HTMLElement | null>
  movable: () => boolean
  resizable: () => boolean
  isOpen: () => boolean
  /**
   * Optional initial size + offset applied each time the modal opens (e.g. a
   * persisted size, or a "fill the content area" default). Returning null falls
   * back to the centered, auto-sized default.
   */
  getInitialLayout?: () => { size: { w: number; h: number } | null; offset: { x: number; y: number } } | null
  /** Called with the panel size when a resize gesture ends (for persistence). */
  onPersist?: (size: { w: number; h: number } | null) => void
}) {
  const offset = ref({ x: 0, y: 0 })
  const size = ref<{ w: number; h: number } | null>(null)

  let mode: 'move' | 'resize' | null = null
  let sx = 0
  let sy = 0          // pointer position at gesture start
  let oOX = 0
  let oOY = 0         // offset at gesture start
  // move:
  let baseLeft = 0
  let baseTop = 0
  let pW = 0
  let pH = 0
  // resize (SE):
  let sW = 0
  let sH = 0
  let maxW = 0
  let maxH = 0

  // Clamp that tolerates an inverted range (target larger than the viewport).
  const clamp = (v: number, a: number, b: number): number =>
    Math.min(Math.max(v, Math.min(a, b)), Math.max(a, b))

  function onMove(e: PointerEvent): void {
    if (mode === 'move') {
      const nx = oOX + (e.clientX - sx)
      const ny = oOY + (e.clientY - sy)
      offset.value = {
        x: clamp(nx, MARGIN - baseLeft, window.innerWidth - MARGIN - pW - baseLeft),
        y: clamp(ny, MARGIN - baseTop, window.innerHeight - MARGIN - pH - baseTop),
      }
    } else if (mode === 'resize') {
      const w = clamp(sW + (e.clientX - sx), MIN_W, Math.max(MIN_W, maxW))
      const h = clamp(sH + (e.clientY - sy), MIN_H, Math.max(MIN_H, maxH))
      size.value = { w, h }
      // Anchor the top-left: cancel the re-centering shift from the size change.
      offset.value = { x: oOX + (w - sW) / 2, y: oOY + (h - sH) / 2 }
    }
  }

  function endSession(): void {
    const wasResize = mode === 'resize'
    mode = null
    document.body.style.removeProperty('user-select')
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerup', endSession)
    if (wasResize) opts.onPersist?.(size.value)
  }

  function startSession(e: PointerEvent): void {
    sx = e.clientX
    sy = e.clientY
    oOX = offset.value.x
    oOY = offset.value.y
    document.body.style.userSelect = 'none'
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', endSession)
    e.preventDefault()
    e.stopPropagation()
  }

  function onHandlePointerDown(e: PointerEvent): void {
    if (!opts.movable() || e.button !== 0) return
    // Never start a drag from an interactive control in the header (the ×).
    if ((e.target as HTMLElement | null)?.closest('button, a, input, select, textarea')) return
    const panel = opts.panelRef.value
    if (!panel) return
    const r = panel.getBoundingClientRect()
    baseLeft = r.left - offset.value.x
    baseTop = r.top - offset.value.y
    pW = r.width
    pH = r.height
    mode = 'move'
    startSession(e)
  }

  function onResizePointerDown(e: PointerEvent): void {
    if (!opts.resizable() || e.button !== 0) return
    const panel = opts.panelRef.value
    if (!panel) return
    const r = panel.getBoundingClientRect()
    sW = r.width
    sH = r.height
    // Cap growth so the SE corner can't run past the viewport edge.
    maxW = window.innerWidth - MARGIN - r.left
    maxH = window.innerHeight - MARGIN - r.top
    mode = 'resize'
    startSession(e)
  }

  // On open: apply a provided initial layout (persisted size / content-fill
  // default), otherwise reset to centered + auto-sized.
  watch(opts.isOpen, (open) => {
    if (!open) return
    const layout = opts.getInitialLayout?.() ?? null
    if (layout) {
      size.value = layout.size
      offset.value = layout.offset
    } else {
      offset.value = { x: 0, y: 0 }
      size.value = null
    }
  })

  return { offset, size, onHandlePointerDown, onResizePointerDown }
}
