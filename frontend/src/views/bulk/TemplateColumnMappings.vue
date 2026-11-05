<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div class="flex flex-col min-h-0 flex-1">
    <div class="flex flex-wrap items-center justify-between gap-2 mb-2 shrink-0">
      <div class="flex flex-wrap items-center gap-2">
        <span class="text-sm font-medium text-gray-700">Column Mappings</span>
        <template v-if="visible.length">
          <span class="px-2 py-0.5 rounded-full text-xs font-medium bg-green-50 text-green-700">{{ counts.mapped }} mapped</span>
          <span v-if="counts.ignored" class="px-2 py-0.5 rounded-full text-xs font-medium bg-gray-100 text-gray-600">{{ counts.ignored }} ignored</span>
          <span v-if="counts.groups" class="px-2 py-0.5 rounded-full text-xs font-medium bg-violet-50 text-violet-800">group memberships</span>
        </template>
        <span v-if="check.missingRequired.length" class="px-2 py-0.5 rounded-full text-xs font-medium bg-red-50 text-red-700">
          {{ check.missingRequired.length }} required {{ check.missingRequired.length === 1 ? 'attribute' : 'attributes' }} not mapped
        </span>
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <slot name="actions" />
      </div>
    </div>

    <div v-if="!visible.length" class="text-sm text-gray-500 text-center py-3 shrink-0">
      <slot name="empty">Choose a sample file, or add input columns, to map them to LDAP attributes.</slot>
    </div>
    <div v-else class="border border-gray-200 rounded-lg min-h-0 flex-1 overflow-y-auto" data-testid="mapping-rows">
      <div class="mapping-row sticky top-0 z-[1] bg-gray-50 border-b border-gray-200 text-[11px] uppercase tracking-wide font-semibold text-gray-500 py-1.5">
        <span></span>
        <span class="px-2">Input column<template v-if="fromSample"> (from sample file)</template></span>
        <span></span>
        <span class="px-2">LDAP attribute</span>
        <span class="px-2">Status</span>
        <span></span>
      </div>
      <div v-for="(v, n) in visible" :key="v.index" class="border-b border-gray-100 last:border-b-0 py-1"
           :class="rowClass(v.index)" :data-status="statusOf(v.index)" :data-column="v.row.column">
        <div class="mapping-row">
          <span class="text-right pr-2 text-xs text-gray-400 tabular-nums">{{ n + 1 }}</span>

          <div class="px-2 min-w-0">
            <input v-if="editableColumns" class="input input-sm w-full text-xs font-mono"
                   :value="v.row.column" placeholder="CSV column"
                   :aria-label="`CSV column for row ${n + 1}`"
                   @input="setColumn(v.index, $event)" />
            <span v-else class="block truncate font-mono text-xs text-gray-900" :title="v.row.column">
              {{ v.row.column }}
              <span v-if="sampleFor(v.row.column)" class="font-sans text-gray-400">e.g. {{ sampleFor(v.row.column) }}</span>
            </span>
          </div>

          <span class="text-center" :class="arrowClass(v.index)" aria-hidden="true">→</span>

          <div class="px-2 min-w-0">
            <!-- Group memberships aren't an attribute: show the target as a
                 chip that can be cleared to type an attribute instead. -->
            <div v-if="statusOf(v.index) === 'groups'" class="flex items-center gap-1" data-testid="group-memberships-chip">
              <span class="flex-1 min-w-0 truncate rounded-md border border-violet-300 bg-violet-50 px-2 py-1 text-xs font-medium text-violet-800">
                Group memberships <span class="font-normal text-violet-700">· add only</span>
              </span>
              <button type="button" class="text-gray-400 hover:text-gray-600 text-base leading-none px-1"
                      :aria-label="`Map ${columnLabel(v.row, n)} to an attribute instead`"
                      @click="setAttribute(v.index, '')">&times;</button>
            </div>
            <AttributeAutocomplete v-else :model-value="v.row.attribute" :options="suggestions"
                                   :label="`LDAP attribute for ${columnLabel(v.row, n)}`"
                                   :invalid="(check.rows[v.index]?.errors.length ?? 0) > 0"
                                   :placeholder="v.row.column.trim() ? 'blank = ignore this column' : 'LDAP attribute'"
                                   @update:model-value="setAttribute(v.index, $event)"
                                   @commit="commitAttribute(v.index)" />
          </div>

          <span class="px-2 text-xs leading-tight" :class="statusClass(v.index)">{{ statusLabel(v.index) }}</span>

          <div class="flex justify-center">
            <button v-if="editableColumns || v.row.notInSample" type="button" @click="emit('remove', v.index)"
                    :aria-label="`Remove ${columnLabel(v.row, n)}`"
                    class="text-red-400 hover:text-red-600 text-lg leading-none">&times;</button>
          </div>
        </div>
        <ul v-if="issuesOf(v.index).length" class="issue-list text-[11px] leading-snug">
          <li v-for="(msg, k) in issuesOf(v.index)" :key="k" :class="msg.error ? 'text-red-700' : 'text-amber-800'">{{ msg.text }}</li>
        </ul>
      </div>
    </div>

    <p v-for="h in hiddenDnMappings" :key="h.column" class="text-xs text-amber-800 mt-1 shrink-0" data-testid="dn-mapping-dropped">
      <span class="font-mono">{{ h.column }}</span> is now the DN column, so its mapping to
      <span class="font-mono">{{ h.attribute }}</span> won't be saved.
    </p>
    <p v-if="check.missingRequired.length" class="text-xs text-red-700 mt-1 shrink-0" data-testid="missing-required">
      Required but not mapped:
      <template v-for="(a, k) in check.missingRequired" :key="a"><span class="font-mono">{{ a }}</span><template v-if="k < check.missingRequired.length - 1">, </template></template>
    </p>
    <p v-if="schemaState === 'error'" class="text-xs text-red-700 mt-1 shrink-0">
      Couldn't load the selected object classes' attributes, so required attributes aren't checked here.
      The import preview still checks them.
    </p>
    <p class="text-xs text-gray-500 mt-1 shrink-0">
      Type the LDAP attribute each input column maps to<template v-if="suggestions.length">; suggestions come from the selected object classes</template>.
      Leave it blank to ignore the column. Choose “Group memberships” for a column listing groups to add each user to.
      <template v-if="dnColumn && fromSample"> The DN column (<span class="font-mono">{{ dnColumn }}</span>) is used for each entry's DN and isn't listed.</template>
    </p>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import AttributeAutocomplete from './AttributeAutocomplete.vue'
