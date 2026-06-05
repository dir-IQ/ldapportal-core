<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <AppModal v-model="visible" title="Import LDIF" size="2xl" fixed-height="min(760px, 90vh)" movable resizable>
    <div class="space-y-3">
      <!-- ── Step 1: pick file + conflict mode ───────────────────────────── -->
      <template v-if="phase === 'pick'">
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">LDIF File</label>
          <div
            role="button"
            tabindex="0"
            aria-label="Choose or drop an LDIF file"
            :class="[
              'border-2 border-dashed rounded-lg p-6 text-center cursor-pointer transition-colors',
              dragging ? 'border-blue-400 bg-blue-50' : 'border-gray-300 hover:border-gray-400',
            ]"
            @click="fileInput?.click()"
            @keydown.enter.prevent="fileInput?.click()"
            @keydown.space.prevent="fileInput?.click()"
            @dragover.prevent="dragging = true"
            @dragleave.prevent="dragging = false"
            @drop.prevent="onDrop"
          >
            <div v-if="!file" class="text-sm text-gray-500">
              <p class="font-medium text-gray-700">Drop an .ldif file here or click to browse</p>
              <p class="text-xs mt-1">Only .ldif files are accepted</p>
            </div>
            <div v-else class="text-sm">
              <p class="font-medium text-gray-900">{{ file.name }}</p>
              <p class="text-xs text-gray-500 mt-1">{{ formatFileSize(file.size) }}</p>
              <button @click.stop="file = null" class="text-xs text-red-500 hover:text-red-700 mt-1">Remove</button>
            </div>
          </div>
          <input ref="fileInput" type="file" accept=".ldif" class="hidden" aria-label="LDIF file" @change="onFileSelect" />
        </div>

        <div>
          <label for="ldif-conflict" class="block text-sm font-medium text-gray-700 mb-1">Conflict Handling</label>
          <select id="ldif-conflict" v-model="conflictHandling" class="input w-full">
            <option value="SKIP">Skip — leave existing entries unchanged</option>
            <option value="OVERWRITE">Update — overwrite existing entry attributes</option>
          </select>
          <p class="text-xs text-gray-500 mt-1">
            Preview shows exactly what would change before anything is written.
          </p>
        </div>
      </template>

      <!-- ── Step 2: preview ──────────────────────────────────────────────── -->
      <template v-else-if="phase === 'preview' && summary">
        <!-- Summary chips -->
        <div class="flex flex-wrap items-center gap-2 text-xs">
          <span class="chip chip-green">Add <b>{{ summary.countsByOp.add.toLocaleString() }}</b></span>
          <span class="chip chip-amber">Modify <b>{{ summary.countsByOp.modify.toLocaleString() }}</b></span>
          <span class="chip chip-red">Delete <b>{{ summary.countsByOp.delete.toLocaleString() }}</b></span>
          <span class="chip chip-blue">Move <b>{{ summary.countsByOp.moddn.toLocaleString() }}</b></span>
          <span class="chip chip-gray">Skip <b>{{ summary.countsByOp.skip.toLocaleString() }}</b></span>
          <span class="chip chip-gray">·</span>
          <span class="chip chip-amber" title="Rows with warnings">⚠ <b>{{ summary.warningCount.toLocaleString() }}</b></span>
          <span class="chip chip-red" title="Rows with errors">✕ <b>{{ summary.errorCount.toLocaleString() }}</b></span>
          <span class="ml-auto text-gray-400">{{ summary.totalRows.toLocaleString() }} records</span>
        </div>

        <!-- IVIA secUser provisioning: confirm/reject + what will happen.
             Shown only when the directory has IVIA enabled. -->
        <div v-if="iviaEnabled"
             class="rounded-lg border border-blue-200 bg-blue-50 px-3 py-2 text-sm text-blue-900">
          <template v-if="summary.containsVendorOverlayEntries">
            <div class="flex items-start gap-2">
              <span aria-hidden="true" class="mt-0.5">ℹ</span>
              <span>
                This file already contains <span class="font-mono">secUser</span> entries, so it's
                imported as-is — no {{ IVIA_ABBR }} accounts will be provisioned on top.
              </span>
            </div>
          </template>
          <template v-else>
            <label class="flex items-start gap-2">
              <input type="checkbox" v-model="provisionVendorAccounts" class="rounded mt-0.5" />
              <span>
                Provision {{ IVIA_ABBR }} accounts (<span class="font-mono">secUser</span>) for imported users
                <span class="block text-xs mt-0.5" :class="provisionVendorAccounts ? 'text-blue-800' : 'text-gray-500'">
                  <template v-if="provisionVendorAccounts">
                    <b>{{ effectiveProvisionCount.toLocaleString() }}</b>
                    new user {{ effectiveProvisionCount === 1 ? 'entry' : 'entries' }} will be provisioned with
                    {{ IVIA_NAME }} ({{ IVIA_ABBR }}) {{ iviaTopologyLabel }}<template v-if="excludedCount"> ({{ excludedCount.toLocaleString() }} excluded)</template>.
                    <span class="block text-blue-700/80">Click a row's <b>+secUser</b> badge to skip provisioning for that user.</span>
                  </template>
                  <template v-else>
                    Imported users will be created as-is — no {{ IVIA_ABBR }} accounts.
                  </template>
                </span>
              </span>
            </label>
          </template>
        </div>

        <!-- Filter + search -->
        <div class="flex flex-wrap items-center gap-2">
          <div class="flex rounded-lg border border-gray-200 overflow-hidden text-xs">
            <button v-for="f in filters" :key="f.value"
                    @click="setFilter(f.value)"
                    :class="['px-3 py-1.5 border-r border-gray-200 last:border-r-0',
                             filter === f.value ? 'bg-blue-600 text-white font-medium' : 'bg-white text-gray-600 hover:bg-gray-50']">
              {{ f.label }}
            </button>
          </div>
          <input v-model="search" @input="onSearchInput" placeholder="Search DN…"
                 class="input input-sm flex-1 min-w-[12rem]" aria-label="Search DN" />
        </div>

        <!-- Rows -->
        <DataTable :columns="cols" :rows="rows" row-key="rowNumber" :loading="loadingPage"
                   :highlight-key="selectedRowNumber ?? undefined"
                   :expanded-key="selectedRowNumber ?? undefined"
                   empty-text="No matching records" empty-icon="folder" @row-click="openRow">
          <template #cell-op="{ row }">
            <span class="badge" :class="opClass(row.op)">{{ row.op }}</span>
          </template>
          <template #cell-dn="{ row }">
            <span class="font-mono text-[13px] text-gray-700 break-all">{{ row.dn || '—' }}</span>
          </template>
          <template #cell-detail="{ row }">
            <span v-if="row.memberDelta" class="text-[13px]">
              member <span class="text-green-700 font-medium">+{{ row.memberDelta.added }}</span>
              <span class="text-red-700 font-medium ml-1">−{{ row.memberDelta.removed }}</span>
            </span>
            <span v-else-if="row.memberCount != null" class="text-[13px] text-gray-600">{{ row.memberCount }} members</span>
            <span v-else class="text-[13px] text-gray-500">
              {{ row.attrCount }} attr{{ row.attrCount === 1 ? '' : 's' }}
              <span v-if="row.objectClasses.length" class="text-gray-400 ml-1">· {{ row.objectClasses.join(', ') }}</span>
            </span>
            <span v-if="rowOverlayEligible(row)"
                  role="button" tabindex="0"
                  @click.stop="toggleRowOverlay(row)"
                  @keydown.enter.stop.prevent="toggleRowOverlay(row)"
                  @keydown.space.stop.prevent="toggleRowOverlay(row)"
                  class="ml-2 inline-flex items-center px-1.5 py-0.5 rounded text-[11px] font-medium align-middle cursor-pointer transition-colors"
                  :class="isExcluded(row)
                    ? 'bg-gray-100 text-gray-400 line-through hover:bg-gray-200'
                    : 'bg-blue-100 text-blue-700 hover:bg-blue-200'"
                  :title="isExcluded(row)
                    ? `Click to provision ${IVIA_ABBR} secUser for this user`
                    : `Click to skip ${IVIA_ABBR} secUser for this user`">
              {{ isExcluded(row) ? 'secUser skipped' : '+secUser' }}
            </span>
          </template>
          <!-- Always render the wrapper span: an empty issues array would
               otherwise make the v-for produce zero vnodes, and DataTable's
               <slot> falls back to interpolating row.issues — rendering a
               literal "[]" in the cell. -->
          <template #cell-issues="{ row }">
            <span class="whitespace-nowrap">
              <span v-for="(iss, i) in row.issues" :key="i" :title="iss.message" :aria-label="iss.code" role="img" class="mr-1">{{ issueIcon(iss) }}</span>
            </span>
          </template>

          <!-- Expanded detail, rendered inline directly beneath the clicked row. -->
          <template #row-detail>
            <div class="px-4 py-3 border-t border-gray-200 bg-gray-50">
              <div class="flex items-center justify-between mb-2">
                <div class="text-sm font-semibold text-gray-700">
                  Row {{ selectedRowNumber }} · <span class="badge" :class="opClass(detail?.op || '')">{{ detail?.op }}</span>
                </div>
                <button @click.stop="selectedRowNumber = null" class="text-xs text-gray-500 hover:text-gray-700">Close</button>
              </div>
              <p class="font-mono text-[13px] text-gray-700 break-all mb-2">{{ detail?.dn }}</p>
              <div v-if="loadingDetail" class="text-xs text-gray-500">Loading…</div>
              <template v-else-if="detail">
                <div v-if="detail.memberDelta" class="text-[13px] mb-2">
                  member <span class="text-green-700 font-medium">+{{ detail.memberDelta.added }}</span>
                  <span class="text-red-700 font-medium ml-1">−{{ detail.memberDelta.removed }}</span>
                </div>
                <ul v-if="detail.issues.length" class="mb-2 space-y-0.5">
                  <li v-for="(iss, i) in detail.issues" :key="i" class="text-xs"
                      :class="iss.severity === 'ERROR' ? 'text-red-700' : iss.severity === 'WARNING' ? 'text-amber-700' : 'text-gray-500'">
                    {{ issueIcon(iss) }} {{ iss.message }}
                  </li>
                </ul>
                <table v-if="Object.keys(detail.attributes).length" class="w-full text-[13px]">
                  <tbody>
                    <tr v-for="(vals, name) in detail.attributes" :key="name" class="align-top">
                      <td class="py-1 pr-4 font-mono text-[13px] text-gray-600 whitespace-nowrap align-top">{{ name }}</td>
                      <td class="py-1 font-mono text-[13px] text-gray-900">
                        <div v-for="(v, i) in vals" :key="i" class="break-all">{{ v }}</div>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </template>
            </div>
          </template>
        </DataTable>

        <!-- Pager -->
        <div class="flex items-center justify-between text-xs text-gray-500">
          <span>Showing {{ rangeStart.toLocaleString() }}–{{ rangeEnd.toLocaleString() }} of {{ totalFiltered.toLocaleString() }}</span>
          <div class="flex items-center gap-2">
            <button @click="changePage(page - 1)" :disabled="page === 0 || loadingPage" class="btn-sm">Prev</button>
            <span>Page {{ page + 1 }} / {{ totalPages }}</span>
            <button @click="changePage(page + 1)" :disabled="page + 1 >= totalPages || loadingPage" class="btn-sm">Next</button>
          </div>
        </div>

      </template>

      <!-- ── Step 3: applied result ───────────────────────────────────────── -->
      <template v-else-if="phase === 'applied' && applyResult">
        <div class="bg-gray-50 border border-gray-200 rounded-lg p-4">
          <h4 class="text-sm font-semibold text-gray-900 mb-2">Import Results</h4>
          <div class="grid grid-cols-4 gap-3 text-center">
            <div><p class="text-lg font-bold text-green-600">{{ applyResult.added }}</p><p class="text-xs text-gray-500">Added</p></div>
            <div><p class="text-lg font-bold text-blue-600">{{ applyResult.updated }}</p><p class="text-xs text-gray-500">Updated</p></div>
            <div><p class="text-lg font-bold text-yellow-600">{{ applyResult.skipped }}</p><p class="text-xs text-gray-500">Skipped</p></div>
            <div><p class="text-lg font-bold text-red-600">{{ applyResult.failed }}</p><p class="text-xs text-gray-500">Failed</p></div>
          </div>
          <div v-if="applyResult.errors?.length" class="mt-3 max-h-40 overflow-y-auto space-y-1">
            <div v-for="(err, i) in applyResult.errors" :key="i"
                 class="text-xs bg-red-50 border border-red-100 rounded px-2 py-1">
              <span v-if="err.dn" class="font-mono text-[13px] text-red-700">{{ err.dn }}: </span>
              <span class="text-red-600">{{ err.message }}</span>
            </div>
          </div>
        </div>
      </template>

      <!-- Error banner -->
      <div v-if="error" class="p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-700">{{ error }}</div>
    </div>

    <template #footer>
      <button @click="back" v-if="phase === 'preview'" :disabled="busy" class="btn-neutral">Back</button>
      <button @click="close" :disabled="busy" class="btn-neutral">{{ phase === 'applied' ? 'Close' : 'Cancel' }}</button>
      <button v-if="phase === 'pick'" @click="doPreview" :disabled="!file || busy" class="btn-primary">
        {{ busy ? 'Analyzing…' : 'Preview' }}
      </button>
      <button v-else-if="phase === 'preview'" @click="doApply" :disabled="busy || !applicableCount" class="btn-primary">
        {{ busy ? 'Importing…' : `Import (${applicableCount.toLocaleString()})` }}
      </button>
    </template>
  </AppModal>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import AppModal from '@/components/AppModal.vue'
