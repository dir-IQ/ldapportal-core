// SPDX-License-Identifier: Apache-2.0
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { cloneDefaults, mergeWithDefaults } from '@/components/dashboard/layout'
import {
  getDashboardLayout,
  saveDashboardLayout,
  resetDashboardLayout,
} from '@/api/dashboard'

const STORAGE_KEY = 'dashboardLayout.v1'

/**
 * Dashboard layout store (Phase B — server-persisted, localStorage cache).
 *
 * Flow on mount:
 *   1. Store init reads localStorage synchronously so the dashboard can render
 *      the user's last-seen layout instantly (no default-layout flash).
 *   2. DashboardView calls load() which fetches the server copy and replaces
 *      the local one. Server wins.
 *   3. If the server returns empty AND localStorage has data, push local up
 *      to the server then clear local (one-time Phase A → B migration).
 *   4. Save/reset write through to both server and localStorage. If the
 *      server call fails, localStorage is still authoritative so the user's
 *      edits survive across page loads.
 */
export const useDashboardLayoutStore = defineStore('dashboardLayout', () => {
  const layout = ref(loadFromLocalStorage())
  const editing = ref(false)
  const draft = ref(null)
  const loaded = ref(false)

  const active = computed(() => (editing.value && draft.value ? draft.value : layout.value))

  // ── Storage helpers ──────────────────────────────────────────────────────
  function loadFromLocalStorage() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY)
      if (!raw) return cloneDefaults()
      return mergeWithDefaults(JSON.parse(raw))
    } catch {
      return cloneDefaults()
    }
  }
  function writeLocalStorage() {
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(layout.value)) } catch { /* ignore */ }
  }
  function clearLocalStorage() {
    try { localStorage.removeItem(STORAGE_KEY) } catch { /* ignore */ }
  }
  function hasLocalStorageData() {
    try { return localStorage.getItem(STORAGE_KEY) != null } catch { return false }
  }

  // ── Server sync ───────────────────────────────────────────────────────────
  /**
   * Load from server, falling back to the already-populated localStorage copy
   * on failure. Idempotent — safe to call repeatedly; only the first call
   * actually hits the server.
   */
  async function load() {
    if (loaded.value) return
    loaded.value = true
    try {
      const { data } = await getDashboardLayout()
      const serverHasLayout = data && typeof data === 'object' && Object.keys(data).length > 0
      if (serverHasLayout) {
        layout.value = mergeWithDefaults(data)
        // Server wins — refresh the localStorage cache to match.
        writeLocalStorage()
      } else if (hasLocalStorageData()) {
        // Phase A → B migration: push the local layout up, then clear local.
        try {
          await saveDashboardLayout(layout.value)
          clearLocalStorage()
        } catch {
          // Upload failed; keep localStorage as the source of truth for now.
        }
      }
    } catch {
      // API unreachable — stick with whatever we read from localStorage.
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
    // Write-through: localStorage first (local cache, never fails loudly),
    // then server. If the server call fails the user's edits still survive
    // reloads via localStorage and will re-sync next time.
    writeLocalStorage()
    try { await saveDashboardLayout(layout.value) } catch { /* local is fallback */ }
  }

  async function reset() {
    layout.value = cloneDefaults()
    clearLocalStorage()
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
