<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div v-if="views.length > 0 || showSave" class="flex items-center gap-2 flex-wrap">
    <!-- Saved view pills -->
    <button v-for="v in views" :key="v.name"
            @click="$emit('load', v.name)"
            class="group flex items-center gap-1 px-2.5 py-1 text-xs rounded-full border border-gray-200 text-gray-600 hover:bg-blue-50 hover:border-blue-200 hover:text-blue-700 transition-colors">
      {{ v.name }}
      <button @click.stop="$emit('delete', v.name)"
              class="opacity-0 group-hover:opacity-100 text-gray-500 hover:text-red-500 transition-opacity ml-0.5">&times;</button>
    </button>

    <!-- Save current filter button -->
    <button v-if="!saving" @click="saving = true"
            class="text-xs text-gray-500 hover:text-gray-600 flex items-center gap-1">
      <svg class="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
        <path stroke-linecap="round" stroke-linejoin="round" d="M12 4.5v15m7.5-7.5h-15" />
      </svg>
      Save filter
    </button>

    <!-- Save input -->
    <div v-if="saving" class="flex items-center gap-1">
      <input ref="nameInput" v-model="newName" type="text" placeholder="Filter name..."
             class="border border-gray-300 rounded px-2 py-0.5 text-xs w-32 focus:outline-none focus:ring-1 focus:ring-blue-500"
             @keydown.enter="doSave" @keydown.escape="saving = false" />
      <button @click="doSave" :disabled="!newName.trim()" class="text-xs text-blue-600 hover:text-blue-800 font-medium">Save</button>
      <button @click="saving = false" aria-label="Cancel" class="text-xs text-gray-500 hover:text-gray-600">&times;</button>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, nextTick } from 'vue'

const props = defineProps({
  views: { type: Array, default: () => [] },
})

const emit = defineEmits(['load', 'delete', 'save'])

const saving = ref(false)
const newName = ref('')
const nameInput = ref(null)

watch(saving, async (v) => {
  if (v) {
    newName.value = ''
    await nextTick()
    nameInput.value?.focus()
  }
})

function doSave() {
  if (newName.value.trim()) {
    emit('save', newName.value.trim())
    saving.value = false
  }
}
</script>
