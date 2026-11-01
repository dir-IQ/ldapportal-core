<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <ul v-if="total > 0" class="space-y-0.5 mb-1">
    <li v-for="(v, i) in values" :key="i"
      :class="['px-1.5 py-0.5 rounded font-mono text-xs break-all border', TONES[tone]]">
      <span v-if="label" class="sr-only">{{ label }}: </span>{{ v }}
    </li>
    <li v-if="total > values.length" class="cell-muted">…and {{ (total - values.length).toLocaleString() }} more</li>
  </ul>
</template>

<script setup lang="ts">
withDefaults(defineProps<{
  values: string[]
  /** How many values there are; `values` may be capped. */
  total: number
  tone: 'a' | 'b' | 'both'
  /** Read out before each value, for screen readers. */
  label?: string
}>(), { label: '' })

const TONES: Record<'a' | 'b' | 'both', string> = {
  a: 'bg-red-50 border-red-200 text-red-900',
  b: 'bg-green-50 border-green-200 text-green-900',
  both: 'bg-gray-50 border-gray-200 text-gray-700',
}
</script>
