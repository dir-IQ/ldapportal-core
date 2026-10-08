<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div ref="rootEl" class="flex flex-col min-h-0 flex-1">
    <div class="flex flex-wrap items-center justify-between gap-2 mb-2 shrink-0">
      <div class="flex flex-wrap items-center gap-2">
        <span class="text-sm font-medium text-gray-700">Column Mappings</span>
        <template v-if="rowCount">
          <span class="px-2 py-0.5 rounded-full text-xs font-medium bg-green-50 text-green-700">{{ counts.mapped }} mapped</span>
          <span v-if="counts.missing" class="px-2 py-0.5 rounded-full text-xs font-medium bg-red-50 text-red-700">{{ counts.missing }} required missing</span>
          <span v-if="counts.ignored" class="px-2 py-0.5 rounded-full text-xs font-medium bg-gray-100 text-gray-600">{{ counts.ignored }} input ignored</span>
          <span v-if="counts.unmapped" class="px-2 py-0.5 rounded-full text-xs font-medium bg-gray-100 text-gray-600">{{ counts.unmapped }} attribute not mapped</span>
        </template>
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <slot name="actions" />
      </div>
    </div>

    <div v-if="!rowCount" class="text-sm text-gray-500 text-center py-3">
      <slot name="empty">Select an object class to populate attribute mappings.</slot>
    </div>
    <template v-else>
      <div class="border border-gray-200 rounded-lg min-h-0 flex-1 overflow-y-auto" data-testid="mapping-stacks">
        <div class="mapping-row sticky top-0 z-[1] bg-gray-50 border-b border-gray-200 text-[11px] uppercase tracking-wide font-semibold text-gray-500 py-1.5">
          <span></span>
          <span class="px-2">Input column<template v-if="fromSample"> (from sample file)</template></span>
          <span class="text-center">Maps to</span>
          <span class="px-2">LDAP attribute (from object class)</span>
          <span></span>
        </div>
        <div v-for="i in rowCount" :key="i" class="mapping-row border-b border-gray-100 last:border-b-0 py-1"
             :class="statusOf(i - 1) === 'missing' ? 'bg-red-50' : ''" :data-status="statusOf(i - 1)">
          <span class="text-right pr-2 text-xs text-gray-400 tabular-nums">{{ i }}</span>

          <!-- Input stack cell: move buttons, then the column name -->
          <div class="flex items-center gap-1 px-1 min-w-0">
            <div class="flex flex-col gap-px shrink-0">
              <button type="button" class="move-btn border-gray-300 text-gray-500" :disabled="i === 1"
                      :aria-label="`Move input ${inputLabel(i - 1)} up`" @click="move('in', i - 1, i - 2)">▲</button>
              <button type="button" class="move-btn border-gray-300 text-gray-500" :disabled="i === rowCount"
                      :aria-label="`Move input ${inputLabel(i - 1)} down`" @click="move('in', i - 1, i)">▼</button>
            </div>
            <div class="stack-chip border-gray-300" :class="chipClass('in', i - 1)" draggable="true"
                 :tabindex="editableInputs ? -1 : 0" data-side="in" :data-index="i - 1"
                 :aria-label="editableInputs ? undefined : `Input ${inputLabel(i - 1)}, row ${i}`"
                 @keydown="onChipKey($event, 'in', i - 1)"
                 @dragstart="onDragStart($event, 'in', i - 1)" @dragend="onDragEnd"
                 @dragover="onDragOver($event, 'in', i - 1)" @drop="onDrop($event, 'in', i - 1)">
              <span class="text-gray-400 select-none" aria-hidden="true">⋮⋮</span>
              <input v-if="editableInputs" class="input input-sm flex-1 min-w-0 text-xs font-mono"
                     :value="stacks.inputs[i - 1] ?? ''" placeholder="CSV column"
                     :aria-label="`CSV column for row ${i}`"
                     :aria-invalid="statusOf(i - 1) === 'missing' ? 'true' : undefined"
                     @input="onType(i - 1, $event)" />
              <span v-else-if="hasInput(i - 1)" class="truncate font-mono text-xs text-gray-900" :title="stacks.inputs[i - 1] ?? ''">
                {{ stacks.inputs[i - 1] }}
                <span v-if="sampleFor(i - 1)" class="font-sans text-gray-400">e.g. {{ sampleFor(i - 1) }}</span>
              </span>
              <span v-else class="truncate text-xs italic text-gray-400">no input column</span>
            </div>
          </div>

          <!-- Row status -->
          <span class="text-center text-[11px] leading-tight" :class="linkClass(i - 1)">
            <span class="block text-base leading-none" aria-hidden="true">→</span>
            {{ linkLabel(i - 1) }}
          </span>

          <!-- Attribute stack cell: the attribute name, then move buttons -->
          <div class="flex items-center gap-1 px-1 min-w-0">
            <div class="stack-chip border-gray-300 bg-gray-50" :class="chipClass('out', i - 1)" draggable="true" tabindex="0"
                 data-side="out" :data-index="i - 1" :data-attr="stacks.attrs[i - 1]?.name"
                 :aria-label="`Attribute ${attrLabel(i - 1)}, row ${i}`"
                 @keydown="onChipKey($event, 'out', i - 1)"
                 @dragstart="onDragStart($event, 'out', i - 1)" @dragend="onDragEnd"
                 @dragover="onDragOver($event, 'out', i - 1)" @drop="onDrop($event, 'out', i - 1)">
              <span class="text-gray-400 select-none" aria-hidden="true">⋮⋮</span>
              <template v-if="stacks.attrs[i - 1]">
                <span class="truncate font-mono text-xs text-gray-900">{{ stacks.attrs[i - 1]!.name }}</span>
                <span v-if="stacks.attrs[i - 1]!.required" class="text-red-500 font-bold text-xs">*</span>
              </template>
              <span v-else class="truncate text-xs italic text-gray-400">no attribute</span>
            </div>
            <div class="flex flex-col gap-px shrink-0">
              <button type="button" class="move-btn border-gray-300 text-gray-500" :disabled="i === 1"
                      :aria-label="`Move attribute ${attrLabel(i - 1)} up`" @click="move('out', i - 1, i - 2)">▲</button>
              <button type="button" class="move-btn border-gray-300 text-gray-500" :disabled="i === rowCount"
                      :aria-label="`Move attribute ${attrLabel(i - 1)} down`" @click="move('out', i - 1, i)">▼</button>
            </div>
          </div>

          <div class="flex justify-center">
            <span v-if="stacks.attrs[i - 1]?.required" class="text-red-500 text-sm font-bold" title="Required">*</span>
            <button v-else-if="stacks.attrs[i - 1]" type="button" @click="emit('remove', i - 1)"
                    aria-label="Remove mapping" class="text-red-400 hover:text-red-600 text-lg leading-none">&times;</button>
          </div>
        </div>
      </div>
      <p class="text-xs text-gray-500 mt-1 shrink-0">
        Each row pairs the input column on the left with the LDAP attribute on the right. Move either side with ▲▼,
        drag a name, or focus it and press Alt+↑ / Alt+↓.
      </p>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { moveItem, rowStatus, type MappingStacks, type RowStatus } from './templateMapping'
