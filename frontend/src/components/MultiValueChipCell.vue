<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  Chip editor for a multi-valued attribute cell (inline edit, Phase 1.5).

  Pure value editor: binds string[] via v-model, knows nothing about
  LDAP or saving. Each value renders as a removable chip; the trailing
  input appends on Enter or comma, Backspace in the empty input removes
  the last chip, and Escape emits `cancel` so the owning table can drop
  the cell's pending edit. Blur commits any half-typed draft so the
  row-level focusout save includes it.

  The container carries the `.input` utility (not hand-rolled control
  styling) so density mode and the form-control lint rules apply; the
  inner text input is intentionally chromeless inside it.
-->
<template>
  <div :class="['input flex h-auto min-h-0 w-full flex-wrap items-center gap-1',
                error ? 'border-red-400 ring-1 ring-red-200' : '']">
    <span v-for="(value, i) in modelValue" :key="`${value}-${i}`" class="badge-blue">
      {{ value }}
      <button
        type="button"
        :aria-label="`Remove ${value}`"
        class="ml-1 leading-none text-blue-700 hover:text-blue-900"
        @click="removeAt(i)"
      >&times;</button>
    </span>
    <input
      v-model="draft"
      type="text"
      class="min-w-20 flex-1 border-0 bg-transparent p-0 text-sm focus:outline-none focus:ring-0"
      :aria-label="inputLabel"
      :placeholder="modelValue.length ? '' : 'Add value…'"
      @keydown="onKeydown"
      @blur="commitDraft"
    />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'

const props = withDefaults(defineProps<{
  modelValue: string[]
  /** Accessible name for the value input (e.g. "description for cn=…"). */
  inputLabel: string
  /** Render the error ring (the cell has a server-side save error). */
  error?: boolean
}>(), { error: false })

const emit = defineEmits<{
  (e: 'update:modelValue', values: string[]): void
  /** Escape pressed — the owner should discard this cell's pending edit. */
  (e: 'cancel'): void
}>()

const draft = ref('')

function removeAt(index: number): void {
  emit('update:modelValue', props.modelValue.filter((_, i) => i !== index))
}

/** Append the trimmed draft as a chip; exact duplicates are dropped silently. */
function commitDraft(): void {
  const value = draft.value.trim()
  draft.value = ''
  if (!value) return
  if (props.modelValue.includes(value)) return
  emit('update:modelValue', [...props.modelValue, value])
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Enter' || event.key === ',') {
    event.preventDefault()
    commitDraft()
    return
  }
  if (event.key === 'Backspace' && draft.value === '' && props.modelValue.length) {
    event.preventDefault()
    removeAt(props.modelValue.length - 1)
    return
  }
  if (event.key === 'Escape') {
    event.preventDefault()
    draft.value = ''
    emit('cancel')
  }
}
</script>
