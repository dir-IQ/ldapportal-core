<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  Bulk-delete users from a CSV upload. Destructive, so the flow is
  deliberately two-step: a mandatory dry-run Preview classifies every row
  (no writes), then a typed confirmation arms the Delete button. The
  backend gates the endpoints on the bulk.delete feature and caps the
  file size; this view never routes through an approval workflow (delete
  parity with single delete).
-->
<template>
  <section class="bg-white border border-gray-200 border-t-0 rounded-b-xl p-6">
    <h2 class="text-lg font-semibold mb-1">Delete Users from CSV</h2>
    <p class="text-sm text-gray-500 mb-4">
      Upload a CSV identifying users to delete. Preview first — nothing is removed until you confirm.
    </p>

    <div class="space-y-3">
      <!-- Resolution controls + CSV file -->
      <div class="grid grid-cols-12 gap-2 items-end">
        <div class="col-span-3">
          <label for="bd-profile" class="block text-sm font-medium text-gray-700 mb-1">Profile <span class="text-red-500">*</span></label>
          <select id="bd-profile" v-model="profileId" class="input w-full" @change="resetResults">
            <option value="">— Select a profile —</option>
            <option v-for="p in profiles" :key="p.id" :value="p.id">{{ p.name }}</option>
          </select>
        </div>

        <div class="col-span-3">
          <label for="bd-mode" class="block text-sm font-medium text-gray-700 mb-1">Match users by</label>
          <select id="bd-mode" v-model="mode" class="input w-full" @change="resetResults">
            <option value="dn">Full DN</option>
            <option value="key">Attribute (e.g. uid)</option>
          </select>
        </div>

        <div v-if="mode === 'key'" class="col-span-2">
          <label for="bd-keyattr" class="block text-sm font-medium text-gray-700 mb-1">
            Attribute <span class="text-red-500">*</span>
          </label>
          <input id="bd-keyattr" v-model="keyAttribute" class="input w-full" placeholder="uid" @input="resetResults" />
        </div>

        <div :class="mode === 'key' ? 'col-span-4' : 'col-span-6'">
          <label for="bd-valuecol" class="block text-sm font-medium text-gray-700 mb-1">CSV column</label>
          <input id="bd-valuecol" v-model="valueColumn" class="input w-full"
                 :placeholder="mode === 'key' ? (keyAttribute || 'uid') : 'dn'" @input="resetResults" />
        </div>

      </div>
      <p v-if="selectedProfile && mode === 'key'" class="text-xs text-gray-600">
        Resolves
        <code class="font-mono text-gray-800">{{ keyAttribute || 'uid' }}</code>
        values under
        <span class="font-mono text-gray-800">{{ selectedProfile.targetUserDn }}</span>
      </p>

      <!-- CSV file + header toggle, left-aligned together. max-w keeps the
           field from stretching across to the right edge. -->
      <div class="max-w-lg">
        <label class="block text-sm font-medium text-gray-700 mb-1">CSV File <span class="text-red-500">*</span></label>
        <label class="csv-file-picker input flex items-center gap-2 w-full cursor-pointer !py-0 !pr-1 hover:border-gray-400 transition-colors bg-white">
          <span class="flex-1 truncate text-sm" :class="file ? 'text-gray-900 font-medium' : 'text-gray-500'">
            {{ file?.name || 'No file chosen' }}
          </span>
          <span class="px-3 py-1 rounded-md bg-blue-50 text-blue-700 text-xs font-medium hover:bg-blue-100 whitespace-nowrap">
            Choose File
          </span>
          <input type="file" accept=".csv,text/csv" @change="onFileChange" aria-label="CSV File" class="sr-only" />
        </label>
        <label for="bd-skip-header" class="flex items-center gap-2 cursor-pointer mt-2">
          <input id="bd-skip-header" type="checkbox" v-model="skipHeaderRow" @change="resetResults" class="rounded border-gray-300" />
          <span class="text-sm text-gray-700">First row in file is a header</span>
        </label>
      </div>

      <!-- Preview (dry run) -->
      <div>
        <button @click="doPreview" :disabled="!canPreview || previewing" class="btn-primary">
          {{ previewing ? 'Previewing…' : 'Preview' }}
        </button>
      </div>

      <!-- Preview -->
      <div v-if="previewResult" class="mt-2 p-4 rounded-lg bg-gray-50 border border-gray-200 text-sm">
        <div class="flex flex-wrap items-center gap-2 mb-3">
          <span class="font-medium text-gray-800">{{ previewResult.totalRows }} rows</span>
          <span class="badge-green">{{ countOf('WILL_DELETE') }} will delete</span>
          <span v-if="countOf('NOT_FOUND')" class="badge-gray">{{ countOf('NOT_FOUND') }} not found</span>
          <span v-if="problemCount" class="badge-amber">{{ problemCount }} need attention</span>
        </div>

        <div class="max-h-64 overflow-auto rounded border border-gray-200 bg-white">
          <table class="w-full text-xs">
            <thead class="bg-gray-100 sticky top-0">
              <tr>
                <th class="px-2 py-1 text-left font-medium text-gray-600">#</th>
                <th class="px-2 py-1 text-left font-medium text-gray-600">Target DN</th>
                <th class="px-2 py-1 text-left font-medium text-gray-600">Outcome</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-gray-100">
              <tr v-for="row in previewResult.rows" :key="row.rowNumber">
                <td class="px-2 py-1 text-gray-500">{{ row.rowNumber }}</td>
                <td class="px-2 py-1 font-mono text-[13px] text-gray-800">{{ row.dn || '—' }}</td>
                <td class="px-2 py-1">
                  <span :class="dispositionBadge(row.disposition)" :title="row.note || ''">
                    {{ dispositionLabel(row.disposition) }}
                  </span>
                  <span v-if="row.note" class="text-gray-500 ml-1">{{ row.note }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- Typed confirmation -->
        <div v-if="countOf('WILL_DELETE') > 0" class="mt-3">
          <p class="text-sm text-gray-700 mb-1">
            This will permanently delete <strong>{{ countOf('WILL_DELETE') }}</strong> user(s).
            Type the profile name
            <code class="font-mono bg-gray-100 px-1 rounded">{{ selectedProfile?.name }}</code>
            to confirm.
          </p>
          <div class="flex gap-2">
            <input v-model="confirmText" class="input w-56"
                   :placeholder="selectedProfile?.name || ''"
                   aria-label="Type the profile name to confirm" />
            <button @click="doDelete" :disabled="!armed || deleting" class="btn-danger">
              {{ deleting ? 'Deleting…' : `Delete ${countOf('WILL_DELETE')} user(s)` }}
            </button>
            <button @click="resetResults" class="btn-neutral">Cancel</button>
          </div>
        </div>
        <p v-else class="mt-3 text-sm text-gray-500">No rows resolved to a deletable user.</p>
      </div>

      <!-- Result -->
      <div v-if="deleteResult" class="mt-2 p-4 rounded-lg bg-gray-50 border border-gray-200 text-sm">
        <div class="grid grid-cols-3 gap-2 mb-3">
          <div class="text-center"><p class="text-2xl font-bold text-red-600">{{ deleteResult.deleted }}</p><p class="text-xs text-gray-500">Deleted</p></div>
          <div class="text-center"><p class="text-2xl font-bold text-yellow-600">{{ deleteResult.skipped }}</p><p class="text-xs text-gray-500">Skipped</p></div>
          <div class="text-center"><p class="text-2xl font-bold text-gray-600">{{ deleteResult.errors }}</p><p class="text-xs text-gray-500">Errors</p></div>
        </div>
        <ul v-if="errorRows.length" class="space-y-1">
          <li v-for="r in errorRows" :key="r.rowNumber" class="text-red-600 text-xs">
            Row {{ r.rowNumber }}{{ r.dn ? ` (${r.dn})` : '' }}: {{ r.message }}
          </li>
        </ul>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useNotificationStore } from '@/stores/notifications'