import DataTable from '@/components/DataTable.vue'
import { useNotificationStore } from '@/stores/notifications'
import { useAuthStore } from '@/stores/auth'
import { useConfirm } from '@/composables/useConfirm'
import { IVIA_ABBR, IVIA_NAME } from '@/constants/productNames'
import { previewLdif, getLdifPreviewPage, getLdifPreviewRow, applyLdifPreview } from '@/api/browse'
import { getIsvaConfig } from '@/api/isvaConfig'

interface PreviewIssue { severity: string; code: string; message: string }
interface MemberDelta { added: number; removed: number }
interface PreviewRow {
  rowNumber: number
  dn: string | null
  op: string
  objectClasses: string[]
  attrCount: number
  memberDelta?: MemberDelta | null
  memberCount?: number | null
  issues: PreviewIssue[]
  userAdd: boolean
}
interface OpCounts { add: number; modify: number; delete: number; moddn: number; skip: number; error: number }
interface PreviewPage { rows: PreviewRow[]; page: number; size: number; totalFiltered: number }
interface PreviewSummary {
  previewId: string
  totalRows: number
  countsByOp: OpCounts
  warningCount: number
  errorCount: number
  truncated: boolean
  page0: PreviewPage
  userAddCount: number
  containsVendorOverlayEntries: boolean
}
interface PreviewRowDetail {
  rowNumber: number
  dn: string
  op: string
  attributes: Record<string, string[]>
  memberDelta?: MemberDelta | null
  issues: PreviewIssue[]
}
interface ImportError { dn: string | null; message: string }
interface ImportResult { added: number; updated: number; skipped: number; failed: number; errors: ImportError[] }

