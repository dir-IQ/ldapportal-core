// SPDX-License-Identifier: Apache-2.0
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { cloneDefaults, mergeWithDefaults } from '@/components/dashboard/layout'
import {
  getDashboardLayout,
  saveDashboardLayout,
  resetDashboardLayout,
} from '@/api/dashboard'

/**
 * Dashboard layout store — server-persisted, no browser storage.
 *
 * The layout is one of the user's UI customizations and lives server-side (the
 * `dashboard_layouts` table, via /dashboard/layout) so it follows the user
 * across browsers and devices. It is structurally large and has its own
 * endpoint, so it stays on that endpoint rather than folding into the general
 * preferences document — but, like everything else, it is never cached in
 * localStorage.
 *
 * Flow on mount:
 *   1. Store starts with the default layout.
 *   2. DashboardView calls load() which fetches the server copy. Server wins.
 *   3. Save/reset write straight to the server.
 */
export const useDashboardLayoutStore = defineStore('dashboardLayout', () => {
  const layout = ref(cloneDefaults())
  const editing = ref(false)
  const draft = ref(null)
  const loaded = ref(false)

  const active = computed(() => (editing.value && draft.value ? draft.value : layout.value))

  // ── Server sync ───────────────────────────────────────────────────────────
  /**
   * Load from server. Idempotent — safe to call repeatedly; only the first
   * call actually hits the server. On failure the default layout stands.
   */
  async function load() {
    if (loaded.value) return
    loaded.value = true
    try {
      const { data } = await getDashboardLayout()
      const serverHasLayout = data && typeof data === 'object' && Object.keys(data).length > 0
      if (serverHasLayout) {
        layout.value = mergeWithDefaults(data)
      }
    } catch {
      // API unreachable — keep the default layout.
    }
  }

  // ── Edit lifecycle ───────────────────────────────────────────────────────
  function startEdit() {
    draft.value = JSON.parse(JSON.stringify(layout.value))
    editing.value = true
  }

  function cancelEdit() {
    draft.value = null
    editing.value = false
  }

  async function save() {
    if (!draft.value) return
    layout.value = draft.value
    draft.value = null
    editing.value = false
    try { await saveDashboardLayout(layout.value) } catch { /* best-effort */ }
  }

  async function reset() {
    layout.value = cloneDefaults()
    if (editing.value) draft.value = cloneDefaults()
    try { await resetDashboardLayout() } catch { /* best-effort */ }
  }

  // ── Per-item visibility toggles ──────────────────────────────────────────
  function togglePanelHidden(panelId) {
    const target = editing.value ? draft.value : layout.value
    if (!target) return
    const idx = target.panelsHidden.indexOf(panelId)
    if (idx >= 0) target.panelsHidden.splice(idx, 1)
    else target.panelsHidden.push(panelId)
  }

  function toggleMetricHidden(metricId) {
    const target = editing.value ? draft.value : layout.value
    if (!target) return
    const idx = target.metricCards.hidden.indexOf(metricId)
    if (idx >= 0) target.metricCards.hidden.splice(idx, 1)
    else target.metricCards.hidden.push(metricId)
  }

  function isPanelHidden(panelId) {
    return active.value.panelsHidden.includes(panelId)
  }
  function isMetricHidden(metricId) {
    return active.value.metricCards.hidden.includes(metricId)
  }

  return {
    layout, editing, draft, active, loaded,
    load,
    startEdit, cancelEdit, save, reset,
    togglePanelHidden, toggleMetricHidden,
    isPanelHidden, isMetricHidden,
  }
})