import { useDragAutoScroll } from '@/composables/useDragAutoScroll'

type Side = 'in' | 'out'

const props = withDefaults(defineProps<{
  /** Inputs typed as text (no sample file loaded) vs fixed names from a sample. */
  editableInputs?: boolean
  /** Whether the input names came from a sample file (column heading copy). */
  fromSample?: boolean
  /** First-row values by column name, shown as hints when the sample has no header row. */
  samples?: Record<string, string> | null
  /** CSV column holding the DN, if the template reads DNs from a column. */
  dnColumn?: string | null
}>(), { editableInputs: false, fromSample: false, samples: null, dnColumn: null })

const stacks = defineModel<MappingStacks>('stacks', { required: true })
const emit = defineEmits<{ (e: 'remove', index: number): void }>()

const rootEl = ref<HTMLElement | null>(null)
const autoScroll = useDragAutoScroll()

const rowCount = computed(() => Math.max(stacks.value.inputs.length, stacks.value.attrs.length))

function hasInput(i: number): boolean {
  const v = stacks.value.inputs[i]
  return v != null && v.trim() !== ''
}

function isDnColumn(i: number): boolean {
  const v = stacks.value.inputs[i]
  return !!props.dnColumn && !!v && v.trim().toLowerCase() === props.dnColumn.trim().toLowerCase()
}

function statusOf(i: number): RowStatus {
  return rowStatus(stacks.value.inputs[i], stacks.value.attrs[i])
}

const counts = computed(() => {
  const c = { mapped: 0, ignored: 0, missing: 0, unmapped: 0 }
  for (let i = 0; i < rowCount.value; i++) {
    const s = statusOf(i)
    if (s === 'ignored' && isDnColumn(i)) continue
    if (s !== 'blank') c[s]++
  }
  return c
})

