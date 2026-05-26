<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div class="p-6">
    <h1 class="text-2xl font-bold text-gray-900 mb-1">Directory Search</h1>
    <p class="text-sm text-gray-500 mb-3">Search for entries across directories using LDAP filters</p>

    <!-- Directory picker — outside the criteria panel, matching the layout
         used by Operational Reports and Compliance Reports so the page
         convention is consistent across the superadmin section. -->
    <div class="mb-3">
      <label class="block text-sm font-medium text-gray-700 mb-1">Directory</label>
      <select v-model="form.directoryId" class="input w-64" aria-label="Directory">
        <option value="" disabled>{{ loadingDirs ? 'Loading…' : '— Select directory —' }}</option>
        <option v-for="d in directories" :key="d.id" :value="d.id">{{ d.displayName }}</option>
      </select>
    </div>

    <!-- Search criteria panel -->
    <div class="bg-white border border-gray-200 rounded-xl p-3 mb-3">
      <!-- 3-column primary criteria: LDAP Filter, Base DN, Attributes.
           These are the fields users touch on most searches; promoting
           Base DN and Attributes out of "Advanced" surfaces them at the
           cost of one extra row. Enter in the filter still triggers
           search (preserved from the original behaviour).
           Responsive: stacks to 1 column below md (~768px) so DnPicker's
           input + browse button doesn't get crushed at narrow widths. -->
      <div class="grid grid-cols-1 md:grid-cols-3 gap-3">
        <div>
          <label for="search-filter" class="block text-xs font-medium text-gray-600 mb-0.5">LDAP Filter</label>
          <input
            id="search-filter"
            v-model="form.filter"
            class="input w-full font-mono text-xs"
            placeholder="(objectClass=inetOrgPerson)"
            @keyup.enter="doSearch"
          />
        </div>
        <div>
          <!-- DnPicker is a composed component (input + browse button) and
               doesn't forward `id` to its inner input, so we leave the
               label visual-only here. Tab order still reaches the input
               normally; only label-click-to-focus is missing — a fair
               trade vs. modifying DnPicker's API. -->
          <label class="block text-xs font-medium text-gray-600 mb-0.5">Base DN</label>
          <DnPicker v-model="form.baseDn" :directory-id="form.directoryId" placeholder="dc=example,dc=com (optional)" />
        </div>
        <div>
          <label for="search-attributes" class="block text-xs font-medium text-gray-600 mb-0.5">
            Attributes
            <span class="text-gray-500 font-normal">(comma-separated, optional)</span>
          </label>
          <input id="search-attributes" v-model="form.attributes" class="input w-full" placeholder="cn,mail,uid" />
        </div>
      </div>

      <!-- Action row: Search button + the lower-frequency
           Clear / Save / Saved-Searches controls promoted out of
           Other Criteria so the primary action stays adjacent to the
           form. -->
      <div class="mt-3 flex items-center gap-2 flex-wrap">
        <button @click="doSearch" :disabled="!form.directoryId || searching" class="btn-primary">
          {{ searching ? 'Searching…' : 'Search' }}
        </button>
        <button @click="clearForm" class="btn-secondary">Clear</button>
        <button @click="promptSaveSearch" class="btn-secondary">Save Search</button>
        <select v-if="savedSearches.length" @change="loadSavedSearch($event)" aria-label="Load saved search" class="input w-48 text-xs">
          <option value="">— Saved Searches —</option>
          <option v-for="(s, i) in savedSearches" :key="i" :value="i">{{ s.name }}</option>
        </select>
        <button v-if="savedSearches.length" @click="clearSavedSearches" class="text-xs text-gray-500 hover:text-gray-600" title="Clear all saved searches">Clear saved</button>
      </div>

      <!-- Builder disclosure: visual filter builder. The component owns
           its own collapse state; we two-way-bind the filter string so
           edits in the builder flow back into form.filter. -->
      <LdapFilterBuilder
        v-model="form.filter"
        :directory-id="form.directoryId"
        class="mt-2"
      />

      <!-- Other criteria disclosure: scope + size limit. (Base DN and
           Attributes were promoted to the primary 3-column row above.)
           Default-collapsed; shows "(modified)" hint when scope or limit
           differs from defaults so users notice hidden state.
           Field layout mirrors the primary 3-col row above: labels
           above the inputs, fields stack below, all responsive. -->
      <details class="mt-2 group">
        <summary class="text-xs text-gray-500 cursor-pointer select-none flex items-center gap-1 hover:text-gray-700">
          <span class="inline-block transition-transform group-open:rotate-90">▸</span>
          Other criteria
          <span v-if="advancedFieldsActive" class="text-blue-600 ml-1 normal-case">(modified)</span>
        </summary>
        <div class="mt-2 grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 gap-3">
          <div>
            <label for="search-scope" class="block text-xs font-medium text-gray-600 mb-0.5">Scope</label>
            <select id="search-scope" v-model="form.scope" class="input w-full">
              <option value="sub">Subtree</option>
              <option value="one">One Level</option>
              <option value="base">Base</option>
            </select>
          </div>
          <div>
            <label for="search-size-limit" class="block text-xs font-medium text-gray-600 mb-0.5">Size Limit</label>
            <input id="search-size-limit" v-model.number="form.limit" type="number" min="1" max="1000" class="input w-full" />
          </div>
          <div>
            <label for="search-time-limit" class="block text-xs font-medium text-gray-600 mb-0.5">
              Time Limit
              <span class="text-gray-500 font-normal">(seconds, 0 = none)</span>
            </label>
            <input id="search-time-limit" v-model.number="form.timeLimit" type="number" min="0" max="3600" class="input w-full" />
          </div>
          <div class="flex items-center gap-2 pt-4 sm:col-span-2 md:col-span-3">
            <input id="search-include-operational" type="checkbox" v-model="form.includeOperational" class="rounded border-gray-300" />
            <label for="search-include-operational" class="text-xs font-medium text-gray-600 cursor-pointer select-none">
              Include operational attributes
              <span class="text-gray-500 font-normal">(createTimestamp, modifyTimestamp, entryUUID, …)</span>
            </label>
          </div>
        </div>
      </details>
    </div>

    <!-- Search history -->
    <div v-if="history.length" class="mb-4">
      <div class="flex items-center gap-2 mb-2">
        <span class="text-xs font-medium text-gray-500 uppercase tracking-wider">Recent Searches</span>
        <button @click="clearHistory" class="text-xs text-gray-500 hover:text-gray-600">Clear</button>
      </div>
      <div class="flex flex-wrap gap-2">
        <button
          v-for="(h, i) in history" :key="i"
          @click="loadFromHistory(h)"
          class="text-xs bg-gray-100 hover:bg-gray-200 text-gray-700 px-3 py-1.5 rounded-full truncate max-w-xs transition-colors"
          :title="h.filter"
        >
          {{ h.filter || '(objectClass=*)' }}
        </button>
      </div>
    </div>

    <!-- Results -->
    <div v-if="hasSearched">
      <!-- "Re-run search" hint stays above the table — it's an
           actionable instruction (not a status), and the user
           shouldn't have to find it inside the table chrome. The
           edit toggle itself moved into each table's toolbar slot
           so it's adjacent to the per-table actions like Export
           LDIF. -->
      <p
        v-if="needsObjectClassReFetch"
        class="text-xs text-amber-600 mb-2"
      >
        Re-run the search to enable editing — objectClass attribute is
        needed to determine which entries are editable.
      </p>

      <!-- Read-only table (default). -->
      <ResultsTable
        v-if="!editMode"
        table-key="directory-search-results"
        :columns="tableColumns"
        :rows="tableRows"
        row-key="dn"
        :row-clickable="true"
        empty-text="No entries found."
        @row-click="(row) => showEntryDetail(row.__entry as DirectoryEntry)"
      >
        <template #toolbar>
          <button
            v-if="results.length && auth.isDirectorySearchInlineEditEnabled"
            type="button"
            @click="onEditToggle"
            :disabled="!canEnableEdit"
            :title="editToggleTitle"
            :class="[
              'btn-secondary text-xs',
              !canEnableEdit ? 'opacity-50 cursor-not-allowed' : '',
            ]"
            data-edit-toggle
          >Edit results</button>
          <span
            v-if="schemaLoading"
            class="text-xs text-gray-500"
          >loading schema…</span>
          <button
            v-if="results.length"
            @click="doExportResults"
            class="btn-secondary text-xs"
          >Export LDIF</button>
        </template>
        <template #cell-dn="{ value }">
          <span class="font-mono text-blue-600 break-all">{{ value }}</span>
        </template>
      </ResultsTable>

      <!-- Editable table (edit mode). Gated again on the operator
           flag in case it flipped to false after the user already
           toggled into edit mode (the toggle button hides immediately;
           this guard prevents the editable surface from continuing
           to render). -->
      <EditableResultsTable
        v-else-if="auth.isDirectorySearchInlineEditEnabled"
        table-key="directory-search-results-edit"
        :columns="tableColumns"
        :rows="tableRows"
        row-key="dn"
        :edit="true"
        :directory-id="form.directoryId"
        :schema-map="schemaMap"
        empty-text="No entries found."
        @row-saved="onRowSaved"
      >
        <template #toolbar>
          <button
            type="button"
            @click="onEditToggle"
            class="btn-secondary text-xs"
            title="Changes save automatically when focus leaves a row."
            data-edit-toggle
          >Done editing</button>
          <span class="text-xs text-gray-500">
            Changes save automatically when focus leaves a row.
          </span>
          <!-- Export LDIF intentionally absent in edit mode — exporting
               while rows have dirty pending edits would produce a
               misleading snapshot (some saved, some not). Toggle out
               of edit first, then export. -->
        </template>
        <template #cell-dn="{ value }">
          <span class="font-mono text-blue-600 break-all">{{ value }}</span>
        </template>
      </EditableResultsTable>
    </div>

    <!-- Entry detail dialog -->
    <div v-if="selectedEntry" class="fixed inset-0 z-50 flex items-center justify-center bg-black/40" @click.self="selectedEntry = null">
      <div v-dialog-a11y role="dialog" aria-modal="true" aria-labelledby="dir-search-entry-title"
           @keydown.escape="selectedEntry = null"
           class="bg-white rounded-xl shadow-xl w-full max-w-2xl max-h-[80vh] flex flex-col">
        <div class="flex items-center justify-between px-5 py-3 border-b border-gray-200">
          <h3 id="dir-search-entry-title" class="text-sm font-semibold text-gray-900 truncate">{{ selectedEntry.dn }}</h3>
          <button @click="selectedEntry = null" aria-label="Close" class="text-gray-500 hover:text-gray-600 text-lg leading-none">&times;</button>
        </div>
        <div class="overflow-y-auto p-5">
          <table class="w-full text-sm">
            <tbody>
              <tr v-for="(values, attr) in selectedEntry.attributes" :key="attr" class="border-b border-gray-100">
                <td class="py-2 pr-4 font-medium text-gray-600 align-top whitespace-nowrap">{{ attr }}</td>
                <td class="py-2 font-mono text-xs text-gray-800 break-all">
                  <div v-for="(v, i) in values" :key="i">{{ v }}</div>
                  <div v-if="attr === 'userAccountControl' && values.length" class="mt-1 flex flex-wrap gap-1">
                    <span v-for="flag in decodeUAC(values[0])" :key="flag"
                      :class="['px-1.5 py-0.5 rounded text-xs font-medium',
                        flag === 'DISABLED' || flag === 'LOCKED_OUT' ? 'bg-red-100 text-red-800' :
                        flag === 'PASSWORD_EXPIRED' || flag === 'PASSWORD_NEVER_EXPIRES' ? 'bg-yellow-100 text-yellow-800' :
                        'bg-gray-100 text-gray-700']">
                      {{ flag }}
                    </span>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
          <p v-if="!Object.keys(selectedEntry.attributes || {}).length" class="text-sm text-gray-500 text-center py-4">No attributes returned.</p>

          <!-- Group membership lookup -->
          <div class="mt-4 border-t border-gray-200 pt-4">
            <button @click="loadGroups(selectedEntry.dn)" :disabled="loadingGroups" class="btn-secondary text-sm">
              {{ loadingGroups ? 'Loading…' : (entryGroups !== null ? 'Refresh Groups' : 'View Groups') }}
            </button>

            <div v-if="entryGroups !== null" class="mt-3">
              <p class="text-xs font-medium text-gray-500 uppercase tracking-wider mb-2">
                Group Memberships ({{ entryGroups.length }})
              </p>
              <div v-if="entryGroups.length === 0" class="text-sm text-gray-500">Not a member of any groups.</div>
              <div v-else class="space-y-1 max-h-48 overflow-y-auto">
                <div v-for="g in entryGroups" :key="g.dn"
                  class="font-mono text-xs text-gray-700 bg-gray-50 rounded px-3 py-1.5 break-all">
                  <span class="font-medium text-gray-900">{{ g.cn || '' }}</span>
                  <span v-if="g.cn" class="text-gray-500 ml-1">—</span>
                  {{ g.dn }}
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>

  <!-- Save search modal -->
  <AppModal v-model="showSaveSearchModal" title="Save Search" size="sm">
    <div class="space-y-3">
      <p class="text-sm text-gray-600">Save the current search parameters for quick access later.</p>
      <div>
        <label for="dsv-search-name" class="block text-sm font-medium text-gray-700 mb-1">Search Name</label>
        <input id="dsv-search-name" v-model="saveSearchName" class="input w-full" placeholder="e.g. Active users in ou=people"
               @keydown.enter="doSaveSearch" />
      </div>
    </div>
    <template #footer>
      <button @click="showSaveSearchModal = false" class="btn-neutral">Cancel</button>
      <button @click="doSaveSearch" :disabled="!saveSearchName.trim()" class="btn-primary">Save</button>
    </template>
  </AppModal>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useNotificationStore } from '@/stores/notifications'
