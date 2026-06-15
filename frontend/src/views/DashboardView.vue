<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, defineAsyncComponent } from 'vue'
import { useRouter } from 'vue-router'
// Lazy-load vuedraggable (+ its sortablejs dependency) — it's only rendered
// in edit mode, so most users never pay the bundle cost on their first
// dashboard visit.
const draggable = defineAsyncComponent(() => import('vuedraggable'))
import { useAuthStore } from '@/stores/auth'
import { useDashboardLayoutStore } from '@/stores/dashboardLayout'
import { useConfirm } from '@/composables/useConfirm'
import { getUnifiedDashboard, dismissSuggestion } from '@/api/dashboard'
import {
  PANEL_IDS, METRIC_IDS,
  COMPLIANCE_PANELS, COMPLIANCE_METRICS, NON_COMPLIANCE_METRICS,
  PANEL_LABELS, METRIC_LABELS,
} from '@/components/dashboard/layout'

import MetricCard from '@/components/dashboard/MetricCard.vue'
import PanelWrapper from '@/components/dashboard/PanelWrapper.vue'
import ApprovalAgingBar from '@/components/dashboard/ApprovalAgingBar.vue'
import { CampaignProgressPanel } from '@/ee'
import DirectoriesPanel from '@/components/dashboard/DirectoriesPanel.vue'
import ProfilesPanel from '@/components/dashboard/ProfilesPanel.vue'
import ActionRequiredPanel from '@/components/dashboard/ActionRequiredPanel.vue'
import SuggestedConfigurationPanel from '@/components/dashboard/SuggestedConfigurationPanel.vue'
import AwarenessPanel from '@/components/dashboard/AwarenessPanel.vue'
import RecentActivityPanel from '@/components/dashboard/RecentActivityPanel.vue'
import ReportJobsPanel from '@/components/dashboard/ReportJobsPanel.vue'
import AllClearPanel from '@/components/dashboard/AllClearPanel.vue'

interface DashboardMetrics {
  pendingApprovals?: number
  openSodViolations?: number
  campaignCompletionPercent?: number | null
  activeCampaigns?: number
  overdueCampaigns?: number
  totalUsers?: number
  totalGroups?: number
}
interface AlertSummary { openCount: number, criticalCount: number, highCount: number }
interface LayoutShape {
  metricCards: { order: string[], hidden: string[] }
  columns: { col1: string[], col2: string[], col3: string[] }
  panelsHidden: string[]
}
interface LayoutStoreShape {
  active: LayoutShape
  draft: LayoutShape | null
  editing: boolean
  load: () => void
  startEdit: () => void
  cancelEdit: () => void
  save: () => void
  reset: () => void
  isPanelHidden: (id: string) => boolean
  togglePanelHidden: (id: string) => void
  isMetricHidden: (id: string) => boolean
  toggleMetricHidden: (id: string) => void
}
interface ApprovalAging {
  lessThan24h?: number
  oneToThreeDays?: number
  threeToSevenDays?: number
  moreThanSevenDays?: number
}
interface DashboardData {
  complianceEnabled?: boolean
  metrics?: DashboardMetrics | null
  alertSummary?: AlertSummary
  approvalAging?: ApprovalAging | null
  approvalsConfigured?: boolean
  enabledReportJobs?: number
  failedReportJobs?: number
  firstDirectoryId?: string | null
  // Collections are passed straight through to child panels that validate
  // their own (typed) props; the server payload is dynamically shaped, so
  // these stay loosely typed rather than duplicating each panel's interface.
  directories?: any[]
  profiles?: any[]
  campaignProgress?: any[]
  recentActivity?: any[]
  actions?: any[]
  suggestions?: any[]
  awareness?: any[]
}

const router = useRouter()
const auth = useAuthStore()
// The dashboard-layout store is plain JS; cast to the shape this view uses so
// the layout/draft accesses below type-check without annotating the store.
const layoutStore = useDashboardLayoutStore() as unknown as LayoutStoreShape
const confirm = useConfirm()

// Two-phase loading: `initialLoad` gates the skeleton (so periodic refreshes
// don't wipe out visible data); `loading` flips true on every fetch so the
// Refresh button's spinner + label show feedback.
const initialLoad = ref(true)
const loading = ref(false)
const error = ref<string | null>(null)
const data = ref<DashboardData | null>(null)
let refreshTimer: ReturnType<typeof setInterval> | null = null

const isSuperadmin = computed(() => auth.isSuperadmin)
// Compliance flag now comes from the unified payload (server-side truth)
// but fall back to auth.isComplianceEnabled while the response is loading.
const complianceEnabled = computed(() =>
  data.value ? !!data.value.complianceEnabled : auth.isComplianceEnabled
)

// ── Derive structured sections from the unified payload ─────────────────────
const metrics = computed(() => data.value?.metrics || null)
const alertSummary = computed(() => data.value?.alertSummary || { openCount: 0, criticalCount: 0, highCount: 0 })
// Directories panel — alphabetical by name (case-insensitive), matching the
// ordering used everywhere else directories are listed.
const directories = computed(() =>
  [...(data.value?.directories || [])].sort((a, b) =>
    (a.name ?? '').localeCompare(b.name ?? '', undefined, { sensitivity: 'base' })),
)
/**
 * Profile rows for the admin Profiles panel. Superadmin payload returns
 * an empty list — superadmins see Directories instead. The dashboard
 * picks which panel to render off this list's presence.
 */
