<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  GroupChips — renders a user's group memberships as scannable pills.

  Each pill shows the group *name* (the leading RDN value of the group
  DN, e.g. the cn), with the full DN in its title. Collapsed by default
  to the first `collapsedCount` names plus a `+N` overflow pill, so the
  cell stays a bounded width no matter how many groups a user is in.
  Pass `expanded` to show the full list — the user-list "Groups" column
  drives this from a single header toggle so an operator can scan every
  row's complete membership at once.
-->
<template>
  <span v-if="!chips.length" class="text-gray-400">—</span>
  <span v-else class="inline-flex flex-wrap gap-1 items-center">
    <span
      v-for="chip in shown"
      :key="chip.dn"
      class="badge-gray max-w-[12rem] truncate"
      :title="chip.dn"
    >{{ chip.name }}</span>
    <span
      v-if="hiddenCount > 0"
      class="badge-blue"
      :title="hiddenTitle"
    >+{{ hiddenCount }}</span>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { rdnValue } from '@/composables/useEntryClassification'

const props = withDefaults(defineProps<{
  /** Group DNs the user belongs to (typically the memberOf attribute). */
  dns: string[]
  /** Show every group instead of collapsing to `collapsedCount` + overflow. */
  expanded?: boolean
  /** How many names to show before collapsing into a `+N` pill. */
  collapsedCount?: number
}>(), {
  expanded: false,
  collapsedCount: 3,
})

interface Chip {
  dn: string
  name: string
}

// Resolve each DN to its display name (the leading RDN value) and sort
// by name so the visible slots are stable row-to-row — a column scan
// then compares like against like. Falls back to the full DN when a
// value can't be parsed.
const chips = computed<Chip[]>(() =>
  props.dns
    .map(dn => ({ dn, name: rdnValue(dn) || dn }))
    .sort((a, b) => a.name.localeCompare(b.name)),
)

const shown = computed<Chip[]>(() =>
  props.expanded ? chips.value : chips.value.slice(0, props.collapsedCount),
)

const hiddenCount = computed<number>(() =>
  props.expanded ? 0 : Math.max(0, chips.value.length - props.collapsedCount),
)

// The +N pill's tooltip lists the names it stands in for, so an operator
// gets the overflow on hover without expanding the whole column.
const hiddenTitle = computed<string>(() =>
  chips.value.slice(props.collapsedCount).map(c => c.name).join('\n'),
)
</script>

<style scoped>
@reference "tailwindcss";
</style>