import { useAuthStore } from '@/stores/auth'
import { useConfirm } from '@/composables/useConfirm'
import { listDirectories } from '@/api/directories'
import { searchEntries } from '@/api/browse'
import { listAttributeTypes } from '@/api/schema'
import DnPicker from '@/components/DnPicker.vue'
import AppModal from '@/components/AppModal.vue'
import ResultsTable, { type ColumnDef } from '@/components/ResultsTable.vue'
import EditableResultsTable from '@/components/EditableResultsTable.vue'
import LdapFilterBuilder from '@/components/LdapFilterBuilder.vue'
import type { AttributeTypeInfo } from '@/composables/useEntryClassification'
import type { components } from '@/api/openapi'

type LdapEntryResponse = components['schemas']['LdapEntryResponse']

interface DirectoryEntry {
  dn: string
  attributes: Record<string, string[]>
}

interface SavedSearch {
  name: string
  baseDn: string
  scope: string
  filter: string
  attributes: string
  limit: number
  // Both fields are optional for back-compat with searches saved before
  // they were added; loadSavedSearch defaults missing values to 0/false.
  timeLimit?: number
  includeOperational?: boolean
}

interface HistoryEntry {
  directoryId: string
  baseDn: string
  scope: string
  filter: string
  attributes: string
  limit: number
  timeLimit?: number
  includeOperational?: boolean
}

