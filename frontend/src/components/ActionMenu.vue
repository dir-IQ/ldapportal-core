<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup>
/**
 * ActionMenu — row-level action buttons with overflow.
 *
 * Keeps up to {@link inlineThreshold} visible items rendered as inline
 * buttons (the old strip-of-buttons layout). When there are more items
 * than that, the first {@link inlineThreshold} still render inline and
 * the remainder fold into a kebab (⋮) dropdown. This way tables with
 * just a couple of actions stay discoverable, while ones with lots of
 * actions (Users, Directories) keep the top 3 instantly clickable and
 * hide the long tail. Callers control the split by ordering the items
 * array — put the most-used actions first.
 *
 * ### Usage
 *
 *   <ActionMenu :items="[
 *     { label: 'Disable',  onClick: () => toggleEnabled(row), variant: 'warning' },
 *     { label: 'Password', onClick: () => openResetPassword(row) },
 *     { label: 'History',  onClick: () => openHistory(row) },
 *     { label: 'Delete',   onClick: () => confirmDelete(row), danger: true },
 *   ]" :disabled="bulkSelectActive">
 *     <template #primary="{ disabled }">
 *       <button class="btn-secondary btn-compact" :disabled="disabled"
 *               @click="openEdit(row)">Edit</button>
 *     </template>
 *   </ActionMenu>
 *
 * ### Item shape
 *   {
 *     label:    string                // visible text
 *     onClick:  () => void            // click handler
 *     danger?:  boolean               // destructive; red styling + auto-divider
 *                                     //   above first danger entry in menu mode
 *     variant?: 'secondary' | 'success' | 'warning' | 'neutral'
 *                                     // non-destructive colour cue for inline
 *                                     //   rendering (menu rows stay neutral)
 *     hidden?:   boolean              // conditional hide
 *     disabled?: boolean
 *     icon?:     string               // optional emoji/glyph prefix
 *     title?:    string               // tooltip
 *   }
 *
 * ### Props
 *   inlineThreshold  number  Target cap on the *total* number of buttons
 *                            visible inline — including the primary slot,
 *                            when present. A non-empty primary slot counts
 *                            as 1 inline button, leaving N-1 item slots.
 *                            Default 3.
 *   disabled         boolean When true, every rendered button is disabled
 *                            (inline items, the kebab trigger, and — via
 *                            the scoped `disabled` slot prop — the primary
 *                            slot). Designed for "bulk-select mode" on the
 *                            parent table, where per-row actions should
 *                            yield to the bulk toolbar.
 *
 * ### Behaviour
 * - Menu closes on outside click, Escape key, or item click.
 * - Right-aligned panel so the menu doesn't overflow the table's right edge.
 * - Inline variant keeps btn-secondary/success/warning colour hints; the
 *   menu variant normalises all rows to neutral text (colour cues are lost
 *   once a user has to open a menu to see them anyway).
 */
import { ref, computed, onMounted, onBeforeUnmount, useSlots, nextTick } from 'vue'

const props = defineProps({
  items:           { type: Array,   default: () => [] },
  /** Total inline buttons (primary + items). Overflow folds into the kebab. */
  inlineThreshold: { type: Number,  default: 3 },
  /** Disable every button (inline, kebab, and primary via scoped slot prop). */
  disabled:        { type: Boolean, default: false },
  /** Aria label for the kebab trigger. */
  ariaLabel:       { type: String,  default: 'Row actions' },
})

const slots = useSlots()
/** Does the caller supply a primary-slot button? Counts as 1 inline button. */
const hasPrimary = computed(() => !!slots.primary)
/** Item slots available inline after reserving space for the primary button. */
const itemsInlineBudget = computed(() =>
  Math.max(0, props.inlineThreshold - (hasPrimary.value ? 1 : 0))
)

const open = ref(false)
const root = ref(null)
const kebabRef = ref(null)

/**
 * Document-relative coordinates for the menu panel. The panel is
 * Teleport'd to <body> (see template) so it escapes any
 * `overflow-hidden` ancestor — the parent ResultsTable wrapper
 * clips its rounded corners with overflow-hidden, which would
 * otherwise hide the menu when it extends past the table chrome.
 *
 * Using {@code position: absolute} with document-relative top/left
 * means the menu scrolls *with* the kebab (both anchored to
 * document coords). On scroll the menu would briefly disagree with
 * the kebab's viewport position, so we close on scroll while open
 * (matches the typical dropdown UX). w-48 = 12rem = 192px.
 */
const MENU_WIDTH_PX = 192
const menuPos = ref({ top: 0, left: 0 })

const visibleItems = computed(() =>
  (props.items || []).filter(i => !i.hidden)
)

const useMenu = computed(() =>
  visibleItems.value.length > itemsInlineBudget.value
)

/** First N visible items render as inline buttons (N = itemsInlineBudget). */
const inlineItems = computed(() =>
  useMenu.value
    ? visibleItems.value.slice(0, itemsInlineBudget.value)
    : visibleItems.value
)

/** Overflow — everything past the budget folds into the kebab menu. */
const overflowItems = computed(() =>
  useMenu.value ? visibleItems.value.slice(itemsInlineBudget.value) : []
)

/**
 * Build the menu render list from the overflow items, inserting a divider
 * before the first danger entry if one exists and isn't already the first
 * entry. Keeps destructive actions visually segregated from routine ones.
 */
