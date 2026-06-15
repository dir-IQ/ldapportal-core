<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <span ref="anchor" class="relative inline-flex items-center"
        @mouseenter="onEnter" @mouseleave="visible = false">
    <button type="button" aria-label="Help" tabindex="-1"
            class="text-gray-500 hover:text-gray-600 transition-colors ml-1">
      <svg class="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
        <path stroke-linecap="round" stroke-linejoin="round" d="M9.879 7.519c1.171-1.025 3.071-1.025 4.242 0 1.172 1.025 1.172 2.687 0 3.712-.203.179-.43.326-.67.442-.745.361-1.45.999-1.45 1.827v.75M21 12a9 9 0 11-18 0 9 9 0 0118 0zm-9 5.25h.008v.008H12v-.008z" />
      </svg>
    </button>
    <!--
      Teleported to <body> and fixed-positioned so the tooltip is never clipped
      by a modal's overflow or hidden behind its chrome. Flips below the icon
      when there isn't room above, and clamps to the viewport horizontally.
    -->
    <Teleport to="body">
      <Transition name="tooltip-fade">
        <div v-if="visible" ref="tip" role="tooltip"
             class="fixed z-[60] w-56 bg-gray-900 text-white text-xs rounded-lg px-3 py-2 shadow-lg pointer-events-none"
             :style="tipStyle">
          {{ text }}
        </div>
      </Transition>
    </Teleport>
  </span>
</template>

<script setup lang="ts">
import { ref, nextTick, type CSSProperties } from 'vue'

defineProps<{ text: string }>()

const visible = ref(false)
const anchor = ref<HTMLElement | null>(null)
const tip = ref<HTMLElement | null>(null)
const tipStyle = ref<CSSProperties>({})

async function onEnter(): Promise<void> {
  visible.value = true
  await nextTick()
  positionTip()
}

function positionTip(): void {
  const a = anchor.value?.getBoundingClientRect()
  const el = tip.value
  if (!a || !el) return
  const margin = 8
  const tw = el.offsetWidth
  const th = el.offsetHeight
  // Centre on the icon, clamped to the viewport.
  let left = a.left + a.width / 2 - tw / 2
  left = Math.max(8, Math.min(left, window.innerWidth - tw - 8))
  // Prefer above; flip below when there isn't room.
  const above = a.top - th - margin
  const top = above >= 8 ? above : a.bottom + margin
  tipStyle.value = { left: `${left}px`, top: `${top}px` }
}
</script>

<style>
.tooltip-fade-enter-active, .tooltip-fade-leave-active {
  transition: opacity 0.15s ease, transform 0.15s ease;
}
.tooltip-fade-enter-from { opacity: 0; transform: translateY(4px); }
.tooltip-fade-leave-to { opacity: 0; }
</style>
