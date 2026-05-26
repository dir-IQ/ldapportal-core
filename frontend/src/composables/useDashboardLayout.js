// SPDX-License-Identifier: Apache-2.0
import { ref, watch } from 'vue'

const STORAGE_KEY = 'dashboard-layout'

const DEFAULT_WIDGETS = [
  { id: 'compliance', label: 'Compliance Posture', visible: true, order: 0 },
  { id: 'metrics', label: 'Aggregate Metrics', visible: true, order: 1 },
  { id: 'approvals', label: 'Approval Aging', visible: true, order: 2 },
  { id: 'campaigns', label: 'Active Campaigns', visible: true, order: 3 },
  { id: 'directories', label: 'Directories', visible: true, order: 4 },
  { id: 'activity', label: 'Recent Activity', visible: true, order: 5 },
  { id: 'notifications', label: 'My Notifications', visible: false, order: 6 },
  { id: 'reviews', label: 'My Pending Reviews', visible: false, order: 7 },
]

/**
 * Composable for managing dashboard widget layout.
 * Persists visibility and order to localStorage.
 */
export function useDashboardLayout() {
  const widgets = ref(loadLayout())
  const editMode = ref(false)

  function loadLayout() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY)
      if (raw) {
        const saved = JSON.parse(raw)
        // Merge saved preferences with defaults (in case new widgets were added)
        const map = new Map(saved.map(w => [w.id, w]))
        return DEFAULT_WIDGETS.map(def => {
          const saved = map.get(def.id)
          return saved ? { ...def, visible: saved.visible, order: saved.order } : { ...def }
        }).sort((a, b) => a.order - b.order)
      }
    } catch { /* ignore */ }
    return DEFAULT_WIDGETS.map(w => ({ ...w }))
  }

  function persist() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(
        widgets.value.map(w => ({ id: w.id, visible: w.visible, order: w.order }))
      ))
    } catch { /* storage full */ }
  }

  function toggleWidget(id) {
    const w = widgets.value.find(w => w.id === id)
    if (w) { w.visible = !w.visible; persist() }
  }

  function moveUp(id) {
    const idx = widgets.value.findIndex(w => w.id === id)
    if (idx > 0) {
      const temp = widgets.value[idx]
      widgets.value[idx] = widgets.value[idx - 1]
      widgets.value[idx - 1] = temp
      reorder()
    }
  }

  function moveDown(id) {
    const idx = widgets.value.findIndex(w => w.id === id)
    if (idx < widgets.value.length - 1) {
      const temp = widgets.value[idx]
      widgets.value[idx] = widgets.value[idx + 1]
      widgets.value[idx + 1] = temp
      reorder()
    }
  }

  function reorder() {
    widgets.value.forEach((w, i) => { w.order = i })
    persist()
  }

  function resetLayout() {
    widgets.value = DEFAULT_WIDGETS.map(w => ({ ...w }))
    persist()
  }

  const visibleWidgets = ref([])
  watch(widgets, (w) => {
    visibleWidgets.value = w.filter(w => w.visible)
  }, { immediate: true, deep: true })

  return {
    widgets,
    visibleWidgets,
    editMode,
    toggleWidget,
    moveUp,
    moveDown,
    resetLayout,
  }
}
