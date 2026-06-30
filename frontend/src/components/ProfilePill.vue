<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  Compact "which provisioning profile am I on" tag: a soft-tinted chip showing
  the profile name (plus a theme-coloured dot when a colour is set). Used in the
  admin user/group list headers and the new/edit modal titles as a quiet,
  glanceable environment cue. Renders in every case — a profile without a theme
  colour gets the same chip in neutral grey, with no dot — so callers no longer
  need a separate fallback.
-->
<template>
  <span class="inline-flex items-center gap-1.5 rounded-md border px-3 py-1 text-sm font-medium align-middle"
        :style="tint ?? undefined"
        :class="tint ? '' : 'bg-gray-50 border-gray-300 text-gray-700'">
    <span v-if="color" class="w-2 h-2 rounded-full shrink-0" :style="{ backgroundColor: color }" aria-hidden="true"></span>
    {{ name }}
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { themeTintStyle } from '@/utils/themeColor'

const props = defineProps<{
  name: string
  /** #RRGGBB profile theme colour, or null/empty for the neutral chip. */
  color?: string | null
}>()

const tint = computed(() => themeTintStyle(props.color))
</script>
