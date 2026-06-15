<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup>
import { cardBorder, cardValueColor } from './auditLabels'

defineProps({
  label: { type: String, required: true },
  value: { type: [Number, String], required: true },
  subtitle: { type: String, default: '' },
  severity: { type: String, default: 'gray' },
})
defineEmits(['click'])
</script>

<template>
  <div class="rounded-xl border-2 p-5 cursor-pointer shadow-sm transition-shadow hover:shadow-md"
       :class="cardBorder(severity)"
       @click="$emit('click')">
    <p class="text-sm font-medium text-gray-500 mb-1">{{ label }}</p>
    <p class="text-3xl font-bold" :class="cardValueColor(severity)">{{ value }}</p>
    <p v-if="subtitle || $slots.subtitle" class="text-xs text-gray-500 mt-1">
      <slot name="subtitle">{{ subtitle }}</slot>
    </p>
  </div>
</template>
