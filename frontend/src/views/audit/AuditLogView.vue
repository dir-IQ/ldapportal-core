<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div class="p-6">
    <h1 class="text-2xl font-bold text-gray-900 mb-4">Audit Log</h1>
    <p class="text-sm text-gray-500 mt-1">Directory change events and administrative actions</p>

    <!-- Scope control. Superadmins pick a directory (incl. "All Directories").
         Admins work against a sidebar-selected provisioning profile, so the
         audit log inherits that and shows the scope read-only. -->
    <div v-if="auth.isSuperadmin && showPicker" class="mb-4">
      <label class="block text-sm font-medium text-gray-700 mb-1">Directory</label>
      <select v-model="selectedDir" class="input w-64" aria-label="Directory">
        <option value="">All Directories</option>
        <option v-for="d in directoryOptions" :key="d.id" :value="d.id">{{ d.displayName }}</option>
      </select>
    </div>
    <div v-else-if="!auth.isSuperadmin && selectedProfile"
         class="mb-3 bg-gray-50 border border-gray-200 rounded-lg px-4 py-2 text-sm text-gray-600">
      Showing audit events for profile
      <span class="font-medium text-gray-800">{{ selectedProfile.name }}</span>.
    </div>

    <!-- Correlation-trace banner: shown when arriving via a "trace" link
         from the Directory Sync console. Narrows the log to every row
         emitted while handling one originating operation. -->
    <div v-if="correlationId"
         class="mb-3 flex items-center justify-between bg-indigo-50 border border-indigo-200 rounded-lg px-4 py-2">
      <span class="text-sm text-indigo-800">
        Showing one operation trace
        <code class="font-mono text-xs bg-indigo-100 px-1.5 py-0.5 rounded ml-1">{{ correlationId }}</code>
      </span>
      <button @click="clearCorrelation" class="text-sm text-indigo-700 hover:text-indigo-900 underline">Clear</button>
    </div>

    <!-- Filters -->
    <div class="bg-white border border-gray-200 rounded-xl p-4 mb-2 grid grid-cols-3 gap-2">
      <FormField label="From" type="datetime-local" v-model="filters.from" />
      <FormField label="To"   type="datetime-local" v-model="filters.to" />
      <div class="mb-2" ref="actionMenuRef">
        <label class="block text-sm font-medium text-gray-700 mb-1">Action</label>
        <div class="relative">
          <!-- Collapsed: a single-row control showing a summary; expanded: a
               scrollable checkbox panel that shows far more options than a
               fixed-height <select multiple> listbox. -->
          <button type="button" @click="actionMenuOpen = !actionMenuOpen"
                  class="input w-full flex items-center justify-between text-left"
                  aria-label="Action" aria-haspopup="listbox" :aria-expanded="actionMenuOpen">
            <span class="truncate" :class="filters.action.length ? 'text-gray-900' : 'text-gray-500'">{{ actionSummary }}</span>
            <span class="text-xs text-gray-400 ml-2">▾</span>
          </button>
          <div v-if="actionMenuOpen"
               class="absolute z-20 mt-1 w-full max-h-72 overflow-y-auto bg-white border border-gray-200 rounded-lg shadow-lg py-1"
               role="listbox" aria-multiselectable="true">
            <div v-if="filters.action.length" class="px-3 py-1.5 border-b border-gray-100">
              <button type="button" @click="filters.action = []" class="text-xs text-blue-600 hover:underline">Clear selection</button>
            </div>
            <div v-for="group in actionGroups" :key="group.label">
              <div class="px-3 pt-2 pb-1 text-xs font-semibold text-gray-500 uppercase tracking-wide">{{ group.label }}</div>
              <label v-for="opt in group.options" :key="opt.value"
                     class="flex items-center gap-2 px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50 cursor-pointer">
                <input type="checkbox" :value="opt.value" v-model="filters.action" class="rounded text-blue-600" />
                {{ opt.label }}
              </label>
            </div>
            <p v-if="actionGroups.length === 0" class="px-3 py-2 text-xs text-gray-500">No actions available</p>
          </div>
        </div>
        <p class="text-xs text-gray-400 mt-1">Leave empty for all actions.</p>
      </div>
    </div>
    <div class="flex gap-2 mb-2">
      <button @click="load()" class="btn-primary">Filter</button>
      <button @click="clearFilters" class="btn-secondary">Clear</button>
    </div>

    <div v-if="needsProfile"
         class="bg-white border border-gray-200 rounded-xl p-8 text-center text-sm text-gray-500">
      Select a profile in the sidebar to view its audit log.
    </div>
    <template v-else>
      <ResultsTable table-key="audit-log" :columns="cols" :rows="tableRows" row-key="id"
        empty-text="No audit events found" empty-icon="clipboard" :auto-fit-first-view="true">
        <template #toolbar>
          <button @click="doExportCsv" :disabled="!events.length" class="btn-secondary text-xs">Export CSV</button>
        </template>
        <template #cell-occurredAt="{ value }"><RelativeTime :value="value as string" /></template>
        <template #cell-action="{ value }">
          <span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium" :class="actionColor(value as string)">{{ actionLabel(value as string) }}</span>
        </template>
        <template #cell-targetDn="{ value }"><span class="truncate block" :title="value as string">{{ value }}</span></template>
        <template #cell-detail="{ value }">
          <span v-if="value" class="text-gray-500 whitespace-pre-wrap">{{ value }}</span>
        </template>
      </ResultsTable>
      <!-- The log is server-paginated to the most recent matching events; the
           rich table sorts/filters/exports that loaded set client-side. When
           the cap is hit, prompt the operator to narrow the filter. -->
      <p v-if="capped" class="text-xs text-gray-500 mt-2">
        Showing the most recent {{ pageSize }} matching events. Narrow the date range or action filter to see older ones.
      </p>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { LocationQueryValue } from 'vue-router'
