<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div class="overflow-x-auto rounded-lg border border-gray-200" tabindex="0"
       ref="tableRef"
       @keydown.down.prevent="moveDown" @keydown.up.prevent="moveUp"
       @keydown.enter.prevent="selectFocused" @keydown.escape="focusedIndex = -1"
       @keydown.space.prevent="toggleFocusedSelection"
       @mousedown="usingKeyboard = false"
       @blur="onBlur">
    <table class="min-w-full divide-y divide-gray-200 text-sm">
      <thead class="bg-gray-50">
        <tr>
          <th v-if="selectable" scope="col" class="px-3 py-3 w-10">
            <input type="checkbox" :checked="allSelected" :indeterminate="someSelected && !allSelected"
              @change="toggleAll" aria-label="Select all rows" class="rounded text-blue-600 focus:ring-blue-500" />
          </th>
          <th
            v-for="col in columns"
            :key="col.key"
            scope="col"
            class="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider"
          >{{ col.label }}</th>
          <th v-if="$slots.actions" scope="col" class="px-4 py-3 text-right text-xs font-semibold text-gray-500 uppercase tracking-wider">Actions</th>
        </tr>
      </thead>
      <tbody class="bg-white divide-y divide-gray-100">
        <tr v-if="loading">
          <td :colspan="totalCols" class="px-4 py-8 text-center text-gray-500">
            Loading…
          </td>
        </tr>
        <tr v-else-if="!rows.length">
          <td :colspan="totalCols">
            <EmptyState :icon="emptyIcon" :title="emptyText" />
          </td>
        </tr>
        <tr
          v-else
          v-for="(row, i) in rows"
          :key="rowKey ? row[rowKey] : i"
          class="hover:bg-gray-50 transition-colors cursor-pointer"
          :class="{
            'bg-blue-50': (selectable && isSelected(row)) || (highlightKey !== undefined && row[rowKey] === highlightKey),
            // Keyboard-only focus ring (focus-visible semantics): the
            // styled element (the row) isn't the focus target (the
            // wrapper div is), so we can't use the native :focus-visible
            // pseudo-class — we track whether the last interaction was a
            // key press instead. A plain mouse click therefore leaves no
            // lingering outline, and onBlur clears it on click-away.
            'ring-2 ring-inset ring-blue-400': usingKeyboard && i === focusedIndex,
          }"
          @click="focusedIndex = i; emit('row-click', row)"
        >
          <td v-if="selectable" class="px-3 py-3 w-10">
            <input type="checkbox" :checked="isSelected(row)"
              @change="toggleRow(row)" :aria-label="`Select row ${rowKey ? row[rowKey] : i + 1}`"
              class="rounded text-blue-600 focus:ring-blue-500" />
          </td>
          <td v-for="col in columns" :key="col.key" class="px-4 py-3 text-gray-700">
            <slot :name="`cell-${col.key}`" :row="row" :value="row[col.key]">
              {{ row[col.key] ?? '—' }}
            </slot>
          </td>
          <td v-if="$slots.actions" class="px-4 py-3 text-right">
            <slot name="actions" :row="row" />
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<script setup lang="ts" generic="T extends Record<string, any>">
import { ref, computed } from 'vue'
import EmptyState from '@/components/EmptyState.vue'

/** A single column. `key`/`label` are required; consumers may carry
 * extra metadata (e.g. `alwaysVisible`, `defaultWidth`) used by their
 * own column-picker logic, so an index signature keeps those allowed. */
export interface DataTableColumn {
  key: string
  label: string
  [extra: string]: unknown
}

const props = withDefaults(defineProps<{
  columns: DataTableColumn[]
  rows?: T[]
  rowKey?: string
  loading?: boolean
  emptyText?: string
  emptyIcon?: string
  selectable?: boolean
  selectedKeys?: Set<unknown>
  /** When set, the row whose rowKey equals this value is highlighted (e.g. an open detail row). */
  highlightKey?: unknown
}>(), {
  rows: () => [],
  rowKey: 'id',
  loading: false,
  emptyText: 'No records found',
  emptyIcon: 'folder',
  selectable: false,
  selectedKeys: () => new Set(),
})

const emit = defineEmits<{
  'update:selectedKeys': [Set<unknown>]
  'row-click': [T]
}>()

const focusedIndex = ref(-1)
// True only while keyboard navigation is the active interaction mode,
// so the focus ring behaves like :focus-visible (keyboard, not mouse).
const usingKeyboard = ref(false)
const tableRef = ref<HTMLDivElement | null>(null)

const totalCols = computed(() => {
  let c = props.columns.length
  if (props.selectable) c++
  return c + 1
})

const allSelected = computed(() => props.rows.length > 0 && props.rows.every(r => props.selectedKeys.has(r[props.rowKey])))
const someSelected = computed(() => props.rows.some(r => props.selectedKeys.has(r[props.rowKey])))

function isSelected(row: T): boolean {
  return props.selectedKeys.has(row[props.rowKey])
}

function toggleRow(row: T): void {
  const key = row[props.rowKey]
  const next = new Set(props.selectedKeys)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  emit('update:selectedKeys', next)
}

function toggleAll(): void {
  if (allSelected.value) {
    emit('update:selectedKeys', new Set())
  } else {
    emit('update:selectedKeys', new Set(props.rows.map(r => r[props.rowKey])))
  }
}

function moveDown(): void {
  usingKeyboard.value = true
  if (focusedIndex.value < props.rows.length - 1) focusedIndex.value++
}

function moveUp(): void {
  usingKeyboard.value = true
  if (focusedIndex.value > 0) focusedIndex.value--
}

function selectFocused(): void {
  usingKeyboard.value = true
  if (focusedIndex.value >= 0 && focusedIndex.value < props.rows.length) {
    emit('row-click', props.rows[focusedIndex.value])
  }
}

function toggleFocusedSelection(): void {
  usingKeyboard.value = true
  if (!props.selectable || focusedIndex.value < 0) return
  toggleRow(props.rows[focusedIndex.value])
}

/** Drop the focus ring when the table loses focus (e.g. click-away). */
function onBlur(): void {
  focusedIndex.value = -1
  usingKeyboard.value = false
}
</script>
