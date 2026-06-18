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
        <option v-for="d in directories" :key="d.id" :value="d.id">{{ d.displayName }}</option>
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
    <div class="bg-white border border-gray-200 rounded-xl p-4 mb-2 grid grid-cols-4 gap-2">
      <FormField label="From" type="datetime-local" v-model="filters.from" />
      <FormField label="To"   type="datetime-local" v-model="filters.to" />
      <div class="mb-2">
        <label class="block text-sm font-medium text-gray-700 mb-1">Action</label>
        <select v-model="filters.action" class="input block w-full" aria-label="Action">
          <option value="">All actions</option>
          <optgroup v-for="group in actionGroups" :key="group.label" :label="group.label">
            <option v-for="opt in group.options" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
          </optgroup>
        </select>
      </div>
      <FormField label="Source" type="select" v-model="filters.source" :options="sourceOptions" />
    </div>
    <div class="flex gap-2 mb-2">
      <button @click="load(0)" class="btn-primary">Filter</button>
      <button @click="clearFilters" class="btn-secondary">Clear</button>
    </div>

    <div v-if="needsProfile"
         class="bg-white border border-gray-200 rounded-xl p-8 text-center text-sm text-gray-500">
      Select a profile in the sidebar to view its audit log.
    </div>
    <DataTable v-else :columns="cols" :rows="events" :loading="loading" row-key="id"
      empty-text="No audit events found" empty-icon="clipboard">
      <template #cell-occurredAt="{ value }"><RelativeTime :value="value" /></template>
      <template #cell-action="{ value }">
        <span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium" :class="actionColor(value)">{{ actionLabel(value) }}</span>
      </template>
      <template #cell-targetDn="{ value }"><span class="truncate block max-w-xs" :title="value">{{ value }}</span></template>
      <template #cell-detail="{ value }">
        <span v-if="value" class="cell-muted whitespace-pre-wrap">{{ formatDetail(value) }}</span>
      </template>
    </DataTable>

    <!-- Pagination -->
    <div v-if="!needsProfile" class="flex items-center justify-between mt-4">
      <button :disabled="page === 0" @click="load(page - 1)" class="btn-secondary">← Prev</button>
      <span class="text-sm text-gray-500">Page {{ page + 1 }} of {{ totalPages }}</span>
      <button :disabled="page >= totalPages - 1" @click="load(page + 1)" class="btn-secondary">Next →</button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useApi } from '@/composables/useApi'
import { useDirectoryPicker } from '@/composables/useDirectoryPicker'
import { useAuthStore } from '@/stores/auth'
import { useProfilePickerStore } from '@/stores/profilePicker'
import { getAuditLog } from '@/api/audit'
import { ACTION_LABELS, actionLabel, actionColor } from '@/components/dashboard/auditLabels'
import DataTable from '@/components/DataTable.vue'
import FormField from '@/components/FormField.vue'
import RelativeTime from '@/components/RelativeTime.vue'

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
const selectedProfile = computed(() => profilePicker.selectedProfile)
const scopeDirectoryId = computed(() =>
  auth.isSuperadmin ? (dirId.value || '') : (selectedProfile.value?.directoryId || ''))
// Admins need a profile selected before there's anything to scope to.
const needsProfile = computed(() => !auth.isSuperadmin && !selectedProfile.value)

const events     = ref([])
const page       = ref(0)
const totalPages = ref(1)
const pageSize   = 20

const filters = ref({ from: '', to: '', action: '', source: '' })

// Correlation id arrives as a query param from the Directory Sync
// "trace" link; it narrows the log to a single originating operation.
// vue-router types a query value as string | string[] | null, so a
// hand-crafted ?correlationId=a&correlationId=b would arrive as an array
// — collapse to the first value so we always bind a single string.
const firstQueryValue = (v) => (Array.isArray(v) ? v[0] : v) || ''
const correlationId = ref(firstQueryValue(route.query.correlationId))

