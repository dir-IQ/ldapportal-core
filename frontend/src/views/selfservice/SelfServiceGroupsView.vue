<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div>
    <div class="mb-6">
      <h1 class="text-2xl font-bold text-gray-900">My Groups</h1>
      <p class="text-sm text-gray-500 mt-1">Groups you are a member of in the directory</p>
    </div>

    <!-- Loading -->
    <div v-if="loading" class="text-center text-gray-500 text-sm py-8">Loading groups...</div>

    <!-- Error -->
    <div v-else-if="errorMsg" class="bg-red-50 border border-red-200 text-red-700 rounded-lg px-4 py-3 text-sm">
      {{ errorMsg }}
    </div>

    <template v-else>
      <!-- Search -->
      <div class="mb-4">
        <input v-model="search" type="text" placeholder="Filter groups..." aria-label="Filter groups"
          class="w-full max-w-sm border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500" />
      </div>

      <!-- Groups list -->
      <div class="bg-white rounded-lg border shadow-sm">
        <div v-for="(group, i) in filteredGroups" :key="group.dn"
          class="px-5 py-4 flex items-center gap-4"
          :class="{ 'border-t border-gray-100': i > 0 }">
          <div class="w-10 h-10 bg-gray-100 text-gray-600 rounded-lg flex items-center justify-center shrink-0">
            <svg class="w-5 h-5" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
              <circle cx="7.5" cy="6" r="2.75"/>
              <circle cx="13.5" cy="6" r="2.75"/>
              <path d="M1.5 17c0-3.04 2.46-5.5 5.5-5.5 1.26 0 2.42.42 3.35 1.14M12 11.64A5.48 5.48 0 0 1 18.5 17"/>
            </svg>
          </div>
          <div class="flex-1 min-w-0">
            <p class="text-sm font-medium text-gray-900">{{ group.name }}</p>
            <p class="text-xs text-gray-500 truncate">{{ group.dn }}</p>
          </div>
          <span v-if="group.description" class="text-xs text-gray-500 hidden md:block max-w-xs truncate">
            {{ group.description }}
          </span>
        </div>

        <div v-if="filteredGroups.length === 0" class="px-5 py-8 text-center text-gray-500 text-sm">
          {{ search ? 'No groups match your search.' : 'You are not a member of any groups.' }}
        </div>
      </div>

      <p class="text-xs text-gray-500 mt-3">
        {{ groups.length }} group{{ groups.length !== 1 ? 's' : '' }} total.
        Group membership is managed by your directory administrators.
      </p>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { getGroups } from '@/api/selfservice'

const search = ref('')
const loading = ref(true)
const errorMsg = ref('')
const groups = ref([])

onMounted(async () => {
  try {
    const { data } = await getGroups()
    groups.value = data
  } catch (e) {
    if (e.response?.status !== 401) {
      errorMsg.value = e.response?.data?.detail || 'Failed to load groups'
    }
  } finally {
    loading.value = false
  }
})

const filteredGroups = computed(() => {
  if (!search.value) return groups.value
  const q = search.value.toLowerCase()
  return groups.value.filter(g =>
    g.name.toLowerCase().includes(q) || g.dn.toLowerCase().includes(q)
  )
})
</script>