import { previewBulkDelete, bulkDelete } from '@/api/csvTemplates'

/** Provisioning profile as consumed by the delete profile picker. */
interface ProfileLite { id: string, name: string, targetUserDn?: string | null }

const props = withDefaults(
  defineProps<{ dirId: string, profiles?: ProfileLite[] }>(),
  { profiles: () => [] },
)
const notif = useNotificationStore()

type Disposition = 'WILL_DELETE' | 'NOT_FOUND' | 'OUT_OF_SCOPE' | 'AMBIGUOUS' | 'INVALID'
interface PreviewRow { rowNumber: number, dn?: string, disposition: Disposition, note?: string }
interface PreviewResult { totalRows: number, rows: PreviewRow[] }
interface DeleteRowResult { rowNumber: number, dn?: string, status: 'DELETED' | 'SKIPPED' | 'ERROR', message?: string }
interface DeleteResult { totalRows: number, deleted: number, skipped: number, errors: number, rows: DeleteRowResult[] }
type ApiError = { response?: { data?: { detail?: string } }, message?: string }

function errMsg(e: unknown): string {
  const err = e as ApiError
  return err.response?.data?.detail || err.message || 'Request failed'
}

const profileId     = ref('')
const mode          = ref<'dn' | 'key'>('dn')
const keyAttribute  = ref('uid')
const valueColumn   = ref('')
const skipHeaderRow = ref(true)
const file          = ref<File | null>(null)