const profiles = computed(() => data.value?.profiles || [])
const showProfilesPanel = computed(() => !isSuperadmin.value)
const campaignProgress = computed(() => data.value?.campaignProgress || [])
const approvalAging = computed(() => data.value?.approvalAging || null)
/**
 * Is the approval-workflow feature in use for the caller's directories?
 * Server computes this as "at least one profile has requireApproval=true".
 * When true the Pending Approvals card, the Approval Aging panel, and
 * the sidebar Approvals link all earn their slots — even when the
 * current queue happens to be empty, because "0 pending" is a useful
 * "inbox clear" signal in an approval-enabled environment.
 *
 * When false, the same UI is hidden so an environment that doesn't use
 * approvals doesn't carry empty approval tiles.
 */
const approvalsConfigured = computed(() => !!data.value?.approvalsConfigured)
/**
 * OR with the live queue depth: if approvals were recently disabled but
 * the queue still has residual items, keep the UI visible until drained
 * so those items are still reachable.
 */
const showApprovalsUI = computed(() =>
  // Globally gated: when both approval master switches are off, the approval
  // widgets are hidden entirely. Otherwise the existing "configured or has
  // residual pending" rule applies.
  auth.isAnyApprovalEnabled && (approvalsConfigured.value || (metrics.value?.pendingApprovals ?? 0) > 0)
)
const recentActivity = computed(() => data.value?.recentActivity || [])
const actions = computed(() => data.value?.actions || [])
const suggestions = computed(() => data.value?.suggestions || [])
const awareness = computed(() => data.value?.awareness || [])
// Show the Report Jobs panel only when at least one scheduled report exists
// (enabled or recently failed). With none defined both counts are 0, so the
// panel is hidden rather than rendering an empty "0 enabled / 0 failed" card.
const reportJobsAvailable = computed(() =>
  !!data.value && ((data.value.enabledReportJobs || 0) > 0 || (data.value.failedReportJobs || 0) > 0)
)

const openAlerts = computed(() => alertSummary.value.openCount ?? 0)

// ── Severity helpers ───────────────────────────────────────────────────────
function sodSeverity(n?: number | null) { if (n == null) return 'gray'; return n === 0 ? 'green' : n <= 5 ? 'yellow' : 'red' }
function campaignSeverity(p?: number | null) { if (p == null) return 'gray'; return p >= 90 ? 'green' : p >= 50 ? 'yellow' : 'red' }
function overdueSeverity(n?: number | null) { if (n == null) return 'gray'; return n === 0 ? 'green' : 'red' }
function approvalsSeverity(n?: number | null) { if (n == null) return 'gray'; return n === 0 ? 'green' : n > 10 ? 'red' : 'yellow' }
function alertsSeverity() {
  const a = alertSummary.value
  if (a.criticalCount > 0) return 'red'
  if (a.highCount > 0) return 'orange'
  if (a.openCount > 0) return 'yellow'
  return 'green'
}

const showAllClear = computed(() =>
  !actions.value.length && !suggestions.value.length && !awareness.value.length
)

// ── Navigation helpers ─────────────────────────────────────────────────────
function firstDirectoryId(): string | null {
  return data.value?.firstDirectoryId || directories.value[0]?.id || null
}
function goAlerts() { if (isSuperadmin.value) router.push('/superadmin/alerts') }
function goSodViolations() {
  if (isSuperadmin.value) return router.push('/superadmin/sod-violations')
  const d = firstDirectoryId(); if (d) router.push(`/directories/${d}/sod-violations`)
}
function goAccessReviews() {
  if (isSuperadmin.value) return router.push('/superadmin/access-reviews')
  const d = firstDirectoryId(); if (d) router.push(`/directories/${d}/access-reviews`)
}
function goApprovals() {
  if (isSuperadmin.value) return router.push('/superadmin/approvals')
  const d = firstDirectoryId(); if (d) router.push(`/directories/${d}/approvals`)
}
const recentActivityViewAll = computed<string | undefined>(() => {
  if (isSuperadmin.value) return '/superadmin/audit-log'
  const d = firstDirectoryId(); return d ? `/directories/${d}/audit` : undefined
})
const reportJobsLink = computed<string | undefined>(() => isSuperadmin.value ? '/superadmin/reports' : undefined)
function onDirectoryClick(dir?: unknown) {
  const id = (dir as { id?: string } | undefined)?.id
  if (!id) return
  // The Directories panel is superadmin-only, so drill into the Directory
  // Browser (pre-selecting this directory via ?dir) rather than the per-
  // directory Users page.
  router.push({ name: 'directoryBrowser', query: { dir: id } })
}
/**
 * Clicking a profile row deep-links to that profile's directory Users
 * page — we don't filter Users by profile today, but landing in the
 * right directory is the most useful next step and keeps the click
 * behaviour parallel to the Directories panel.
 */
function onProfileClick(p?: unknown) {
  const dirId = (p as { directoryId?: string } | undefined)?.directoryId
  if (!dirId) return
  router.push(`/directories/${dirId}/users`)
}

// ── Layout wiring (edit mode + render lists) ───────────────────────────────
// The dashboard is always a 3-column grid. Feature flags filter individual
// panels and metric cards in/out, but the column structure stays constant
// so users and code don't have to deal with a mode-switch.

