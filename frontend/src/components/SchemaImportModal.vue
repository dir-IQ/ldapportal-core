<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <AppModal v-model="visible" title="Update schema" size="2xl" :dirty="dirty" movable resizable>
    <div class="space-y-3">
      <!-- ── Step 1: pick file ─────────────────────────────────────────────── -->
      <template v-if="phase === 'pick'">
        <p class="text-sm text-gray-500">
          Upload an LDIF of schema definitions (attributeTypes / objectClasses). Every element is
          classified against the live schema; nothing is written until you apply the preview.
        </p>
        <label class="block text-sm font-medium text-gray-700 mb-1">Schema LDIF file</label>
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
      </template>

      <!-- ── Step 2: preview ───────────────────────────────────────────────── -->
      <template v-else-if="phase === 'preview' && summary">
        <!-- Counts -->
        <div class="flex flex-wrap items-center gap-2 text-xs">
          <span class="chip chip-green">Add new <b>{{ summary.counts.addNew }}</b></span>
          <span class="chip chip-amber">Modify existing <b>{{ summary.counts.modifyExisting }}</b></span>
          <span class="chip chip-red">Unsupported <b>{{ summary.counts.unsupported }}</b></span>
          <span class="chip chip-red" title="Elements with blocking errors">✕ <b>{{ summary.counts.errors }}</b></span>
          <span class="ml-auto text-gray-400">{{ summary.total }} element{{ summary.total === 1 ? '' : 's' }} · {{ vendorLabel(summary.vendor) }}</span>
        </div>

        <!-- Blocking banner -->
        <div v-if="summary.blocking"
             class="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-800">
          <div class="flex items-start gap-2">
            <span aria-hidden="true" class="mt-0.5">✕</span>
            <span>
              This LDIF cannot be applied: one or more elements are unsupported or have blocking
              errors (see the rows marked below). Fix or remove them and preview again.
            </span>
          </div>
        </div>

        <!-- Add-only filter: skip updates to existing elements, apply only the new ones. -->
        <label v-if="summary.counts.modifyExisting > 0"
               class="flex items-start gap-2 text-sm text-gray-700 cursor-pointer select-none rounded-lg border border-gray-200 bg-gray-50 px-3 py-2">
          <input v-model="addNewOnly" type="checkbox" class="mt-0.5 rounded border-gray-300"
                 aria-label="Exclude existing elements" />
          <span>
            Exclude existing elements — apply only the
            <b>{{ summary.counts.addNew }}</b> new one{{ summary.counts.addNew === 1 ? '' : 's' }}, skipping
            <b>{{ summary.counts.modifyExisting }}</b>
            update{{ summary.counts.modifyExisting === 1 ? '' : 's' }} to existing element{{ summary.counts.modifyExisting === 1 ? '' : 's' }}.
          </span>
        </label>

        <!-- Elements table -->
        <div class="border border-gray-200 rounded-xl overflow-hidden">
          <table class="w-full text-sm">
            <thead class="bg-gray-50 text-gray-500 text-xs uppercase tracking-wider">
              <tr>
                <th class="text-left font-semibold px-3 py-2">Kind</th>
                <th class="text-left font-semibold px-3 py-2">Name</th>
                <th class="text-left font-semibold px-3 py-2">OID</th>
                <th class="text-left font-semibold px-3 py-2">Action</th>
                <th class="text-left font-semibold px-3 py-2">Issues</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="summary.elements.length === 0">
                <td colspan="5" class="px-3 py-4 text-center text-gray-500">No schema elements found in this LDIF.</td>
              </tr>
              <tr v-for="el in summary.elements" :key="el.rowNumber"
                  :class="['border-t border-gray-100 align-top', elementBlocking(el) ? 'bg-red-50/50' : '', willSkip(el) ? 'opacity-45' : '']">
                <td class="px-3 py-2 text-gray-600 whitespace-nowrap">{{ kindLabel(el.kind) }}</td>
                <td class="px-3 py-2 font-mono text-gray-800 break-all" :class="willSkip(el) ? 'line-through' : ''">{{ el.name || '—' }}</td>
                <td class="px-3 py-2 font-mono text-[13px] text-gray-500 break-all">{{ el.oid || '—' }}</td>
                <td class="px-3 py-2 whitespace-nowrap">
                  <span class="badge" :class="actionClass(el.action)">{{ actionLabel(el.action) }}</span>
                  <span v-if="willSkip(el)" class="ml-1 text-[11px] text-gray-400">skipped</span>
                </td>
                <td class="px-3 py-2">
                  <ul v-if="el.issues.length" class="space-y-0.5">
                    <li v-for="(iss, i) in el.issues" :key="i" class="text-xs"
                        :class="iss.severity === 'ERROR' ? 'text-red-700' : 'text-amber-700'">
                      {{ issueIcon(iss) }} {{ iss.message }}
                    </li>
                  </ul>
                  <span v-else class="text-xs text-gray-400">—</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- OpenLDAP config-admin credentials -->
        <div v-if="isOpenLdap" class="rounded-lg border border-gray-200 bg-gray-50 p-4">
          <p class="text-sm font-medium text-gray-700 mb-1">Config-admin credentials</p>
          <p class="text-xs text-gray-500 mb-3">
            OpenLDAP schema lives under <span class="font-mono">cn=config</span>, written with a config-admin
            bind rather than the directory's data bind. These are used only for this apply and are never stored.
          </p>
          <label class="block text-xs font-medium text-gray-600 mb-1">Config bind DN</label>
          <input v-model="configBindDn" type="text" autocomplete="off" placeholder="cn=admin,cn=config"
                 class="input w-full mb-3 font-mono" aria-label="Config bind DN" />
          <label class="block text-xs font-medium text-gray-600 mb-1">Config password</label>
          <input v-model="configPassword" type="password" autocomplete="new-password"
                 class="input w-full" aria-label="Config password" />
        </div>
      </template>

      <!-- ── Step 3: applied result ────────────────────────────────────────── -->
      <template v-else-if="phase === 'applied' && applyResult">
        <div class="bg-gray-50 border border-gray-200 rounded-lg p-4">
          <h4 class="text-sm font-semibold text-gray-900 mb-2">Apply results</h4>
          <div class="grid grid-cols-2 gap-3 text-center">
            <div><p class="text-lg font-bold text-green-600">{{ applyResult.applied }}</p><p class="text-xs text-gray-500">Applied</p></div>
            <div><p class="text-lg font-bold text-red-600">{{ applyResult.failed }}</p><p class="text-xs text-gray-500">Failed</p></div>
          </div>
          <div v-if="applyResult.errors?.length" class="mt-3 max-h-40 overflow-y-auto space-y-1">
            <div v-for="(err, i) in applyResult.errors" :key="i"
                 class="text-xs bg-red-50 border border-red-100 rounded px-2 py-1">
              <span v-if="err.targetDn" class="font-mono text-[13px] text-red-700">{{ err.targetDn }}: </span>
              <span class="text-red-600">{{ err.message }}</span>
            </div>
          </div>
        </div>
      </template>

      <!-- Error banner -->
      <div v-if="error" class="p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-700">{{ error }}</div>
    </div>

    <template #footer="{ close }">
      <button v-if="phase === 'preview'" @click="back" :disabled="busy" class="btn-neutral">Back</button>
      <button @click="close" :disabled="busy" class="btn-neutral">{{ phase === 'applied' ? 'Close' : 'Cancel' }}</button>
      <button v-if="phase === 'pick'" @click="doPreview" :disabled="!file || busy" class="btn-primary">
        {{ busy ? 'Analyzing…' : 'Preview' }}
      </button>
      <button v-else-if="phase === 'preview'" @click="doApply" :disabled="applyDisabled" class="btn-primary">
        {{ busy ? 'Applying…' : `Apply (${effectiveCount})` }}
      </button>
    </template>
  </AppModal>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import AppModal from '@/components/AppModal.vue'