interface DirectoryOption {
  id: string
  displayName: string
  directoryType?: string
}

const HISTORY_KEY = 'ldap-search-history'
const SAVED_KEY   = 'ldap-saved-searches'
const MAX_HISTORY = 10

const notif  = useNotificationStore()
const auth   = useAuthStore()
const confirm = useConfirm()

const directories = ref<DirectoryOption[]>([])
const loadingDirs = ref(false)
const searching   = ref(false)
const hasSearched = ref(false)
const results      = ref<DirectoryEntry[]>([])
const selectedEntry = ref<DirectoryEntry | null>(null)
const entryGroups   = ref<Array<{ dn: string, cn: string }> | null>(null)
const loadingGroups = ref(false)

const form = ref({
  directoryId: '',
  baseDn: '',
  scope: 'sub',
  filter: '',
  attributes: '',
  limit: 100,
  timeLimit: 0,           // 0 = no server-side timeout
  includeOperational: false,
})

const history = ref<HistoryEntry[]>(loadHistory())
const savedSearches = ref<SavedSearch[]>(loadSavedSearches())

// ── Inline-edit state (Phase 1 / Task 7) ────────────────────────────
//
// Toggle is per-tab session state — no localStorage. The next time the
// user lands on this page they'll see read-only mode by default; edit
// mode is opt-in each visit.
//
// Row cap: per-row inputs scale linearly in DOM size. 500 keeps the
// UI snappy on a mid-range laptop; bulk update + the LDIF export
// flow handle larger sets.
const EDIT_MODE_ROW_CAP = 500
const editMode = ref(false)
const schemaMap = ref<Map<string, AttributeTypeInfo>>(new Map())
const schemaLoading = ref(false)
// Set of directoryIds whose schema we've already fetched (success or
// failure). Avoids re-fetching when the user toggles edit on/off
// repeatedly against the same directory.
const schemaFetched = new Set<string>()

