// SPDX-License-Identifier: Apache-2.0
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { dnEquals } from '@/utils/dn'

/** One entry shown in the quick-look drawer. */
export interface QuickLookTarget {
  directoryId: string
  dn: string
}

/**
 * The DN quick-look drawer's state: a stack of entries, so following a DN
 * from inside the drawer (secDN → user → group …) can be stepped back.
 * Opening from the page starts a fresh stack; following a link inside the
 * drawer pushes onto it. Rendered once by AppLayout (DnQuickLookDrawer).
 */
export const useQuickLookStore = defineStore('quickLook', () => {
  const stack = ref<QuickLookTarget[]>([])

  const current = computed<QuickLookTarget | null>(() => stack.value[stack.value.length - 1] ?? null)
  const isOpen = computed(() => stack.value.length > 0)
  const canGoBack = computed(() => stack.value.length > 1)

  /** Open the drawer at `dn`, replacing whatever it was showing. */
  function open(directoryId: string, dn: string): void {
    stack.value = [{ directoryId, dn }]
  }

  /** Follow a DN from inside the drawer; a no-op if it's already showing. */
  function push(directoryId: string, dn: string): void {
    const top = current.value
    if (top && top.directoryId === directoryId && dnEquals(top.dn, dn)) return
    stack.value = [...stack.value, { directoryId, dn }]
  }

  function back(): void {
    if (canGoBack.value) stack.value = stack.value.slice(0, -1)
  }

  /** Return to the `index`-th entry of the trail (breadcrumb click). */
  function goTo(index: number): void {
    if (index >= 0 && index < stack.value.length) stack.value = stack.value.slice(0, index + 1)
  }

  function close(): void {
    stack.value = []
  }

  return { stack, current, isOpen, canGoBack, open, push, back, goTo, close }
})