import { useApi, downloadBlob } from '@/composables/useApi'
import { useDirectoryPicker } from '@/composables/useDirectoryPicker'
import { useAuthStore } from '@/stores/auth'
import { useProfilePickerStore } from '@/stores/profilePicker'
import { getAuditLog, getAuditActions } from '@/api/audit'
import { actionLabel, actionColor } from '@/components/dashboard/auditLabels'
import ResultsTable, { type ColumnDef } from '@/components/ResultsTable.vue'
import FormField from '@/components/FormField.vue'
import RelativeTime from '@/components/RelativeTime.vue'

interface AuditFilters {
  from: string
  to: string
  action: string[]
}

interface AuditEvent {
  id: string
  occurredAt?: string
  actorUsername?: string
  action?: string
  targetDn?: string
  detail?: Record<string, unknown>
}

// Row shape handed to ResultsTable: detail flattened to a string so the table's
// client-side sort/filter/CSV treat it as plain text (the raw object would
// stringify to "[object Object]").
interface AuditRow {
  id: string
  occurredAt: string
  actorUsername: string
  action: string
  targetDn: string
  detail: string
  // Index signature so AuditRow[] satisfies ResultsTable's SortableRow[].
  [key: string]: unknown
}

interface ActionOption { value: string; label: string }
interface ActionGroup { label: string; options: ActionOption[] }
interface DirectoryOption { id: string; displayName: string }

const route = useRoute()
const router = useRouter()
const { loading, call } = useApi()
const auth = useAuthStore()
const profilePicker = useProfilePickerStore()

// Two scoping models, by role. Superadmins pick a directory (or "All
// Directories") via the existing directory picker. Admins work against a
// provisioning profile chosen in the sidebar; the audit log inherits that
// selection and scopes to the profile's directory — consistent with every
// other admin view — rather than offering a directory picker (which doesn't
// map to the profile-scoped admin model).
const { dirId, directories, selectedDir, showPicker } = useDirectoryPicker()
// useDirectoryPicker is a plain-JS composable; give the list a shape for the
// template (the superadmin directory picker).
const directoryOptions = computed<DirectoryOption[]>(() => directories.value as DirectoryOption[])
const selectedProfile = computed(() => profilePicker.selectedProfile)
const scopeDirectoryId = computed(() =>
  auth.isSuperadmin ? (dirId.value || '') : (selectedProfile.value?.directoryId || ''))
// Admins need a profile selected before there's anything to scope to.
const needsProfile = computed(() => !auth.isSuperadmin && !selectedProfile.value)

const events   = ref<AuditEvent[]>([])
// The log is server-paginated; we fetch the most recent page (up to the
// backend's max page size) and let ResultsTable sort/filter/paginate it
// client-side. `capped` warns when there may be older events beyond this set.
const pageSize = 200
const capped   = computed(() => events.value.length >= pageSize)

