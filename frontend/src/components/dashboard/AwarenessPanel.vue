<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { useRouter } from 'vue-router'

interface AwarenessItem {
  type: string
  title: string
  detail?: string | null
  link?: string | null
}

withDefaults(defineProps<{ awareness?: AwarenessItem[] }>(), { awareness: () => [] })

const router = useRouter()

// Per-type presentation: a colored left accent + a tinted icon chip so a
// glance conveys severity. `icon` selects one of the inline glyphs below.
type Tone = { accent: string; chip: string; icon: 'warn' | 'alert' | 'pulse' | 'clock' | 'info' }
function tone(type: string): Tone {
  switch (type) {
    case 'RECONCILIATION_DRIFT_OPEN': return { accent: 'border-amber-400', chip: 'bg-amber-100 text-amber-600', icon: 'warn' }
    case 'REPLICATION_LAG_HIGH':      return { accent: 'border-red-400',   chip: 'bg-red-100 text-red-600',     icon: 'alert' }
    case 'RECENT_CHANGES':            return { accent: 'border-blue-400',  chip: 'bg-blue-100 text-blue-600',   icon: 'pulse' }
    case 'UPCOMING_DEADLINE':         return { accent: 'border-amber-400', chip: 'bg-amber-100 text-amber-600', icon: 'clock' }
    case 'STALE_APPROVAL':            return { accent: 'border-amber-400', chip: 'bg-amber-100 text-amber-600', icon: 'clock' }
    default:                          return { accent: 'border-gray-300', chip: 'bg-gray-100 text-gray-500',   icon: 'info' }
  }
}

function go(item: AwarenessItem) {
  if (item.link) router.push(item.link)
}
</script>

<template>
  <div class="bg-white border border-gray-200 rounded-xl overflow-hidden shadow-sm">
    <div class="px-5 py-3 border-b border-gray-100 flex items-center gap-2">
      <span class="inline-flex items-center justify-center w-6 h-6 rounded-md bg-amber-100 text-amber-600" aria-hidden="true">
        <svg class="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z" />
          <path d="M12 9v4" /><path d="M12 17h.01" />
        </svg>
      </span>
      <h2 class="text-sm font-semibold text-gray-700">Awareness</h2>
    </div>
    <div v-if="!awareness?.length" class="px-5 py-8 text-center text-sm text-gray-500">
      Nothing to note.
    </div>
    <ul v-else class="divide-y divide-gray-50">
      <li v-for="(aw, i) in awareness" :key="i"
          class="px-4 py-3 flex items-center gap-3 border-l-4 transition-colors"
          :class="[tone(aw.type).accent, aw.link ? 'cursor-pointer hover:bg-gray-50' : '']"
          @click="go(aw)">
        <span class="inline-flex items-center justify-center w-8 h-8 rounded-lg shrink-0" :class="tone(aw.type).chip" aria-hidden="true">
          <svg class="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <template v-if="tone(aw.type).icon === 'warn'">
              <path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z" />
              <path d="M12 9v4" /><path d="M12 17h.01" />
            </template>
            <template v-else-if="tone(aw.type).icon === 'alert'">
              <circle cx="12" cy="12" r="9" /><path d="M12 8v4" /><path d="M12 16h.01" />
            </template>
            <template v-else-if="tone(aw.type).icon === 'pulse'">
              <path d="M3 12h4l3 8 4-16 3 8h4" />
            </template>
            <template v-else-if="tone(aw.type).icon === 'clock'">
              <circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" />
            </template>
            <template v-else>
              <circle cx="12" cy="12" r="9" /><path d="M12 11v5" /><path d="M12 8h.01" />
            </template>
          </svg>
        </span>
        <div class="flex-1 min-w-0">
          <p class="text-sm text-gray-800">{{ aw.title }}</p>
          <p v-if="aw.detail" class="text-xs text-gray-500 mt-0.5">{{ aw.detail }}</p>
        </div>
        <svg v-if="aw.link" class="w-4 h-4 text-gray-300 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
          <path stroke-linecap="round" stroke-linejoin="round" d="M8.25 4.5l7.5 7.5-7.5 7.5" />
        </svg>
      </li>
    </ul>
  </div>
</template>
