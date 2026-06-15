// SPDX-License-Identifier: Apache-2.0
import type { Directive } from 'vue'

// Same focusable set as composables/useDialogA11y.ts. The composable powers
// the reusable AppModal/ConfirmDialog (script-side); this directive is the
// template-only counterpart for the many bespoke in-view modals/drawers, so
// they gain a focus trap without each view's <script> being touched (and thus
// without triggering a whole-view TS conversion).
const SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'textarea:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',')

interface TrapState {
  onKeydown: (e: KeyboardEvent) => void
  previouslyFocused: HTMLElement | null
}

const states = new WeakMap<HTMLElement, TrapState>()

function focusable(el: HTMLElement): HTMLElement[] {
  // getClientRects() is empty for display:none / detached nodes — skips hidden
  // controls without the position:fixed false-negative an offsetParent check hits.
  return Array.from(el.querySelectorAll<HTMLElement>(SELECTOR)).filter(
    (n) => n.getClientRects().length > 0,
  )
}

function install(el: HTMLElement): void {
  const previouslyFocused = document.activeElement as HTMLElement | null

  const onKeydown = (e: KeyboardEvent): void => {
    if (e.key !== 'Tab') return
    const els = focusable(el)
    if (els.length === 0) return
    const first = els[0]
    const last = els[els.length - 1]
    const active = document.activeElement as HTMLElement | null
    if (e.shiftKey && active === first) {
      e.preventDefault()
      last.focus()
    } else if (!e.shiftKey && active === last) {
      e.preventDefault()
      first.focus()
    }
  }

  el.addEventListener('keydown', onKeydown)
  states.set(el, { onKeydown, previouslyFocused })

  // Focus the first control once the dialog has painted.
  requestAnimationFrame(() => {
    if (!el.isConnected) return
    const els = focusable(el)
    ;(els[0] ?? el).focus()
  })
}

function teardown(el: HTMLElement): void {
  const s = states.get(el)
  if (!s) return
  el.removeEventListener('keydown', s.onKeydown)
  // el is detached on unmount, but previouslyFocused is still in the document.
  s.previouslyFocused?.focus?.()
  states.delete(el)
}

/**
 * v-dialog-a11y — focus trap + focus restore for a modal/drawer container.
 *
 * Apply to the dialog's root element alongside `role="dialog"`,
 * `aria-modal="true"` and `aria-labelledby` in the template. On insert it
 * focuses the first control and cycles Tab within the element; on removal it
 * restores focus to whatever was focused before opening.
 *
 * Escape-to-close stays in the template (`@keydown.escape="<close expr>"`):
 * only the component knows how to close itself. Assumes the element is shown
 * via `v-if` (mounted == open); for `v-show` toggles use `useDialogA11y`.
 */
export const vDialogA11y: Directive<HTMLElement> = {
  mounted: install,
  unmounted: teardown,
}
