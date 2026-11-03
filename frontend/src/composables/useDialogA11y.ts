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
 *
 * Dialogs can stack (a modal opened from a modal, a confirmation over a
 * modal). Each listens on `document`, so only the topmost open one acts on a
 * key: Escape closes that one alone, and the focus trap is its own.
 */
// Open dialogs, oldest first; the last entry is the one the keyboard belongs to.
const openStack: symbol[] = []

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
  const token = Symbol('dialog')

  function release(): void {
    const i = openStack.indexOf(token)
    if (i !== -1) openStack.splice(i, 1)
  }

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
    if (!opts.isOpen() || openStack[openStack.length - 1] !== token) return
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
        release()
        openStack.push(token)
        document.addEventListener('keydown', onKeydown, true)
        await nextTick()
        focusable()[0]?.focus()
      } else {
        release()
        document.removeEventListener('keydown', onKeydown, true)
        previouslyFocused?.focus?.()
        previouslyFocused = null
      }
    },
    { immediate: true },
  )

  onBeforeUnmount(() => {
    release()
    document.removeEventListener('keydown', onKeydown, true)
  })
}