const menuEntries = computed(() => {
  const out = []
  const items = overflowItems.value
  let dividerInserted = false
  for (let i = 0; i < items.length; i++) {
    const it = items[i]
    if (it.danger && !dividerInserted && i > 0) {
      out.push({ divider: true })
      dividerInserted = true
    }
    out.push(it)
  }
  return out
})

/** Map item → button class for inline rendering. */
function inlineClass(item) {
  if (item.danger)               return 'btn-danger-soft btn-compact'
  if (item.variant === 'success')  return 'btn-success-soft btn-compact'
  if (item.variant === 'warning')  return 'btn-warning btn-compact'
  if (item.variant === 'neutral')  return 'btn-neutral btn-compact'
  return 'btn-secondary btn-compact'
}

async function toggle() {
  if (props.disabled) return
  if (!open.value) {
    // About to open — capture the kebab's position so the
    // teleported panel renders aligned to it. Compute right-edge
    // alignment so the menu's right edge meets the kebab's right
    // edge (the long-standing right-aligned look).
    open.value = true
    await nextTick()
    if (kebabRef.value) {
      const rect = kebabRef.value.getBoundingClientRect()
      menuPos.value = {
        top: rect.bottom + window.scrollY + 4,
        left: rect.right + window.scrollX - MENU_WIDTH_PX,
      }
    }
  } else {
    open.value = false
  }
}

function close() {
  open.value = false
}

/**
 * Close on scroll. The teleported panel's coords are
 * document-relative, so it would briefly drift relative to the
 * kebab's viewport position during scroll — matches the typical
 * dropdown UX of dismissing on scroll rather than recalculating.
 * Capture phase catches scrolls on inner scrollable containers
 * (long table bodies, modal overflow), not just the window.
 */
function onScroll() {
  if (open.value) close()
}

function runItem(item) {
  if (item.disabled || props.disabled) return
  close()
  item.onClick?.()
}

function onDocClick(ev) {
  if (!open.value) return
  if (root.value && !root.value.contains(ev.target)) close()
}

function onEsc(ev) {
  if (ev.key === 'Escape') close()
}

onMounted(() => {
  document.addEventListener('click', onDocClick)
  document.addEventListener('keydown', onEsc)
  window.addEventListener('scroll', onScroll, true)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', onDocClick)
  document.removeEventListener('keydown', onEsc)
  window.removeEventListener('scroll', onScroll, true)
})
</script>

<template>
  <div ref="root" class="inline-flex items-center gap-1 justify-end whitespace-nowrap">
    <slot name="primary" :disabled="disabled" />

    <!-- Inline items: always rendered (may be the full list or a prefix of it). -->
    <button v-for="(item, idx) in inlineItems" :key="idx"
            type="button"
            :disabled="item.disabled || disabled"
            :title="item.title || ''"
            :class="inlineClass(item)"
            @click="runItem(item)">
      <span v-if="item.icon" class="mr-1">{{ item.icon }}</span>{{ item.label }}
    </button>

    <!-- Kebab: only when there are overflow items. -->
    <div v-if="useMenu" class="relative">
      <button ref="kebabRef"
              type="button"
              class="btn-secondary btn-compact action-menu-kebab"
              :disabled="disabled"
              :aria-label="ariaLabel"
              :aria-expanded="open ? 'true' : 'false'"
              aria-haspopup="menu"
              @click.stop="toggle">
        <svg viewBox="0 0 24 24" class="w-4 h-4" fill="currentColor" aria-hidden="true">
          <circle cx="12" cy="5" r="1.75" />
          <circle cx="12" cy="12" r="1.75" />
          <circle cx="12" cy="19" r="1.75" />
        </svg>
      </button>

      <!-- Teleport-to-body so the panel escapes any overflow-hidden
           ancestor (notably the ResultsTable rounded wrapper). The
           document-level click listener (onDocClick) still works
           because it checks `root.contains(target)` — and since the
           teleported panel lives outside the root, clicking inside
           it would close. So we add an explicit reverse-check via
           a click handler on the panel itself that stops
           propagation. -->
      <Teleport to="body">
        <div v-if="open"
             role="menu"
             :style="{
               position: 'absolute',
               top: menuPos.top + 'px',
               left: menuPos.left + 'px',
               width: '12rem',
             }"
             class="bg-white border border-gray-200 rounded-lg shadow-lg py-1 z-30 text-left"
             @click.stop>
          <template v-for="(entry, idx) in menuEntries" :key="idx">
            <div v-if="entry.divider" class="my-1 border-t border-gray-100" />
            <button v-else
                    type="button"
                    role="menuitem"
                    :disabled="entry.disabled"
                    :title="entry.title || ''"
                    class="w-full text-left px-3 py-1.5 text-sm transition-colors flex items-center gap-2"
                    :class="entry.danger
                      ? 'text-red-600 hover:bg-red-50 disabled:text-red-300'
                      : 'text-gray-700 hover:bg-gray-50 disabled:text-gray-300'"
                    @click="runItem(entry)">
              <span v-if="entry.icon" class="text-xs">{{ entry.icon }}</span>
              <span>{{ entry.label }}</span>
            </button>
          </template>
        </div>
      </Teleport>
    </div>
  </div>
</template>

<style scoped>
@reference "tailwindcss";

/* Square up the kebab button so it sits visually on par with a text-labelled
   compact button next to it. */
.action-menu-kebab {
  padding: 0.25rem 0.375rem;
  line-height: 1;
}
</style>