const props = defineProps<{ directoryId: string }>()
const emit = defineEmits<{ (e: 'imported'): void }>()
const visible = defineModel<boolean>({ default: false })

const notif = useNotificationStore()
const auth = useAuthStore()
const confirm = useConfirm()

const PAGE_SIZE = 50

const fileInput = ref<HTMLInputElement | null>(null)
const file = ref<File | null>(null)
const conflictHandling = ref<'SKIP' | 'OVERWRITE'>('SKIP')
// Checked = provision IVIA secUser overlays for imported users (the default).
const provisionVendorAccounts = ref(true)
const dragging = ref(false)
const error = ref('')
const busy = ref(false)

const summary = ref<PreviewSummary | null>(null)
const previewId = ref('')
const rows = ref<PreviewRow[]>([])
const page = ref(0)
const totalFiltered = ref(0)
const filter = ref('ALL')
const search = ref('')
const loadingPage = ref(false)

const selectedRowNumber = ref<number | null>(null)
const detail = ref<PreviewRowDetail | null>(null)
const loadingDetail = ref(false)

const applyResult = ref<ImportResult | null>(null)

// 1-based preview row numbers the operator opted out of secUser provisioning
// (via the +secUser badge). Sent to the apply call as excludeOverlayRows.
const excludedRows = ref<Set<number>>(new Set())

