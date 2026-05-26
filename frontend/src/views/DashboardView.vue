<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup>
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
import ApprovalAgingPanel from '@/components/dashboard/ApprovalAgingPanel.vue'
import { CampaignProgressPanel } from '@/ee'
import DirectoriesPanel from '@/components/dashboard/DirectoriesPanel.vue'
import ProfilesPanel from '@/components/dashboard/ProfilesPanel.vue'
import ActionRequiredPanel from '@/components/dashboard/ActionRequiredPanel.vue'
import SuggestedConfigurationPanel from '@/components/dashboard/SuggestedConfigurationPanel.vue'
import AwarenessPanel from '@/components/dashboard/AwarenessPanel.vue'
import RecentActivityPanel from '@/components/dashboard/RecentActivityPanel.vue'
import ReportJobsPanel from '@/components/dashboard/ReportJobsPanel.vue'
import AllClearPanel from '@/components/dashboard/AllClearPanel.vue'

const router = useRouter()
const auth = useAuthStore()
const layoutStore = useDashboardLayoutStore()
const confirm = useConfirm()

// Two-phase loading: `initialLoad` gates the skeleton (so periodic refreshes
// don't wipe out visible data); `loading` flips true on every fetch so the
// Refresh button's spinner + label show feedback.
const initialLoad = ref(true)
const loading = ref(false)
const error = ref(null)
const data = ref(null)
let refreshTimer = null

const isSuperadmin = computed(() => auth.isSuperadmin)
// Compliance flag now comes from the unified payload (server-side truth)
// but fall back to auth.isComplianceEnabled while the response is loading.
const complianceEnabled = computed(() =>
  data.value ? !!data.value.complianceEnabled : auth.isComplianceEnabled
)

// ── Derive structured sections from the unified payload ─────────────────────
const metrics = computed(() => data.value?.metrics || null)
const alertSummary = computed(() => data.value?.alertSummary || { openCount: 0, criticalCount: 0, highCount: 0 })
const directories = computed(() => data.value?.directories || [])
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
  approvalsConfigured.value || (metrics.value?.pendingApprovals ?? 0) > 0
)
const recentActivity = computed(() => data.value?.recentActivity || [])
const actions = computed(() => data.value?.actions || [])
const suggestions = computed(() => data.value?.suggestions || [])
const awareness = computed(() => data.value?.awareness || [])
const reportJobsAvailable = computed(() =>
  data.value && (data.value.enabledReportJobs != null || data.value.failedReportJobs != null)
)

const openAlerts = computed(() => alertSummary.value.openCount ?? 0)

