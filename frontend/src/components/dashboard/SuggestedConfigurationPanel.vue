<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
interface Suggestion {
  key: string
  icon: string
  title: string
  description: string
  link: string
}

withDefaults(
  defineProps<{
    suggestions?: Suggestion[]
    maxHeight?: string
  }>(),
  { suggestions: () => [], maxHeight: '20rem' },
)
defineEmits<{ dismiss: [key: string] }>()

const suggestionIcons: Record<string, string> = {
  setup: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="10" cy="10" r="2.5"/><path d="M10 1.5v2M10 16.5v2M18.5 10h-2M3.5 10h-2M16 4l-1.4 1.4M5.4 14.6 4 16M16 16l-1.4-1.4M5.4 5.4 4 4"/></svg>',
  policy: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M10 2l7 4v5c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6l7-4z"/><path d="M7 10l2 2 4-4"/></svg>',
  review: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M9 2H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9"/><path d="M9 11l8-8"/><path d="M14 3h3v3"/></svg>',
  sync: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M3 10a7 7 0 0 1 12.9-3.8M17 10a7 7 0 0 1-12.9 3.8"/><path d="M16 3v4h-4M4 17v-4h4"/></svg>',
  alert: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M10 2l7 4v5c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6l7-4z"/><path d="M10 8v3M10 14h.01"/></svg>',
  report: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M5 16V10M10 16V4M15 16v-4"/></svg>',
}
</script>

<template>
  <div class="bg-white border border-gray-200 rounded-xl overflow-hidden shadow-sm flex flex-col">
    <div class="px-5 py-3 border-b border-gray-100">
      <h2 class="text-sm font-semibold text-gray-700">Suggested Configuration</h2>
    </div>
    <div v-if="!suggestions.length" class="px-5 py-8 text-center text-sm text-gray-500">
      No suggestions right now.
    </div>
    <div v-else class="p-3 space-y-2 overflow-y-auto" :style="{ maxHeight }">
      <div v-for="s in suggestions" :key="s.key"
           class="border border-gray-200 rounded-lg p-3 flex items-start gap-3">
        <span class="w-5 h-5 shrink-0 mt-0.5 text-gray-500" v-html="suggestionIcons[s.icon] || suggestionIcons.setup"></span>
        <RouterLink :to="s.link" class="flex-1 min-w-0 block">
          <p class="text-sm font-medium text-gray-900">{{ s.title }}</p>
          <p class="text-xs text-gray-500 mt-0.5">{{ s.description }}</p>
        </RouterLink>
        <button @click.stop="$emit('dismiss', s.key)"
                class="text-gray-600 hover:text-gray-600 text-xs shrink-0 px-2 py-1 rounded hover:bg-gray-100"
                title="Dismiss">✕</button>
      </div>
    </div>
  </div>
</template>
