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
             and scrolls. Height is dynamic: the panel takes its content's
             natural height (no inner scrollbar while everything fits) capped
             at the padded viewport, where the body starts scrolling. A
             drag-resize sizes the whole panel instead. -->
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
          <!-- Body. A flex column so content can use the modal's full height:
               a scroll region opts in with `min-h-0 overflow-y-auto` (plus the
               same pair on any intermediate wrappers) and then shrinks into
               exactly the available space instead of capping at a fixed
               max-height with dead space below. Content that doesn't opt in
               behaves as before — flex items never shrink below their natural
               height without min-h-0, so the body scrollbar still takes over. -->
          <div class="px-6 py-4 overflow-y-auto flex-1 min-h-0 flex flex-col">
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
import { computed, nextTick, onBeforeUnmount, ref, useId, watch } from 'vue'
import { useDialogA11y } from '@/composables/useDialogA11y'
import { useDraggableModal } from '@/composables/useDraggableModal'
import { usePreferencesStore } from '@/stores/preferences'

const props = withDefaults(
  defineProps<{
    modelValue?: boolean
    title?: string
    size?: 'sm' | 'md' | 'lg' | 'xl' | '2xl'
    /** Drag the header to reposition the modal. On by default; ignored on
     *  narrow viewports (phones keep modals put). Pass :movable="false" to opt out. */
    movable?: boolean
    /** Drag the SE corner to resize. On by default; ignored below sm. Pass
     *  :resizable="false" to opt out. */
    resizable?: boolean
    /** Default the panel to fill the content area (viewport minus the nav) with
     *  margins, instead of the size-class cap. */
    fill?: boolean
    /** Key under which the drag-resized size is persisted to the user's
     *  preferences (`modals` namespace), so it follows them across sessions.
     *  Defaults to a slug of the title — pass an explicit key for modals whose
     *  title is dynamic (e.g. includes a record name) so sizes don't fragment.
     *  (A non-resizable modal never persists anyway.) */
    storageKey?: string
  }>(),
  { modelValue: false, title: '', size: 'md', movable: true, resizable: true,
    fill: false, storageKey: '' },
)
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const titleId = useId()
const panelRef = ref<HTMLElement | null>(null)

useDialogA11y({
  isOpen: () => props.modelValue,
  containerRef: panelRef,
  onClose: () => emit('update:modelValue', false),
})

// ── Fill-to-content default + size persistence ──
const FILL_MARGIN = 24
const MIN_W = 280
const MIN_H = 160

// Size persistence is on by default, keyed by the title (so any resizable modal
// remembers its size) unless an explicit storageKey is given. A blank key (no
// title and no storageKey) disables it.
function slugify(s: string): string {
  return s.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')
}
const effectiveStorageKey = computed(() => props.storageKey || slugify(props.title))

// The app's main content region (right of the sidebar nav); falls back to the
// full viewport (e.g. login, or before layout mounts).
function contentRect(): { left: number; top: number; width: number; height: number } {
  const el = document.getElementById('main-content')
  if (el) {
    const r = el.getBoundingClientRect()
    return { left: r.left, top: r.top, width: r.width, height: r.height }
  }
  return { left: 0, top: 0, width: window.innerWidth, height: window.innerHeight }
}

// Translate offset that centers a w×h panel within the content area (the panel
// is otherwise flex-centered in the full viewport).
function centeredOffset(w: number, h: number): { x: number; y: number } {
  const c = contentRect()
  return {
    x: c.left + c.width / 2 - window.innerWidth / 2,
    y: c.top + c.height / 2 - window.innerHeight / 2,
  }
}

function readPersistedSize(): { w: number; h: number } | null {
  if (!effectiveStorageKey.value) return null
  try {
    return usePreferencesStore().read<{ w: number; h: number } | null>('modals', effectiveStorageKey.value, null)
  } catch {
    return null
  }
}

function persistSize(s: { w: number; h: number } | null): void {
  if (!effectiveStorageKey.value || !s) return
  try {
    usePreferencesStore().write('modals', effectiveStorageKey.value, { w: Math.round(s.w), h: Math.round(s.h) })
  } catch {
    /* preferences unavailable — size just won't persist */
  }
}

