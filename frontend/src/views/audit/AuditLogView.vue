<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div class="p-6">
    <h1 class="text-2xl font-bold text-gray-900 mb-4">Audit Log</h1>
    <p class="text-sm text-gray-500 mt-1">Directory change events and administrative actions</p>

    <!-- Directory picker (superadmin only) -->
    <div v-if="showPicker" class="mb-4">
      <label class="block text-sm font-medium text-gray-700 mb-1">Directory</label>
      <select v-model="selectedDir" class="input w-64">
        <option value="">All Directories</option>
        <option v-for="d in directories" :key="d.id" :value="d.id">{{ d.displayName }}</option>
      </select>
    </div>

    <!-- Filters -->
    <div class="bg-white border border-gray-200 rounded-xl p-4 mb-2 grid grid-cols-3 gap-2">
      <FormField label="From" type="datetime-local" v-model="filters.from" />
      <FormField label="To"   type="datetime-local" v-model="filters.to" />
      <FormField label="Action" type="select" v-model="filters.action" :options="actionOptions" />
    </div>
    <div class="flex gap-2 mb-2">
      <button @click="load(0)" class="btn-primary">Filter</button>
      <button @click="clearFilters" class="btn-secondary">Clear</button>
    </div>

    <DataTable :columns="cols" :rows="events" :loading="loading" row-key="id"
      empty-text="No audit events found" empty-icon="clipboard">
      <template #cell-occurredAt="{ value }"><RelativeTime :value="value" /></template>
      <template #cell-action="{ value }">
        <span class="badge-gray">{{ value }}</span>
      </template>
      <template #cell-targetDn="{ value }"><span class="text-xs truncate block max-w-xs" :title="value">{{ value }}</span></template>
      <template #cell-detail="{ value }">
        <span v-if="value" class="text-xs whitespace-pre-wrap">{{ formatDetail(value) }}</span>
      </template>
    </DataTable>

    <!-- Pagination -->
    <div class="flex items-center justify-between mt-4">
      <button :disabled="page === 0" @click="load(page - 1)" class="btn-secondary">← Prev</button>
      <span class="text-sm text-gray-500">Page {{ page + 1 }} of {{ totalPages }}</span>
      <button :disabled="page >= totalPages - 1" @click="load(page + 1)" class="btn-secondary">Next →</button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useApi } from '@/composables/useApi'
import { useDirectoryPicker } from '@/composables/useDirectoryPicker'
import { getAuditLog } from '@/api/audit'
import DataTable from '@/components/DataTable.vue'
import FormField from '@/components/FormField.vue'
import RelativeTime from '@/components/RelativeTime.vue'

const route = useRoute()
const { loading, call } = useApi()
const { dirId, directories, selectedDir, loadingDirs, showPicker } = useDirectoryPicker()

const events     = ref([])
const page       = ref(0)
const totalPages = ref(1)
const pageSize   = 20

const filters = ref({ from: '', to: '', action: '' })

const actionOptions = [
  { value: '', label: 'All actions' },
  ...['USER_CREATE','USER_UPDATE','USER_DELETE','USER_ENABLE','USER_DISABLE','USER_MOVE',
      'GROUP_CREATE','GROUP_DELETE','GROUP_MEMBER_ADD','GROUP_MEMBER_REMOVE','LDAP_CHANGE']
     .map(v => ({ value: v, label: v }))
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
  filters.value = { from: '', to: '', action: '' }
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
  page.value = p
  try {
    await call(async () => {
      const params = {
        page: p, size: pageSize,
        directoryId:   dirId.value || undefined,
        // datetime-local yields `YYYY-MM-DDTHH:MM` with no zone, but
        // the backend's @RequestParam OffsetDateTime requires a zone
        // offset. Interpret the picker value in the user's local zone
        // and serialise to ISO with offset (toISOString → UTC `Z`).
        from:          toIsoZoned(filters.value.from),
        to:            toIsoZoned(filters.value.to),
        action:        filters.value.action || undefined,
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

onMounted(() => load(0))
</script>

<style scoped>
@reference "tailwindcss";
</style>
