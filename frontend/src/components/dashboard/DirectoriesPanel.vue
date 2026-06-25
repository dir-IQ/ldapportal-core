<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import type { components } from '@/api/openapi'
import EmptyState from '@/components/EmptyState.vue'

// Backend's DirectoryStatDto plus the `name` and `enabled` fields the
// dashboard service merges in. All numeric fields are nullable per the
// schema (Format: int64 with `?`), so each cell access has to be guarded.
// `reachable` is the LDAP connectivity probe result for an enabled
// directory (true/false), and null when the directory is disabled (so
// no probe ran). The generated DTO types it as a plain boolean, so we
// Omit + re-declare it to keep the null state the dot logic relies on
// (an intersection would collapse `boolean & (boolean|null)` to `boolean`).
type DirectoryStat = Omit<components['schemas']['DirectoryStatDto'], 'reachable'> & {
  id: string
  name: string
  enabled: boolean
  reachable?: boolean | null
}

withDefaults(defineProps<{
  directories?: DirectoryStat[]
  showCampaigns?: boolean
  showSod?: boolean
  rowClickable?: boolean
  /**
   * Whether the LDAP user/group counts have loaded. The dashboard paints first
   * without counts (they're the slow part) and fills them in on a follow-up
   * request; while false, the Users/Groups cells render a skeleton instead of a
   * placeholder zero. Defaults to true so other callers are unaffected.
   */
  countsLoaded?: boolean
}>(), {
  directories: () => [],
  showCampaigns: true,
  showSod: true,
  rowClickable: false,
  countsLoaded: true,
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

/**
 * Solid status dot for a directory whose reachability is already resolved.
 * `enabled` is only a config flag — an enabled directory whose LDAP host is
 * unreachable must NOT read green, or the dashboard claims health it doesn't
 * have. So: disabled → grey outline; enabled-but-unreachable → red;
 * enabled-and-reachable → green. The still-loading state (enabled with a null
 * `reachable`, before the slow probe resolves) renders as a spinner in the
 * template instead — see {@link isLoading} — so it isn't handled here.
 */
function statusDotClass(dir: DirectoryStat): string {
  if (!dir.enabled) return 'border border-gray-400'
  return dir.reachable ? 'bg-green-500' : 'bg-red-500'
}

/**
 * An enabled directory whose reachability hasn't resolved yet. The dashboard
 * paints before the slow reachability/counts load, so this is the brief window
 * where the status is genuinely unknown — shown as a spinner rather than a
 * colour that would imply a result.
 */
function isLoading(dir: DirectoryStat): boolean {
  return dir.enabled && dir.reachable == null
}

/** Short status word for screen-reader / aria labels. */
function statusText(dir: DirectoryStat): string {
  if (!dir.enabled) return 'disabled'
  if (dir.reachable == null) return 'checking'
  return dir.reachable ? 'enabled' : 'unreachable'
}

/** Fuller hover text for the status indicator's title attribute. */
function statusTitle(dir: DirectoryStat): string {
  if (!dir.enabled) return 'Disabled'
  if (dir.reachable == null) return 'Checking reachability…'
  return dir.reachable ? 'Reachable' : 'Unreachable'
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
  <div id="dashboard-scope-panel" class="@container bg-white border border-gray-200 rounded-xl overflow-hidden shadow-sm">
    <div class="px-5 py-3 border-b border-gray-100">
      <h2 class="text-sm font-semibold text-gray-700">Directories</h2>
    </div>

    <!-- Empty state lives outside the grid so the "No directories" message
         doesn't pretend to be a card. -->
    <EmptyState v-if="!directories.length" icon="folder" title="No directories configured." />

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
        :aria-label="rowClickable ? `Open ${dir.name}, ${statusText(dir)}` : undefined"
        class="text-left bg-gray-100 border border-gray-200 rounded-lg overflow-hidden transition-colors w-full"
        :class="rowClickable
          ? 'hover:bg-gray-200 hover:border-gray-300 cursor-pointer'
          : ''"
        @click="rowClickable && $emit('rowClick', dir)"
      >
        <!-- Header: status dot + name + chevron (chevron only when the
             whole card is clickable, since that's the affordance signal). -->
        <div class="px-3 py-2 border-b border-gray-200 flex items-center gap-2">
          <!-- A spinner while the reachability probe is still in flight;
               resolves to a solid green/red dot (or a hollow grey outline when
               disabled). The spinner reads as "loading" far more clearly than a
               faint colour, and its ring shape stays distinct from the solid
               state dots. -->
          <span v-if="isLoading(dir)"
                class="w-3 h-3 rounded-full border-2 border-gray-300 border-t-gray-500 animate-spin shrink-0"
                :title="statusTitle(dir)" aria-hidden="true"></span>
          <span v-else class="w-2 h-2 rounded-full shrink-0" :class="statusDotClass(dir)" :title="statusTitle(dir)" aria-hidden="true"></span>
          <!-- Visually-hidden status text so screen readers announce
               enabled / disabled / unreachable — the coloured dot is
               decorative-only. -->
          <span class="sr-only">{{ statusText(dir) }}.</span>
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
            <dd class="text-gray-700 font-medium tabular-nums">
              <span v-if="countsLoaded">{{ formatCount(dir.userCount) }}</span>
              <span v-else class="inline-block h-3 w-10 bg-gray-200 rounded animate-pulse align-middle">
                <span class="sr-only">Loading</span>
              </span>
            </dd>
          </div>
          <div class="flex justify-between items-baseline">
            <dt class="text-gray-500">Groups</dt>
            <dd class="text-gray-700 font-medium tabular-nums">
              <span v-if="countsLoaded">{{ formatCount(dir.groupCount) }}</span>
              <span v-else class="inline-block h-3 w-10 bg-gray-200 rounded animate-pulse align-middle">
                <span class="sr-only">Loading</span>
              </span>
            </dd>
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
