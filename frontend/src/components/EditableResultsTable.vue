<script setup lang="ts">
// SPDX-License-Identifier: Apache-2.0
/**
 * Editable variant of {@link ResultsTable} for the directory search
 * results page. Phase 1 of the inline-edit feature
 * (docs/superpowers/plans/2026-05-05-directory-search-inline-edit.md).
 *
 * Rendered side-by-side with {@code ResultsTable}: the parent picks
 * whichever instance to mount based on its {@code editMode} flag.
 * The component prop API mirrors what {@code DirectorySearchView}
 * passes to {@code ResultsTable} — same {@code columns} / {@code rows}
 * shape, same {@code <template #toolbar>} and {@code <template
 * #cell-dn>} slots, same {@code @row-click} emit — so the parent
 * can swap them cleanly.
 *
 * Phase 1 task progression inside this component:
 *   - Task 4: read-only render (this scaffold). Edit affordances
 *     deliberately absent; the {@code edit} prop is accepted but
 *     has no effect yet.
 *   - Task 5: editable cells when {@code edit=true} and the row's
 *     classification + schema permit; per-row dirty tracking.
 *   - Task 6: save-on-row-blur via {@link updateEntry}, optimistic
 *     UI, per-row state indicator.
 *
 * Why a sibling component, not a fork or a prop on {@code ResultsTable}:
 * {@code ResultsTable} carries sort / filter / paginate / column-picker
 * logic that doesn't compose with cell editing (sorting an editable
 * row out from under the user's cursor is jarring). Keeping the two
 * separate lets each evolve at its own pace; if the editable variant
 * grows its own sort / filter UX, it'll be a deliberate decision.
 */
import { ref } from 'vue'
import {
  type DirectoryEntry,
  type AttributeTypeInfo,
  type EntryClassification,
  isAttributeEditable,
  classify,
} from '@/composables/useEntryClassification'
import {
  updateEntry,
  type AttributeModification,
  type LdapEntryResponse,
} from '@/api/ldapEntry'
import type { ColumnDef } from './ResultsTable.vue'
import type { SortableRow } from '@/composables/useTablePreferences'

interface Props {
  /** Stable namespace; reserved for future per-table preference persistence. */
  tableKey: string
  columns: ColumnDef[]
  rows: SortableRow[]
  /** Property on each row used as a Vue :key. */
  rowKey?: string
  /** When true, clicking a row emits {@code row-click} (e.g. open detail). */
  rowClickable?: boolean
  /** Empty-state copy. */
  emptyText?: string
  /**
   * Edit-mode flag. When false (Phase 1 default), every cell renders
   * read-only. Task 5 enables editable inputs for cells whose row +
   * attribute combination passes {@link isAttributeEditable}.
   */
  edit?: boolean
  /** Directory whose entries are being rendered. Used by the saver. */
  directoryId?: string
  /**
   * Schema map from lowercased attribute name → {@link AttributeTypeInfo}.
   * Phase 1's editable predicate locks any attribute the schema doesn't
   * cover (conservative); Phase 1.5 will route multi-valued attrs
   * through the chip editor when this map says so.
   */
  schemaMap?: Map<string, AttributeTypeInfo>
}

const props = withDefaults(defineProps<Props>(), {
  rowKey: 'dn',
  rowClickable: false,
  emptyText: 'No results.',
  edit: false,
  directoryId: '',
  schemaMap: () => new Map<string, AttributeTypeInfo>(),
})

const emit = defineEmits<{
  (e: 'row-click', row: SortableRow): void
  /**
   * Fires after a successful save. Parent (DirectorySearchView)
   * merges the response back into its `results` so subsequent reads
   * reflect server-side normalisation (case folding, trim, etc.).
   */
  (e: 'row-saved', dn: string, response: LdapEntryResponse): void
}>()

/**
 * Per-row dirty map: dn → attr → newValue. Phase 1 stores the typed
 * input string here; Phase 1.5 will widen the value type to
 * {@code string | string[]} for the chip-editor branch.
 */
const dirtyMap = ref<Map<string, Map<string, string>>>(new Map())

type SaveState = 'idle' | 'saving' | 'saved' | 'error'
const saveState = ref<Map<string, SaveState>>(new Map())
/** Per-cell error messages keyed by dn → attr → message. */
const cellErrors = ref<Map<string, Map<string, string>>>(new Map())
/** Top-level row error (no `attribute` field on the response body). */
const rowError = ref<Map<string, string>>(new Map())
const SAVED_INDICATOR_MS = 2000

