<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <!-- Sits at the right end of a field's label row, so the link is grey
       (secondary to the field's own controls) and the panel opens right-aligned
       to stay on-screen. -->
  <div class="relative inline-block text-xs text-gray-500">(<button type="button"
      class="font-medium underline hover:text-blue-700"
      :aria-expanded="open ? 'true' : 'false'" :aria-controls="panelId" @click="toggle">{{ linkText }}</button>)
    <!-- Stays open until closed with ×, Escape or the link, so the operator can
         keep it beside the form while preparing a file. Not modal: the rest of
         the page stays usable. Escape is handled on the panel itself, which
         holds focus while open (a non-modal dialog, not a static element). -->
    <!-- eslint-disable-next-line vuejs-accessibility/no-static-element-interactions -->
    <div v-if="open" :id="panelId" ref="panelEl" role="dialog" aria-modal="false" :aria-labelledby="titleId" tabindex="-1"
         class="absolute right-0 top-full mt-1 z-30 w-[38rem] max-w-[calc(100vw-2rem)] rounded-lg border border-gray-300 bg-white shadow-lg p-4 text-xs text-gray-700"
         data-testid="file-format-help" @keydown.esc.stop.prevent="close">
      <div class="flex items-start justify-between gap-3 mb-2">
        <h3 :id="titleId" class="text-sm font-semibold text-gray-900">{{ title }}</h3>
        <button type="button" aria-label="Close" class="text-gray-400 hover:text-gray-600 text-lg leading-none -mt-1"
                @click="close">&times;</button>
      </div>
      <div class="max-h-[60vh] overflow-y-auto">
        <slot />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, ref, useId } from 'vue'

withDefaults(defineProps<{
  title: string
  linkText?: string
}>(), { linkText: 'Input file format' })

const open = ref(false)
const panelEl = ref<HTMLElement | null>(null)
const panelId = `file-format-${useId()}`
const titleId = `${panelId}-title`

async function toggle() {
  open.value = !open.value
  if (open.value) {
    await nextTick()
    panelEl.value?.focus()
  }
}

function close() {
  open.value = false
}
</script>
