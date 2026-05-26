<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <PageContainer>
    <h1 class="text-2xl font-bold text-gray-900 mb-6">Schema Browser</h1>
    <p class="text-sm text-gray-500 mt-1">Browse LDAP schema object classes and attribute types</p>

    <!-- Directory picker -->
    <div class="mb-6">
      <label class="block text-sm font-medium text-gray-700 mb-1">Directory</label>
      <select v-model="selectedDirId" class="input w-64">
        <option value="" disabled>{{ loadingDirs ? 'Loading…' : '— Select directory —' }}</option>
        <option v-for="d in directories" :key="d.id" :value="d.id">{{ d.displayName }}</option>
      </select>
    </div>

    <!-- Tabs -->
    <div class="flex gap-1 mb-6 bg-gray-100 p-1 rounded-lg w-fit">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        @click="switchTab(tab.key)"
        :class="activeTab === tab.key
          ? 'bg-white text-gray-900 shadow-sm'
          : 'text-gray-500 hover:text-gray-700'"
        class="px-4 py-1.5 rounded-md text-sm font-medium transition-colors"
      >{{ tab.label }}</button>
    </div>

    <div class="flex gap-4">
      <!-- Left panel: list -->
      <div class="w-64 shrink-0">
        <input
          v-model="search"
          type="text"
          placeholder="Filter…"
          class="input w-full mb-3"
        />
        <div v-if="listLoading" class="text-sm text-gray-500 text-center py-4">Loading…</div>
        <div v-else class="bg-white border border-gray-200 rounded-xl overflow-hidden max-h-[60vh] overflow-y-auto">
          <div v-if="filteredList.length === 0" class="p-4 text-sm text-gray-500 text-center">Nothing found.</div>
          <button
            v-for="item in filteredList"
            :key="item.name"
            @click="loadDetail(item.name)"
            :class="selected === item.name ? 'bg-blue-50 text-blue-700 font-medium' : 'text-gray-700 hover:bg-gray-50'"
            class="w-full text-left px-3 py-2 text-sm border-b border-gray-50 last:border-0 font-mono"
          >{{ item.name }}</button>
        </div>
      </div>

      <!-- Right panel: detail -->
      <div class="flex-1">
        <div v-if="!selected" class="text-sm text-gray-500 mt-8 text-center">
          Select an item from the list to see details.
        </div>
        <div v-else-if="detailLoading" class="text-sm text-gray-500 mt-8 text-center">Loading…</div>
        <div v-else-if="detail" class="bg-white border border-gray-200 rounded-xl p-5">
          <h2 class="text-lg font-semibold text-gray-900 font-mono mb-1">{{ selected }}</h2>
          <p v-if="detail.oid" class="text-xs text-gray-500 font-mono mb-4">{{ detail.oid }}</p>
          <div v-else class="mb-3"></div>

          <!-- Object class detail -->
          <template v-if="activeTab === 'objectClasses'">
            <div v-if="detail.required?.length" class="mb-4">
              <p class="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">Required Attributes</p>
              <div class="flex flex-wrap gap-1">
                <button v-for="a in detail.required" :key="a"
                  @click="navigateToAttribute(a)"
                  class="text-xs bg-red-50 text-red-700 rounded px-2 py-0.5 font-mono hover:bg-red-100 hover:underline cursor-pointer transition-colors inline-flex items-center gap-0.5">{{ a }}<svg class="w-2.5 h-2.5 opacity-40" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 1h7v7M11 1 5 7"/></svg></button>
              </div>
            </div>
            <div v-if="detail.optional?.length">
              <p class="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">Optional Attributes</p>
              <div class="flex flex-wrap gap-1">
                <button v-for="a in detail.optional" :key="a"
                  @click="navigateToAttribute(a)"
                  class="text-xs bg-gray-100 text-gray-700 rounded px-2 py-0.5 font-mono hover:bg-gray-200 hover:underline cursor-pointer transition-colors inline-flex items-center gap-0.5">{{ a }}<svg class="w-2.5 h-2.5 opacity-40" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 1h7v7M11 1 5 7"/></svg></button>
              </div>
            </div>
            <div v-if="!detail.required?.length && !detail.optional?.length" class="text-sm text-gray-500">
              No attribute information available.
            </div>
          </template>

          <!-- Attribute type detail -->
          <template v-else>
            <table class="w-full text-sm">
              <tbody class="divide-y divide-gray-50">
                <tr v-for="[k, v] in detailRows" :key="k" class="py-2">
                  <td class="py-2 pr-4 text-xs font-semibold text-gray-500 uppercase tracking-wider w-40">{{ k }}</td>
                  <td class="py-2 text-gray-800 font-mono text-xs">{{ v }}</td>
                </tr>
              </tbody>
            </table>
          </template>
        </div>
        <button v-if="parentObjectClass && activeTab === 'attributeTypes' && detail"
          @click="navigateBackToObjectClass()"
          class="mt-3 text-sm text-blue-600 hover:text-blue-800 flex items-center gap-1">
          &larr; Back to {{ parentObjectClass }}
        </button>
      </div>
    </div>
  </PageContainer>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useNotificationStore } from '@/stores/notifications'
