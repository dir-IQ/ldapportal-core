// SPDX-License-Identifier: Apache-2.0
import { defineStore } from 'pinia'
import { markRaw, ref } from 'vue'

/**
 * Registry of "does the current page hold unsaved work?" predicates.
 *
 * Admin pages register a predicate (usually "is an editor dialog open, or
 * a form partly filled") under a human-readable page name via
 * {@link useUnsavedChangesGuard}. Anything that is about to throw the
 * page away — today the sidebar profile picker, which remounts the
 * active view — asks {@link dirtyPage} first and warns the user, naming
 * the page, before proceeding.
 *
 * Predicates are evaluated lazily at ask time, so registering is cheap
 * and pages don't have to push state updates here.
 */
export interface UnsavedChangesGuard {
  /** Page name used in the warning, e.g. "Users". */
  page: string
  /** Returns true while the page has work the user would lose on reload. */
  isDirty: () => boolean
}

interface Registration extends UnsavedChangesGuard {
  id: number
}

export const useUnsavedChangesStore = defineStore('unsavedChanges', () => {
  const guards = ref<Registration[]>([])
  let nextId = 1

  /**
   * Register a guard. Returns the matching unregister function; callers
   * must invoke it when the page unmounts or a stale predicate would keep
   * warning for a page that no longer exists.
   */
  function register(page: string, isDirty: () => boolean): () => void {
    const id = nextId++
    // markRaw: the predicate closes over the page's refs already; wrapping
    // it in a reactive proxy would gain nothing and costs a proxy trap per call.
    guards.value = [...guards.value, markRaw({ id, page, isDirty })]
    return () => {
      guards.value = guards.value.filter(g => g.id !== id)
    }
  }

  /**
   * Name of the first registered page whose predicate reports unsaved
   * work, or null when everything is clean. A predicate that throws is
   * treated as clean rather than blocking navigation forever.
   */
  function dirtyPage(): string | null {
    for (const g of guards.value) {
      try {
        if (g.isDirty()) return g.page
      } catch {
        // A broken predicate must never trap the user on a page.
      }
    }
    return null
  }

  /** Drop every registration (logout / tests). */
  function clear(): void {
    guards.value = []
  }

  return { guards, register, dirtyPage, clear }
})