// IVIA config for this directory (enabled flag + topology), fetched when the
// modal opens if the addon is entitled. Null = not enabled / not fetched.
const iviaConfig = ref<{ enabled: boolean; topologyMode: string } | null>(null)

// IVIA secUser provisioning applies only when the addon is entitled AND enabled
// for this specific directory.
const iviaEnabled = computed(() =>
  auth.isIsvaIntegrationEnabled && iviaConfig.value?.enabled === true)

const iviaTopologyLabel = computed(() =>
  iviaConfig.value?.topologyMode === 'LINKED'
    ? 'linked secUser entries'
    : 'secUser accounts')

// A row eligible for secUser provisioning: a user-add candidate, with
// provisioning left on globally, and not suppressed by the file already
// containing secUser entries. (Eligible rows show the +secUser badge; whether
// it's actually provisioned also depends on the per-row opt-out below.)
function rowOverlayEligible(row: PreviewRow): boolean {
  return iviaEnabled.value
    && provisionVendorAccounts.value
    && !!summary.value
    && !summary.value.containsVendorOverlayEntries
    && row.userAdd
}

function isExcluded(row: PreviewRow): boolean {
  return excludedRows.value.has(row.rowNumber)
}

function toggleRowOverlay(row: PreviewRow): void {
  const next = new Set(excludedRows.value)
  if (next.has(row.rowNumber)) next.delete(row.rowNumber)
  else next.add(row.rowNumber)
  excludedRows.value = next
}