function isMetricHiddenByFlag(id: string) {
  if (!complianceEnabled.value && (COMPLIANCE_METRICS as Set<string>).has(id)) return true
  if (complianceEnabled.value && (NON_COMPLIANCE_METRICS as Set<string>).has(id)) return true
  // Alerts is an EE entitlement (ALERTING). Hide the card where it isn't
  // granted so community doesn't show a perpetually-zero card whose click
  // target (the EE-only /superadmin/alerts route) goes nowhere.
  if (id === 'alerts' && !auth.isAlertingEnabled) return true
  return false
}
function isPanelHiddenByFlag(id: string) {
  return !complianceEnabled.value && (COMPLIANCE_PANELS as Set<string>).has(id)
}

/**
 * Metric cards that aren't meaningful in the current environment — today
 * that's just the Pending Approvals card when no profile uses approvals
 * AND the queue is empty. Anchored on the server-computed
 * {@code approvalsConfigured} flag rather than raw pending count, so an
 * approvals-enabled but quiet environment still shows the "0 pending"
 * card as an explicit inbox-clear signal. Only applied outside edit
 * mode — editors need to see every tile.
 */
function isMetricHiddenByEmptyData(id: string) {
  if (id === 'approvals') return !showApprovalsUI.value
  return false
}

// Metric cards to actually render
const visibleMetricIds = computed(() => {
  const order = layoutStore.active.metricCards.order
  const userHidden = new Set(layoutStore.active.metricCards.hidden)
  return order.filter(id => {
    if (isMetricHiddenByFlag(id)) return false
    if (!layoutStore.editing && userHidden.has(id)) return false
    if (!layoutStore.editing && isMetricHiddenByEmptyData(id)) return false
    return true
  })
})

// Literal Tailwind classes so the JIT scanner picks them up:
// grid-cols-4 grid-cols-5 grid-cols-6 grid-cols-7
const metricsGridClass = computed(() => {
  const count = visibleMetricIds.value.length
  if (count <= 4) return 'grid-cols-4'
  if (count <= 5) return 'grid-cols-5'
  if (count <= 6) return 'grid-cols-6'
  return 'grid-cols-7'
})

// ── Drag proxies ────────────────────────────────────────────────────────────
// The edit-mode draggables bind to these proxies rather than the raw column
// arrays so flag-hidden panels (e.g. Campaign Progress when compliance is
// off) stay out of the user's way during editing. When the user reorders,
// the proxy's setter reconstructs the full array, keeping flag-hidden items
// at their saved positions so they re-appear in the same slot when the
// feature is re-enabled.
function visibleColumnProxy(col: 'col1' | 'col2' | 'col3') {
  return computed<string[]>({
    get() {
      const draft = layoutStore.draft
      if (!draft) return []
      return draft.columns[col].filter(id => !isPanelHiddenByFlag(id))
    },
    set(newVisibleOrder) {
      const draft = layoutStore.draft
      if (!draft) return
      draft.columns[col] = mergePreservingHidden(
        draft.columns[col],
        newVisibleOrder,
        isPanelHiddenByFlag,
      )
    },
  })
}

const metricsEditProxy = computed<string[]>({
  get() {
    const draft = layoutStore.draft
    if (!draft) return []
    return draft.metricCards.order.filter(id => !isMetricHiddenByFlag(id))
  },
  set(newOrder) {
    const draft = layoutStore.draft
    if (!draft) return
    draft.metricCards.order = mergePreservingHidden(
      draft.metricCards.order,
      newOrder,
      isMetricHiddenByFlag,
    )
  },
})

const col1Edit = visibleColumnProxy('col1')
const col2Edit = visibleColumnProxy('col2')
const col3Edit = visibleColumnProxy('col3')

/**
 * Rebuild a full ordered list from a reordered visible subset, keeping any
 * flag-hidden items pinned at their original indices (clamped to the new
 * length if they'd fall off the end).
 */
function mergePreservingHidden(fullOld: string[], newVisible: string[], isHiddenFn: (id: string) => boolean): string[] {
  const hidden: { idx: number, id: string }[] = []
  fullOld.forEach((id, i) => { if (isHiddenFn(id)) hidden.push({ idx: i, id }) })
  const total = newVisible.length + hidden.length
  const out = new Array(total)
  for (const { idx, id } of hidden) out[Math.min(idx, total - 1)] = id
  let vi = 0
  for (let i = 0; i < total; i++) {
    if (out[i] === undefined) out[i] = newVisible[vi++]
  }
  while (vi < newVisible.length) out.push(newVisible[vi++])
  return out
}

// Panels per column to render. In edit mode we keep user-hidden panels visible
// (dimmed) so they can be un-hidden; we also keep feature-flag-disabled panels
// visible with a note. Outside edit mode we suppress them.
function panelIdsForColumn(col: 'col1' | 'col2' | 'col3') {
  const ids = layoutStore.active.columns[col] || []
  const userHidden = new Set(layoutStore.active.panelsHidden)
  return ids.filter(id => {
    if (isPanelHiddenByFlag(id) && !layoutStore.editing) return false
    if (userHidden.has(id) && !layoutStore.editing) return false
    return true
  })
}
const col1Ids = computed(() => panelIdsForColumn('col1'))
const col2Ids = computed(() => panelIdsForColumn('col2'))
const col3Ids = computed(() => panelIdsForColumn('col3'))