const filters = ref<AuditFilters>({ from: '', to: '', action: [] })

// ResultsTable column definitions — sortable + resizable, with sensible
// starting widths. Detail is widest (free-form JSON), When/Action narrow.
const cols: ColumnDef[] = [
  { key: 'occurredAt',    label: 'When',   defaultWidth: 150 },
  { key: 'actorUsername', label: 'Actor',  defaultWidth: 140 },
  { key: 'action',        label: 'Action', defaultWidth: 160 },
  { key: 'targetDn',      label: 'Target', defaultWidth: 280 },
  { key: 'detail',        label: 'Detail', defaultWidth: 360 },
]

// Flatten each event for the table: detail → newline-joined "key: value" text.
const tableRows = computed<AuditRow[]>(() => events.value.map(e => ({
  id:             e.id,
  occurredAt:     e.occurredAt ?? '',
  actorUsername:  e.actorUsername ?? '',
  action:         e.action ?? '',
  targetDn:       e.targetDn ?? '',
  detail:         formatDetail(e.detail),
})))

// Correlation id arrives as a query param from the Directory Sync
// "trace" link; it narrows the log to a single originating operation.
// vue-router types a query value as string | string[] | null, so a
// hand-crafted ?correlationId=a&correlationId=b would arrive as an array
// — collapse to the first value so we always bind a single string.
const firstQueryValue = (v: LocationQueryValue | LocationQueryValue[]): string =>
  (Array.isArray(v) ? v[0] : v) || ''
const correlationId = ref<string>(firstQueryValue(route.query.correlationId))

// The audit-action filter catalogue is edition-filtered on the backend
// (GET /audit/actions returns AuditAction names the current edition exposes),
// so non-community actions (access reviews, SoD, HR, auditor portal) never
// reach the picker without the client maintaining its own exclude list.
const exposedActions = ref<string[]>([])

// Display-only grouping of the backend-provided action names into
// operator-friendly buckets. Prefix matching keeps grouping in sync with the
// enum; the *set* of actions comes from the backend, not from these prefixes.
const ACTION_CATEGORIES: { label: string; prefixes: string[] }[] = [
  { label: 'Users',                  prefixes: ['USER_', 'PASSWORD_'] },
  { label: 'Groups',                 prefixes: ['GROUP_'] },
  { label: 'Directory entries',      prefixes: ['ENTRY_', 'LDIF_', 'INTEGRITY_', 'BULK_', 'LDAP_'] },
  { label: 'Approvals',              prefixes: ['APPROVAL_'] },
  { label: 'Lifecycle playbooks',    prefixes: ['PLAYBOOK_'] },
  { label: 'Provisioning profiles',  prefixes: ['PROFILE_'] },
  { label: 'Application accounts',   prefixes: ['ACCOUNT_'] },
  { label: 'API tokens',             prefixes: ['API_TOKEN_'] },
  { label: 'Directory sync',         prefixes: ['REPLICATION_'] },
]

// Action multi-select dropdown: a single-row trigger that opens a checkbox
// panel. Summary text keeps the collapsed control compact.
const actionMenuOpen = ref(false)
const actionMenuRef = ref<HTMLElement | null>(null)
const actionSummary = computed<string>(() => {
  const sel = filters.value.action
  if (!sel.length) return 'All actions'
  if (sel.length === 1) return actionLabel(sel[0])
  return `${sel.length} actions selected`
})
function onActionClickOutside(e: MouseEvent): void {
  if (actionMenuRef.value && !actionMenuRef.value.contains(e.target as Node)) {
    actionMenuOpen.value = false
  }
}
onMounted(() => document.addEventListener('click', onActionClickOutside))
onBeforeUnmount(() => document.removeEventListener('click', onActionClickOutside))

const actionGroups = computed<ActionGroup[]>(() => {
  const allowed = exposedActions.value
  const claimed = new Set<string>()
  const groups: ActionGroup[] = ACTION_CATEGORIES
    .map(cat => {
      const options = allowed
        .filter(k => cat.prefixes.some(p => k.startsWith(p)))
        .map(k => { claimed.add(k); return { value: k, label: actionLabel(k) } })
        .sort((a, b) => a.label.localeCompare(b.label))
      return { label: cat.label, options }
    })
    .filter(g => g.options.length > 0)
  // Catch-all for any exposed action no category prefix claimed, so a new enum
  // value still appears in the picker rather than going missing.
  const orphans = allowed
    .filter(k => !claimed.has(k))
    .map(k => ({ value: k, label: actionLabel(k) }))
    .sort((a, b) => a.label.localeCompare(b.label))
  if (orphans.length) groups.push({ label: 'Other', options: orphans })
  return groups
})