// Group every AuditAction known to the frontend into operator-friendly
// buckets. Prefix matching keeps this in sync with the backend enum
// automatically — adding a new ACTION_LABELS entry under an existing
// prefix shows up in the right group without code changes here.
const ACTION_CATEGORIES = [
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

// Action families for governance/compliance features that don't ship in the
// community edition (access reviews, segregation of duties, HR integration,
// auditor portal). Hidden from the picker — including the catch-all — so
// operators aren't offered actions that never occur here.
const NON_COMMUNITY_PREFIXES = ['CAMPAIGN_', 'REVIEW_', 'SOD_', 'HR_', 'AUDITOR_']
const isCommunityAction = (k) => !NON_COMMUNITY_PREFIXES.some(p => k.startsWith(p))

const actionGroups = (() => {
  const claimed = new Set()
  const groups = ACTION_CATEGORIES.map(cat => {
    const options = Object.keys(ACTION_LABELS)
      .filter(k => cat.prefixes.some(p => k.startsWith(p)))
      .map(k => { claimed.add(k); return { value: k, label: ACTION_LABELS[k] } })
      .sort((a, b) => a.label.localeCompare(b.label))
    return { label: cat.label, options }
  }).filter(g => g.options.length > 0)
  // Catch-all so any future enum value not covered by a prefix above still
  // appears in the picker rather than going missing — minus the non-community
  // families, which would otherwise resurface here.
  const orphans = Object.keys(ACTION_LABELS)
    .filter(k => !claimed.has(k) && isCommunityAction(k))
    .map(k => ({ value: k, label: ACTION_LABELS[k] }))
    .sort((a, b) => a.label.localeCompare(b.label))
  if (orphans.length) groups.push({ label: 'Other', options: orphans })
  return groups
})()

const sourceOptions = [
  { value: '',                 label: 'All sources' },
  { value: 'INTERNAL',         label: 'Application' },
  { value: 'LDAP_CHANGELOG',   label: 'LDAP changelog' },
]

const cols = [
  { key: 'occurredAt',    label: 'When' },
  { key: 'actorUsername', label: 'Actor' },
  { key: 'action',        label: 'Action' },
  { key: 'targetDn',      label: 'Target' },
  { key: 'detail',        label: 'Detail' },
]

function fmtDate(v) {
  if (!v) return '—'
  return new Date(v).toLocaleString()
}

function formatDetail(detail) {
  if (!detail || typeof detail !== 'object') return ''
  return Object.entries(detail).map(([k, v]) => `${k}: ${v}`).join('\n')
}

function clearFilters() {
  filters.value = { from: '', to: '', action: '', source: '' }
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
function toIsoZoned(v) {
  if (!v) return undefined
  const d = new Date(v)
  return Number.isNaN(d.getTime()) ? undefined : d.toISOString()
}

async function load(p = 0) {
  // Admin view scopes to the sidebar-selected profile; until one is chosen
  // (e.g. while it loads after mount) there's nothing to query.
  if (needsProfile.value) {
    events.value = []
    totalPages.value = 1
    return
  }
  page.value = p
  try {
    await call(async () => {
      const params = {
        page: p, size: pageSize,
        directoryId:   scopeDirectoryId.value || undefined,
        // datetime-local yields `YYYY-MM-DDTHH:MM` with no zone, but
        // the backend's @RequestParam OffsetDateTime requires a zone
        // offset. Interpret the picker value in the user's local zone
        // and serialise to ISO with offset (toISOString → UTC `Z`).
        from:          toIsoZoned(filters.value.from),
        to:            toIsoZoned(filters.value.to),
        action:        filters.value.action || undefined,
        source:        filters.value.source || undefined,
        correlationId: correlationId.value || undefined,
      }
      const { data } = await getAuditLog(params)
      const paged = data.content ? data : { content: data, totalPages: 1 }
      events.value     = paged.content
      totalPages.value = paged.totalPages || 1
    })
  } catch {
    // Error already displayed by useApi — prevent unhandled rejection
  }
}

function clearCorrelation() {
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
  load(0)
})

// Admins: the sidebar profile populates asynchronously after mount and can be
// switched while on this view — reload whenever its directory changes.
watch(() => selectedProfile.value?.directoryId, () => {
  if (!auth.isSuperadmin) load(0)
})

onMounted(() => load(0))
</script>

<style scoped>
@reference "tailwindcss";
</style>
