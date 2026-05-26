<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <PageContainer variant="form">
    <div class="flex items-center justify-between mb-6">
      <div>
        <h1 class="text-2xl font-bold text-gray-900">Notifications</h1>
        <p class="text-sm text-gray-500 mt-1">Your in-app notifications and alerts</p>
      </div>
      <button v-if="hasUnread" @click="handleMarkAllRead" class="btn-secondary">Mark all read</button>
    </div>

    <!-- Filter tabs -->
    <div class="flex gap-2 mb-4">
      <button @click="filter = 'all'" :class="tabClass('all')">All</button>
      <button @click="filter = 'unread'" :class="tabClass('unread')">Unread</button>
    </div>

    <div v-if="loading && !notifications.length" class="text-sm text-gray-500 py-8 text-center">Loading...</div>

    <div v-else-if="filtered.length === 0" class="bg-gray-50 border border-gray-200 rounded-xl p-8 text-center">
      <p class="text-sm text-gray-500">{{ filter === 'unread' ? 'No unread notifications.' : 'No notifications yet.' }}</p>
    </div>

    <div v-else class="space-y-1">
      <div v-for="n in filtered" :key="n.id"
           @click="handleClick(n)"
           :class="['flex gap-3 px-4 py-3 rounded-lg cursor-pointer transition-colors',
             n.read ? 'hover:bg-gray-50' : 'bg-blue-50/50 hover:bg-blue-50']">
        <div class="shrink-0 mt-1">
          <span :class="['w-2 h-2 rounded-full inline-block', n.read ? 'bg-gray-300' : 'bg-blue-500']" />
        </div>
        <div class="flex-1 min-w-0">
          <p :class="['text-sm', n.read ? 'text-gray-600' : 'text-gray-900 font-medium']">{{ n.title }}</p>
          <p v-if="n.body" class="text-xs text-gray-500 mt-0.5">{{ n.body }}</p>
          <p class="text-[10px] text-gray-500 mt-1">{{ formatDate(n.createdAt) }}</p>
        </div>
      </div>
    </div>

    <!-- Pagination -->
    <div v-if="totalPages > 1" class="flex items-center justify-between mt-4">
      <span class="text-xs text-gray-500">Page {{ page + 1 }} of {{ totalPages }}</span>
      <div class="flex gap-1">
        <button @click="loadPage(page - 1)" :disabled="page === 0" class="btn-sm">Prev</button>
        <button @click="loadPage(page + 1)" :disabled="page >= totalPages - 1" class="btn-sm">Next</button>
      </div>
    </div>
  </PageContainer>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getNotifications, markRead, markAllRead } from '@/api/notifications'
import PageContainer from '@/components/PageContainer.vue'

const router = useRouter()
const loading = ref(true)
const notifications = ref([])
const page = ref(0)
const totalPages = ref(1)
const filter = ref('all')

const filtered = computed(() => {
  if (filter.value === 'unread') return notifications.value.filter(n => !n.read)
  return notifications.value
})

const hasUnread = computed(() => notifications.value.some(n => !n.read))

function tabClass(value) {
  return ['px-3 py-1.5 text-xs rounded-lg border transition-colors',
    filter.value === value
      ? 'bg-blue-50 border-blue-300 text-blue-700 font-medium'
      : 'border-gray-200 text-gray-600 hover:bg-gray-50'
  ].join(' ')
}

function formatDate(iso) {
  if (!iso) return ''
  return new Date(iso).toLocaleString('en-US', {
    year: 'numeric', month: 'short', day: 'numeric',
    hour: '2-digit', minute: '2-digit',
  })
}

async function handleClick(n) {
  if (!n.read) {
    try { await markRead(n.id) } catch { /* silent */ }
    n.read = true
  }
  if (n.link) router.push(n.link)
}

async function handleMarkAllRead() {
  try { await markAllRead() } catch { /* silent */ }
  notifications.value.forEach(n => { n.read = true })
}

async function loadPage(p) {
  page.value = p
  loading.value = true
  try {
    const { data } = await getNotifications({ page: p, size: 20 })
    notifications.value = data.content || []
    totalPages.value = data.totalPages || 1
  } catch { /* silent */ }
  loading.value = false
}

onMounted(() => loadPage(0))
</script>
