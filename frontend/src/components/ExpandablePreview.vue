<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  Wraps a preview table in a scroll container that is sized to fit the
  remaining viewport (so the form stays on one screen and only the table
  scrolls), plus an Expand toggle that promotes the table to a full-screen
  overlay for scanning large result sets. The default slot is the <table>.
-->
<template>
  <Teleport to="body" :disabled="!expanded">
    <div
      :class="expanded ? 'fixed inset-0 z-50 flex items-stretch justify-center bg-black/40 p-4' : ''"
      @click.self="expanded = false"
    >
      <div :class="expanded ? 'flex min-h-0 w-full max-w-6xl flex-col rounded-lg bg-white shadow-xl dark:bg-gray-800' : ''">
        <!-- Toolbar: just the Expand affordance inline; title + Collapse when full-screen. -->
        <div
          class="flex items-center justify-between"
          :class="expanded ? 'border-b border-gray-200 px-4 py-2 dark:border-gray-700' : 'mb-1'"
        >
          <span v-if="expanded" class="text-sm font-medium text-gray-800 dark:text-gray-100">{{ title }}</span>
          <span v-else aria-hidden="true"></span>
          <button
            type="button"
            class="inline-flex items-center gap-1 rounded text-xs font-medium text-gray-600 hover:text-gray-900 dark:text-gray-300 dark:hover:text-white"
            :aria-label="expanded ? 'Collapse preview' : 'Expand preview to full screen'"
            @click="expanded = !expanded"
          >
            <svg class="h-3.5 w-3.5" viewBox="0 0 20 20" fill="none" stroke="currentColor"
                 stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path v-if="expanded" d="M8 3v5H3M12 3v5h5M8 17v-5H3M12 17v-5h5" />
              <path v-else d="M7 3H3v4M13 3h4v4M7 17H3v-4M13 17h4v-4" />
            </svg>
            {{ expanded ? 'Collapse' : 'Expand' }}
          </button>
        </div>

        <div
          ref="scrollEl"
          class="overflow-auto"
          :class="[bodyClass, expanded ? 'min-h-0 flex-1' : '']"
          :style="expanded ? undefined : fitStyle"
        >
          <slot />
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useFitToViewport } from '@/composables/useFitToViewport'

const props = withDefaults(defineProps<{
  /** Heading shown in the full-screen overlay. */
  title: string
  /** Pixels to reserve beneath the table (confirm controls + margin). */
  reserveBelow?: number
  /** Minimum fitted height before the page is allowed to scroll. */
  minHeight?: number
  /** Extra classes for the scroll container (e.g. border on the delete table). */
  bodyClass?: string
}>(), {
  reserveBelow: 120,
  minHeight: 160,
  bodyClass: '',
})

const expanded = ref(false)
const scrollEl = ref<HTMLElement | null>(null)
const { height, remeasure } = useFitToViewport(scrollEl, {
  reserveBelow: props.reserveBelow,
  minHeight: props.minHeight,
})
const fitStyle = computed(() => (height.value == null ? undefined : { height: `${height.value}px` }))

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape' && expanded.value) expanded.value = false
}

function lockBodyScroll(lock: boolean) {
  if (typeof document !== 'undefined') document.body.style.overflow = lock ? 'hidden' : ''
}

// Lock background scroll while the overlay is open; re-fit when returning inline.
watch(expanded, (open) => {
  lockBodyScroll(open)
  if (!open) remeasure()
})

onMounted(() => {
  if (typeof window !== 'undefined') window.addEventListener('keydown', onKey)
})
onBeforeUnmount(() => {
  if (typeof window !== 'undefined') window.removeEventListener('keydown', onKey)
  lockBodyScroll(false)
})
</script>