import { listDirectories } from '@/api/directories'
import { listObjectClasses, getObjectClass, listAttributeTypes, getAttributeType } from '@/api/schema'
import PageContainer from '@/components/PageContainer.vue'

const notif = useNotificationStore()

const tabs = [
  { key: 'objectClasses',  label: 'Object Classes' },
  { key: 'attributeTypes', label: 'Attribute Types' },
]

const directories   = ref([])
const loadingDirs   = ref(false)
const selectedDirId = ref('')

const activeTab   = ref('objectClasses')
const search      = ref('')
const allItems    = ref([])
const listLoading = ref(false)
const selected    = ref(null)
const detail      = ref(null)
const detailLoading = ref(false)
const parentObjectClass = ref(null)

const filteredList = computed(() => {
  const q = search.value.toLowerCase()
  if (!q) return allItems.value
  return allItems.value.filter(item =>
    item.name.toLowerCase().includes(q) || (item.oid && item.oid.includes(q))
  )
})

const detailRows = computed(() => {
  if (!detail.value || activeTab.value !== 'attributeTypes') return []
  const skip = new Set(['name'])
  return Object.entries(detail.value)
    .filter(([k]) => !skip.has(k))
    .map(([k, v]) => [k, v == null ? '—' : String(v)])
})

// Reload object classes / attribute types when directory changes
watch(selectedDirId, () => {
  if (selectedDirId.value) loadList()
})

async function loadList() {
  if (!selectedDirId.value) return
  listLoading.value = true
  selected.value = null
  detail.value = null
  allItems.value = []
  try {
    const fn = activeTab.value === 'objectClasses' ? listObjectClasses : listAttributeTypes
    const { data } = await fn(selectedDirId.value)
    allItems.value = Array.isArray(data)
      ? data.map(d => typeof d === 'string' ? { name: d, oid: null } : d)
      : data
  } catch (e) {
    notif.error(e.response?.data?.detail || e.message)
  } finally {
    listLoading.value = false
  }
}

async function loadDetail(name) {
  if (!selectedDirId.value) return
  selected.value = name
  detail.value = null
  detailLoading.value = true
  try {
    const fn = activeTab.value === 'objectClasses' ? getObjectClass : getAttributeType
    const { data } = await fn(selectedDirId.value, name)
    detail.value = data
  } catch (e) {
    notif.error(e.response?.data?.detail || e.message)
  } finally {
    detailLoading.value = false
  }
}

function switchTab(tab) {
  activeTab.value = tab
  search.value = ''
  parentObjectClass.value = null
  if (selectedDirId.value) loadList()
}

async function navigateToAttribute(name) {
  parentObjectClass.value = selected.value
  activeTab.value = 'attributeTypes'
  search.value = ''
  await loadList()
  loadDetail(name)
}

async function navigateBackToObjectClass() {
  const name = parentObjectClass.value
  parentObjectClass.value = null
  activeTab.value = 'objectClasses'
  search.value = ''
  await loadList()
  loadDetail(name)
}

onMounted(async () => {
  loadingDirs.value = true
  try {
    const { data } = await listDirectories()
    directories.value = data.filter(d => d.directoryType !== 'ENTRA_ID')
    if (directories.value.length) {
      selectedDirId.value = directories.value[0].id
    }
  } catch (e) {
    notif.error(e.response?.data?.detail || e.message)
  } finally {
    loadingDirs.value = false
  }
})
</script>

<style scoped>
@reference "tailwindcss";
</style>