const canEnableEdit = computed(() =>
  results.value.length > 0
  && results.value.length <= EDIT_MODE_ROW_CAP
  && !!form.value.directoryId,
)
const editToggleTitle = computed(() => {
  if (results.value.length === 0) return 'Run a search first'
  if (results.value.length > EDIT_MODE_ROW_CAP) {
    return `Editing disabled for >${EDIT_MODE_ROW_CAP} rows. Narrow the search first.`
  }
  return ''
})

// Hint shown when edit mode is on but the loaded results lack
// objectClass — without it we can't classify any row, so every
// cell would be locked. Resolved by re-running the search.
const needsObjectClassReFetch = computed(() => {
  if (!editMode.value || !results.value.length) return false
  return results.value.some(e => {
    const oc = e.attributes?.objectClass ?? e.attributes?.objectclass
    return !oc || oc.length === 0
  })
})

async function ensureSchema(): Promise<void> {
  const dirId = form.value.directoryId
  if (!dirId) return
  if (schemaFetched.has(dirId)) return
  schemaLoading.value = true
  try {
    const { data } = await listAttributeTypes(dirId)
    const map = new Map<string, AttributeTypeInfo>()
    if (Array.isArray(data)) {
      for (const a of data as AttributeTypeInfo[]) {
        if (a.name) map.set(a.name.toLowerCase(), a)
      }
    }
    schemaMap.value = map
  } catch {
    // Silent fallback — EditableResultsTable conservatively locks
    // every cell when the schema is empty, which is the right
    // degraded-mode behaviour.
    schemaMap.value = new Map()
  } finally {
    schemaFetched.add(dirId)
    schemaLoading.value = false
  }
}

