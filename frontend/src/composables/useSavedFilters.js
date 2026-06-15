// SPDX-License-Identifier: Apache-2.0
import { ref, computed } from 'vue'
import { usePreferencesStore } from '@/stores/preferences'

/**
 * Composable for saving and restoring filter states per page.
 * Persists to the server-side preferences document (namespace `filters`, keyed
 * by page identifier) via the preferences store — not localStorage — so saved
 * views follow the user across browsers and devices.
 *
 * Usage:
 *   const { savedViews, activeView, saveView, loadView, deleteView, currentFilters }
 *     = useSavedFilters('audit-log', { action: '', from: '', to: '' })
 */
export function useSavedFilters(pageKey, defaultFilters) {
  const prefsStore = usePreferencesStore()
  const currentFilters = ref({ ...defaultFilters })

  // Load saved views from the preferences document.
  const savedViews = ref(loadFromStore())

  function loadFromStore() {
    const stored = prefsStore.read('filters', pageKey, [])
    return Array.isArray(stored) ? stored : []
  }

  function persist() {
    prefsStore.write('filters', pageKey, savedViews.value)
  }

  function saveView(name) {
    savedViews.value = savedViews.value.filter(v => v.name !== name)
    savedViews.value.push({
      name,
      filters: { ...currentFilters.value },
      savedAt: new Date().toISOString(),
    })
    persist()
  }

  function loadView(name) {
    const view = savedViews.value.find(v => v.name === name)
    if (view) {
      currentFilters.value = { ...view.filters }
    }
  }

  function deleteView(name) {
    savedViews.value = savedViews.value.filter(v => v.name !== name)
    persist()
  }

  function resetFilters() {
    currentFilters.value = { ...defaultFilters }
  }

  const activeView = ref(null)

  return {
    savedViews: computed(() => savedViews.value),
    activeView,
    currentFilters,
    saveView,
    loadView,
    deleteView,
    resetFilters,
  }
}