/**
 * Resolve the underlying {@link DirectoryEntry} the parent stashes
 * on each flattened row. {@code DirectorySearchView} uses {@code __entry}
 * as the key (see its {@code tableRows} computed). Falls back to a
 * minimal stub if absent so storybook / spec rows without
 * {@code __entry} still render.
 */
function rowEntry(row: SortableRow): DirectoryEntry {
  const stash = (row as { __entry?: DirectoryEntry }).__entry
  if (stash) return stash
  return { dn: String(row.dn ?? ''), attributes: {} }
}

function isCellEditable(row: SortableRow, attr: string): boolean {
  if (!props.edit) return false
  return isAttributeEditable(rowEntry(row), attr, props.schemaMap)
}

/**
 * Lookup precedence for the value displayed in a cell:
 *   1. dirty value (user-typed pending edit)
 *   2. row[attr] (parent's flattened display value)
 *   3. empty string
 */
function cellValue(row: SortableRow, attr: string): string {
  const rowDn = String(row[props.rowKey] ?? row.dn ?? '')
  const pending = dirtyMap.value.get(rowDn)?.get(attr)
  if (pending !== undefined) return pending
  const raw = row[attr]
  return raw == null ? '' : String(raw)
}

function onCellInput(row: SortableRow, attr: string, value: string): void {
  const rowDn = String(row[props.rowKey] ?? row.dn ?? '')
  if (!dirtyMap.value.has(rowDn)) dirtyMap.value.set(rowDn, new Map())
  dirtyMap.value.get(rowDn)!.set(attr, value)
}

/**
 * Hover tooltip shown on locked cells in edit mode, hinting at why
 * the cell is read-only. Helps the user understand the lock without
 * cluttering the cell with an icon.
 */
function lockReason(row: SortableRow, attr: string): string {
  if (!props.edit) return ''
  const lower = attr.toLowerCase()
  if (attr === 'dn') return 'DN — rename via the dedicated rename flow'
  if (lower === 'objectclass') return 'objectClass — schema-controlled'
  if (lower === 'modifytimestamp' || lower === 'createtimestamp'
      || lower === 'entryuuid' || lower === 'creatorsname'
      || lower === 'modifiersname') {
    return 'operational attribute — set by the directory'
  }
  const entry = rowEntry(row)
  if (!entry.attributes.objectClass && !entry.attributes.objectclass) {
    return 'objectClass missing — re-run search to enable editing'
  }
  // RDN attribute check: if this column is the row's RDN attribute,
  // changes need a modrdn instead of a modify.
  const rdnAttr = (entry.dn.split(',')[0] ?? '').split('=')[0].trim().toLowerCase()
  if (lower === rdnAttr) return 'RDN attribute — rename via the dedicated rename flow'
  if (props.schemaMap.size === 0) return 'schema unavailable — editing locked'
  if (!props.schemaMap.has(lower)) return 'attribute not in schema'
  if (props.schemaMap.get(lower)?.singleValued === false) {
    return 'multi-valued — editing lands in Phase 1.5'
  }
  return 'cannot edit this attribute on this entry'
}

/**
 * Build the {@link AttributeModification} list for a row's pending
 * edits. Phase 1 single-valued semantics:
 *   - non-empty value          → REPLACE attr [value]
 *   - empty + schema-optional  → DELETE attr [] (LDAP empty-string
 *                                ≠ unset, so blanking removes
 *                                the attribute rather than storing
 *                                the literal "")
 *   - empty + schema-required  → REPLACE attr [""] (server will
 *                                reject; user sees a per-cell error)
 */
function buildModifications(dirty: Map<string, string>): AttributeModification[] {
  const out: AttributeModification[] = []
  for (const [attr, raw] of dirty) {
    const lower = attr.toLowerCase()
    const trimmed = raw.trim()
    const schema = props.schemaMap.get(lower)
    // singleValued is implicitly true unless the schema says otherwise;
    // Phase 1 only edits single-valued attrs so we don't branch on it
    // here. (Phase 1.5 will, for the chip editor.)
    if (trimmed === '' && schema && (schema as { required?: boolean }).required !== true) {
      out.push({ operation: 'DELETE', attribute: attr, values: [] })
    } else {
      out.push({ operation: 'REPLACE', attribute: attr, values: [raw] })
    }
  }
  return out
}

