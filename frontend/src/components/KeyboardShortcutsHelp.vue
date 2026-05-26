<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <Teleport to="body">
    <div v-if="modelValue" class="fixed inset-0 z-50 flex items-center justify-center bg-black/40" @click.self="$emit('update:modelValue', false)">
      <div v-dialog-a11y role="dialog" aria-modal="true" aria-labelledby="kbd-shortcuts-title"
           @keydown.escape="$emit('update:modelValue', false)"
           class="bg-white dark:bg-gray-800 rounded-xl shadow-xl max-w-md w-full mx-4 p-6">
        <div class="flex items-center justify-between mb-4">
          <h2 id="kbd-shortcuts-title" class="text-lg font-semibold text-gray-900 dark:text-gray-100">Keyboard Shortcuts</h2>
          <button @click="$emit('update:modelValue', false)" aria-label="Close" class="text-gray-500 hover:text-gray-600 dark:hover:text-gray-300">✕</button>
        </div>
        <div class="space-y-2">
          <div v-for="s in shortcuts" :key="s.keys"
            class="flex items-center justify-between py-1.5 border-b border-gray-100 dark:border-gray-700 last:border-0">
            <span class="text-sm text-gray-600 dark:text-gray-300">{{ s.description }}</span>
            <div class="flex gap-1">
              <kbd v-for="k in s.keys.split(' ')" :key="k"
                class="px-2 py-0.5 rounded bg-gray-100 dark:bg-gray-700 text-xs font-mono font-semibold text-gray-700 dark:text-gray-200 border border-gray-200 dark:border-gray-600">
                {{ k }}
              </kbd>
            </div>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { SHORTCUTS } from '@/composables/useKeyboardShortcuts'

defineProps({ modelValue: { type: Boolean, default: false } })
defineEmits(['update:modelValue'])

const shortcuts = SHORTCUTS
</script>
