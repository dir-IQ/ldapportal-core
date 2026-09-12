// SPDX-License-Identifier: Apache-2.0
import { onScopeDispose } from 'vue'
import { useUnsavedChangesStore } from '@/stores/unsavedChanges'

/**
 * Declare, from a page's setup, when that page holds unsaved work.
 *
 *   useUnsavedChangesGuard('Users', () => showEditDialog.value)
 *
 * The predicate is consulted by AppLayout before it remounts the active
 * view (a sidebar profile switch). When it returns true the user is asked
 * "The Users page has unsaved changes … Switch anyway?" and can cancel.
 *
 * Registration lives for the component's effect scope: it is removed
 * automatically when the page unmounts, so a stale guard never warns for
 * a page that is already gone. Call it once per page, from `<script setup>`.
 */
export function useUnsavedChangesGuard(page: string, isDirty: () => boolean): void {
  const unregister = useUnsavedChangesStore().register(page, isDirty)
  onScopeDispose(unregister)
}