function getInitialLayout(): { size: { w: number; h: number } | null; offset: { x: number; y: number } } | null {
  const c = contentRect()
  const persisted = readPersistedSize()
  let target: { w: number; h: number } | null = null
  if (persisted && persisted.w && persisted.h) {
    // Clamp a remembered size to the current content area.
    target = {
      w: Math.max(MIN_W, Math.min(persisted.w, c.width - 2 * FILL_MARGIN)),
      h: Math.max(MIN_H, Math.min(persisted.h, c.height - 2 * FILL_MARGIN)),
    }
  } else if (props.fill) {
    target = {
      w: Math.max(MIN_W, c.width - 2 * FILL_MARGIN),
      h: Math.max(MIN_H, c.height - 2 * FILL_MARGIN),
    }
  }
  return target ? { size: target, offset: centeredOffset(target.w, target.h) } : null
}

const { offset, size, onHandlePointerDown, onResizePointerDown } = useDraggableModal({
  panelRef,
  movable: () => props.movable && window.innerWidth >= 640,
  resizable: () => props.resizable && window.innerWidth >= 640,
  isOpen: () => props.modelValue,
  // Apply a custom layout when there's something to apply: a fill default, or a
  // persisted size under the effective key. With neither, getInitialLayout
  // returns null and the composable keeps its centered, auto-sized default — so
  // a never-resized modal looks exactly as before.
  getInitialLayout: () => (props.fill || effectiveStorageKey.value ? getInitialLayout() : null),
  onPersist: persistSize,
})

// ── Grow-only height lock ──
// Once open, the panel never shrinks: it keeps the largest height its
// content has needed, so a tabbed modal doesn't bounce when switching to a
// shorter tab — after the tallest tab has rendered, the height simply never
// changes. It still grows when content genuinely needs more room (capped at
// the padded viewport), which also covers modals that open on a small
// loading overlay before their real content arrives. Resets on close; a
// drag-resize takes over entirely.
const grownMinHeight = ref(0)
let panelObserver: ResizeObserver | null = null

function viewportCap(): number {
  // The p-4 wrapper leaves a 1rem margin all round.
  return window.innerHeight - 32
}

function clampGrownHeight(): void {
  if (grownMinHeight.value > viewportCap()) grownMinHeight.value = viewportCap()
}

watch(() => props.modelValue, (open) => {
  grownMinHeight.value = 0
  panelObserver?.disconnect()
  panelObserver = null
  window.removeEventListener('resize', clampGrownHeight)
  if (!open) return
  window.addEventListener('resize', clampGrownHeight)
  nextTick(() => {
    if (!panelRef.value || typeof ResizeObserver === 'undefined') return
    panelObserver = new ResizeObserver(() => {
      if (size.value) return // an explicit drag-resize owns the size
      const h = panelRef.value?.offsetHeight ?? 0
      if (h > grownMinHeight.value) grownMinHeight.value = Math.min(h, viewportCap())
    })
    panelObserver.observe(panelRef.value)
  })
}, { immediate: true })

onBeforeUnmount(() => {
  panelObserver?.disconnect()
  window.removeEventListener('resize', clampGrownHeight)
})

// Sizing precedence: an explicit drag-resize wins (and drops the max-w/max-h
// caps). Otherwise the height is dynamic: the panel takes its content's
// natural height — no inner scrollbar while everything fits — capped at 100%
// of the p-4 inset wrapper, i.e. the window minus a 1rem margin all round,
// beyond which the body scrolls. The grow-only lock above holds the height
// steady once reached. The drag offset is applied as a transform — but only
// once moved/resized, so the open/close scale transition (which also
// animates `transform`) is left alone in the common case.
const panelStyle = computed(() => {
  const style: Record<string, string> = {}
  if (size.value) {
    style.width = `${size.value.w}px`
    style.height = `${size.value.h}px`
    style.maxWidth = 'none'
    style.maxHeight = 'none'
  } else {
    style.maxHeight = '100%'
    if (grownMinHeight.value) style.minHeight = `${grownMinHeight.value}px`
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