// ── Severity helpers ───────────────────────────────────────────────────────
function sodSeverity(n) { if (n == null) return 'gray'; return n === 0 ? 'green' : n <= 5 ? 'yellow' : 'red' }
function campaignSeverity(p) { if (p == null) return 'gray'; return p >= 90 ? 'green' : p >= 50 ? 'yellow' : 'red' }
function overdueSeverity(n) { if (n == null) return 'gray'; return n === 0 ? 'green' : 'red' }
function approvalsSeverity(n) { if (n == null) return 'gray'; return n === 0 ? 'green' : n > 10 ? 'red' : 'yellow' }
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
function firstDirectoryId() {
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
const recentActivityViewAll = computed(() => {
  if (isSuperadmin.value) return '/superadmin/audit-log'
  const d = firstDirectoryId(); return d ? `/directories/${d}/audit` : null
})
const reportJobsLink = computed(() => isSuperadmin.value ? '/superadmin/reports' : null)
function onDirectoryClick(dir) {
  if (!dir?.id) return
  router.push(`/directories/${dir.id}/users`)
}
/**
 * Clicking a profile row deep-links to that profile's directory Users
 * page — we don't filter Users by profile today, but landing in the
 * right directory is the most useful next step and keeps the click
 * behaviour parallel to the Directories panel.
 */
function onProfileClick(p) {
  if (!p?.directoryId) return
  router.push(`/directories/${p.directoryId}/users`)
}

// ── Layout wiring (edit mode + render lists) ───────────────────────────────
// The dashboard is always a 3-column grid. Feature flags filter individual
// panels and metric cards in/out, but the column structure stays constant
// so users and code don't have to deal with a mode-switch.

function isMetricHiddenByFlag(id) {
  if (!complianceEnabled.value && COMPLIANCE_METRICS.has(id)) return true
  if (complianceEnabled.value && NON_COMPLIANCE_METRICS.has(id)) return true
  return false
}
function isPanelHiddenByFlag(id) {
  return !complianceEnabled.value && COMPLIANCE_PANELS.has(id)
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
function isMetricHiddenByEmptyData(id) {
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
function visibleColumnProxy(col) {
  return computed({
    get() {
      if (!layoutStore.draft) return []
      return layoutStore.draft.columns[col].filter(id => !isPanelHiddenByFlag(id))
    },
    set(newVisibleOrder) {
      layoutStore.draft.columns[col] = mergePreservingHidden(
        layoutStore.draft.columns[col],
        newVisibleOrder,
        isPanelHiddenByFlag,
      )
    },
  })
}

const metricsEditProxy = computed({
  get() {
    if (!layoutStore.draft) return []
    return layoutStore.draft.metricCards.order.filter(id => !isMetricHiddenByFlag(id))
  },
  set(newOrder) {
    layoutStore.draft.metricCards.order = mergePreservingHidden(
      layoutStore.draft.metricCards.order,
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
function mergePreservingHidden(fullOld, newVisible, isHiddenFn) {
  const hidden = []
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
function panelIdsForColumn(col) {
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

function panelLabel(id) {
  // The 'directories' slot renders ProfilesPanel for admins and
  // DirectoriesPanel for superadmins — keep the edit-mode label in sync
  // so the drag handle and hide-toggle tooltip match what's visible.
  if (id === 'directories' && !isSuperadmin.value) return 'Profiles'
  return PANEL_LABELS[id] || id
}

/**
 * Admin user/group count cards show "in scope" totals summed from the
 * per-profile Profiles panel below, rather than raw directory totals.
 * Relabel accordingly so the number's meaning is unambiguous.
 */
const usersCardLabel = computed(() => isSuperadmin.value ? 'Total Users' : 'Users in scope')
const groupsCardLabel = computed(() => isSuperadmin.value ? 'Total Groups' : 'Groups in scope')
const usersCardSubtitle = computed(() => isSuperadmin.value
  ? `Across ${directories.value.length} director${directories.value.length === 1 ? 'y' : 'ies'}`
  : `Across ${profiles.value.length} profile${profiles.value.length === 1 ? '' : 's'}`)
const groupsCardSubtitle = usersCardSubtitle
function metricLabel(id) {
  // Keep the edit-mode PanelWrapper label in sync with the visible card
  // for the relabelled admin metrics (see usersCardLabel / groupsCardLabel).
  if (!isSuperadmin.value) {
    if (id === 'users') return usersCardLabel.value
    if (id === 'groups') return groupsCardLabel.value
  }
  return METRIC_LABELS[id] || id
}

// ── Data load ──────────────────────────────────────────────────────────────
async function load() {
  loading.value = true
  try {
    const res = await getUnifiedDashboard()
    data.value = res.data
    error.value = null
  } catch (e) {
    error.value = e.response?.data?.detail || e.message || 'Failed to load dashboard'
  } finally {
    loading.value = false
    initialLoad.value = false
  }
}

async function dismiss(key) {
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

      <!-- ── Top row: draggable metric cards ───────────────────────────────── -->
      <draggable
        v-if="layoutStore.editing"
        v-model="metricsEditProxy"
        :item-key="(el) => el"
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
              :value="metrics.openSodViolations"
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
              :value="metrics.pendingApprovals"
              :severity="approvalsSeverity(metrics.pendingApprovals)"
              subtitle="Click to review"
              @click="goApprovals" />
            <MetricCard v-else-if="id === 'overdue'"
              label="Overdue Campaigns"
              :value="metrics.overdueCampaigns"
              :severity="overdueSeverity(metrics.overdueCampaigns)"
              subtitle="Past deadline"
              @click="goAccessReviews" />
            <MetricCard v-else-if="id === 'users'"
              :label="usersCardLabel"
              :value="(metrics.totalUsers ?? 0).toLocaleString()"
              severity="gray"
              :subtitle="usersCardSubtitle" />
            <MetricCard v-else-if="id === 'groups'"
              :label="groupsCardLabel"
              :value="(metrics.totalGroups ?? 0).toLocaleString()"
              severity="gray"
              :subtitle="groupsCardSubtitle" />
          </PanelWrapper>
        </template>
      </draggable>

      <div v-else class="grid gap-4 mb-6" :class="metricsGridClass">
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
            label="Open SoD Violations" :value="metrics.openSodViolations"
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
            label="Pending Approvals" :value="metrics.pendingApprovals"
            :severity="approvalsSeverity(metrics.pendingApprovals)"
            subtitle="Click to review" @click="goApprovals" />
          <MetricCard v-else-if="id === 'overdue'"
            label="Overdue Campaigns" :value="metrics.overdueCampaigns"
            :severity="overdueSeverity(metrics.overdueCampaigns)"
            subtitle="Past deadline" @click="goAccessReviews" />
          <MetricCard v-else-if="id === 'users'"
            :label="usersCardLabel"
            :value="(metrics.totalUsers ?? 0).toLocaleString()"
            severity="gray"
            :subtitle="usersCardSubtitle" />
          <MetricCard v-else-if="id === 'groups'"
            :label="groupsCardLabel"
            :value="(metrics.totalGroups ?? 0).toLocaleString()"
            severity="gray"
            :subtitle="groupsCardSubtitle" />
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
          :item-key="(el) => el"
          handle=".panel-drag-handle"
          group="dashboard-panels"
          :animation="150"
          class="space-y-6 min-h-24"
          :class="{ 'dashboard-drop-empty': !col1Edit.length }">
          <template #item="{ element: id }">
            <PanelWrapper :label="panelLabel(id)" :editing="true"
                          :hidden="layoutStore.isPanelHidden(id)"
                          @toggle-hide="layoutStore.togglePanelHidden(id)">
              <ApprovalAgingPanel v-if="id === 'approval-aging'" :aging="approvalAging" />
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
                :enabled="data.enabledReportJobs || 0"
                :failed="data.failedReportJobs || 0"
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
            <ApprovalAgingPanel v-if="id === 'approval-aging' && showApprovalsUI" :aging="approvalAging" />
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
              :enabled="data.enabledReportJobs || 0"
              :failed="data.failedReportJobs || 0"
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
          :item-key="(el) => el"
          handle=".panel-drag-handle"
          group="dashboard-panels"
          :animation="150"
          class="space-y-6 min-h-24"
          :class="{ 'dashboard-drop-empty': !col2Edit.length }">
          <template #item="{ element: id }">
            <PanelWrapper :label="panelLabel(id)" :editing="true"
                          :hidden="layoutStore.isPanelHidden(id)"
                          @toggle-hide="layoutStore.togglePanelHidden(id)">
              <ApprovalAgingPanel v-if="id === 'approval-aging'" :aging="approvalAging" />
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
                :enabled="data.enabledReportJobs || 0"
                :failed="data.failedReportJobs || 0"
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
            <ApprovalAgingPanel v-else-if="id === 'approval-aging' && showApprovalsUI" :aging="approvalAging" />
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
              :enabled="data.enabledReportJobs || 0"
              :failed="data.failedReportJobs || 0"
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
          :item-key="(el) => el"
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
              <ApprovalAgingPanel v-else-if="id === 'approval-aging'" :aging="approvalAging" />
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
                :enabled="data.enabledReportJobs || 0"
                :failed="data.failedReportJobs || 0"
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
            <ApprovalAgingPanel v-else-if="id === 'approval-aging' && showApprovalsUI" :aging="approvalAging" />
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
              :enabled="data.enabledReportJobs || 0"
              :failed="data.failedReportJobs || 0"
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
