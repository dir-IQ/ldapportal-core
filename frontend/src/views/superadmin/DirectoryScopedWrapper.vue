<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div class="p-6">
    <!-- Directory picker -->
    <div class="mb-5">
      <label for="dsw-directory" class="block text-sm font-medium text-gray-700 mb-1">Directory</label>
      <select id="dsw-directory" v-model="selectedDir" class="border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 w-full max-w-sm">
        <option value="" disabled>{{ loading ? 'Loading…' : '— Select directory —' }}</option>
        <option v-for="d in directories" :key="d.id" :value="d.id">{{ d.displayName }}</option>
      </select>
    </div>

    <div v-if="!selectedDir && !loading" class="text-gray-500 text-sm">Select a directory to continue.</div>

    <!-- Render the directory-scoped route as a child -->
    <RouterView v-if="selectedDir" />
  </div>
</template>

<script setup>
import { ref, watch, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { listDirectories } from '@/api/directories'

const props = defineProps({
  defaultChild: { type: String, required: true },
})

const router = useRouter()
const route = useRoute()

const directories = ref([])
const selectedDir = ref('')
const loading = ref(true)

watch(selectedDir, (dirId) => {
  if (dirId) {
    router.replace({ name: props.defaultChild, params: { dirId } })
  }
})

// Sync picker if navigated with a dirId already in the URL
watch(() => route.params.dirId, (dirId) => {
  if (dirId && dirId !== selectedDir.value) selectedDir.value = dirId
}, { immediate: true })

onMounted(async () => {
  try {
    const { data } = await listDirectories()
    directories.value = data
    // Auto-select from route or first directory
    if (route.params.dirId) {
      selectedDir.value = route.params.dirId
    } else if (data.length === 1) {
      selectedDir.value = data[0].id
    }
  } catch (e) {
    console.warn('Failed to load directories:', e)
  } finally {
    loading.value = false
  }
})
</script>
