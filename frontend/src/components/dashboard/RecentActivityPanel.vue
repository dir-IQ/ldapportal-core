<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import RelativeTime from '@/components/RelativeTime.vue'
import { actionLabel, actionColor, affectedLabel } from './auditLabels'

interface AuditEvent {
  id: string
  action: string
  targetDn?: string | null
  actorUsername?: string | null
  occurredAt: string
  detail?: Record<string, unknown> | null
}

withDefaults(defineProps<{
  events?: AuditEvent[]
  viewAllTo?: string | null
}>(), {
  events: () => [],
  viewAllTo: null,
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
        <!-- Affected entity: the target DN for LDAP-entry actions, otherwise a
             label derived from the audit detail (e.g. account username, approval
             request type) so workflow rows aren't blank. -->
        <span class="min-w-0 flex-1 truncate text-gray-700"
              :title="evt.targetDn || undefined">
          {{ affectedLabel(evt) }}
        </span>
        <span class="shrink-0 text-xs text-gray-500">
          {{ evt.actorUsername || 'system' }}
          <span class="mx-1">&middot;</span>
          <RelativeTime :value="evt.occurredAt" />
        </span>
      </li>
    </ul>
  </div>
</template>