import { useNotificationStore } from '@/stores/notifications'
import { useConfirm } from '@/composables/useConfirm'
import { previewSchemaLdif, applySchemaPreview } from '@/api/schema'

interface SchemaPreviewIssue { severity: string; code: string; message: string }
interface SchemaPreviewElement {
  rowNumber: number
  kind: 'ATTRIBUTE_TYPE' | 'OBJECT_CLASS'
  name: string | null
  oid: string | null
  action: 'ADD_NEW' | 'MODIFY_EXISTING' | 'UNSUPPORTED'
  targetDn: string
  definition: string
  issues: SchemaPreviewIssue[]
}
interface SchemaCounts { addNew: number; modifyExisting: number; unsupported: number; errors: number }
interface SchemaPreviewSummary {
  previewId: string
  directoryId: string
  vendor: string
  total: number
  counts: SchemaCounts
  elements: SchemaPreviewElement[]
  blocking: boolean
}
interface SchemaUpdateError { targetDn: string; message: string }
interface SchemaUpdateResult { applied: number; failed: number; errors: SchemaUpdateError[] }

const props = defineProps<{ directoryId: string; directoryType?: string }>()
const emit = defineEmits<{ (e: 'applied'): void }>()
const visible = defineModel<boolean>({ default: false })

const notif = useNotificationStore()
const confirm = useConfirm()

const isOpenLdap = computed(() => props.directoryType === 'OPENLDAP')

const fileInput = ref<HTMLInputElement | null>(null)
const file = ref<File | null>(null)
const dragging = ref(false)

const busy = ref(false)
const error = ref('')

const summary = ref<SchemaPreviewSummary | null>(null)
const previewId = ref('')
const applyResult = ref<SchemaUpdateResult | null>(null)

const configBindDn = ref('')
const configPassword = ref('')

// When on, apply only the ADD_NEW elements and skip updates to existing ones.
const addNewOnly = ref(false)