const selectedProfile = computed<ProfileLite | null>(() =>
  props.profiles.find(p => p.id === profileId.value) ?? null,
)

const previewing    = ref(false)
const deleting      = ref(false)
const previewResult = ref<PreviewResult | null>(null)
const deleteResult  = ref<DeleteResult | null>(null)
const confirmText   = ref('')

const canPreview = computed(() =>
  !!file.value && !!selectedProfile.value
  && (mode.value === 'dn' || !!keyAttribute.value.trim()),
)

// Armed once the operator types the selected profile's name (case-insensitive) —
// the profile-name analogue of the former "type DELETE" gate.
const armed = computed(() => {
  const name = selectedProfile.value?.name?.trim().toLowerCase()
  return !!name && confirmText.value.trim().toLowerCase() === name
})

const problemCount = computed(() =>
  (previewResult.value?.rows ?? []).filter(
    r => r.disposition === 'OUT_OF_SCOPE' || r.disposition === 'AMBIGUOUS' || r.disposition === 'INVALID',
  ).length,
)

const errorRows = computed(() =>
  (deleteResult.value?.rows ?? []).filter(r => r.status === 'ERROR'),
)

function countOf(d: Disposition): number {
  return (previewResult.value?.rows ?? []).filter(r => r.disposition === d).length
}

const DISPOSITION_LABELS: Record<Disposition, string> = {
  WILL_DELETE: 'Will delete',
  NOT_FOUND: 'Not found',
  OUT_OF_SCOPE: 'Out of scope',
  AMBIGUOUS: 'Ambiguous',
  INVALID: 'Invalid',
}
function dispositionLabel(d: Disposition): string {
  return DISPOSITION_LABELS[d] ?? d
}
function dispositionBadge(d: Disposition): string {
  if (d === 'WILL_DELETE') return 'badge-green'
  if (d === 'NOT_FOUND') return 'badge-gray'
  if (d === 'OUT_OF_SCOPE') return 'badge-red'
  return 'badge-amber'
}

function onFileChange(e: Event) {
  file.value = (e.target as HTMLInputElement).files?.[0] ?? null
  resetResults()
}

function resetResults() {
  previewResult.value = null
  deleteResult.value = null
  confirmText.value = ''
}

function buildRequest() {
  const dnMode = mode.value === 'dn'
  return {
    keyAttribute: dnMode ? null : keyAttribute.value.trim(),
    valueColumn: valueColumn.value.trim() || null,
    // Key-attribute lookups are scoped to the selected profile's target OU.
    baseDn: dnMode ? null : (selectedProfile.value?.targetUserDn ?? null),
    skipHeaderRow: skipHeaderRow.value,
  }
}

async function doPreview() {
  if (!canPreview.value || !file.value) return
  previewing.value = true
  deleteResult.value = null
  confirmText.value = ''
  try {
    const { data } = await previewBulkDelete(props.dirId, file.value, buildRequest())
    previewResult.value = data
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    previewing.value = false
  }
}

async function doDelete() {
  if (!armed.value || !file.value) return
  deleting.value = true
  try {
    const { data } = await bulkDelete(props.dirId, file.value, buildRequest())
    deleteResult.value = data
    previewResult.value = null
    confirmText.value = ''
    notif.success(`Bulk delete done: ${data.deleted} deleted, ${data.errors} errors`)
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    deleting.value = false
  }
}
</script>

<style scoped>
@reference "tailwindcss";

/* Match the file picker height to neighbouring .input controls, including
   the compact density override (see BulkView.vue for the rationale). */
.csv-file-picker {
  height: 38px;
}
[data-density="compact"] .csv-file-picker {
  height: 30px;
}
</style>
