<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="modelValue" class="fixed inset-0 z-40 flex items-center justify-center p-4"
           role="dialog" aria-modal="true" :aria-labelledby="titleId">
        <!--
          Backdrop click intentionally does NOT close the modal. Accidental
          off-target clicks (especially on tall forms where the operator
          drags-to-select text past the panel edge) used to destroy
          half-completed work. Dismissal paths now: the explicit × button,
          the footer Cancel/Close, and Escape (kept for WCAG 2.1.2 keyboard
          accessibility — wired in useDialogA11y, not here).
        -->
        <div class="fixed inset-0 bg-black/40" />
        <!-- Panel is a flex column: header/footer are fixed, the body flexes
             and scrolls. This lets fixedHeight (and a future resize) size the
             whole panel while the body reflows into the remaining space. -->
        <div ref="panelRef"
             :class="['relative bg-white rounded-xl shadow-xl w-full flex flex-col overflow-hidden', sizeClass]"
             :style="panelStyle">
          <!-- Header (doubles as the drag handle when `movable`) -->
          <div :class="['flex items-center justify-between px-6 py-4 border-b border-gray-200 shrink-0',
                        movable ? 'cursor-move select-none' : '']"
               @pointerdown="onHandlePointerDown">
            <h2 :id="titleId" class="text-lg font-semibold text-gray-900">
              <slot name="title">{{ title }}</slot>
            </h2>
            <button @click="$emit('update:modelValue', false)" aria-label="Close"
                    class="text-gray-500 hover:text-gray-600 text-xl leading-none transition-colors">&#215;</button>
          </div>
          <!-- Body -->
          <div class="px-6 py-4 overflow-y-auto flex-1 min-h-0">
            <slot />
          </div>
          <!-- Footer -->
          <div v-if="$slots.footer" class="px-6 py-4 border-t border-gray-200 flex justify-end gap-3 shrink-0">
            <slot name="footer" />
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, ref, useId } from 'vue'
import { useDialogA11y } from '@/composables/useDialogA11y'
import { useDraggableModal } from '@/composables/useDraggableModal'

const props = withDefaults(
  defineProps<{
    modelValue?: boolean
    title?: string
    size?: 'sm' | 'md' | 'lg' | 'xl' | '2xl'
    fixedHeight?: string
    /** Opt-in: drag the header to reposition the modal. Off by default, and
     *  ignored on narrow viewports (phones keep modals put). */
    movable?: boolean
  }>(),
  { modelValue: false, title: '', size: 'md', fixedHeight: '', movable: false },
)
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const titleId = useId()
const panelRef = ref<HTMLElement | null>(null)

useDialogA11y({
  isOpen: () => props.modelValue,
  containerRef: panelRef,
  onClose: () => emit('update:modelValue', false),
})

const { offset, onHandlePointerDown } = useDraggableModal({
  panelRef,
  enabled: () => props.movable && window.innerWidth >= 640,
  isOpen: () => props.modelValue,
})

// Height comes from fixedHeight (or a viewport cap); the drag offset is applied
// as a transform — but only once moved, so the open/close scale transition
// (which also animates `transform`) is left alone for the common case.
const panelStyle = computed(() => {
  const style: Record<string, string> = props.fixedHeight
    ? { height: props.fixedHeight }
    : { maxHeight: '90vh' }
  const { x, y } = offset.value
  if (x || y) style.transform = `translate(${x}px, ${y}px)`
  return style
})

const sizeClass = computed(
  () =>
    ({
      sm: 'max-w-sm',
      md: 'max-w-lg',
      lg: 'max-w-2xl',
      xl: 'max-w-4xl',
      '2xl': 'max-w-6xl',
    })[props.size] || 'max-w-lg',
)
</script>

<style>
.modal-enter-active, .modal-leave-active {
  transition: opacity 0.2s ease;
}
.modal-enter-active > div:last-child, .modal-leave-active > div:last-child {
  transition: transform 0.2s ease, opacity 0.2s ease;
}
.modal-enter-from, .modal-leave-to { opacity: 0; }
.modal-enter-from > div:last-child { transform: scale(0.95) translateY(8px); }
.modal-leave-to > div:last-child { transform: scale(0.95); }
</style>
