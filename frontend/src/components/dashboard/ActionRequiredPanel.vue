<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup>
import { useRouter } from 'vue-router'

defineProps({
  actions: { type: Array, default: () => [] },
  maxHeight: { type: String, default: '20rem' },
})

const router = useRouter()

const severityColor = {
  CRITICAL: 'bg-red-50 border-red-200 text-red-800',
  HIGH: 'bg-orange-50 border-orange-200 text-orange-800',
  MEDIUM: 'bg-amber-50 border-amber-200 text-amber-800',
  LOW: 'bg-gray-50 border-gray-200 text-gray-700',
}
const severityDot = {
  CRITICAL: 'bg-red-500',
  HIGH: 'bg-orange-500',
  MEDIUM: 'bg-amber-500',
  LOW: 'bg-gray-400',
}
</script>

<template>
  <div class="bg-white border border-gray-200 rounded-xl overflow-hidden shadow-sm flex flex-col">
    <div class="px-5 py-3 border-b border-gray-100">
      <h2 class="text-sm font-semibold text-gray-700">Action Required</h2>
    </div>
    <div v-if="!actions.length" class="px-5 py-8 text-center text-sm text-gray-500">
      Nothing needs your attention.
    </div>
    <div v-else class="p-3 space-y-2 overflow-y-auto" :style="{ maxHeight }">
      <div v-for="a in actions" :key="a.type + a.link"
           class="border rounded-lg p-3 flex items-center gap-3 cursor-pointer transition-shadow hover:shadow-sm"
           :class="severityColor[a.severity] || severityColor.MEDIUM"
           @click="router.push(a.link)">
        <span class="w-2.5 h-2.5 rounded-full shrink-0" :class="severityDot[a.severity]"></span>
        <div class="flex-1 min-w-0">
          <p class="text-sm font-medium">{{ a.title }}</p>
          <p v-if="a.detail" class="text-xs opacity-70 mt-0.5">{{ a.detail }}</p>
        </div>
        <span class="text-xl font-bold opacity-30 shrink-0">{{ a.count }}</span>
      </div>
    </div>
  </div>
</template>
