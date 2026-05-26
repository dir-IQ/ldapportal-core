<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup>
import { useRouter } from 'vue-router'

defineProps({
  awareness: { type: Array, default: () => [] },
})

const router = useRouter()
</script>

<template>
  <div class="bg-white border border-gray-200 rounded-xl overflow-hidden shadow-sm">
    <div class="px-5 py-3 border-b border-gray-100">
      <h2 class="text-sm font-semibold text-gray-700">Awareness</h2>
    </div>
    <div v-if="!awareness.length" class="px-5 py-8 text-center text-sm text-gray-500">
      Nothing to note.
    </div>
    <ul v-else class="divide-y divide-gray-50">
      <li v-for="(aw, i) in awareness" :key="i"
          class="px-4 py-3 flex items-center gap-3 cursor-pointer hover:bg-gray-50"
          @click="router.push(aw.link)">
        <div class="flex-1 min-w-0">
          <p class="text-sm text-gray-800">{{ aw.title }}</p>
          <p v-if="aw.detail" class="text-xs text-gray-500 mt-0.5">{{ aw.detail }}</p>
        </div>
        <svg class="w-4 h-4 text-gray-300 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
          <path stroke-linecap="round" stroke-linejoin="round" d="M8.25 4.5l7.5 7.5-7.5 7.5" />
        </svg>
      </li>
    </ul>
  </div>
</template>