// Whole-file provisioning count after per-row opt-outs. excludedRows only ever
// holds user-add rows (the badge is the only way to add to it), so subtracting
// its size from the intrinsic userAddCount is correct.
const excludedCount = computed(() => excludedRows.value.size)
const effectiveProvisionCount = computed(() =>
  Math.max(0, (summary.value?.userAddCount ?? 0) - excludedCount.value))

const filters = [
  { value: 'ALL', label: 'All' },
  { value: 'ADD', label: 'Adds' },
  { value: 'MODIFY', label: 'Modifies' },
  { value: 'DELETE', label: 'Deletes' },
  { value: 'CONFLICTS', label: 'Conflicts' },
  { value: 'ERRORS', label: 'Errors' },
]

const cols = [
  { key: 'op', label: 'Op' },
  { key: 'dn', label: 'DN' },
  { key: 'detail', label: 'Attrs / Members' },
  { key: 'issues', label: '' },
]

const phase = computed<'pick' | 'preview' | 'applied'>(() =>
  applyResult.value ? 'applied' : summary.value ? 'preview' : 'pick')

const totalPages = computed(() => Math.max(1, Math.ceil(totalFiltered.value / PAGE_SIZE)))
const rangeStart = computed(() => (totalFiltered.value === 0 ? 0 : page.value * PAGE_SIZE + 1))
const rangeEnd = computed(() => page.value * PAGE_SIZE + rows.value.length)