function hasUnsavedCells(): boolean {
  // The component owns the dirty map. Without a ref it's hard to
  // peek — we use a simple convention: the editable-table component
  // sets data-row-state="error" or "saving" on rows whose dirty
  // map is non-empty. For toggling-off we conservatively prompt
  // when ANY row appears mid-save or in-error.
  return document.querySelectorAll(
    '[data-save-state="saving"], [data-save-state="error"]'
  ).length > 0
}

async function onEditToggle(): Promise<void> {
  if (editMode.value) {
    // Toggling off — confirm if anything is mid-flight.
    if (hasUnsavedCells()) {
      const proceed = await confirm({
        title: 'Discard unsaved edits',
        message: 'Some rows have unsaved or failed edits. Discard them?',
        confirmLabel: 'Discard',
        danger: true,
      })
      if (!proceed) return
    }
    editMode.value = false
    return
  }
  await ensureSchema()
  editMode.value = true
}

// Reset edit mode whenever the user changes directory — edit state
// belongs to a specific (dir, search) pair and shouldn't survive a
// directory switch. The schema cache stays per-dir.
watch(() => form.value.directoryId, () => { editMode.value = false })

// If the operator disables the inline-edit feature while the user is
// in edit mode, drop back to read-only. Pending edits in the
// EditableResultsTable's dirty map are lost (the table unmounts);
// acceptable, since flipping the feature off is an admin action and
// the user would need to retype anyway.
watch(() => auth.isDirectorySearchInlineEditEnabled, (enabled) => {
  if (!enabled) editMode.value = false
})

