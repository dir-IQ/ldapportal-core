<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <span class="relative inline-flex items-center" @mouseenter="visible = true" @mouseleave="visible = false">
    <button type="button" aria-label="Help" class="text-gray-500 hover:text-gray-600 transition-colors ml-1" tabindex="-1">
      <svg class="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
        <path stroke-linecap="round" stroke-linejoin="round" d="M9.879 7.519c1.171-1.025 3.071-1.025 4.242 0 1.172 1.025 1.172 2.687 0 3.712-.203.179-.43.326-.67.442-.745.361-1.45.999-1.45 1.827v.75M21 12a9 9 0 11-18 0 9 9 0 0118 0zm-9 5.25h.008v.008H12v-.008z" />
      </svg>
    </button>
    <Transition name="tooltip-fade">
      <div v-if="visible"
           class="absolute z-40 bottom-full left-1/2 -translate-x-1/2 mb-2 w-56 bg-gray-900 text-white text-xs rounded-lg px-3 py-2 shadow-lg pointer-events-none">
        {{ text }}
        <div class="absolute top-full left-1/2 -translate-x-1/2 -mt-px">
          <div class="border-4 border-transparent border-t-gray-900" />
        </div>
      </div>
    </Transition>
  </span>
</template>

<script setup>
import { ref } from 'vue'
defineProps({ text: { type: String, required: true } })
const visible = ref(false)
</script>

<style>
.tooltip-fade-enter-active, .tooltip-fade-leave-active {
  transition: opacity 0.15s ease, transform 0.15s ease;
}
.tooltip-fade-enter-from { opacity: 0; transform: translateY(4px); }
.tooltip-fade-leave-to { opacity: 0; }
</style>
