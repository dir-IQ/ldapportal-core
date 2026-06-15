<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
// Sidebar bell: shows the unread badge and opens the notification drawer.
// The list UI lives in NotificationDrawer; this component only owns the
// trigger and the polled unread count.
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { getUnreadCount } from '@/api/notifications'
import NotificationDrawer from '@/components/NotificationDrawer.vue'

const open = ref(false)
const unreadCount = ref(0)
let pollInterval: ReturnType<typeof setInterval> | null = null

async function fetchCount(): Promise<void> {
  try {
    const { data } = await getUnreadCount()
    unreadCount.value = data.count || 0
  } catch { /* silent */ }
}

onMounted(() => {
  fetchCount()
  pollInterval = setInterval(fetchCount, 30000)
})

onBeforeUnmount(() => {
  if (pollInterval) clearInterval(pollInterval)
})
</script>

<template>
  <div>
    <button
      @click="open = true"
      class="relative p-1 rounded hover:bg-white/10 text-white/60 hover:text-white transition-colors"
      title="Notifications"
      aria-label="Notifications"
    >
      <svg class="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5">
        <path stroke-linecap="round" stroke-linejoin="round" d="M14.857 17.082a23.848 23.848 0 005.454-1.31A8.967 8.967 0 0118 9.75v-.7V9A6 6 0 006 9v.75a8.967 8.967 0 01-2.312 6.022c1.733.64 3.56 1.085 5.455 1.31m5.714 0a24.255 24.255 0 01-5.714 0m5.714 0a3 3 0 11-5.714 0" />
      </svg>
      <span v-if="unreadCount > 0"
            class="absolute -top-1 -right-1 bg-red-500 text-white text-[9px] font-bold rounded-full w-4 h-4 flex items-center justify-center">
        {{ unreadCount > 9 ? '9+' : unreadCount }}
      </span>
    </button>

    <NotificationDrawer v-model="open" @changed="fetchCount" />
  </div>
</template>