function onRowSaved(dn: string, response: LdapEntryResponse): void {
  // Merge the server response back into results so subsequent reads
  // reflect server-side normalisation. Match by dn.
  const updatedAttrs = (response.attributes ?? {}) as Record<string, string[]>
  for (let i = 0; i < results.value.length; i++) {
    if (results.value[i].dn === dn) {
      results.value[i] = {
        ...results.value[i],
        attributes: { ...results.value[i].attributes, ...updatedAttrs },
      }
      break
    }
  }
}

// Surface a hint on the "Other criteria" disclosure when any field
// inside it has a non-default value. Base DN and Attributes live in
// the primary 3-column row above, so they're always visible — only
// Scope, Size Limit, Time Limit, and Include Operational live behind
// the disclosure and need this hint.
const advancedFieldsActive = computed(() =>
  form.value.scope !== 'sub'
  || form.value.limit !== 100
  || (form.value.timeLimit ?? 0) !== 0
  || !!form.value.includeOperational,
)

// Discover the union of attribute names returned across all rows. We cap
// at 8 to keep the table from exploding for sparse-but-wide LDAP results;
// the column picker lets the user un-hide more if needed.
const resultAttributes = computed<string[]>(() => {
  if (!results.value.length) return []
  const cols = new Set<string>()
  for (const entry of results.value) {
    for (const key of Object.keys(entry.attributes || {})) {
      cols.add(key)
    }
  }
  return [...cols].sort().slice(0, 8)
})

const tableColumns = computed<ColumnDef[]>(() => [
  { key: 'dn', label: 'DN', alwaysVisible: true, defaultWidth: 320 },
  ...resultAttributes.value.map(a => ({ key: a, label: a })),
])

// Flatten { dn, attributes: { cn: [...], mail: [...] } } into
// { dn, cn: 'a, b', mail: 'x', __entry: <original> } so the ResultsTable
// generic sort/filter helpers can index columns by key. The original entry
// is kept under __entry for the row-click handler.
const tableRows = computed(() =>
  results.value.map((entry) => {
    const flat: Record<string, unknown> = { dn: entry.dn, __entry: entry }
    for (const attr of resultAttributes.value) {
      flat[attr] = (entry.attributes[attr] || []).join(', ')
    }
    return flat
  }),
)