const phase = computed<'pick' | 'preview' | 'applied'>(() =>
  applyResult.value ? 'applied' : summary.value ? 'preview' : 'pick')

// Elements an apply would actually attempt: the non-blocking ones.
const applicableCount = computed(() =>
  summary.value ? summary.value.elements.filter(el => !elementBlocking(el)).length : 0)

// How many elements the current apply will write: add-only narrows it to the
// ADD_NEW count; otherwise every applicable (non-blocking) element.
const effectiveCount = computed(() =>
  addNewOnly.value ? (summary.value?.counts.addNew ?? 0) : applicableCount.value)

const hasConfigCreds = computed(() => !!configBindDn.value && !!configPassword.value)
const applyDisabled = computed(() =>
  busy.value
  || !summary.value
  || summary.value.blocking
  || effectiveCount.value === 0
  || (isOpenLdap.value && !hasConfigCreds.value))

// A row the add-only toggle will exclude from this apply.
function willSkip(el: SchemaPreviewElement): boolean {
  return addNewOnly.value && el.action === 'MODIFY_EXISTING'
}

// Guard against losing an in-progress upload/preview on an accidental close;
// the applied result is not "unsaved", so it closes freely.
const dirty = computed(() => phase.value !== 'applied' && (!!file.value || !!summary.value))

// Reset every time the modal opens so a re-open starts clean.
watch(visible, (open) => { if (open) reset() })

// Per-element blocking mirrors the backend SchemaPreviewElement.blocking()
// (a derived accessor that isn't serialized).
function elementBlocking(el: SchemaPreviewElement): boolean {
  return el.action === 'UNSUPPORTED' || el.issues.some(i => i.severity === 'ERROR')
}

function vendorLabel(type: string): string {
  switch (type) {
    case 'OPENLDAP': return 'OpenLDAP'
    case 'ORACLE_UNIFIED_DIRECTORY': return 'OpenDJ / OUD'
    default: return type
  }
}
function kindLabel(kind: string): string {
  return kind === 'OBJECT_CLASS' ? 'objectClass' : 'attributeType'
}
function actionLabel(action: string): string {
  switch (action) {
    case 'ADD_NEW': return 'Add new'
    case 'MODIFY_EXISTING': return 'Modify existing'
    case 'UNSUPPORTED': return 'Unsupported'
    default: return action
  }
}
function actionClass(action: string): string {
  switch (action) {
    case 'ADD_NEW': return 'badge-green'
    case 'MODIFY_EXISTING': return 'badge-yellow'
    case 'UNSUPPORTED': return 'badge-red'
    default: return 'badge-gray'
  }
}
function issueIcon(iss: SchemaPreviewIssue): string {
  return iss.severity === 'ERROR' ? '✕' : '⚠'
}

function reset() {
  file.value = null
  summary.value = null
  previewId.value = ''
  applyResult.value = null
  configBindDn.value = ''
  configPassword.value = ''
  addNewOnly.value = false
  error.value = ''
  busy.value = false
  dragging.value = false
}

function back() {
  summary.value = null
  previewId.value = ''
  error.value = ''
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
  input.value = ''
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
  try {
    const { data } = await previewSchemaLdif(props.directoryId, file.value)
    summary.value = data as SchemaPreviewSummary
    previewId.value = summary.value.previewId
  } catch (e) {
    error.value = errMsg(e)
  } finally {
    busy.value = false
  }
}

async function doApply() {
  if (!summary.value || summary.value.blocking) return
  const ok = await confirm({
    title: 'Apply schema changes?',
    message: `This will write ${effectiveCount.value} schema `
      + `${effectiveCount.value === 1 ? 'element' : 'elements'} to the live directory`
      + `${addNewOnly.value ? ' (new elements only)' : ''}. `
      + 'Online schema additions are largely irreversible — export the current schema first if you have not.',
    confirmLabel: 'Apply',
    danger: true,
  })
  if (!ok) return
  error.value = ''
  busy.value = true
  try {
    const { data } = await applySchemaPreview(
      props.directoryId, previewId.value,
      isOpenLdap.value ? configBindDn.value : '',
      isOpenLdap.value ? configPassword.value : '',
      addNewOnly.value)
    const result = data as SchemaUpdateResult
    applyResult.value = result
    if (result.failed > 0) {
      notif.error(`Applied ${result.applied}, ${result.failed} failed`)
    } else {
      notif.success(`Applied ${result.applied} schema element${result.applied === 1 ? '' : 's'}`)
    }
    // Let the host refresh its schema lists so applied elements show immediately.
    emit('applied')
  } catch (e) {
    error.value = errMsg(e)
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
@reference "tailwindcss";
.chip { @apply inline-flex items-center gap-1 px-2 py-0.5 rounded-full; }
.chip-green { @apply bg-green-100 text-green-800; }
.chip-amber { @apply bg-amber-100 text-amber-800; }
.chip-red { @apply bg-red-100 text-red-800; }
</style>
