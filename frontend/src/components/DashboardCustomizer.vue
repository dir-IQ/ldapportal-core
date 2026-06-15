<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div v-if="editMode" class="bg-blue-50 border border-blue-200 rounded-xl p-4 mb-6">
    <div class="flex items-center justify-between mb-3">
      <h3 class="text-sm font-semibold text-blue-900">Customize Dashboard</h3>
      <div class="flex gap-2">
        <button @click="$emit('reset')" class="text-xs text-blue-600 hover:text-blue-800">Reset to default</button>
        <button @click="$emit('close')" class="text-xs text-blue-600 hover:text-blue-800 font-medium">Done</button>
      </div>
    </div>
    <div class="space-y-1">
      <div v-for="(w, i) in widgets" :key="w.id"
           class="flex items-center gap-3 px-3 py-2 rounded-lg bg-white border border-blue-100">
        <label class="flex items-center gap-2 flex-1 text-sm cursor-pointer">
          <input type="checkbox" :checked="w.visible" @change="$emit('toggle', w.id)"
                 class="rounded text-blue-600 focus:ring-blue-500" />
          {{ w.label }}
        </label>
        <div class="flex gap-1">
          <button @click="$emit('move-up', w.id)" :disabled="i === 0" aria-label="Move up"
                  class="text-gray-500 hover:text-gray-600 disabled:opacity-30 p-0.5">
            <svg class="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M4.5 15.75l7.5-7.5 7.5 7.5" />
            </svg>
          </button>
          <button @click="$emit('move-down', w.id)" :disabled="i === widgets.length - 1" aria-label="Move down"
                  class="text-gray-500 hover:text-gray-600 disabled:opacity-30 p-0.5">
            <svg class="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M19.5 8.25l-7.5 7.5-7.5-7.5" />
            </svg>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
defineProps({
  widgets: { type: Array, required: true },
  editMode: { type: Boolean, default: false },
})

defineEmits(['toggle', 'move-up', 'move-down', 'reset', 'close'])
</script>