function formatDetail(detail: unknown): string {
  if (!detail || typeof detail !== 'object') return ''
  return Object.entries(detail as Record<string, unknown>)
    .map(([k, v]) => `${k}: ${v}`).join('\n')
}

function clearFilters(): void {
  filters.value = { from: '', to: '', action: [] }
  // Also drop an active correlation trace so "Clear" means clear
  // everything. When no trace is active this is a no-op (the route
  // watcher won't fire), preserving the apply-on-Filter behaviour.
  clearCorrelation()
}

// `<input type="datetime-local">` returns a string like
// `2026-05-01T21:25` with no zone, but the audit-log endpoint binds
// each `from`/`to` to OffsetDateTime (zone required). new Date(v)
// parses the value in the browser's local zone and toISOString()
// normalises to ISO-8601 + UTC `Z` — exactly what the backend wants.
function toIsoZoned(v: string): string | undefined {
  if (!v) return undefined
  const d = new Date(v)
  return Number.isNaN(d.getTime()) ? undefined : d.toISOString()
}

async function loadActions(): Promise<void> {
  try {
    const { data } = await getAuditActions()
    exposedActions.value = data
  } catch {
    // Non-fatal: leave the picker with no action options if the catalogue
    // can't load; the rest of the log still works.
  }
}

async function load(): Promise<void> {
  // Admin view scopes to the sidebar-selected profile; until one is chosen
  // (e.g. while it loads after mount) there's nothing to query.
  if (needsProfile.value) {
    events.value = []
    return
  }
  try {
    await call(async () => {
      const params = {
        page: 0, size: pageSize,
        directoryId:   scopeDirectoryId.value || undefined,
        // datetime-local yields `YYYY-MM-DDTHH:MM` with no zone, but
        // the backend's @RequestParam OffsetDateTime requires a zone
        // offset. Interpret the picker value in the user's local zone
        // and serialise to ISO with offset (toISOString → UTC `Z`).
        from:          toIsoZoned(filters.value.from),
        to:            toIsoZoned(filters.value.to),
        action:        filters.value.action.length ? filters.value.action : undefined,
        correlationId: correlationId.value || undefined,
      }
      const { data } = await getAuditLog(params)
      events.value = data.content ?? data
    })
  } catch {
    // Error already displayed by useApi — prevent unhandled rejection
  }
}

// Client-side CSV of the loaded events (what the table is showing), built from
// the same flattened rows. No server export endpoint for audit today; the rows
// are already in memory, so export-what-you-see is the natural behaviour.
function csvCell(v: string): string {
  // RFC 4180: quote always; escape embedded quotes by doubling.
  return '"' + String(v ?? '').replace(/"/g, '""') + '"'
}
function doExportCsv(): void {
  const headers = cols.map(c => c.label ?? c.key)
  const lines = [headers.map(csvCell).join(',')]
  for (const r of tableRows.value) {
    lines.push(cols.map(c => csvCell(
      c.key === 'action' ? actionLabel(r.action) : String(r[c.key] ?? ''),
    )).join(','))
  }
  const blob = new Blob([lines.join('\r\n')], { type: 'text/csv;charset=utf-8' })
  downloadBlob(blob, 'audit-log.csv')
}

function clearCorrelation(): void {
  // Drop the query param; the route watcher reloads with it removed.
  const q = { ...route.query }
  delete q.correlationId
  router.replace({ query: q })
}

// React to in-place navigations that change the trace target (e.g. a
// second "trace" click while already on this view). Initial mount is
// handled by onMounted, so this fires only on subsequent changes.
watch(() => route.query.correlationId, (v) => {
  correlationId.value = firstQueryValue(v)
  load()
})

// Admins: the sidebar profile populates asynchronously after mount and can be
// switched while on this view — reload whenever its directory changes.
watch(() => selectedProfile.value?.directoryId, () => {
  if (!auth.isSuperadmin) load()
})

onMounted(() => { loadActions(); load() })
</script>

<style scoped>
@reference "tailwindcss";
</style>
