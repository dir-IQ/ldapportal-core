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
    <ul v-show="open" :id="listId" role="listbox" :aria-label="`Suggestions for ${label}`"
        class="absolute left-0 right-0 top-full mt-1 z-20 max-h-56 overflow-y-auto rounded-lg border border-gray-300 bg-white py-1 shadow-lg text-xs">
      <li v-for="(o, i) in matches" :id="`${listId}-${i}`" :key="o.name" role="option"
          :aria-selected="i === active ? 'true' : 'false'" :data-attr="o.name"
          class="flex items-center justify-between gap-2 px-2.5 py-1 font-mono cursor-pointer"
          :class="i === active ? 'bg-blue-50 text-blue-800' : 'text-gray-900 hover:bg-gray-50'"
          @mousedown.prevent="pick(o.name)">
        <span class="truncate">{{ o.name }}</span>
        <span v-if="o.required" class="font-sans text-[11px] font-semibold text-red-700">required</span>
      </li>
    </ul>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, useId } from 'vue'
import type { SchemaAttr } from './templateMapping'

const MAX_SUGGESTIONS = 8

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
const focused = ref(false)
const dismissed = ref(false)
const active = ref(-1)

/** Prefix matches first, then other substring matches; the exact value is left out. */
const matches = computed<SchemaAttr[]>(() => {
  const q = props.modelValue.trim().toLowerCase()
  const starts: SchemaAttr[] = []
  const contains: SchemaAttr[] = []
  for (const o of props.options) {
    const n = o.name.toLowerCase()
    if (n === q) continue
    if (!q || n.startsWith(q)) starts.push(o)
    else if (n.includes(q)) contains.push(o)
  }
  return [...starts, ...contains].slice(0, MAX_SUGGESTIONS)
})

const open = computed(() => focused.value && !dismissed.value && matches.value.length > 0)

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
