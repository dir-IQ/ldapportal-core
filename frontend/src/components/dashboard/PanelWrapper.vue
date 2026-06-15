<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup>
defineProps({
  label: { type: String, default: '' },
  editing: { type: Boolean, default: false },
  hidden: { type: Boolean, default: false },
})
defineEmits(['toggle-hide'])
</script>

<template>
  <div class="relative" :class="{ 'opacity-40 ring-2 ring-dashed ring-gray-300 rounded-xl': editing && hidden }">
    <!-- Edit-mode overlay: 6-dot drag handle (left) + hide/show button (right) -->
    <div v-if="editing" class="absolute top-2 left-2 z-20 pointer-events-auto">
      <button type="button"
              class="panel-drag-handle cursor-grab active:cursor-grabbing p-1 rounded bg-white/95 border border-gray-200 shadow-sm text-gray-500 hover:text-gray-700"
              :title="label ? `Drag ${label} to reorder` : 'Drag to reorder'">
        <svg class="w-3.5 h-3.5" viewBox="0 0 14 20" fill="currentColor" aria-hidden="true">
          <circle cx="4" cy="4" r="1.5" />
          <circle cx="10" cy="4" r="1.5" />
          <circle cx="4" cy="10" r="1.5" />
          <circle cx="10" cy="10" r="1.5" />
          <circle cx="4" cy="16" r="1.5" />
          <circle cx="10" cy="16" r="1.5" />
        </svg>
      </button>
    </div>
    <button v-if="editing"
            type="button"
            class="absolute top-2 right-2 z-20 p-1 rounded bg-white/95 border border-gray-200 shadow-sm text-gray-500 hover:text-gray-800"
            :title="hidden ? `Show ${label || 'panel'}` : `Hide ${label || 'panel'}`"
            @click="$emit('toggle-hide')">
      <!-- Eye / eye-off -->
      <svg v-if="!hidden" class="w-3.5 h-3.5" fill="none" viewBox="0 0 20 20" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
        <path stroke-linecap="round" stroke-linejoin="round" d="M2 10c2-4 5-6 8-6s6 2 8 6c-2 4-5 6-8 6s-6-2-8-6z" />
        <circle cx="10" cy="10" r="2.5" />
      </svg>
      <svg v-else class="w-3.5 h-3.5" fill="none" viewBox="0 0 20 20" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
        <path stroke-linecap="round" stroke-linejoin="round" d="M3 3l14 14M6 6c-1.5 1-2.8 2.4-4 4 2 4 5 6 8 6 1.4 0 2.7-.4 4-1M14 14c1.5-1 2.8-2.4 4-4-2-4-5-6-8-6-.6 0-1.2.1-1.8.2" />
      </svg>
    </button>

    <slot />
  </div>
</template>
