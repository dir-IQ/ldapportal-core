// SPDX-License-Identifier: Apache-2.0
import { onBeforeUnmount, watch, nextTick, type Ref } from 'vue'

/**
 * Accessible-dialog behaviour shared by every modal/drawer: focus trap,
 * Escape-to-close, and focus restoration to the trigger on close.
 *
 * Pair it with `role="dialog"`, `aria-modal="true"` and an `aria-labelledby`
 * pointing at the dialog's title (use `useId()` for the id) on the markup —
 * this composable owns the keyboard/focus behaviour, not the ARIA wiring.
 *
 * `isOpen` is a getter (so it tracks a prop/ref reactively); `containerRef`
 * is the dialog panel element (the focusable region); `onClose` is invoked on
 * Escape and should flip whatever drives `isOpen`.
 */
export function useDialogA11y(opts: {
  isOpen: () => boolean
  containerRef: Ref<HTMLElement | null>
  onClose: () => void
}): void {
  const SELECTOR = [
    'a[href]',
    'button:not([disabled])',
    'textarea:not([disabled])',
    'input:not([disabled])',
    'select:not([disabled])',
    '[tabindex]:not([tabindex="-1"])',
  ].join(',')

  let previouslyFocused: HTMLElement | null = null

  function focusable(): HTMLElement[] {
    const c = opts.containerRef.value
    if (!c) return []
    // getClientRects() is empty for display:none / detached nodes, so this
    // skips hidden controls without the position:fixed false-negative that
    // an offsetParent check would hit on the fixed-positioned backdrop.
    return Array.from(c.querySelectorAll<HTMLElement>(SELECTOR)).filter(
      (el) => el.getClientRects().length > 0,
    )
  }

  function onKeydown(e: KeyboardEvent): void {
    if (!opts.isOpen()) return
    if (e.key === 'Escape') {
      e.stopPropagation()
      opts.onClose()
      return
    }
    if (e.key !== 'Tab') return
    const els = focusable()
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

  watch(
    opts.isOpen,
    async (open) => {
      if (open) {
        previouslyFocused = document.activeElement as HTMLElement | null
        document.addEventListener('keydown', onKeydown, true)
        await nextTick()
        focusable()[0]?.focus()
      } else {
        document.removeEventListener('keydown', onKeydown, true)
        previouslyFocused?.focus?.()
        previouslyFocused = null
      }
    },
    { immediate: true },
  )

  onBeforeUnmount(() => {
    document.removeEventListener('keydown', onKeydown, true)
  })
}
