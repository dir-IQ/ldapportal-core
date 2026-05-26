// SPDX-License-Identifier: Apache-2.0
import { ref, computed } from 'vue'

/**
 * Composable for saving and restoring filter states per page.
 * Persists to localStorage keyed by page identifier.
 *
 * Usage:
 *   const { savedViews, activeView, saveView, loadView, deleteView, currentFilters }
 *     = useSavedFilters('audit-log', { action: '', from: '', to: '' })
 */
export function useSavedFilters(pageKey, defaultFilters) {
  const STORAGE_KEY = `saved-filters:${pageKey}`
  const currentFilters = ref({ ...defaultFilters })

  // Load saved views from localStorage
  const savedViews = ref(loadFromStorage())

  function loadFromStorage() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY)
      return raw ? JSON.parse(raw) : []
    } catch { return [] }
  }

  function persist() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(savedViews.value))
    } catch { /* storage full or disabled */ }
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
