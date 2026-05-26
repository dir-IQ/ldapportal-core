// SPDX-License-Identifier: Apache-2.0
import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * Backing store for the global ConfirmDialog mounted in AppLayout.
 *
 * Use the {@link useConfirm} composable rather than poking this store
 * directly — it returns a Promise<boolean> per call so consumers can
 * write {@code if (await confirm(...))} where they used to write
 * {@code if (window.confirm(...))}.
 *
 * Mirrors the upgradeModal store's shape (visible/params + show/hide).
 * Only one confirm dialog can be open at a time; if {@code show} is
 * called while one is already open, the prior promise is rejected
 * (treated as "user cancelled by superseding dialog"). In practice
 * we never trigger overlapping confirms — the route-leave guards and
 * the toggle-out confirms can't fire simultaneously.
 */
export const useConfirmStore = defineStore('confirm', () => {
  const visible = ref(false)
  /** @type {import('vue').Ref<{
   *    title: string,
   *    message: string,
   *    confirmLabel: string,
   *    confirmClass: string,
   *    danger: boolean,
   *  } | null>} */
  const params = ref(null)
  /** @type {((value: boolean) => void) | null} */
  let resolver = null

  function show(opts) {
    // If another confirm is already open, settle its promise as
    // cancelled before replacing it. Avoids dangling resolvers when
    // two call sites race.
    if (resolver) {
      try { resolver(false) } catch { /* best-effort */ }
    }
    params.value = {
      title: opts.title ?? 'Confirm',
      message: opts.message ?? 'Are you sure?',
      confirmLabel: opts.confirmLabel ?? 'Confirm',
      confirmClass: opts.confirmClass ?? '',
      danger: opts.danger ?? false,
    }
    visible.value = true
    return new Promise((resolve) => {
      resolver = resolve
    })
  }

  function resolve(value) {
    visible.value = false
    if (resolver) {
      const r = resolver
      resolver = null
      r(value)
    }
  }

  return { visible, params, show, resolve }
})
