<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  Compact "which provisioning profile am I on" pill: a theme-coloured dot + the
  profile name, in a soft tint of the profile's theme colour. Used in admin page
  headers and the new/edit modals as a quiet, glanceable environment cue (the
  calmer replacement for the former full-width colour band). Render only when a
  themeColor is set; callers fall back to their default styling otherwise.
-->
<template>
  <span class="inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-sm font-medium align-middle"
        :style="pillStyle">
    <span class="w-2 h-2 rounded-full shrink-0" :style="{ backgroundColor: color }" aria-hidden="true"></span>
    {{ name }}
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  name: string
  /** #RRGGBB profile theme colour. */
  color: string
}>()

// Soft tint derived from the theme colour: ~10% background, ~32% border, and a
// darkened theme text so the chip reads without a heavy fill. color-mix keeps
// this working for any profile colour without per-colour maths.
const pillStyle = computed(() => ({
  backgroundColor: `color-mix(in srgb, ${props.color} 10%, white)`,
  borderColor: `color-mix(in srgb, ${props.color} 32%, white)`,
  color: `color-mix(in srgb, ${props.color} 80%, black)`,
}))
</script>
