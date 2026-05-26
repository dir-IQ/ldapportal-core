<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup>
import RelativeTime from '@/components/RelativeTime.vue'
import { actionLabel, actionColor, shortDn } from './auditLabels'

defineProps({
  events: { type: Array, default: () => [] },
  viewAllTo: { type: String, default: null },
})
</script>

<template>
  <div class="bg-white border border-gray-200 rounded-xl overflow-hidden shadow-sm">
    <div class="px-5 py-3 border-b border-gray-100 flex items-center justify-between">
      <h2 class="text-sm font-semibold text-gray-700">Recent Activity</h2>
      <RouterLink v-if="viewAllTo" :to="viewAllTo" class="text-xs text-blue-600 hover:text-blue-800">View all</RouterLink>
    </div>
    <div v-if="!events.length" class="px-5 py-8 text-center text-sm text-gray-500">No recent events.</div>
    <ul v-else class="divide-y divide-gray-50">
      <li v-for="evt in events" :key="evt.id"
          class="px-4 py-2 flex items-center gap-2 text-sm">
        <span class="shrink-0 text-[11px] font-medium px-1.5 py-0.5 rounded" :class="actionColor(evt.action)">
          {{ actionLabel(evt.action) }}
        </span>
        <span v-if="evt.targetDn"
              class="min-w-0 flex-1 truncate text-gray-700"
              :title="evt.targetDn">
          {{ shortDn(evt.targetDn) }}
        </span>
        <span v-else class="flex-1"></span>
        <span class="shrink-0 text-xs text-gray-500">
          {{ evt.actorUsername || 'system' }}
          <span class="mx-1">&middot;</span>
          <RelativeTime :value="evt.occurredAt" />
        </span>
      </li>
    </ul>
  </div>
</template>