async function doSearch(): Promise<void> {
  if (!form.value.directoryId) return
  searching.value = true
  hasSearched.value = false
  try {
    // When edit mode is on, the results table needs `objectClass`
    // (to classify each row) and `modifyTimestamp` (Phase 1.5
    // concurrency check; harmless for Phase 1). Auto-augment the
    // user-supplied attribute list rather than forcing them to
    // remember.
    let attrs = form.value.attributes
    if (editMode.value) {
      const requested = (attrs ?? '')
        .split(',')
        .map(s => s.trim())
        .filter(s => s.length > 0)
      const lower = new Set(requested.map(s => s.toLowerCase()))
      const ensured: string[] = [...requested]
      // Empty `attrs` means "all user attributes" by LDAP convention,
      // which already covers objectClass — but doesn't include
      // operational attrs like modifyTimestamp. Only inject when
      // the user has explicitly listed some attrs.
      if (requested.length > 0) {
        if (!lower.has('objectclass')) ensured.push('objectClass')
        if (!lower.has('modifytimestamp')) ensured.push('modifyTimestamp')
      }
      attrs = ensured.join(',')
    }

    const params: Record<string, unknown> = {
      baseDn: form.value.baseDn || undefined,
      scope: form.value.scope,
      filter: form.value.filter || undefined,
      attributes: attrs || undefined,
      limit: form.value.limit,
    }
    // Only send the new params when they deviate from defaults — keeps
    // the wire request short for the common case and means a
    // pre-feature client (or a saved search from before this feature
    // landed) won't accidentally send timeLimit=0 / includeOperational=
    // false explicitly. Backend defaults match these omissions.
    if (form.value.timeLimit && form.value.timeLimit > 0) {
      params.timeLimit = form.value.timeLimit
    }
    if (form.value.includeOperational) {
      params.includeOperational = true
    }
    const { data } = await searchEntries(form.value.directoryId, params)
    results.value = Array.isArray(data) ? (data as DirectoryEntry[]) : []
    hasSearched.value = true
    saveToHistory(form.value)
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    notif.error(err.response?.data?.detail || err.message || 'Search failed')
  } finally {
    searching.value = false
  }
}