// Records that an Import would actually act on (everything except skips/errors).
const applicableCount = computed(() => {
  const c = summary.value?.countsByOp
  return c ? c.add + c.modify + c.delete + c.moddn : 0
})

watch(visible, (open) => {
  if (open) reset()
})

function reset() {
  file.value = null
  error.value = ''
  busy.value = false
  conflictHandling.value = 'SKIP'
  provisionVendorAccounts.value = true
  summary.value = null
  previewId.value = ''
  rows.value = []
  page.value = 0
  totalFiltered.value = 0
  filter.value = 'ALL'
  search.value = ''
  selectedRowNumber.value = null
  detail.value = null
  applyResult.value = null
  excludedRows.value = new Set()
}

// Fetch the directory's IVIA config so the preview can tell the operator
// whether (and how) secUser accounts will be provisioned. Best-effort: a 404
// (no config) or any error just means "not enabled" — no provisioning copy.
async function loadIviaConfig() {
  iviaConfig.value = null
  if (!auth.isIsvaIntegrationEnabled) return
  try {
    const { data } = await getIsvaConfig(props.directoryId)
    iviaConfig.value = { enabled: data.enabled === true, topologyMode: data.topologyMode }
  } catch {
    iviaConfig.value = null
  }
}

function setFile(f: File | null | undefined) {
  if (!f) return
  if (!f.name.toLowerCase().endsWith('.ldif')) {
    error.value = 'Only .ldif files are accepted'
    return
  }
  error.value = ''
  file.value = f
}

function onFileSelect(e: Event) {
  const input = e.target as HTMLInputElement
  setFile(input.files?.[0])
  input.value = '' // allow re-selecting the same file
}

function onDrop(e: DragEvent) {
  dragging.value = false
  setFile(e.dataTransfer?.files?.[0])
}

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

function errMsg(e: unknown, fallback = 'Something went wrong'): string {
  const err = e as { response?: { data?: { detail?: string; message?: string } }; message?: string }
  return err.response?.data?.detail || err.response?.data?.message || err.message || fallback
}

async function doPreview() {
  if (!file.value) return
  error.value = ''
  busy.value = true
  // Load the directory's IVIA config so the preview can show whether secUser
  // accounts will be provisioned. Fire alongside the preview; not fatal.
  loadIviaConfig()
  // Clear any view state carried over from a previous preview / Back.
  search.value = ''
  page.value = 0
  totalFiltered.value = 0
  selectedRowNumber.value = null
  detail.value = null
  excludedRows.value = new Set()
  try {
    const { data } = await previewLdif(props.directoryId, file.value, conflictHandling.value)
    const s = data as PreviewSummary
    summary.value = s
    previewId.value = s.previewId
    // Problems-first: default to the Errors view when any record failed.
    if (s.errorCount > 0) {
      filter.value = 'ERRORS'
      await loadPage(0)
    } else {
      filter.value = 'ALL'
      rows.value = s.page0.rows
      totalFiltered.value = s.page0.totalFiltered
      page.value = 0
    }
  } catch (e) {
    error.value = errMsg(e)
  } finally {
    busy.value = false
  }
}

let pageSeq = 0
async function loadPage(targetPage: number) {
  if (!previewId.value) return
  const seq = ++pageSeq
  loadingPage.value = true
  try {
    const { data } = await getLdifPreviewPage(props.directoryId, previewId.value, {
      op: filter.value === 'ALL' ? undefined : filter.value,
      q: search.value || undefined,
      page: targetPage,
      size: PAGE_SIZE,
    })
    if (seq !== pageSeq) return // superseded by a newer filter/search/page request
    const p = data as PreviewPage
    rows.value = p.rows
    totalFiltered.value = p.totalFiltered
    page.value = p.page
  } catch (e) {
    if (seq === pageSeq) error.value = errMsg(e)
  } finally {
    if (seq === pageSeq) loadingPage.value = false
  }
}

