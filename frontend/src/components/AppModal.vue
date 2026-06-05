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
          <!-- Resize grip (SE corner). Hidden below sm so phones keep modals
               fixed-size. aria-hidden: keyboard resize is intentionally deferred
               and the modal is fully usable without it. -->
          <div v-if="resizable"
               class="hidden sm:block absolute bottom-1 right-1 w-3.5 h-3.5 cursor-nwse-resize text-gray-300 hover:text-gray-500"
               aria-hidden="true"
               @pointerdown="onResizePointerDown">
            <svg viewBox="0 0 12 12" class="w-full h-full" fill="none" stroke="currentColor"
                 stroke-width="1.5" stroke-linecap="round">
              <path d="M11 5 L5 11 M11 9 L9 11" />
            </svg>
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
    /** Drag the header to reposition the modal. On by default; ignored on
     *  narrow viewports (phones keep modals put). Pass :movable="false" to opt out. */
    movable?: boolean
    /** Drag the SE corner to resize. On by default; ignored below sm. Pass
     *  :resizable="false" to opt out. */
    resizable?: boolean
  }>(),
  { modelValue: false, title: '', size: 'md', fixedHeight: '', movable: true, resizable: true },
)
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const titleId = useId()
const panelRef = ref<HTMLElement | null>(null)

useDialogA11y({
  isOpen: () => props.modelValue,
  containerRef: panelRef,
  onClose: () => emit('update:modelValue', false),
})

const { offset, size, onHandlePointerDown, onResizePointerDown } = useDraggableModal({
  panelRef,
  movable: () => props.movable && window.innerWidth >= 640,
  resizable: () => props.resizable && window.innerWidth >= 640,
  isOpen: () => props.modelValue,
})

// Sizing precedence: an explicit drag-resize wins (and drops the max-w/max-h
// caps); otherwise fixedHeight, else a viewport cap. The drag offset is applied
// as a transform — but only once moved/resized, so the open/close scale
// transition (which also animates `transform`) is left alone in the common case.
const panelStyle = computed(() => {
  const style: Record<string, string> = {}
  if (size.value) {
    style.width = `${size.value.w}px`
    style.height = `${size.value.h}px`
    style.maxWidth = 'none'
    style.maxHeight = 'none'
  } else if (props.fixedHeight) {
    style.height = props.fixedHeight
  } else {
    style.maxHeight = '90vh'
  }
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
