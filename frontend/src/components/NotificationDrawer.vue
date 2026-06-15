<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
/**
 * Right-side notification centre. Slides in from the right edge as the single
 * surface for in-app notifications (replaces the old bell dropdown and the
 * standalone /notifications page). Opened by NotificationBell via v-model.
 *
 * Overlay conventions (see docs/frontend-conventions.md): teleported to body,
 * z-40 (below z-50 toasts), Esc/focus-trap via useDialogA11y.
 */
import { ref, computed, watch, useId } from 'vue'
import { useRouter } from 'vue-router'
import { getNotifications, markRead, markAllRead } from '@/api/notifications'
import { useDialogA11y } from '@/composables/useDialogA11y'

interface AppNotification {
  id: string | number
  title: string
  body?: string
  createdAt?: string
  read?: boolean
  link?: string
}

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  // Fired whenever read-state changes so the bell can refresh its badge count.
  changed: []
}>()

const router = useRouter()
const titleId = useId()
const panelRef = ref<HTMLElement | null>(null)

const notifications = ref<AppNotification[]>([])
const loading = ref(false)
const loadingMore = ref(false)
const page = ref(0)
const totalPages = ref(1)
const filter = ref<'all' | 'unread'>('all')
const PAGE_SIZE = 20

const filtered = computed(() =>
  filter.value === 'unread'
    ? notifications.value.filter((n) => !n.read)
    : notifications.value,
)
const hasUnread = computed(() => notifications.value.some((n) => !n.read))
const canLoadMore = computed(() => page.value < totalPages.value - 1)

function close(): void {
  emit('update:modelValue', false)
}

async function load(reset: boolean): Promise<void> {
  if (reset) { page.value = 0; loading.value = true }
  else { loadingMore.value = true }
  try {
    const { data } = await getNotifications({ page: page.value, size: PAGE_SIZE })
    const content: AppNotification[] = data.content || []
    notifications.value = reset ? content : [...notifications.value, ...content]
    totalPages.value = data.totalPages || 1
  } catch { /* silent — surfaced as an empty list */ }
  loading.value = false
  loadingMore.value = false
}

async function loadMore(): Promise<void> {
  if (!canLoadMore.value || loadingMore.value) return
  page.value += 1
  await load(false)
}

async function handleClick(n: AppNotification): Promise<void> {
  if (!n.read) {
    try { await markRead(n.id) } catch { /* silent */ }
    n.read = true
    emit('changed')
  }
  close()
  if (n.link) router.push(n.link)
}

async function handleMarkAllRead(): Promise<void> {
  try { await markAllRead() } catch { /* silent */ }
  notifications.value.forEach((n) => { n.read = true })
  emit('changed')
}

function formatRelative(iso?: string): string {
  if (!iso) return ''
  const diff = Date.now() - new Date(iso).getTime()
  const mins = Math.floor(diff / 60000)
  if (mins < 1) return 'just now'
  if (mins < 60) return `${mins}m ago`
  const hours = Math.floor(mins / 60)
  if (hours < 24) return `${hours}h ago`
  const days = Math.floor(hours / 24)
  return `${days}d ago`
}

// Fetch fresh on each open; reset the filter so it doesn't persist stale state.
// immediate so a drawer mounted already-open also loads (guarded on isOpen).
watch(() => props.modelValue, (isOpen) => {
  if (isOpen) { filter.value = 'all'; load(true) }
}, { immediate: true })

useDialogA11y({ isOpen: () => props.modelValue, containerRef: panelRef, onClose: close })
</script>

<template>
  <Teleport to="body">
    <Transition name="drawer">
      <div
        v-if="modelValue"
        class="fixed inset-0 z-40"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="titleId"
      >
        <!-- Backdrop. Non-interactive, matching AppModal: dismissal is via the
             × button and Escape (useDialogA11y), not a backdrop click. -->
        <div class="absolute inset-0 bg-black/40" />

        <!-- Panel -->
        <aside
          ref="panelRef"
          class="drawer-panel absolute inset-y-0 right-0 w-full max-w-md bg-white shadow-xl flex flex-col"
        >
          <!-- Header -->
          <div class="px-5 py-4 border-b border-gray-200 flex items-start justify-between gap-3 shrink-0">
            <div>
              <h2 :id="titleId" class="text-lg font-semibold text-gray-900">Notifications</h2>
              <p class="text-xs text-gray-500 mt-0.5">Your in-app notifications and alerts</p>
            </div>
            <button
              @click="close"
              class="text-gray-500 hover:text-gray-700 text-xl leading-none transition-colors -mt-1"
              aria-label="Close notifications"
            >&#215;</button>
          </div>

          <!-- Tabs + Mark all read -->
          <div class="px-5 py-3 border-b border-gray-200 flex items-center justify-between gap-2 shrink-0">
            <div class="flex gap-2">
              <button
                @click="filter = 'all'"
                :class="filter === 'all' ? 'btn-tab-active' : 'btn-tab'"
              >All</button>
              <button
                @click="filter = 'unread'"
                :class="filter === 'unread' ? 'btn-tab-active' : 'btn-tab'"
              >Unread</button>
            </div>
            <button
              v-if="hasUnread"
              @click="handleMarkAllRead"
              class="btn-secondary btn-sm"
            >Mark all read</button>
          </div>

          <!-- List -->
          <div class="flex-1 min-h-0 overflow-y-auto">
            <div v-if="loading" class="p-6 text-center text-sm text-gray-500">Loading…</div>

            <div v-else-if="filtered.length === 0" class="p-8 text-center">
              <p class="text-sm text-gray-500">
                {{ filter === 'unread' ? 'No unread notifications.' : 'No notifications yet.' }}
              </p>
            </div>

            <ul v-else class="divide-y divide-gray-100">
              <li v-for="n in filtered" :key="n.id">
                <button
                  @click="handleClick(n)"
                  :class="['w-full text-left px-5 py-3 flex gap-3 transition-colors',
                    n.read ? 'hover:bg-gray-50' : 'bg-blue-50/50 hover:bg-blue-50']"
                >
                  <span
                    :class="['w-2 h-2 rounded-full inline-block shrink-0 mt-1.5',
                      n.read ? 'bg-gray-300' : 'bg-blue-500']"
                  />
                  <span class="min-w-0 flex-1">
                    <span :class="['block text-sm', n.read ? 'text-gray-600' : 'text-gray-900 font-medium']">{{ n.title }}</span>
                    <span v-if="n.body" class="block text-xs text-gray-500 mt-0.5">{{ n.body }}</span>
                    <span class="block text-[10px] text-gray-500 mt-1">{{ formatRelative(n.createdAt) }}</span>
                  </span>
                </button>
              </li>
            </ul>
          </div>

          <!-- Load more -->
          <div v-if="canLoadMore" class="px-5 py-3 border-t border-gray-200 shrink-0">
            <button
              @click="loadMore"
              :disabled="loadingMore"
              class="btn-secondary btn-sm w-full"
            >{{ loadingMore ? 'Loading…' : 'Load more' }}</button>
          </div>
        </aside>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
@reference "tailwindcss";

/* Backdrop fades; panel slides in from the right. Both driven by the single
   wrapper Transition so they stay in sync. */
.drawer-enter-active,
.drawer-leave-active {
  transition: opacity 0.2s ease;
}
.drawer-enter-from,
.drawer-leave-to {
  opacity: 0;
}
.drawer-enter-active .drawer-panel,
.drawer-leave-active .drawer-panel {
  transition: transform 0.25s ease;
}
.drawer-enter-from .drawer-panel,
.drawer-leave-to .drawer-panel {
  transform: translateX(100%);
}
</style>