import {
  canonicalAttr, GROUP_MEMBERSHIPS_OPTION, isDnRow, isReservedAttr, rowStatus,
  type MappingCheck, type MappingRow, type RowStatus, type SchemaAttr,
} from './templateMapping'

const props = withDefaults(defineProps<{
  /** Column names typed as text (no sample file loaded) vs fixed names from a sample. */
  editableColumns?: boolean
  /** Whether the columns came from a sample file; the DN column is then hidden. */
  fromSample?: boolean
  /** First-row values by column name, shown as hints when the sample has no header row. */
  samples?: Record<string, string> | null
  /** CSV column holding the DN, if the template reads DNs from a column. */
  dnColumn?: string | null
  /** Attributes of the selected object classes, or null while unknown. */
  schema?: SchemaAttr[] | null
  schemaState?: 'idle' | 'loading' | 'loaded' | 'error'
  /** Result of checkMappings() for the current rows. */
  check: MappingCheck
}>(), { editableColumns: false, fromSample: false, samples: null, dnColumn: null, schema: null, schemaState: 'idle' })

const rows = defineModel<MappingRow[]>('rows', { required: true })
const emit = defineEmits<{ (e: 'remove', index: number): void }>()

/** Rows on screen, with their index in `rows`. A sample's DN column is hidden. */
const visible = computed(() => rows.value
  .map((row, index) => ({ row, index }))
  .filter(v => !(props.fromSample && isDnRow(v.row, props.dnColumn))))

/** Hidden DN-column rows that still carry a typed attribute (it isn't saved). */
const hiddenDnMappings = computed(() => props.fromSample
  ? rows.value.filter(r => isDnRow(r, props.dnColumn) && r.attribute.trim())
  : [])

/**
 * Suggestions: group memberships first (a special target, not an attribute),
 * then required attributes, then the rest, as the schema lists them.
 */
const suggestions = computed<SchemaAttr[]>(() => {
  const s = (props.schema ?? []).filter(a => !isReservedAttr(a.name))
  return [GROUP_MEMBERSHIPS_OPTION, ...s.filter(a => a.required), ...s.filter(a => !a.required)]
})

function statusOf(i: number): RowStatus {
  return rowStatus(rows.value[i], props.dnColumn)
}

const counts = computed(() => {
  const c = { mapped: 0, ignored: 0, groups: 0 }
  for (const v of visible.value) {
    const s = statusOf(v.index)
    if (s === 'mapped' || s === 'ignored' || s === 'groups') c[s]++
  }
  return c
})

function issuesOf(i: number): { text: string, error: boolean }[] {
  const r = props.check.rows[i]
  if (!r) return []
  return [...r.errors.map(text => ({ text, error: true })), ...r.warnings.map(text => ({ text, error: false }))]
}

function rowClass(i: number): string {
  const r = props.check.rows[i]
  if (r?.errors.length) return 'bg-red-50'
  if (r?.warnings.length) return 'bg-amber-50'
  return ''
}

function statusLabel(i: number): string {
  switch (statusOf(i)) {
    case 'mapped': return 'mapped'
    case 'groups': return 'adds the user to the listed groups'
    case 'ignored': return 'ignored'
    case 'incomplete': return 'needs a column'
    case 'dn': return 'DN column'
    default: return ''
  }
}
function statusClass(i: number): string {
  switch (statusOf(i)) {
    case 'mapped': return 'text-blue-600'
    case 'groups': return 'text-violet-800'
    case 'incomplete': return 'text-red-700 font-medium'
    default: return 'text-gray-500'
  }
}

function arrowClass(i: number): string {
  switch (statusOf(i)) {
    case 'mapped': return 'text-blue-600'
    case 'groups': return 'text-violet-600'
    default: return 'text-gray-300'
  }
}

function sampleFor(column: string): string {
  return props.samples?.[column] || ''
}

function columnLabel(row: MappingRow, n: number): string {
  return row.column.trim() || `row ${n + 1}`
}

function setColumn(i: number, e: Event) {
  rows.value[i] = { ...rows.value[i], column: (e.target as HTMLInputElement).value }
}
function setAttribute(i: number, value: string) {
  rows.value[i] = { ...rows.value[i], attribute: value }
}
/** On leaving the field: trim, and use the schema's spelling of a known attribute. */
function commitAttribute(i: number) {
  const row = rows.value[i]
  if (!row) return
  const next = canonicalAttr(row.attribute, props.schema)
  if (next !== row.attribute) rows.value[i] = { ...row, attribute: next }
}
</script>

<style scoped>
.mapping-row {
  display: grid;
  grid-template-columns: 2rem minmax(0, 1fr) 1.5rem minmax(0, 1fr) 7.5rem 2rem;
  align-items: center;
}
.issue-list {
  /* Line up with the attribute column: number + input column + arrow. */
  padding: 2px 0.5rem 2px calc(2rem + 0.5rem);
}
</style>