function panelLabel(id: string) {
  // The 'directories' slot renders ProfilesPanel for admins and
  // DirectoriesPanel for superadmins — keep the edit-mode label in sync
  // so the drag handle and hide-toggle tooltip match what's visible.
  if (id === 'directories' && !isSuperadmin.value) return 'Profiles'
  return PANEL_LABELS[id as keyof typeof PANEL_LABELS] || id
}

// Directory-population summary strip (replaces the former Total Users /
// Total Groups metric cards). For superadmins the counts are raw directory
// totals across all connections; for admins they're "in scope" totals summed
// from the per-profile Profiles panel below — relabelled so the number's
// meaning is unambiguous. The scope figure is directories (superadmin) or
// provisioning profiles (admin).
const usersStatLabel = computed(() => isSuperadmin.value ? 'users' : 'users in scope')
const groupsStatLabel = computed(() => isSuperadmin.value ? 'groups' : 'groups in scope')
const scopeCount = computed(() => isSuperadmin.value ? directories.value.length : profiles.value.length)
const scopeLabel = computed(() => {
  const n = scopeCount.value
  return isSuperadmin.value
    ? `director${n === 1 ? 'y' : 'ies'}`
    : `profile${n === 1 ? '' : 's'}`
})
function metricLabel(id: string) {
  return METRIC_LABELS[id as keyof typeof METRIC_LABELS] || id
}

// Scroll the Directories/Profiles panel into view when the scope stat chip
// is activated — the chip's figure maps directly to that panel, so it
// doubles as a jump link. The panel root carries id="dashboard-scope-panel".
function scrollToScopePanel() {
  const el = document.getElementById('dashboard-scope-panel')
  if (!el) return
  el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  // Flash a highlight so the jump is perceptible even when the panel is
  // already on screen — otherwise the button looks like it does nothing.
  el.classList.add('scope-panel-flash')
  window.setTimeout(() => el.classList.remove('scope-panel-flash'), 1200)
}

// ── Data load ──────────────────────────────────────────────────────────────
async function load() {
  loading.value = true
  try {
    const res = await getUnifiedDashboard()
    data.value = res.data
    error.value = null
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    error.value = err.response?.data?.detail || err.message || 'Failed to load dashboard'
  } finally {
    loading.value = false
    initialLoad.value = false
  }
}

async function dismiss(key: string) {
  try {
    await dismissSuggestion(key)
    if (data.value?.suggestions) {
      data.value.suggestions = data.value.suggestions.filter(s => s.key !== key)
    }
  } catch { /* silent */ }
}

onMounted(() => {
  // Kick off the server layout fetch alongside the dashboard data fetch.
  // load() is idempotent — safe to call on every mount.
  layoutStore.load()
  load()
  refreshTimer = setInterval(load, 60000)
})
onUnmounted(() => { if (refreshTimer) clearInterval(refreshTimer) })

// ── Edit controls ──────────────────────────────────────────────────────────
function onEdit()   { layoutStore.startEdit() }
function onCancel() { layoutStore.cancelEdit() }
function onSave()   { layoutStore.save() }
async function onReset() {
  if (await confirm({
    title: 'Reset dashboard layout',
    message: 'Reset dashboard layout to defaults?',
    confirmLabel: 'Reset',
  })) {
    layoutStore.reset()
  }
}
</script>

