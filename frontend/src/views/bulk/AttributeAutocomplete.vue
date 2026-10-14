<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div class="relative min-w-0">
    <input ref="inputEl" class="input input-sm w-full text-xs font-mono" type="text" role="combobox"
           autocomplete="off" spellcheck="false" aria-autocomplete="list"
           :aria-expanded="open ? 'true' : 'false'" :aria-controls="listId"
           :aria-activedescendant="open && active >= 0 ? `${listId}-${active}` : undefined"
           :aria-label="label" :aria-invalid="invalid ? 'true' : undefined"
           :placeholder="placeholder" :value="modelValue"
           @input="onInput" @focus="onFocus" @blur="onBlur" @keydown="onKey" />
    <!-- Rendered at the dialog's root (or body) with fixed positioning so the
         table's scroll box can't clip it; it stays inside the aria-modal dialog. -->
    <Teleport v-if="teleportTarget" :to="teleportTarget">
      <ul v-if="open" :id="listId" role="listbox" :aria-label="`Suggestions for ${label}`"
          :style="listStyle" data-testid="attr-suggestions"
          class="fixed z-50 max-h-56 overflow-y-auto rounded-lg border border-gray-300 bg-white py-1 shadow-lg text-xs">
        <li v-for="(o, i) in matches" :id="`${listId}-${i}`" :key="o.name" role="option"
            :aria-selected="i === active ? 'true' : 'false'" :data-attr="o.name"
            class="flex items-center justify-between gap-2 px-2.5 py-1 font-mono cursor-pointer"
            :class="i === active ? 'bg-blue-50 text-blue-800' : 'text-gray-900 hover:bg-gray-50'"
            @mousedown.prevent="pick(o.name)">
          <span class="truncate">{{ o.name }}</span>
          <span v-if="o.required" class="font-sans text-[11px] font-semibold text-red-700">required</span>
        </li>
      </ul>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import type { SchemaAttr } from './templateMapping'

/** Matches the list's max-h-56, used to decide whether it fits below the input. */
const LIST_MAX_HEIGHT = 224
const GAP = 4

const props = withDefaults(defineProps<{
  modelValue: string
  /** Attributes to suggest (the selected object classes'). */
  options: SchemaAttr[]
  /** Accessible name of the input. */
  label: string
  invalid?: boolean
  placeholder?: string
}>(), { invalid: false, placeholder: '' })

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  /** Fired when the operator leaves the field or picks a suggestion. */
  (e: 'commit'): void
}>()

const listId = `attr-suggest-${useId()}`
const inputEl = ref<HTMLInputElement | null>(null)
const teleportTarget = ref<HTMLElement | null>(null)
const listStyle = ref<Record<string, string>>({})
const focused = ref(false)
const dismissed = ref(false)
const active = ref(-1)

/**
 * Every option matching what's typed: an exact match first (so a short name
 * like `l` or `c` stays pickable), then prefix matches, then other substring
 * matches. The list scrolls when there are more than fit.
 */
const matches = computed<SchemaAttr[]>(() => {
  const q = props.modelValue.trim().toLowerCase()
  const exact: SchemaAttr[] = []
  const starts: SchemaAttr[] = []
  const contains: SchemaAttr[] = []
  for (const o of props.options) {
    const n = o.name.toLowerCase()
    if (n === q) exact.push(o)
    else if (!q || n.startsWith(q)) starts.push(o)
    else if (n.includes(q)) contains.push(o)
  }
  return [...exact, ...starts, ...contains]
})

const open = computed(() => focused.value && !dismissed.value && matches.value.length > 0)

/** Places the list under the input, or above it when there's more room there. */
function position() {
  const r = inputEl.value?.getBoundingClientRect()
  if (!r) return
  const below = window.innerHeight - r.bottom
  const above = r.top
  const style: Record<string, string> = { left: `${r.left}px`, width: `${r.width}px` }
  if (below < LIST_MAX_HEIGHT + GAP && above > below) style.bottom = `${window.innerHeight - r.top + GAP}px`
  else style.top = `${r.bottom + GAP}px`
  listStyle.value = style
}

function onViewportChange() {
  if (open.value) position()
}

function stopTracking() {
  window.removeEventListener('scroll', onViewportChange, true)
  window.removeEventListener('resize', onViewportChange)
}

watch(open, (isOpen) => {
  if (!isOpen) { stopTracking(); return }
  position()
  // Capture: scrolling the table or the modal body moves the input too.
  window.addEventListener('scroll', onViewportChange, true)
  window.addEventListener('resize', onViewportChange)
})

onMounted(() => {
  teleportTarget.value = inputEl.value?.closest<HTMLElement>('[role="dialog"]') ?? document.body
})
onBeforeUnmount(stopTracking)

/** Keeps the highlighted option visible as the arrow keys move through a long list. */
function revealActive() {
  nextTick(() => {
    const el = active.value >= 0 ? document.getElementById(`${listId}-${active.value}`) : null
    el?.scrollIntoView?.({ block: 'nearest' })
  })
}

function onInput(e: Event) {
  dismissed.value = false
  active.value = -1
  emit('update:modelValue', (e.target as HTMLInputElement).value)
}

function onFocus() {
  focused.value = true
  dismissed.value = false
  active.value = -1
}

function onBlur() {
  focused.value = false
  emit('commit')
}

function pick(name: string) {
  emit('update:modelValue', name)
  dismissed.value = true
  active.value = -1
  emit('commit')
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    e.preventDefault()
    dismissed.value = false
    const n = matches.value.length
    if (!n) return
    active.value = e.key === 'ArrowDown' ? (active.value + 1) % n : (active.value <= 0 ? n - 1 : active.value - 1)
    revealActive()
    return
  }
  if (e.key === 'Enter' && open.value && active.value >= 0) {
    // Choose the highlighted suggestion instead of submitting the form.
    e.preventDefault()
    pick(matches.value[active.value].name)
    return
  }
  if (e.key === 'Escape' && open.value) {
    // Close the list without closing the surrounding modal.
    e.preventDefault()
    e.stopPropagation()
    dismissed.value = true
  }
}

defineExpose({ focus: () => inputEl.value?.focus() })
</script>