function doExportResults(): void {
  const lines: string[] = []
  for (const entry of results.value) {
    lines.push(`dn: ${entry.dn}`)
    for (const [attr, values] of Object.entries(entry.attributes || {})) {
      for (const v of values) {
        lines.push(`${attr}: ${v}`)
      }
    }
    lines.push('')
  }
  const blob = new Blob([lines.join('\n')], { type: 'application/ldif' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'search-export.ldif'
  a.click()
  URL.revokeObjectURL(url)
}

function decodeUAC(value: string): string[] {
  const uac = parseInt(value, 10)
  if (isNaN(uac)) return []
  const flags: string[] = []
  if (uac & 0x0002) flags.push('DISABLED')
  if (uac & 0x0010) flags.push('LOCKED_OUT')
  if (uac & 0x0020) flags.push('PASSWORD_NOT_REQUIRED')
  if (uac & 0x0200) flags.push('NORMAL_ACCOUNT')
  if (uac & 0x10000) flags.push('PASSWORD_NEVER_EXPIRES')
  if (uac & 0x800000) flags.push('PASSWORD_EXPIRED')
  if (uac & 0x40000) flags.push('SMARTCARD_REQUIRED')
  return flags
}

function showEntryDetail(entry: DirectoryEntry): void {
  selectedEntry.value = entry
  entryGroups.value = null
}

async function loadGroups(dn: string): Promise<void> {
  if (!form.value.directoryId || !dn) return
  loadingGroups.value = true
  try {
    const escapedDn = dn.replace(/([\\*()])/g, '\\$1')
    const { data } = await searchEntries(form.value.directoryId, {
      scope: 'sub',
      filter: `(|(member=${escapedDn})(uniqueMember=${escapedDn})(memberUid=${dn.split(',')[0].split('=')[1] || dn}))`,
      attributes: 'cn,dn',
      limit: 200,
    })
    const arr = Array.isArray(data) ? (data as DirectoryEntry[]) : []
    entryGroups.value = arr
      .map(e => ({ dn: e.dn, cn: (e.attributes?.cn || [])[0] || '' }))
      .sort((a, b) => (a.cn || a.dn).localeCompare(b.cn || b.dn))
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    notif.error('Failed to load groups: ' + (err.response?.data?.detail || err.message))
    entryGroups.value = []
  } finally {
    loadingGroups.value = false
  }
}

function clearForm(): void {
  form.value = {
    ...form.value,
    baseDn: '', scope: 'sub', filter: '', attributes: '', limit: 100,
    timeLimit: 0, includeOperational: false,
  }
  results.value = []
  hasSearched.value = false
}

function saveToHistory(f: typeof form.value): void {
  const entry: HistoryEntry = {
    directoryId: f.directoryId, baseDn: f.baseDn, scope: f.scope,
    filter: f.filter, attributes: f.attributes, limit: f.limit,
    timeLimit: f.timeLimit, includeOperational: f.includeOperational,
  }
  // Treat missing timeLimit / includeOperational on older history
  // entries as 0 / false so a freshly-typed search with default values
  // still dedupes against pre-feature entries with the same core fields.
  const norm = (v: number | undefined): number => v ?? 0
  const normBool = (v: boolean | undefined): boolean => !!v
  const same = (a: HistoryEntry, b: HistoryEntry): boolean =>
    a.directoryId === b.directoryId && a.baseDn === b.baseDn
      && a.scope === b.scope && a.filter === b.filter
      && a.attributes === b.attributes && a.limit === b.limit
      && norm(a.timeLimit) === norm(b.timeLimit)
      && normBool(a.includeOperational) === normBool(b.includeOperational)
  if (history.value.some(h => same(h, entry))) return
  history.value = [entry, ...history.value].slice(0, MAX_HISTORY)
  try { localStorage.setItem(HISTORY_KEY, JSON.stringify(history.value)) } catch { /* ignore */ }
}

function loadHistory(): HistoryEntry[] {
  try { return JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]') as HistoryEntry[] } catch { return [] }
}

/**
 * Coerce missing optional fields (older history/saved-search entries
 * written before timeLimit + includeOperational landed) to their
 * default values. Without this, `undefined` would slip into form state,
 * bypass our `v-model.number` coercions, and cause `<input type=number>`
 * to render with a stale value from the previous search.
 */
function normalizeOptionalFields<T extends { timeLimit?: number, includeOperational?: boolean }>(
  src: T,
): T & { timeLimit: number, includeOperational: boolean } {
  return {
    ...src,
    timeLimit: src.timeLimit ?? 0,
    includeOperational: src.includeOperational ?? false,
  }
}

function loadFromHistory(h: HistoryEntry): void {
  form.value = { ...form.value, ...normalizeOptionalFields(h) }
  doSearch()
}

function clearHistory(): void {
  history.value = []
  try { localStorage.removeItem(HISTORY_KEY) } catch { /* ignore */ }
}

function loadSavedSearches(): SavedSearch[] {
  try { return JSON.parse(localStorage.getItem(SAVED_KEY) || '[]') as SavedSearch[] } catch { return [] }
}

const showSaveSearchModal = ref(false)
const saveSearchName = ref('')

function promptSaveSearch(): void {
  saveSearchName.value = ''
  showSaveSearchModal.value = true
}

function doSaveSearch(): void {
  const name = saveSearchName.value.trim()
  if (!name) return
  showSaveSearchModal.value = false
  const entry: SavedSearch = {
    name, baseDn: form.value.baseDn, scope: form.value.scope,
    filter: form.value.filter, attributes: form.value.attributes, limit: form.value.limit,
    timeLimit: form.value.timeLimit, includeOperational: form.value.includeOperational,
  }
  savedSearches.value = [...savedSearches.value.filter(s => s.name !== entry.name), entry]
  try { localStorage.setItem(SAVED_KEY, JSON.stringify(savedSearches.value)) } catch { /* ignore */ }
}

function loadSavedSearch(event: Event): void {
  const target = event.target as HTMLSelectElement
  const idx = target.value
  if (idx === '') return
  const s = savedSearches.value[parseInt(idx, 10)]
  if (s) {
    form.value = { ...form.value, ...normalizeOptionalFields(s) }
    doSearch()
  }
  target.value = ''
}

function clearSavedSearches(): void {
  savedSearches.value = []
  try { localStorage.removeItem(SAVED_KEY) } catch { /* ignore */ }
}

onMounted(async () => {
  loadingDirs.value = true
  try {
    const { data } = await listDirectories()
    directories.value = (data as DirectoryOption[]).filter(d => d.directoryType !== 'ENTRA_ID')
    if (directories.value.length) form.value.directoryId = directories.value[0].id
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    notif.error(err.response?.data?.detail || err.message || 'Failed to load directories')
  } finally {
    loadingDirs.value = false
  }
})
</script>

<style scoped>
@reference "tailwindcss";
</style>