<template>
  <div class="p-6">
    <div class="flex items-center justify-between mb-6">
      <div>
        <h1 class="text-2xl font-bold text-gray-900">Dashboard</h1>
        <p class="text-sm text-gray-500 mt-0.5">
          <template v-if="layoutStore.editing">
            Drag panels to reorder within a column. Toggle the eye icon to hide or show.
          </template>
          <template v-else>
            {{ complianceEnabled ? 'Compliance posture and activity overview' : 'Directory activity overview' }}
          </template>
        </p>
      </div>
      <div class="flex items-center gap-2">
        <template v-if="!layoutStore.editing">
          <button @click="load" :disabled="loading"
                  class="group inline-flex items-center gap-0 hover:gap-1.5 focus-visible:gap-1.5 text-sm font-medium text-gray-600 hover:text-gray-900 bg-white border border-gray-200 rounded-lg px-2 py-1.5 hover:border-gray-300 hover:shadow-sm disabled:opacity-50 disabled:cursor-not-allowed transition-all"
                  :class="{ '!gap-1.5': loading }"
                  :title="loading ? 'Refreshing dashboard…' : 'Refresh dashboard'">
            <svg :class="{ 'animate-spin': loading }" class="w-4 h-4 shrink-0" viewBox="0 0 24 24"
                 fill="none" stroke="currentColor" stroke-width="1.75"
                 stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M3 12a9 9 0 0 1 15.5-6.3L21 8M21 3v5h-5M21 12a9 9 0 0 1-15.5 6.3L3 16M3 21v-5h5" />
            </svg>
            <span class="overflow-hidden whitespace-nowrap transition-all duration-200"
                  :class="loading
                    ? 'max-w-xs opacity-100'
                    : 'max-w-0 opacity-0 group-hover:max-w-xs group-hover:opacity-100 group-focus-visible:max-w-xs group-focus-visible:opacity-100'">
              {{ loading ? 'Refreshing' : 'Refresh' }}
            </span>
          </button>
          <button @click="onEdit"
                  class="group inline-flex items-center gap-0 hover:gap-1.5 focus-visible:gap-1.5 text-sm font-medium text-gray-700 hover:text-gray-900 bg-white border border-gray-200 rounded-lg px-2 py-1.5 hover:border-gray-300 hover:shadow-sm transition-all"
                  title="Customize dashboard layout">
            <svg class="w-4 h-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                 stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
              <path d="m18.5 2.5 3 3L12 15l-4 1 1-4 9.5-9.5z" />
            </svg>
            <span class="overflow-hidden whitespace-nowrap max-w-0 opacity-0 group-hover:max-w-xs group-hover:opacity-100 group-focus-visible:max-w-xs group-focus-visible:opacity-100 transition-all duration-200">
              Edit Layout
            </span>
          </button>
        </template>
        <template v-else>
          <button @click="onReset"
                  class="inline-flex items-center gap-1.5 text-sm font-medium text-gray-600 hover:text-gray-900 bg-white border border-gray-200 rounded-lg px-3 py-1.5 hover:border-gray-300 hover:shadow-sm transition-all"
                  title="Reset layout to defaults">
            <svg class="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                 stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M3 7v6h6" />
              <path d="M21 17a9 9 0 0 0-15.5-6L3 13" />
            </svg>
            <span>Reset</span>
          </button>
          <button @click="onCancel"
                  class="inline-flex items-center gap-1.5 text-sm font-medium text-gray-600 hover:text-gray-900 bg-white border border-gray-200 rounded-lg px-3 py-1.5 hover:border-gray-300 hover:shadow-sm transition-all"
                  title="Discard changes">
            <svg class="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                 stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M18 6 6 18M6 6l12 12" />
            </svg>
            <span>Cancel</span>
          </button>
          <button @click="onSave"
                  class="inline-flex items-center gap-1.5 text-sm font-medium text-white bg-blue-600 hover:bg-blue-700 rounded-lg px-3 py-1.5 shadow-sm hover:shadow transition-all"
                  title="Save layout">
            <svg class="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                 stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M5 12l5 5L20 7" />
            </svg>
            <span>Save</span>
          </button>
        </template>
      </div>
    </div>

    <!-- Loading skeleton — only on initial mount; refreshes keep data visible -->
    <div v-if="initialLoad" class="space-y-6 animate-pulse">
      <div class="grid gap-4" :class="complianceEnabled ? 'grid-cols-5' : 'grid-cols-4'">
        <div v-for="i in (complianceEnabled ? 5 : 4)" :key="i" class="bg-white rounded-xl border border-gray-200 p-5">
          <div class="h-3 bg-gray-200 rounded w-1/2 mb-3" />
          <div class="h-8 bg-gray-200 rounded w-1/3" />
        </div>
      </div>
      <div class="grid grid-cols-3 gap-6">
        <div v-for="i in 3" :key="i" class="bg-white rounded-xl border border-gray-200 p-6">
          <div class="h-4 bg-gray-200 rounded w-1/3 mb-4" />
          <div v-for="j in 3" :key="j" class="h-3 bg-gray-100 rounded w-full mb-2" />
        </div>
      </div>
    </div>

    <div v-else-if="error" class="bg-red-50 border border-red-200 rounded-xl p-6 text-red-700">
      <p class="font-medium">Failed to load dashboard</p>
      <p class="text-sm mt-1">{{ error }}</p>
    </div>

    <template v-else-if="metrics">

      <!-- Directory-population summary. Stat chips carrying the user / group
           totals (and directory/profile scope) that used to live in the
           metric-card row, so the figures survive even when that row is empty
           (e.g. community with alerting and compliance off). The scope chip is
           a jump link to the Directories/Profiles panel below — its figure maps
           directly to that panel. Icons are decorative (aria-hidden); each
           figure stays screen-reader readable alongside its label. -->
      <div class="flex flex-wrap items-center gap-2 mb-6">
        <!-- Users -->
        <div class="inline-flex items-center gap-2 bg-white border border-gray-200 rounded-lg px-3 py-1.5">
          <span class="inline-flex items-center justify-center w-6 h-6 rounded-md bg-blue-50 text-blue-500 shrink-0">
            <svg class="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                 stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <circle cx="12" cy="8" r="3.25" />
              <path d="M5.5 19a6.5 6.5 0 0 1 13 0" />
            </svg>
          </span>
          <span class="text-lg font-semibold text-gray-900 tabular-nums leading-none">{{ (metrics.totalUsers ?? 0).toLocaleString() }}</span>
          <span class="text-sm text-gray-500">{{ usersStatLabel }}</span>
        </div>
        <!-- Groups -->
        <div class="inline-flex items-center gap-2 bg-white border border-gray-200 rounded-lg px-3 py-1.5">
          <span class="inline-flex items-center justify-center w-6 h-6 rounded-md bg-violet-50 text-violet-500 shrink-0">
            <svg class="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                 stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <circle cx="9" cy="9" r="3" />
              <path d="M3.5 19.5a5.5 5.5 0 0 1 11 0" />
              <path d="M16 4.2a3 3 0 0 1 0 5.6" />
              <path d="M18 13.6a5.5 5.5 0 0 1 2.5 4.6" />
            </svg>
          </span>
          <span class="text-lg font-semibold text-gray-900 tabular-nums leading-none">{{ (metrics.totalGroups ?? 0).toLocaleString() }}</span>
          <span class="text-sm text-gray-500">{{ groupsStatLabel }}</span>
        </div>
        <!-- Scope (directories for superadmin, profiles for admin) — jumps to its panel -->
        <button type="button" @click="scrollToScopePanel"
                :aria-label="`Jump to ${scopeLabel} below`"
                class="group inline-flex items-center gap-2 bg-white border border-gray-200 rounded-lg px-3 py-1.5 hover:border-gray-300 hover:shadow-sm transition-all">
          <span class="inline-flex items-center justify-center w-6 h-6 rounded-md bg-emerald-50 text-emerald-600 shrink-0">
            <svg class="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                 stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <ellipse cx="12" cy="6" rx="7" ry="3" />
              <path d="M5 6v12c0 1.66 3.13 3 7 3s7-1.34 7-3V6" />
              <path d="M5 12c0 1.66 3.13 3 7 3s7-1.34 7-3" />
            </svg>
          </span>
          <span class="text-lg font-semibold text-gray-900 tabular-nums leading-none">{{ scopeCount.toLocaleString() }}</span>
          <span class="text-sm text-gray-500">{{ scopeLabel }}</span>
          <!-- Double chevron reads as "jump to the section below", not a
               dropdown caret (which a single chevron implied). -->
          <svg class="w-3.5 h-3.5 text-gray-400 shrink-0 transition-transform group-hover:translate-y-0.5" viewBox="0 0 24 24"
               fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M6 5l6 6 6-6" />
            <path d="M6 12l6 6 6-6" />
          </svg>
        </button>
      </div>

      <!-- ── Top row: draggable metric cards ───────────────────────────────── -->
      <draggable
        v-if="layoutStore.editing"
        v-model="metricsEditProxy"
        :item-key="(el: string) => el"
        handle=".panel-drag-handle"
        :animation="150"
        class="grid gap-4 mb-6"
        :class="metricsGridClass">
        <template #item="{ element: id }">
          <PanelWrapper
            :label="metricLabel(id)"
            :editing="true"
            :hidden="layoutStore.isMetricHidden(id)"
            @toggle-hide="layoutStore.toggleMetricHidden(id)">
            <MetricCard
              v-if="id === 'alerts'"
              label="Active Alerts" :value="openAlerts" :severity="alertsSeverity()"
              @click="goAlerts">
              <template #subtitle>
                <span v-if="isSuperadmin">{{ alertSummary.criticalCount }} critical, {{ alertSummary.highCount }} high</span>
                <span v-else>Open alerts</span>
              </template>
            </MetricCard>
            <MetricCard v-else-if="id === 'sod'"
              label="Open SoD Violations"
              :value="metrics.openSodViolations ?? 0"
              :severity="sodSeverity(metrics.openSodViolations)"
              subtitle="Click to view violations"
              @click="goSodViolations" />
            <MetricCard v-else-if="id === 'campaign'"
              label="Campaign Completion"
              :value="metrics.campaignCompletionPercent != null ? metrics.campaignCompletionPercent + '%' : 'N/A'"
              :severity="campaignSeverity(metrics.campaignCompletionPercent)"
              :subtitle="`${metrics.activeCampaigns ?? 0} active campaign${metrics.activeCampaigns === 1 ? '' : 's'}`"
              @click="goAccessReviews" />
            <MetricCard v-else-if="id === 'approvals'"
              label="Pending Approvals"
              :value="metrics.pendingApprovals ?? 0"
              :severity="approvalsSeverity(metrics.pendingApprovals)"
              @click="goApprovals">
              <template #subtitle><ApprovalAgingBar :aging="approvalAging" /></template>
            </MetricCard>
            <MetricCard v-else-if="id === 'overdue'"
              label="Overdue Campaigns"
              :value="metrics.overdueCampaigns ?? 0"
              :severity="overdueSeverity(metrics.overdueCampaigns)"
              subtitle="Past deadline"
              @click="goAccessReviews" />
          </PanelWrapper>
        </template>
      </draggable>

      <div v-else-if="visibleMetricIds.length" class="grid gap-4 mb-6" :class="metricsGridClass">
        <template v-for="id in visibleMetricIds" :key="id">
          <MetricCard v-if="id === 'alerts'"
            label="Active Alerts" :value="openAlerts" :severity="alertsSeverity()"
            @click="goAlerts">
            <template #subtitle>
              <span v-if="isSuperadmin">{{ alertSummary.criticalCount }} critical, {{ alertSummary.highCount }} high</span>
              <span v-else>Open alerts</span>
            </template>
          </MetricCard>
          <MetricCard v-else-if="id === 'sod'"
            label="Open SoD Violations" :value="metrics.openSodViolations ?? 0"
            :severity="sodSeverity(metrics.openSodViolations)"
            subtitle="Click to view violations" @click="goSodViolations" />
          <MetricCard v-else-if="id === 'campaign'"
            label="Campaign Completion"
            :value="metrics.campaignCompletionPercent != null ? metrics.campaignCompletionPercent + '%' : 'N/A'"
            :severity="campaignSeverity(metrics.campaignCompletionPercent)"
            :subtitle="`${metrics.activeCampaigns ?? 0} active campaign${metrics.activeCampaigns === 1 ? '' : 's'}`"
            @click="goAccessReviews" />
          <!--
            Hide the Pending Approvals card in environments that neither
            use approvals nor have residual pending items. In an
            approvals-enabled env, the card shows even at zero — "inbox
            clear" is a useful signal. Edit-mode card above stays
            unconditional so the layout can still be managed.
          -->
          <MetricCard v-else-if="id === 'approvals' && showApprovalsUI"
            label="Pending Approvals" :value="metrics.pendingApprovals ?? 0"
            :severity="approvalsSeverity(metrics.pendingApprovals)" @click="goApprovals">
            <template #subtitle><ApprovalAgingBar :aging="approvalAging" /></template>
          </MetricCard>
          <MetricCard v-else-if="id === 'overdue'"
            label="Overdue Campaigns" :value="metrics.overdueCampaigns ?? 0"
            :severity="overdueSeverity(metrics.overdueCampaigns)"
            subtitle="Past deadline" @click="goAccessReviews" />
        </template>
      </div>

      <!-- ── Content grid: always 3 columns ─────────────────────────────── -->
      <!-- Feature flags hide individual panels (e.g. Campaign Progress when
           compliance is off) but the 3-column structure stays constant so
           there's no layout swap between modes or between display/edit. -->
      <div class="grid gap-6 grid-cols-3">
        <!-- Column 1 -->
        <draggable
          v-if="layoutStore.editing"
          v-model="col1Edit"
          :item-key="(el: string) => el"
          handle=".panel-drag-handle"
          group="dashboard-panels"
          :animation="150"
          class="space-y-6 min-h-24"
          :class="{ 'dashboard-drop-empty': !col1Edit.length }">
          <template #item="{ element: id }">
            <PanelWrapper :label="panelLabel(id)" :editing="true"
                          :hidden="layoutStore.isPanelHidden(id)"
                          @toggle-hide="layoutStore.togglePanelHidden(id)">
              <DirectoriesPanel v-if="id === 'directories' && isSuperadmin"
                :directories="directories"
                :show-campaigns="complianceEnabled" :show-sod="complianceEnabled"
                row-clickable @row-click="onDirectoryClick" />
              <ProfilesPanel v-else-if="id === 'directories'"
                :profiles="profiles"
                row-clickable @row-click="onProfileClick" />
              <RecentActivityPanel v-else-if="id === 'recent-activity'"
                :events="recentActivity" :view-all-to="recentActivityViewAll" />
              <ReportJobsPanel v-else-if="id === 'report-jobs'"
                :enabled="data?.enabledReportJobs || 0"
                :failed="data?.failedReportJobs || 0"
                :to="reportJobsLink" />
              <CampaignProgressPanel v-else-if="id === 'campaign-progress'" :campaigns="campaignProgress" />
              <AwarenessPanel v-else-if="id === 'awareness'" :awareness="awareness" />
              <ActionRequiredPanel v-else-if="id === 'action-required'" :actions="actions" />
              <SuggestedConfigurationPanel v-else-if="id === 'suggested-config'"
                :suggestions="suggestions" @dismiss="dismiss" />
            </PanelWrapper>
          </template>
        </draggable>
        <div v-else class="space-y-6">
          <template v-for="id in col1Ids" :key="id">
            <DirectoriesPanel v-if="id === 'directories' && isSuperadmin"
              :directories="directories"
              :show-campaigns="complianceEnabled" :show-sod="complianceEnabled"
              row-clickable @row-click="onDirectoryClick" />
            <ProfilesPanel v-else-if="id === 'directories'"
              :profiles="profiles"
              row-clickable @row-click="onProfileClick" />
            <RecentActivityPanel v-else-if="id === 'recent-activity'"
              :events="recentActivity" :view-all-to="recentActivityViewAll" />
            <ReportJobsPanel v-else-if="id === 'report-jobs' && reportJobsAvailable"
              :enabled="data?.enabledReportJobs || 0"
              :failed="data?.failedReportJobs || 0"
              :to="reportJobsLink" />
            <CampaignProgressPanel v-else-if="id === 'campaign-progress'" :campaigns="campaignProgress" />
            <AwarenessPanel v-else-if="id === 'awareness'" :awareness="awareness" />
            <ActionRequiredPanel v-else-if="id === 'action-required' && actions.length" :actions="actions" />
            <SuggestedConfigurationPanel v-else-if="id === 'suggested-config' && suggestions.length"
              :suggestions="suggestions" @dismiss="dismiss" />
          </template>
        </div>

        <!-- Column 2 -->
        <draggable
          v-if="layoutStore.editing"
          v-model="col2Edit"
          :item-key="(el: string) => el"
          handle=".panel-drag-handle"
          group="dashboard-panels"
          :animation="150"
          class="space-y-6 min-h-24"
          :class="{ 'dashboard-drop-empty': !col2Edit.length }">
          <template #item="{ element: id }">
            <PanelWrapper :label="panelLabel(id)" :editing="true"
                          :hidden="layoutStore.isPanelHidden(id)"
                          @toggle-hide="layoutStore.togglePanelHidden(id)">
              <DirectoriesPanel v-if="id === 'directories' && isSuperadmin"
                :directories="directories"
                :show-campaigns="complianceEnabled" :show-sod="complianceEnabled"
                row-clickable @row-click="onDirectoryClick" />
              <ProfilesPanel v-else-if="id === 'directories'"
                :profiles="profiles"
                row-clickable @row-click="onProfileClick" />
              <RecentActivityPanel v-else-if="id === 'recent-activity'"
                :events="recentActivity" :view-all-to="recentActivityViewAll" />
              <ReportJobsPanel v-else-if="id === 'report-jobs'"
                :enabled="data?.enabledReportJobs || 0"
                :failed="data?.failedReportJobs || 0"
                :to="reportJobsLink" />
              <CampaignProgressPanel v-else-if="id === 'campaign-progress'" :campaigns="campaignProgress" />
              <AwarenessPanel v-else-if="id === 'awareness'" :awareness="awareness" />
              <ActionRequiredPanel v-else-if="id === 'action-required'" :actions="actions" />
              <SuggestedConfigurationPanel v-else-if="id === 'suggested-config'"
                :suggestions="suggestions" @dismiss="dismiss" />
            </PanelWrapper>
          </template>
        </draggable>
        <div v-else class="space-y-6">
          <template v-for="id in col2Ids" :key="id">
            <CampaignProgressPanel v-if="id === 'campaign-progress'" :campaigns="campaignProgress" />
            <AwarenessPanel v-else-if="id === 'awareness'" :awareness="awareness" />
            <DirectoriesPanel v-else-if="id === 'directories' && isSuperadmin"
              :directories="directories"
              :show-campaigns="complianceEnabled" :show-sod="complianceEnabled"
              row-clickable @row-click="onDirectoryClick" />
            <ProfilesPanel v-else-if="id === 'directories'"
              :profiles="profiles"
              row-clickable @row-click="onProfileClick" />
            <RecentActivityPanel v-else-if="id === 'recent-activity'"
              :events="recentActivity" :view-all-to="recentActivityViewAll" />
            <ReportJobsPanel v-else-if="id === 'report-jobs' && reportJobsAvailable"
              :enabled="data?.enabledReportJobs || 0"
              :failed="data?.failedReportJobs || 0"
              :to="reportJobsLink" />
            <ActionRequiredPanel v-else-if="id === 'action-required' && actions.length" :actions="actions" />
            <SuggestedConfigurationPanel v-else-if="id === 'suggested-config' && suggestions.length"
              :suggestions="suggestions" @dismiss="dismiss" />
          </template>
        </div>

        <!-- Column 3 -->
        <draggable
          v-if="layoutStore.editing"
          v-model="col3Edit"
          :item-key="(el: string) => el"
          handle=".panel-drag-handle"
          group="dashboard-panels"
          :animation="150"
          class="space-y-6 min-h-24"
          :class="{ 'dashboard-drop-empty': !col3Edit.length }">
          <template #item="{ element: id }">
            <PanelWrapper :label="panelLabel(id)" :editing="true"
                          :hidden="layoutStore.isPanelHidden(id)"
                          @toggle-hide="layoutStore.togglePanelHidden(id)">
              <ActionRequiredPanel v-if="id === 'action-required'" :actions="actions" />
              <SuggestedConfigurationPanel v-else-if="id === 'suggested-config'"
                :suggestions="suggestions" @dismiss="dismiss" />
              <DirectoriesPanel v-else-if="id === 'directories' && isSuperadmin"
                :directories="directories"
                :show-campaigns="complianceEnabled" :show-sod="complianceEnabled"
                row-clickable @row-click="onDirectoryClick" />
              <ProfilesPanel v-else-if="id === 'directories'"
                :profiles="profiles"
                row-clickable @row-click="onProfileClick" />
              <RecentActivityPanel v-else-if="id === 'recent-activity'"
                :events="recentActivity" :view-all-to="recentActivityViewAll" />
              <ReportJobsPanel v-else-if="id === 'report-jobs'"
                :enabled="data?.enabledReportJobs || 0"
                :failed="data?.failedReportJobs || 0"
                :to="reportJobsLink" />
              <CampaignProgressPanel v-else-if="id === 'campaign-progress'" :campaigns="campaignProgress" />
              <AwarenessPanel v-else-if="id === 'awareness'" :awareness="awareness" />
            </PanelWrapper>
          </template>
        </draggable>
        <div v-else class="space-y-6">
          <template v-for="id in col3Ids" :key="id">
            <ActionRequiredPanel v-if="id === 'action-required' && actions.length" :actions="actions" />
            <SuggestedConfigurationPanel v-else-if="id === 'suggested-config' && suggestions.length"
              :suggestions="suggestions" @dismiss="dismiss" />
            <DirectoriesPanel v-else-if="id === 'directories' && isSuperadmin"
              :directories="directories"
              :show-campaigns="complianceEnabled" :show-sod="complianceEnabled"
              row-clickable @row-click="onDirectoryClick" />
            <ProfilesPanel v-else-if="id === 'directories'"
              :profiles="profiles"
              row-clickable @row-click="onProfileClick" />
            <RecentActivityPanel v-else-if="id === 'recent-activity'"
              :events="recentActivity" :view-all-to="recentActivityViewAll" />
            <ReportJobsPanel v-else-if="id === 'report-jobs' && reportJobsAvailable"
              :enabled="data?.enabledReportJobs || 0"
              :failed="data?.failedReportJobs || 0"
              :to="reportJobsLink" />
            <CampaignProgressPanel v-else-if="id === 'campaign-progress'" :campaigns="campaignProgress" />
            <AwarenessPanel v-else-if="id === 'awareness'" :awareness="awareness" />
          </template>
          <AllClearPanel v-if="!layoutStore.editing && showAllClear" />
        </div>
      </div>

    </template>
  </div>
</template>

<style scoped>
@reference "tailwindcss";

/* Placeholder rendered inside an empty draggable column during edit mode.
   Uses ::before so vuedraggable's child tracking isn't affected — drops
   still land in the draggable itself, not on a sibling element. */
.dashboard-drop-empty::before {
  content: 'Drop a panel here';
  @apply block border-2 border-dashed border-gray-300 rounded-xl text-xs text-gray-500 text-center py-6;
}
</style>
