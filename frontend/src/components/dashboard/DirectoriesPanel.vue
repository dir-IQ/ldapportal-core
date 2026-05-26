<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import type { components } from '@/api/openapi'

// Backend's DirectoryStatDto plus the `name` and `enabled` fields the
// dashboard service merges in. All numeric fields are nullable per the
// schema (Format: int64 with `?`), so each cell access has to be guarded.
type DirectoryStat = components['schemas']['DirectoryStatDto'] & {
  id: string
  name: string
  enabled: boolean
}

withDefaults(defineProps<{
  directories?: DirectoryStat[]
  showCampaigns?: boolean
  showSod?: boolean
  rowClickable?: boolean
}>(), {
  directories: () => [],
  showCampaigns: true,
  showSod: true,
  rowClickable: false,
})

defineEmits<{
  rowClick: [DirectoryStat]
}>()

/**
 * Format a count: localised thousands separators, '—' for null/negative.
 * Pulled out of the template so each metric row stays a single readable
 * line in the markup.
 */
function formatCount(n: number | null | undefined): string {
  if (n == null || n < 0) return '—'
  return n.toLocaleString()
}
</script>

<template>
  <!-- @container declares this card a containment context so the inner
       grid below can use @-prefixed breakpoints that respond to THIS
       element's width rather than the viewport's. The dashboard places
       this panel inside a multi-column grid, so the panel's width is
       decoupled from the viewport. Without @container, viewport-relative
       breakpoints (md:, xl:) would keep packing cards into a narrow
       panel column even on a wide screen. -->
  <div class="@container bg-white border border-gray-200 rounded-xl overflow-hidden shadow-sm">
    <div class="px-5 py-3 border-b border-gray-100">
      <h2 class="text-sm font-semibold text-gray-700">Directories</h2>
    </div>

    <!-- Empty state lives outside the grid so the "No directories" message
         doesn't pretend to be a card. -->
    <div v-if="!directories.length" class="px-5 py-8 text-center text-sm text-gray-500">
      No directories configured.
    </div>

    <!--
      Card grid replaces the prior <table>. The table layout cut metric
      columns off whenever the panel was placed in a narrow dashboard
      cell — the rightmost columns (Campaigns, SoD) silently disappeared
      past the viewport edge. Cards reflow with the panel's own width,
      so every metric stays visible regardless of dashboard layout.

      Breakpoints (container, not viewport):
        - default: 1 col — narrow panels (single-column dashboard)
        - @md (≥28rem ≈ 448px): 2 cols — typical dashboard column
        - @2xl (≥42rem ≈ 672px): 3 cols — when the user widens the
          panel via the dashboard's panel-width selector
    -->
    <div v-else class="p-3 grid grid-cols-1 @md:grid-cols-2 @2xl:grid-cols-3 gap-3">
      <component
        :is="rowClickable ? 'button' : 'div'"
        v-for="dir in directories"
        :key="dir.id"
        :type="rowClickable ? 'button' : undefined"
        :aria-label="rowClickable ? `Open ${dir.name}, ${dir.enabled ? 'enabled' : 'disabled'}` : undefined"
        class="text-left bg-gray-100 border border-gray-200 rounded-lg overflow-hidden transition-colors w-full"
        :class="rowClickable
          ? 'hover:bg-gray-200 hover:border-gray-300 cursor-pointer'
          : ''"
        @click="rowClickable && $emit('rowClick', dir)"
      >
        <!-- Header: status dot + name + chevron (chevron only when the
             whole card is clickable, since that's the affordance signal). -->
        <div class="px-3 py-2 border-b border-gray-200 flex items-center gap-2">
          <span class="w-2 h-2 rounded-full shrink-0" :class="dir.enabled ? 'bg-green-500' : 'border border-gray-400'" aria-hidden="true"></span>
          <!-- Visually-hidden status text so screen readers announce
               enabled/disabled — the coloured dot is decorative-only. -->
          <span class="sr-only">{{ dir.enabled ? 'Enabled.' : 'Disabled.' }}</span>
          <span class="font-medium text-gray-900 truncate flex-1" :title="dir.name">{{ dir.name }}</span>
          <svg v-if="rowClickable" class="w-4 h-4 text-gray-500 shrink-0" fill="none" viewBox="0 0 20 20" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
            <path stroke-linecap="round" stroke-linejoin="round" d="M7 5l5 5-5 5" />
          </svg>
        </div>

        <!-- Metrics: label-on-left, value-on-right. Right-aligning values
             makes them easier to compare visually across cards. -->
        <dl class="px-3 py-2 text-xs space-y-1">
          <div class="flex justify-between items-baseline">
            <dt class="text-gray-500">Users</dt>
            <dd class="text-gray-700 font-medium tabular-nums">{{ formatCount(dir.userCount) }}</dd>
          </div>
          <div class="flex justify-between items-baseline">
            <dt class="text-gray-500">Groups</dt>
            <dd class="text-gray-700 font-medium tabular-nums">{{ formatCount(dir.groupCount) }}</dd>
          </div>
          <div class="flex justify-between items-baseline">
            <dt class="text-gray-500">Pending</dt>
            <dd class="tabular-nums" :class="dir.pendingApprovals && dir.pendingApprovals > 0 ? 'text-amber-600 font-medium' : 'text-gray-500'">
              {{ dir.pendingApprovals ?? 0 }}
            </dd>
          </div>
          <div v-if="showCampaigns" class="flex justify-between items-baseline">
            <dt class="text-gray-500">Campaigns</dt>
            <dd class="tabular-nums" :class="dir.activeCampaigns && dir.activeCampaigns > 0 ? 'text-blue-600 font-medium' : 'text-gray-500'">
              {{ dir.activeCampaigns ?? 0 }}
            </dd>
          </div>
          <div v-if="showSod" class="flex justify-between items-baseline">
            <dt class="text-gray-500">SoD</dt>
            <dd class="tabular-nums" :class="dir.openSodViolations && dir.openSodViolations > 0 ? 'text-red-600 font-medium' : 'text-green-600'">
              {{ dir.openSodViolations ?? 0 }}
            </dd>
          </div>
        </dl>
      </component>
    </div>
  </div>
</template>
