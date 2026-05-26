<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <Teleport to="body">
    <div v-if="modelValue" class="fixed inset-0 z-40 flex items-center justify-center bg-black/40"
         @click.self="$emit('update:modelValue', false)"
         role="dialog" aria-modal="true" :aria-labelledby="titleId">
      <div ref="panelRef" class="bg-white rounded-xl shadow-xl max-w-md w-full mx-4 p-6">
        <h3 :id="titleId" class="text-lg font-semibold text-gray-900 mb-2">{{ title }}</h3>
        <p class="text-sm text-gray-600 mb-6">{{ message }}</p>
        <div class="flex justify-end gap-3">
          <button
            @click="$emit('update:modelValue', false)"
            class="px-4 py-2 text-sm rounded-lg border border-gray-300 hover:bg-gray-50"
          >Cancel</button>
          <button
            @click="confirm"
            :class="[
              'px-4 py-2 text-sm rounded-lg text-white font-medium',
              confirmClass || (danger ? 'bg-red-600 hover:bg-red-700' : 'bg-blue-600 hover:bg-blue-700')
            ]"
          >{{ confirmLabel }}</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, useId } from 'vue'
import { useDialogA11y } from '@/composables/useDialogA11y'

const props = withDefaults(
  defineProps<{
    modelValue?: boolean
    title?: string
    message?: string
    confirmLabel?: string
    confirmClass?: string
    danger?: boolean
  }>(),
  {
    modelValue: false,
    title: 'Confirm',
    message: 'Are you sure?',
    confirmLabel: 'Confirm',
    confirmClass: '',
    danger: false,
  },
)
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  confirm: []
}>()

const titleId = useId()
const panelRef = ref<HTMLElement | null>(null)

useDialogA11y({
  isOpen: () => props.modelValue,
  containerRef: panelRef,
  onClose: () => emit('update:modelValue', false),
})

function confirm(): void {
  emit('confirm')
  emit('update:modelValue', false)
}
</script>