interface SaveErrorBody {
  attribute?: string
  message?: string
  code?: string
  detail?: string
}

function extractErrorBody(err: unknown): SaveErrorBody {
  // Axios envelope: err.response.data → typically { code, message,
  // attribute? } per GlobalExceptionHandler. Spring fallback:
  // { detail, title }. Be permissive across both shapes.
  const data = (err as { response?: { data?: SaveErrorBody } }).response?.data
  return data ?? {}
}

async function saveRow(row: SortableRow): Promise<void> {
  const dn = String(row[props.rowKey] ?? row.dn ?? '')
  const dirty = dirtyMap.value.get(dn)
  if (!dirty || dirty.size === 0) return

  const entry = rowEntry(row)
  const cls: EntryClassification = classify(entry)
  if (cls === 'unknown') {
    // Cells should not have been editable in the first place;
    // belt-and-braces guard. Drop the dirty entries.
    dirtyMap.value.delete(dn)
    return
  }
  if (!props.directoryId) {
    rowError.value.set(dn, 'No directory bound to this table; cannot save.')
    saveState.value.set(dn, 'error')
    return
  }

  saveState.value.set(dn, 'saving')
  cellErrors.value.delete(dn)
  rowError.value.delete(dn)

  try {
    const { data: updated } = await updateEntry(
      props.directoryId,
      cls,
      dn,
      buildModifications(dirty),
    )
    dirtyMap.value.delete(dn)
    saveState.value.set(dn, 'saved')
    emit('row-saved', dn, updated)
    setTimeout(() => {
      // Auto-clear the green indicator after a moment, but only if
      // the row's state is still 'saved' (a subsequent edit may
      // have moved it back to 'saving' / 'error').
      if (saveState.value.get(dn) === 'saved') {
        saveState.value.set(dn, 'idle')
      }
    }, SAVED_INDICATOR_MS)
  } catch (err) {
    saveState.value.set(dn, 'error')
    const body = extractErrorBody(err)
    if (body.attribute && body.message) {
      if (!cellErrors.value.has(dn)) cellErrors.value.set(dn, new Map())
      cellErrors.value.get(dn)!.set(body.attribute, body.message)
    } else {
      rowError.value.set(dn, body.message ?? body.detail ?? 'Save failed.')
    }
    // Keep the dirty map populated so the user can retry.
  }
}

/**
 * Row {@code focusout} handler. Fires when focus leaves any element
 * inside the row; we only want to save when focus has actually left
 * the {@code <tr>} entirely (not when tabbing between cells of the
 * same row).
 */
function onRowFocusout(event: FocusEvent, row: SortableRow): void {
  const next = event.relatedTarget as Node | null
  const tr = event.currentTarget as HTMLElement | null
  if (next && tr && tr.contains(next)) return
  void saveRow(row)
}

function rowSaveState(row: SortableRow): SaveState {
  const dn = String(row[props.rowKey] ?? row.dn ?? '')
  return saveState.value.get(dn) ?? 'idle'
}

function rowSaveError(row: SortableRow): string | undefined {
  const dn = String(row[props.rowKey] ?? row.dn ?? '')
  return rowError.value.get(dn)
}

function cellSaveError(row: SortableRow, attr: string): string | undefined {
  const dn = String(row[props.rowKey] ?? row.dn ?? '')
  return cellErrors.value.get(dn)?.get(attr)
}

async function retrySave(row: SortableRow): Promise<void> {
  await saveRow(row)
}

defineExpose({
  // Tests reach in to assert state without mounting a real parent.
  // Production code never reads these.
  dirtyMap,
  saveState,
  cellErrors,
  rowError,
  saveRow,
})
</script>