function setFilter(value: string) {
  if (filter.value === value) return
  filter.value = value
  selectedRowNumber.value = null
  loadPage(0)
}

let searchTimer: ReturnType<typeof setTimeout> | undefined
function onSearchInput() {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => loadPage(0), 300)
}

function changePage(target: number) {
  if (target < 0 || target >= totalPages.value) return
  loadPage(target)
}

let detailSeq = 0
async function openRow(row: PreviewRow) {
  // Clicking the already-open row collapses it.
  if (selectedRowNumber.value === row.rowNumber) {
    selectedRowNumber.value = null
    detail.value = null
    return
  }
  selectedRowNumber.value = row.rowNumber
  detail.value = null
  loadingDetail.value = true
  const seq = ++detailSeq
  try {
    const { data } = await getLdifPreviewRow(props.directoryId, previewId.value, row.rowNumber)
    if (seq !== detailSeq) return // a different row was opened meanwhile
    detail.value = data as PreviewRowDetail
  } catch (e) {
    if (seq === detailSeq) error.value = errMsg(e)
  } finally {
    if (seq === detailSeq) loadingDetail.value = false
  }
}

async function doApply() {
  // Importing change records with `changetype: delete` removes entries — make
  // the destructive part explicit before anything is written.
  const deletes = summary.value?.countsByOp.delete ?? 0
  if (deletes > 0) {
    const ok = await confirm({
      title: 'Apply destructive import?',
      message: `This import will permanently DELETE ${deletes.toLocaleString()} `
        + `${deletes === 1 ? 'entry' : 'entries'} from the directory, alongside the other `
        + 'changes. This cannot be undone.',
      confirmLabel: 'Import & delete',
      danger: true,
    })
    if (!ok) return
  }
  error.value = ''
  busy.value = true
  try {
    const { data } = await applyLdifPreview(
      props.directoryId, previewId.value, !provisionVendorAccounts.value,
      Array.from(excludedRows.value))
    const result = data as ImportResult
    applyResult.value = result
    if (result.added + result.updated > 0) {
      emit('imported')
      notif.success(`Imported: ${result.added} added, ${result.updated} updated`)
    } else if (result.failed > 0) {
      notif.error(`Import finished with ${result.failed} failure(s)`)
    } else {
      notif.success('Import complete')
    }
  } catch (e) {
    error.value = errMsg(e)
  } finally {
    busy.value = false
  }
}

function back() {
  summary.value = null
  previewId.value = ''
  rows.value = []
  page.value = 0
  totalFiltered.value = 0
  filter.value = 'ALL'
  search.value = ''
  selectedRowNumber.value = null
  detail.value = null
  error.value = ''
  excludedRows.value = new Set()
}

function close() {
  visible.value = false
}

function opClass(op: string): string {
  switch (op) {
    case 'ADD': return 'badge-green'
    case 'MODIFY': return 'badge-yellow'
    case 'DELETE': return 'badge-red'
    case 'MODDN': return 'badge-blue'
    case 'ERROR': return 'badge-red'
    default: return 'badge-gray'
  }
}

function issueIcon(iss: PreviewIssue): string {
  if (iss.severity === 'ERROR') return '✕'
  if (iss.severity === 'WARNING') return '⚠'
  return 'ℹ'
}
</script>

<style scoped>
@reference "tailwindcss";
.chip { @apply inline-flex items-center gap-1 px-2 py-0.5 rounded-full; }
.chip-green { @apply bg-green-100 text-green-800; }
.chip-amber { @apply bg-amber-100 text-amber-800; }
.chip-red { @apply bg-red-100 text-red-800; }
.chip-blue { @apply bg-blue-100 text-blue-800; }
.chip-gray { @apply bg-gray-100 text-gray-600; }
</style>
