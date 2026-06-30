<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  Read-only "Active profile" field for the bulk pages, shown where the profile
  selector used to be. Admin bulk operations are scoped by the sidebar profile
  picker, so this just reflects that choice: a box filled with a soft tint of the
  profile's theme colour (the same recipe as the header profile pill), with the
  target DN beneath. Falls back to neutral grey when the profile has no theme
  colour, and to an em-dash when no profile is selected.
-->
<template>
  <div>
    <label class="block text-sm font-medium text-gray-700 mb-1">Active profile</label>
    <div class="flex items-center gap-2 rounded-lg border px-3 py-2 text-sm font-semibold"
         :style="tint ?? undefined"
         :class="tint ? '' : 'bg-gray-50 border-gray-300 text-gray-900'">
      <span v-if="color" class="w-2.5 h-2.5 rounded-full shrink-0" :style="{ backgroundColor: color }" aria-hidden="true"></span>
      <span class="truncate">{{ name || '—' }}</span>
    </div>
    <p v-if="dn" class="mt-1 text-xs text-gray-500 font-mono break-all">{{ dn }}</p>
    <p v-else class="mt-1 text-xs text-gray-400">Select a profile in the sidebar.</p>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { themeTintStyle } from '@/utils/themeColor'

const props = defineProps<{
  name?: string | null
  /** #RRGGBB theme colour, or null/empty for neutral styling. */
  color?: string | null
  dn?: string | null
}>()

const tint = computed(() => themeTintStyle(props.color))
</script>
