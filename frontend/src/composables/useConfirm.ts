// SPDX-License-Identifier: Apache-2.0
/**
 * Promise-based wrapper around the existing {@code ConfirmDialog}
 * component, replacing native {@code window.confirm()} calls. Mounts
 * one shared dialog instance globally (in AppLayout) and routes each
 * call through the {@code useConfirmStore} so caller code stays
 * one-liner-flat:
 *
 *   import { useConfirm } from '@/composables/useConfirm'
 *   const confirm = useConfirm()
 *   if (await confirm('Reset dashboard layout?')) layoutStore.reset()
 *
 * The opts shape matches {@code ConfirmDialog}'s props so any
 * existing presentation (danger styling, custom button label, etc.)
 * carries over.
 */
import { useConfirmStore } from '@/stores/confirm'

export interface ConfirmOptions {
  title?: string
  /** Body text shown above the buttons. Plain string only (no HTML). */
  message: string
  /** Affirmative button label. Defaults to "Confirm". */
  confirmLabel?: string
  /** Optional Tailwind class string overriding the affirmative button. */
  confirmClass?: string
  /** Renders the affirmative button red when true. */
  danger?: boolean
}

export function useConfirm() {
  const store = useConfirmStore()

  return function confirm(
    optsOrMessage: ConfirmOptions | string,
  ): Promise<boolean> {
    const opts: ConfirmOptions = typeof optsOrMessage === 'string'
      ? { message: optsOrMessage }
      : optsOrMessage
    return store.show(opts)
  }
}
