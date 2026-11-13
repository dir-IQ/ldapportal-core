<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div v-if="rows.length === 0" class="text-sm text-gray-500">
    No attributes returned for this entry.
  </div>
  <table v-else class="w-full text-sm">
    <thead>
      <tr class="border-b border-gray-200">
        <th class="text-left py-2 pr-4 text-xs font-semibold text-gray-500 uppercase tracking-wider"
            :class="compact ? 'w-36' : 'w-48'">Attribute</th>
        <th class="text-left py-2 text-xs font-semibold text-gray-500 uppercase tracking-wider">Value</th>
      </tr>
    </thead>
    <tbody class="divide-y divide-gray-50">
      <tr v-for="[attr, values] in rows" :key="attr">
        <td class="py-2 pr-4 text-gray-600 font-mono text-[13px] align-top break-all">{{ attr }}</td>
        <td class="py-2 text-gray-900 font-mono text-[13px]">
          <div v-for="(val, i) in values" :key="i" class="break-all">
            <DnLink v-if="directoryId && looksLikeDn(val)" :dn="val" :directory-id="directoryId" :nested="nested" />
            <template v-else>{{ formatAttributeValue(val) }}</template>
          </div>
        </td>
      </tr>
    </tbody>
  </table>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import DnLink from './DnLink.vue'
import { looksLikeDn } from '@/utils/dnValue'

/**
 * An entry's attributes as a two-column table, sorted by name, with DN
 * values rendered as quick-look links. Shared by the Directory Browser's
 * detail panel and the quick-look drawer.
 */
const props = withDefaults(defineProps<{
  attributes: Record<string, string[]>
  /** Directory the DN links resolve in; links are off without it. */
  directoryId?: string
  /** Rendered inside the drawer: DN links push onto its trail. */
  nested?: boolean
  /** Narrower attribute column, for the drawer. */
  compact?: boolean
}>(), { directoryId: '', nested: false, compact: false })

const rows = computed<Array<[string, string[]]>>(() =>
  Object.entries(props.attributes ?? {})
    .sort(([a], [b]) => a.localeCompare(b, undefined, { sensitivity: 'base' })))

/** Binary values (non-printable characters) are summarised, not dumped. */
function formatAttributeValue(val: string): string {
  if (val && /[\x00-\x08\x0E-\x1F]/.test(val)) return `[binary data, ${val.length} bytes]`
  return val
}
</script>
