<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div ref="bellRef">
    <!-- Bell button -->
    <button @click="toggle" class="relative p-1 rounded hover:bg-white/10 text-white/60 hover:text-white transition-colors" title="Notifications">
      <svg class="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5">
        <path stroke-linecap="round" stroke-linejoin="round" d="M14.857 17.082a23.848 23.848 0 005.454-1.31A8.967 8.967 0 0118 9.75v-.7V9A6 6 0 006 9v.75a8.967 8.967 0 01-2.312 6.022c1.733.64 3.56 1.085 5.455 1.31m5.714 0a24.255 24.255 0 01-5.714 0m5.714 0a3 3 0 11-5.714 0" />
      </svg>
      <span v-if="unreadCount > 0"
            class="absolute -top-1 -right-1 bg-red-500 text-white text-[9px] font-bold rounded-full w-4 h-4 flex items-center justify-center">
        {{ unreadCount > 9 ? '9+' : unreadCount }}
      </span>
    </button>

    <!-- Dropdown panel — teleported to body so it isn't clipped by sidebar overflow -->
    <Teleport to="body">
      <div v-if="open" ref="dropdownRef"
           class="fixed w-80 bg-white border border-gray-200 rounded-xl shadow-xl z-[100] max-h-[420px] flex flex-col"
           :style="dropdownStyle">
        <div class="px-4 py-3 border-b border-gray-100 flex items-center justify-between">
          <span class="text-sm font-semibold text-gray-900">Notifications</span>
          <button v-if="unreadCount > 0" @click="handleMarkAllRead" class="text-xs text-blue-600 hover:text-blue-800">Mark all read</button>
        </div>

        <div v-if="loading" class="p-4 text-center text-sm text-gray-500">Loading...</div>
        <div v-else-if="notifications.length === 0" class="p-6 text-center text-sm text-gray-500">No notifications</div>

        <div v-else class="overflow-y-auto flex-1">
          <button v-for="n in notifications" :key="n.id"
                  @click="handleClick(n)"
                  :class="['w-full text-left px-4 py-3 border-b border-gray-50 hover:bg-gray-50 transition-colors flex gap-3',
                    n.read ? 'opacity-60' : '']">
            <div class="shrink-0 mt-0.5">
              <span :class="['w-2 h-2 rounded-full inline-block', n.read ? 'bg-transparent' : 'bg-blue-500']" />
            </div>
            <div class="min-w-0">
              <p class="text-sm text-gray-900 font-medium truncate">{{ n.title }}</p>
              <p v-if="n.body" class="text-xs text-gray-500 truncate mt-0.5">{{ n.body }}</p>
              <p class="text-[10px] text-gray-500 mt-1">{{ formatRelative(n.createdAt) }}</p>
            </div>
          </button>
        </div>

        <div class="px-4 py-2 border-t border-gray-100 text-center">
          <RouterLink to="/notifications" @click="open = false" class="text-xs text-blue-600 hover:text-blue-800">View all</RouterLink>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { ref, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { getUnreadCount, getNotifications, markRead, markAllRead } from '@/api/notifications'

const router = useRouter()
const open = ref(false)
const bellRef = ref(null)
const dropdownRef = ref(null)
const unreadCount = ref(0)
const notifications = ref([])
const loading = ref(false)
const dropdownStyle = ref({})
let pollInterval = null

function toggle() {
  open.value = !open.value
  if (open.value) positionDropdown()
}

function positionDropdown() {
  nextTick(() => {
    if (!bellRef.value) return
    const rect = bellRef.value.getBoundingClientRect()
    // Open upward from the bell, to the right of the sidebar
    dropdownStyle.value = {
      left: rect.right + 8 + 'px',
      bottom: (window.innerHeight - rect.bottom) + 'px',
    }
  })
}

async function fetchCount() {
  try {
    const { data } = await getUnreadCount()
    unreadCount.value = data.count || 0
  } catch { /* silent */ }
}

async function fetchNotifications() {
  loading.value = true
  try {
    const { data } = await getNotifications({ page: 0, size: 15 })
    notifications.value = data.content || []
  } catch { /* silent */ }
  loading.value = false
}

async function handleClick(n) {
  if (!n.read) {
    try { await markRead(n.id) } catch { /* silent */ }
    n.read = true
    unreadCount.value = Math.max(0, unreadCount.value - 1)
  }
  open.value = false
  if (n.link) router.push(n.link)
}

async function handleMarkAllRead() {
  try { await markAllRead() } catch { /* silent */ }
  unreadCount.value = 0
  notifications.value.forEach(n => { n.read = true })
}

function handleClickOutside(e) {
  if (bellRef.value?.contains(e.target)) return
  if (dropdownRef.value?.contains(e.target)) return
  open.value = false
}

function formatRelative(iso) {
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

watch(open, (isOpen) => {
  if (isOpen) fetchNotifications()
})

onMounted(() => {
  fetchCount()
  pollInterval = setInterval(fetchCount, 30000)
  document.addEventListener('click', handleClickOutside)
})

onBeforeUnmount(() => {
  clearInterval(pollInterval)
  document.removeEventListener('click', handleClickOutside)
})
</script>