<template>
  <div class="border border-gray-200 rounded-lg overflow-hidden bg-white">
    <!-- Optional toolbar (matches ResultsTable's contract: a flex row
         that lays out slotted items left-to-right with a small gap). -->
    <div v-if="$slots.toolbar" class="flex items-center gap-2 px-3 py-2 border-b border-gray-200 bg-gray-50">
      <slot name="toolbar" />
    </div>

    <!-- Empty state. -->
    <div v-if="rows.length === 0" class="p-6 text-center text-sm text-gray-500">
      {{ emptyText }}
    </div>

    <!-- Table. Phase 1 ships without sort / filter / paginate /
         column-picker — those are ResultsTable's job, and this
         component is for editing the rows the parent decided to
         show. -->
    <div v-else class="overflow-x-auto">
      <table class="w-full text-sm">
        <thead class="bg-gray-50 sticky top-0 z-10">
          <tr>
            <th
              v-for="col in columns"
              :key="col.key"
              class="px-3 py-2 text-left font-medium text-gray-600 border-b border-gray-200 whitespace-nowrap"
            >{{ col.label ?? col.key }}</th>
            <!-- Save-state indicator column. Header intentionally
                 minimal — the column itself is "operational" UX,
                 not data. Only renders in edit mode to keep the
                 read-only table layout stable. -->
            <th
              v-if="edit"
              class="px-2 py-2 border-b border-gray-200 w-8"
              aria-label="Save state"
            ></th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in rows"
            :key="String(row[rowKey])"
            :class="[
              'border-b border-gray-100',
              rowClickable && !edit ? 'hover:bg-blue-50 cursor-pointer' : '',
              edit && rowSaveState(row) === 'error' ? 'bg-red-50/40' : '',
            ]"
            :data-row-dn="String(row[rowKey] ?? row.dn)"
            :data-save-state="rowSaveState(row)"
            @click="rowClickable && !edit ? $emit('row-click', row) : undefined"
            @focusout="edit ? onRowFocusout($event, row) : undefined"
          >
            <td
              v-for="col in columns"
              :key="col.key"
              class="px-3 py-2 align-top break-all"
            >
              <!-- Edit-mode editable input. Branch chosen first so the
                   slot fallbacks below only fire on read-only cells. -->
              <template v-if="isCellEditable(row, col.key)">
                <input
                  :value="cellValue(row, col.key)"
                  @input="onCellInput(row, col.key, ($event.target as HTMLInputElement).value)"
                  :data-edit-cell="col.key"
                  :data-row-dn="String(row[rowKey] ?? row.dn)"
                  :aria-label="`${col.key} for ${row.dn}`"
                  :class="[
                    'input w-full text-sm',
                    cellSaveError(row, col.key) ? 'border-red-400 ring-1 ring-red-200' : '',
                  ]"
                />
                <p
                  v-if="cellSaveError(row, col.key)"
                  class="mt-1 text-xs text-red-600"
                  :data-cell-error="col.key"
                >{{ cellSaveError(row, col.key) }}</p>
              </template>
              <!-- Read-only path: caller's per-cell slot (when given)
                   or the default span. In edit mode, locked cells
                   carry a tooltip explaining why. -->
              <slot
                v-else
                :name="`cell-${col.key}`"
                :value="row[col.key]"
                :row="row"
              >
                <span
                  class="text-gray-800"
                  :class="{ 'text-gray-500 cursor-not-allowed': edit }"
                  :title="edit ? lockReason(row, col.key) : ''"
                >{{ row[col.key] }}</span>
              </slot>
            </td>

            <!-- Save-state indicator: spinner / ✓ / ⚠ + retry. -->
            <td
              v-if="edit"
              class="px-2 py-2 align-top w-8 text-center"
              :data-row-state="rowSaveState(row)"
            >
              <span
                v-if="rowSaveState(row) === 'saving'"
                class="inline-block w-3 h-3 rounded-full border-2 border-blue-400 border-t-transparent animate-spin"
                aria-label="Saving"
                title="Saving…"
              ></span>
              <span
                v-else-if="rowSaveState(row) === 'saved'"
                class="text-green-600"
                aria-label="Saved"
                title="Saved"
              >✓</span>
              <button
                v-else-if="rowSaveState(row) === 'error'"
                type="button"
                class="text-amber-600 hover:text-amber-800 leading-none"
                :title="rowSaveError(row) ?? 'Save failed — click to retry'"
                aria-label="Retry save"
                @click="retrySave(row)"
              >⚠</button>
            </td>
          </tr>
          <!-- Row-level (top-level) error banner, rendered as a
               second tr below any row whose save failed without an
               attribute-scoped error. Spans the data columns + the
               indicator column. -->
          <template v-for="row in rows" :key="`err-${String(row[rowKey])}`">
            <tr
              v-if="edit && rowSaveError(row) && !cellErrors.get(String(row[rowKey] ?? row.dn))?.size"
              :data-row-error-for="String(row[rowKey] ?? row.dn)"
            >
              <td
                :colspan="columns.length + 1"
                class="bg-red-50 border-l-4 border-red-400 px-3 py-2 text-xs text-red-800"
              >
                {{ rowSaveError(row) }}
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
@reference "tailwindcss";
</style>
