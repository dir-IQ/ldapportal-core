<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <AppModal v-model="visible" title="Import LDIF" size="lg">
    <div class="space-y-2">
      <!-- File picker with drag-and-drop -->
      <div>
        <label class="block text-sm font-medium text-gray-700 mb-1">LDIF File</label>
        <div
          :class="[
            'border-2 border-dashed rounded-lg p-6 text-center cursor-pointer transition-colors',
            dragging ? 'border-blue-400 bg-blue-50' : 'border-gray-300 hover:border-gray-400',
          ]"
          @click="fileInput?.click()"
          @dragover.prevent="dragging = true"
          @dragleave.prevent="dragging = false"
          @drop.prevent="onDrop"
        >
          <div v-if="!file" class="text-sm text-gray-500">
            <p class="font-medium text-gray-700">Drop an .ldif file here or click to browse</p>
            <p class="text-xs mt-1">Only .ldif files are accepted</p>
          </div>
          <div v-else class="text-sm">
            <p class="font-medium text-gray-900">{{ file.name }}</p>
            <p class="text-xs text-gray-500 mt-1">{{ formatFileSize(file.size) }}</p>
            <button @click.stop="file = null" class="text-xs text-red-500 hover:text-red-700 mt-1">Remove</button>
          </div>
        </div>
        <input ref="fileInput" type="file" accept=".ldif" class="hidden" @change="onFileSelect" />
      </div>

      <!-- Conflict handling -->
      <div>
        <label class="block text-sm font-medium text-gray-700 mb-1">Conflict Handling</label>
        <select v-model="conflictHandling" class="input w-full">
          <option value="SKIP">Skip — leave existing entries unchanged</option>
          <option value="OVERWRITE">Update — overwrite existing entry attributes</option>
        </select>
      </div>

      <!-- Dry run toggle -->
      <label class="flex items-center gap-2 text-sm text-gray-700">
        <input type="checkbox" v-model="dryRun" class="rounded border-gray-300" />
        Dry run (validate only, do not apply changes)
      </label>

      <!-- Results summary -->
      <div v-if="result" class="bg-gray-50 border border-gray-200 rounded-lg p-4">
        <h4 class="text-sm font-semibold text-gray-900 mb-2">
          {{ dryRunUsed ? 'Dry Run Results' : 'Import Results' }}
        </h4>
        <div class="grid grid-cols-4 gap-3 text-center">
          <div>
            <p class="text-lg font-bold text-green-600">{{ result.added }}</p>
            <p class="text-xs text-gray-500">Added</p>
          </div>
          <div>
            <p class="text-lg font-bold text-blue-600">{{ result.updated }}</p>
            <p class="text-xs text-gray-500">Updated</p>
          </div>
          <div>
            <p class="text-lg font-bold text-yellow-600">{{ result.skipped }}</p>
            <p class="text-xs text-gray-500">Skipped</p>
          </div>
          <div>
            <p class="text-lg font-bold text-red-600">{{ result.failed }}</p>
            <p class="text-xs text-gray-500">Failed</p>
          </div>
        </div>

        <!-- Error list -->
        <div v-if="result.errors?.length" class="mt-3">
          <button @click="showErrors = !showErrors"
                  class="text-xs font-medium text-red-600 hover:text-red-800 flex items-center gap-1">
            <svg :class="['w-3 h-3 transition-transform', showErrors && 'rotate-90']"
                 viewBox="0 0 20 20" fill="currentColor">
              <path fill-rule="evenodd" d="M7.21 14.77a.75.75 0 01.02-1.06L11.168 10 7.23 6.29a.75.75 0 111.04-1.08l4.5 4.25a.75.75 0 010 1.08l-4.5 4.25a.75.75 0 01-1.06-.02z" clip-rule="evenodd"/>
            </svg>
            {{ result.errors.length }} error(s)
          </button>
          <div v-if="showErrors" class="mt-2 max-h-40 overflow-y-auto space-y-1">
            <div v-for="(err, i) in result.errors" :key="i"
                 class="text-xs bg-red-50 border border-red-100 rounded px-2 py-1">
              <span v-if="err.dn" class="font-mono text-red-700">{{ err.dn }}: </span>
              <span class="text-red-600">{{ err.message }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Error message -->
      <div v-if="error" class="p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-700">
        {{ error }}
      </div>
    </div>

    <template #footer>
      <button @click="close" :disabled="importing"
              class="px-4 py-2 text-sm rounded-lg border border-gray-300 hover:bg-gray-50">
        {{ result ? 'Close' : 'Cancel' }}
      </button>
      <button @click="doImport" :disabled="!file || importing"
              class="px-4 py-2 text-sm rounded-lg text-white font-medium bg-blue-600 hover:bg-blue-700 disabled:opacity-50">
        {{ importing ? 'Importing…' : (dryRun ? 'Validate' : 'Import') }}
      </button>
    </template>
  </AppModal>
</template>

<script setup>
import { ref, watch } from 'vue'
import AppModal from '@/components/AppModal.vue'
import { importLdif } from '@/api/browse'

const props = defineProps({
  modelValue: Boolean,
  directoryId: { type: String, required: true },
})

const emit = defineEmits(['update:modelValue', 'imported'])

const visible = ref(props.modelValue)
watch(() => props.modelValue, v => { visible.value = v })
watch(visible, v => { emit('update:modelValue', v) })

const fileInput        = ref(null)
const file             = ref(null)
const conflictHandling = ref('SKIP')
const dryRun           = ref(false)
const dragging         = ref(false)
const importing        = ref(false)
const error            = ref('')
const result           = ref(null)
const showErrors       = ref(false)
const dryRunUsed       = ref(false)

// Reset state when modal opens
watch(() => props.modelValue, (open) => {
  if (open) {
    file.value = null
    error.value = ''
    result.value = null
    showErrors.value = false
    dryRunUsed.value = false
    conflictHandling.value = 'SKIP'
    dryRun.value = false
  }
})

function onFileSelect(e) {
  const selected = e.target.files?.[0]
  if (selected) file.value = selected
  e.target.value = '' // allow re-selecting same file
}

function onDrop(e) {
  dragging.value = false
  const dropped = e.dataTransfer.files?.[0]
  if (dropped && dropped.name.endsWith('.ldif')) {
    file.value = dropped
  }
}

function formatFileSize(bytes) {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

async function doImport() {
  if (!file.value) return
  error.value = ''
  result.value = null
  importing.value = true
  dryRunUsed.value = dryRun.value

  try {
    const { data } = await importLdif(
      props.directoryId, file.value, conflictHandling.value, dryRun.value)
    result.value = data
    if (!dryRun.value && data.added + data.updated > 0) {
      emit('imported')
    }
  } catch (e) {
    error.value = e.response?.data?.detail || e.response?.data?.message || e.message
  } finally {
    importing.value = false
  }
}

function close() {
  visible.value = false
}
</script>

<style scoped>
@reference "tailwindcss";
</style>
