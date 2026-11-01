<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  The profile editor's theme-colour preview: the profile's tag in the chosen
  colour on a light page and a dark one, and advice when the colour won't show
  well (or isn't a hex colour). The swatches use fixed page colours, so both
  show whichever theme the editor itself is in.
-->
<template>
  <div>
    <div v-if="previews" class="flex flex-wrap gap-2" data-testid="theme-preview">
      <div v-for="t in previews" :key="t.label"
           class="rounded-md border px-3 py-2"
           :style="{ backgroundColor: t.page, borderColor: t.pageBorder }">
        <span class="block text-[11px] mb-1" :style="{ color: t.caption }">{{ t.label }}</span>
        <span class="inline-flex items-center gap-1.5 rounded-md border px-3 py-1 text-sm font-medium"
              :data-testid="`theme-preview-tag-${t.key}`"
              :style="{ backgroundColor: t.tint.background, borderColor: t.tint.border, color: t.tint.text }">
          <span class="w-2 h-2 rounded-full shrink-0" :style="{ backgroundColor: color ?? undefined }" aria-hidden="true"></span>
          {{ name || 'Profile name' }}
        </span>
      </div>
    </div>
    <p v-if="advice" class="mt-1 text-xs text-amber-700" role="status" data-testid="theme-advice">{{ advice }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { themeTint, themeColorAdvice, type ThemeTint } from '@/utils/themeColor'

const props = defineProps<{
  /** The colour being picked, #RRGGBB; blank for none. */
  color?: string | null
  /** The profile's name, as the tag shows it. */
  name?: string | null
}>()

interface Preview {
  key: 'light' | 'dark'
  label: string
  page: string
  pageBorder: string
  caption: string
  tint: ThemeTint
}

const previews = computed<Preview[] | null>(() => {
  const light = themeTint(props.color, false)
  const dark = themeTint(props.color, true)
  if (!light || !dark) return null
  return [
    { key: 'light', label: 'Light mode', page: '#ffffff', pageBorder: '#e5e7eb', caption: '#6b7280', tint: light },
    { key: 'dark', label: 'Dark mode', page: '#111827', pageBorder: '#374151', caption: '#9ca3af', tint: dark },
  ]
})

const advice = computed(() => themeColorAdvice(props.color))
</script>
