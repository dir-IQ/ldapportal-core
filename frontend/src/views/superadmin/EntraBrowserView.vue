<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getDirectory } from '@/api/directories'
import { getEntraSyncStatus, triggerEntraSync, listEntraUsers, listEntraGroups } from '@/api/entra'
import { useNotificationStore } from '@/stores/notifications'
import DataTable from '@/components/DataTable.vue'

const route = useRoute()
const router = useRouter()
const notif = useNotificationStore()
const directoryId = route.params.directoryId

const directory = ref(null)
const tab = ref('users')
const loading = ref(true)
const syncing = ref(false)
const syncStatus = ref(null)
const users = ref([])
const groups = ref([])
const search = ref('')

const userCols = [
  { key: 'displayName', label: 'Name' },
  { key: 'userPrincipalName', label: 'UPN' },
  { key: 'mail', label: 'Email' },
  { key: 'accountEnabled', label: 'Enabled' },
]

const groupCols = [
  { key: 'displayName', label: 'Name' },
  { key: 'description', label: 'Description' },
  { key: 'memberCount', label: 'Members' },
]

const filteredUsers = computed(() => {
  if (!search.value) return users.value
  const q = search.value.toLowerCase()
  return users.value.filter(u =>
    (u.displayName || '').toLowerCase().includes(q) ||
    (u.userPrincipalName || '').toLowerCase().includes(q) ||
    (u.mail || '').toLowerCase().includes(q))
})

const filteredGroups = computed(() => {
  if (!search.value) return groups.value
  const q = search.value.toLowerCase()
  return groups.value.filter(g =>
    (g.displayName || '').toLowerCase().includes(q) ||
    (g.description || '').toLowerCase().includes(q))
})

function fmtDateTime(iso) {
  if (!iso) return '—'
  return new Date(iso).toLocaleString()
}

async function loadData() {
  loading.value = true
  try {
    const [dirRes, statusRes] = await Promise.all([
      getDirectory(directoryId),
      getEntraSyncStatus(directoryId),
    ])
    directory.value = dirRes.data
    syncStatus.value = statusRes.data

    if (statusRes.data.userCount > 0 || statusRes.data.groupCount > 0) {
      const [usersRes, groupsRes] = await Promise.all([
        listEntraUsers(directoryId),
        listEntraGroups(directoryId),
      ])
      users.value = usersRes.data
      groups.value = groupsRes.data
    }
  } catch (e) {
    notif.error(e.response?.data?.detail || e.message)
  } finally {
    loading.value = false
  }
}

async function doSync(full) {
  syncing.value = true
  try {
    const { data } = await triggerEntraSync(directoryId, full)
    notif.success(`Sync complete: ${data.usersSynced} users, ${data.groupsSynced} groups, ${data.auditEvents} events`)
    await loadData()
  } catch (e) {
    notif.error(e.response?.data?.detail || e.message)
  } finally {
    syncing.value = false
  }
}

onMounted(loadData)
</script>

<template>
  <div class="p-6">
    <div class="flex items-center justify-between mb-6">
      <div>
        <h1 class="text-2xl font-bold text-gray-900">Entra ID Browser</h1>
        <p class="text-sm text-gray-500 mt-1">{{ directory?.displayName || 'Loading...' }}</p>
      </div>
      <button @click="router.push('/superadmin/directories')" class="btn-neutral btn-compact">Back to Directories</button>
    </div>

    <!-- Sync Status Card -->
    <div class="bg-white border border-gray-200 rounded-xl p-5 mb-6 shadow-sm">
      <div class="flex items-center justify-between mb-3">
        <h2 class="text-sm font-semibold text-gray-700">Sync Status</h2>
        <div class="flex gap-2">
          <button @click="doSync(false)" :disabled="syncing" class="btn-secondary text-xs">
            {{ syncing ? 'Syncing...' : 'Delta Sync' }}
          </button>
          <button @click="doSync(true)" :disabled="syncing" class="btn-neutral text-xs">
            Full Sync
          </button>
        </div>
      </div>
      <div v-if="syncStatus" class="grid grid-cols-2 sm:grid-cols-4 gap-4 text-sm">
        <div>
          <p class="text-gray-500 text-xs">Last Full Sync</p>
          <p class="font-medium text-gray-900">{{ fmtDateTime(syncStatus.lastFullSync) }}</p>
        </div>
        <div>
          <p class="text-gray-500 text-xs">Last Audit Poll</p>
          <p class="font-medium text-gray-900">{{ fmtDateTime(syncStatus.auditLastPoll) }}</p>
        </div>
        <div>
          <p class="text-gray-500 text-xs">Cached Users</p>
          <p class="font-medium text-gray-900">{{ syncStatus.userCount?.toLocaleString() }}</p>
        </div>
        <div>
          <p class="text-gray-500 text-xs">Cached Groups</p>
          <p class="font-medium text-gray-900">{{ syncStatus.groupCount?.toLocaleString() }}</p>
        </div>
      </div>
      <div v-if="syncStatus && !syncStatus.lastFullSync" class="mt-3 text-sm text-amber-700 bg-amber-50 rounded-lg px-4 py-2">
        No data synced yet. Click "Full Sync" to pull users and groups from Entra ID.
      </div>
    </div>

    <!-- Tabs + Search -->
    <div class="flex items-center justify-between mb-4 border-b">
      <div class="flex gap-1">
        <button v-for="t in ['users', 'groups']" :key="t"
                :class="['px-4 py-2 text-sm font-medium border-b-2 -mb-px capitalize',
                  tab === t ? 'border-blue-600 text-blue-600' : 'border-transparent text-gray-500 hover:text-gray-700']"
                @click="tab = t">
          {{ t }} ({{ t === 'users' ? users.length : groups.length }})
        </button>
      </div>
      <div class="pb-2">
        <input v-model="search" type="text" placeholder="Search..." aria-label="Search" class="input input-sm text-xs w-48" />
      </div>
    </div>

    <!-- Loading -->
    <div v-if="loading" class="text-center py-12 text-gray-500">Loading...</div>

    <!-- Users Table -->
    <div v-else-if="tab === 'users'">
      <DataTable :columns="userCols" :rows="filteredUsers" :loading="loading" row-key="objectId"
                 empty-text="No users synced" empty-icon="users">
        <template #cell-accountEnabled="{ value }">
          <span :class="value ? 'text-green-600' : 'text-red-600'">{{ value ? 'Yes' : 'No' }}</span>
        </template>
      </DataTable>
    </div>

    <!-- Groups Table -->
    <div v-else>
      <DataTable :columns="groupCols" :rows="filteredGroups" :loading="loading" row-key="objectId"
                 empty-text="No groups synced" empty-icon="users">
        <template #cell-memberCount="{ value }">
          <span class="font-medium">{{ value }}</span>
        </template>
      </DataTable>
    </div>
  </div>
</template>

<style scoped>
@reference "tailwindcss";
</style>