function sampleFor(i: number): string {
  const v = stacks.value.inputs[i]
  return (v && props.samples?.[v]) || ''
}

function inputLabel(i: number): string {
  return hasInput(i) ? stacks.value.inputs[i]! : 'empty slot'
}
function attrLabel(i: number): string {
  return stacks.value.attrs[i]?.name ?? 'empty slot'
}

function linkLabel(i: number): string {
  switch (statusOf(i)) {
    case 'ignored': return isDnColumn(i) ? 'DN column' : 'ignored'
    case 'missing': return 'needs input'
    case 'unmapped': return 'not mapped'
    default: return ''
  }
}
function linkClass(i: number): string {
  switch (statusOf(i)) {
    case 'mapped': return 'text-blue-600'
    case 'missing': return 'text-red-600 font-medium'
    default: return 'text-gray-400'
  }
}

function chipClass(side: Side, i: number): Record<string, boolean> {
  const empty = side === 'in' ? !hasInput(i) && !props.editableInputs : !stacks.value.attrs[i]
  return {
    'stack-chip-empty': empty,
    'stack-chip-over': dragOver.value?.side === side && dragOver.value.index === i,
    'opacity-40': drag.value?.side === side && drag.value.index === i,
    'opacity-70': side === 'in' && statusOf(i) === 'ignored',
  }
}

function arr(side: Side): unknown[] {
  return side === 'in' ? stacks.value.inputs : stacks.value.attrs
}

/** Keeps both stacks the same length so every row has a slot on each side. */
function equalize() {
  const n = rowCount.value
  while (stacks.value.inputs.length < n) stacks.value.inputs.push(null)
  while (stacks.value.attrs.length < n) stacks.value.attrs.push(null)
}

async function move(side: Side, from: number, to: number) {
  equalize()
  if (!moveItem(arr(side), from, to)) return
  await nextTick()
  const chip = rootEl.value?.querySelector<HTMLElement>(`.stack-chip[data-side="${side}"][data-index="${to}"]`)
  ;(chip?.querySelector<HTMLElement>('input') ?? chip)?.focus()
}

function onType(i: number, e: Event) {
  stacks.value.inputs[i] = (e.target as HTMLInputElement).value
}

function onChipKey(e: KeyboardEvent, side: Side, i: number) {
  if (!e.altKey || (e.key !== 'ArrowUp' && e.key !== 'ArrowDown')) return
  e.preventDefault()
  move(side, i, e.key === 'ArrowUp' ? i - 1 : i + 1)
}

// ── Drag and drop (within one stack only) ─────────────────────────────────────
const drag = ref<{ side: Side, index: number } | null>(null)
const dragOver = ref<{ side: Side, index: number } | null>(null)

function onDragStart(e: DragEvent, side: Side, i: number) {
  // Let text selection inside a typed input work normally.
  if ((e.target as HTMLElement).tagName === 'INPUT') { e.preventDefault(); return }
  drag.value = { side, index: i }
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = 'move'
    e.dataTransfer.setData('text/plain', String(i))
  }
  autoScroll.start(rootEl.value)
}
function onDragEnd() {
  drag.value = null
  dragOver.value = null
  autoScroll.stop()
}
function onDragOver(e: DragEvent, side: Side, i: number) {
  if (!drag.value || drag.value.side !== side) return
  e.preventDefault()
  dragOver.value = { side, index: i }
}
function onDrop(e: DragEvent, side: Side, i: number) {
  if (!drag.value || drag.value.side !== side) return
  e.preventDefault()
  const from = drag.value.index
  onDragEnd()
  move(side, from, i)
}
</script>

<style scoped>
@reference "tailwindcss";

.mapping-row {
  display: grid;
  grid-template-columns: 2rem minmax(0, 1fr) 4.5rem minmax(0, 1fr) 2rem;
  align-items: center;
}
.stack-chip {
  @apply flex flex-1 min-w-0 items-center gap-1.5 rounded-md border px-2 py-1 cursor-grab;
}
.stack-chip:focus-visible {
  @apply outline-2 outline-offset-1 outline-blue-500;
}
.stack-chip-empty {
  @apply border-dashed bg-transparent;
}
.stack-chip-over {
  @apply border-blue-500 ring-2 ring-blue-100;
}
.move-btn {
  @apply w-5 h-4 flex items-center justify-center rounded-sm border text-[9px] leading-none;
}
.move-btn:hover:not(:disabled) {
  @apply border-blue-500 text-blue-700;
}
.move-btn:disabled {
  @apply opacity-30 cursor-default;
}
</style>
