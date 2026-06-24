<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <PageContainer>
    <h1 class="text-2xl font-bold text-gray-900 mb-6">Schema Browser</h1>
    <p class="text-sm text-gray-500 mt-1">Browse LDAP schema object classes and attribute types</p>

    <!-- Directory picker -->
    <div class="mb-6">
      <label class="block text-sm font-medium text-gray-700 mb-1">Directory</label>
      <select v-model="selectedDirId" class="input w-64">
        <option v-if="loadingDirs" value="" disabled>Loading…</option>
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
        <div v-else ref="listEl" class="bg-white border border-gray-200 rounded-xl overflow-hidden max-h-[60vh] overflow-y-auto">
          <div v-if="filteredList.length === 0" class="p-4 text-sm text-gray-500 text-center">Nothing found.</div>
          <button
            v-for="item in filteredList"
            :key="item.name"
            :data-selected="selected === item.name || undefined"
            @click="navigateTo(activeTab, item.name)"
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
          <p v-if="detail.oid" class="text-[13px] text-gray-500 font-mono mb-4">{{ detail.oid }}</p>
          <div v-else class="mb-3"></div>

          <!-- Object class detail -->
          <template v-if="oc">
            <div v-if="oc.required?.length" class="mb-4">
              <p class="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">Required Attributes</p>
              <div class="flex flex-wrap gap-1">
                <button v-for="a in oc.required" :key="a"
                  @click="navigateTo('attributeTypes', a)"
                  class="text-[13px] bg-red-50 text-red-700 rounded px-2 py-0.5 font-mono hover:bg-red-100 hover:underline cursor-pointer transition-colors inline-flex items-center gap-0.5">{{ a }}<svg class="w-2.5 h-2.5 opacity-40" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 1h7v7M11 1 5 7"/></svg></button>
              </div>
            </div>
            <div v-if="oc.optional?.length">
              <p class="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">Optional Attributes</p>
              <div class="flex flex-wrap gap-1">
                <button v-for="a in oc.optional" :key="a"
                  @click="navigateTo('attributeTypes', a)"
                  class="text-[13px] bg-gray-100 text-gray-700 rounded px-2 py-0.5 font-mono hover:bg-gray-200 hover:underline cursor-pointer transition-colors inline-flex items-center gap-0.5">{{ a }}<svg class="w-2.5 h-2.5 opacity-40" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 1h7v7M11 1 5 7"/></svg></button>
              </div>
            </div>
            <div v-if="!oc.required?.length && !oc.optional?.length" class="text-sm text-gray-500">
              No attribute information available.
            </div>
          </template>

          <!-- Attribute type detail -->
          <template v-else-if="attr">
            <!-- Facts -->
            <dl class="grid grid-cols-[9rem_1fr] gap-x-4 gap-y-2 text-sm mb-5">
              <dt class="text-xs font-semibold text-gray-500 uppercase tracking-wider self-center">Syntax</dt>
              <dd class="text-gray-800">
                <template v-if="attr.syntax">
                  <span>{{ attr.syntax.description || 'Unknown syntax' }}</span>
                  <span class="text-gray-400 font-mono text-xs ml-2">{{ syntaxOidLabel }}</span>
                </template>
                <span v-else class="text-gray-400">—</span>
              </dd>

              <dt class="text-xs font-semibold text-gray-500 uppercase tracking-wider self-center">Single-valued</dt>
              <dd class="text-gray-800">{{ attr.singleValued ? 'Yes' : 'No (multi-valued)' }}</dd>

              <template v-if="attr.description">
                <dt class="text-xs font-semibold text-gray-500 uppercase tracking-wider self-center">Description</dt>
                <dd class="text-gray-800">{{ attr.description }}</dd>
              </template>
            </dl>

            <!-- Used-by (reverse index) -->
            <div v-if="usedBy.length">
              <div class="flex items-center justify-between mb-2">
                <p class="text-xs font-semibold text-gray-500 uppercase tracking-wider">Used by object classes</p>
                <!-- Direct vs inherited selector -->
                <div class="flex gap-0.5 bg-gray-100 p-0.5 rounded-md text-xs">
                  <button @click="usageScope = 'direct'"
                    :class="usageScope === 'direct' ? 'bg-white text-gray-900 shadow-sm' : 'text-gray-500 hover:text-gray-700'"
                    class="px-2 py-0.5 rounded transition-colors">Direct</button>
                  <button @click="usageScope = 'all'"
                    :class="usageScope === 'all' ? 'bg-white text-gray-900 shadow-sm' : 'text-gray-500 hover:text-gray-700'"
                    class="px-2 py-0.5 rounded transition-colors">Incl. inherited</button>
                </div>
              </div>

              <div v-if="requiredBy.length" class="mb-3">
                <p class="text-[11px] font-semibold text-gray-400 uppercase tracking-wider mb-1">Required (MUST)</p>
                <div class="flex flex-wrap gap-1">
                  <button v-for="u in requiredBy" :key="u.objectClass"
                    @click="navigateTo('objectClasses', u.objectClass)"
                    class="text-[13px] bg-red-50 text-red-700 rounded px-2 py-0.5 font-mono hover:bg-red-100 hover:underline cursor-pointer transition-colors inline-flex items-center gap-0.5">{{ u.objectClass }}<span v-if="u.inherited" class="opacity-40" title="Inherited from a superclass">↑</span></button>
                </div>
              </div>

              <div v-if="optionalBy.length">
                <p class="text-[11px] font-semibold text-gray-400 uppercase tracking-wider mb-1">Optional (MAY)</p>
                <div class="flex flex-wrap gap-1">
                  <button v-for="u in optionalBy" :key="u.objectClass"
                    @click="navigateTo('objectClasses', u.objectClass)"
                    class="text-[13px] bg-gray-100 text-gray-700 rounded px-2 py-0.5 font-mono hover:bg-gray-200 hover:underline cursor-pointer transition-colors inline-flex items-center gap-0.5">{{ u.objectClass }}<span v-if="u.inherited" class="opacity-40" title="Inherited from a superclass">↑</span></button>
                </div>
              </div>

              <p v-if="!requiredBy.length && !optionalBy.length" class="text-sm text-gray-500">
                No object class declares this attribute directly — switch to “Incl. inherited”.
              </p>
            </div>
            <div v-else class="text-sm text-gray-500">Not used by any object class.</div>
          </template>
        </div>

        <button v-if="navStack.length && detail"
          @click="goBack()"
          class="mt-3 text-sm text-blue-600 hover:text-blue-800 flex items-center gap-1">
          &larr; Back to {{ navStack[navStack.length - 1].name }}
        </button>
      </div>
    </div>
  </PageContainer>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, nextTick } from 'vue'
import { useNotificationStore } from '@/stores/notifications'
import { listDirectories } from '@/api/directories'
import { listObjectClasses, getObjectClass, listAttributeTypes, getAttributeType } from '@/api/schema'
import PageContainer from '@/components/PageContainer.vue'

type TabKey = 'objectClasses' | 'attributeTypes'

interface Directory {
  id: string
  displayName: string
  directoryType?: string
}
interface SchemaListItem {
  name: string
  oid?: string | null
}
interface ObjectClassDetail {
  oid?: string | null
  required?: string[]
  optional?: string[]
}
interface SyntaxInfo {
  oid: string
  description?: string | null
  maxLength?: number | null
}
interface AttributeUsage {
  objectClass: string
  required: boolean
  inherited: boolean
}
interface AttributeDetail {
  oid?: string | null
  description?: string | null
  singleValued?: boolean
  syntax?: SyntaxInfo | null
  usedBy?: AttributeUsage[]
}
type Detail = ObjectClassDetail | AttributeDetail
/** A point we can return to — where the user was before following a chip. */
interface NavEntry {
  tab: TabKey
  name: string
}

const notif = useNotificationStore()

const tabs: { key: TabKey, label: string }[] = [
  { key: 'objectClasses',  label: 'Object Classes' },
  { key: 'attributeTypes', label: 'Attribute Types' },
]

const directories   = ref<Directory[]>([])
const loadingDirs   = ref(false)
const selectedDirId = ref('')

const activeTab   = ref<TabKey>('objectClasses')
const search      = ref('')
const allItems    = ref<SchemaListItem[]>([])
const listLoading = ref(false)
const selected    = ref<string | null>(null)
const detail      = ref<Detail | null>(null)
const detailLoading = ref(false)
// The scrollable list container, so the selected row can be brought back into
// view after a cross-tab jump reloads (and resets) the list to the top.
const listEl = ref<HTMLElement | null>(null)
// Cross-type navigation history (object class ↔ attribute), so following a
// chip can always be walked back, to any depth.
const navStack = ref<NavEntry[]>([])
// "Direct" shows only object classes that declare the attribute themselves;
// "all" also includes ones that inherit it from a superclass.
const usageScope = ref<'direct' | 'all'>('direct')

const filteredList = computed<SchemaListItem[]>(() => {
  const q = search.value.toLowerCase()
  if (!q) return allItems.value
  return allItems.value.filter(item =>
    item.name.toLowerCase().includes(q) || (item.oid != null && item.oid.includes(q)),
  )
})

// Narrow the active detail by tab so the template can read the right shape.
const oc = computed<ObjectClassDetail | null>(() =>
  activeTab.value === 'objectClasses' ? (detail.value as ObjectClassDetail | null) : null)
const attr = computed<AttributeDetail | null>(() =>
  activeTab.value === 'attributeTypes' ? (detail.value as AttributeDetail | null) : null)

// OID plus its {length} bound, e.g. "1.3.…15{128}". Built here rather than in
// the template so the literal braces don't trip the template compiler.
const syntaxOidLabel = computed(() => {
  const s = attr.value?.syntax
  if (!s) return ''
  return s.maxLength != null ? `${s.oid}{${s.maxLength}}` : s.oid
})

const usedBy = computed<AttributeUsage[]>(() => attr.value?.usedBy ?? [])
const inScope = (u: AttributeUsage) => usageScope.value === 'all' || !u.inherited
const requiredBy = computed(() => usedBy.value.filter(u => u.required && inScope(u)))
const optionalBy = computed(() => usedBy.value.filter(u => !u.required && inScope(u)))

// Reload object classes / attribute types when the directory changes.
watch(selectedDirId, () => {
  navStack.value = []
  if (selectedDirId.value) loadList()
})

function apiError(e: unknown): string {
  const err = e as { response?: { data?: { detail?: string } }, message?: string }
  return err.response?.data?.detail || err.message || 'Request failed'
}

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
      ? data.map((d: SchemaListItem | string) => typeof d === 'string' ? { name: d, oid: null } : d)
      : []
  } catch (e) {
    notif.error(apiError(e))
  } finally {
    listLoading.value = false
  }
}

async function loadDetail(name: string) {
  if (!selectedDirId.value) return
  selected.value = name
  detail.value = null
  detailLoading.value = true
  try {
    const fn = activeTab.value === 'objectClasses' ? getObjectClass : getAttributeType
    const { data } = await fn(selectedDirId.value, name)
    detail.value = data
  } catch (e) {
    notif.error(apiError(e))
  } finally {
    detailLoading.value = false
  }
  scrollSelectedIntoView()
}

/**
 * Bring the selected list row into view. After following a chip across tabs
 * (and after the back link), the list was reloaded and reset to the top, so
 * the active item would otherwise be scrolled off-screen. {@code 'nearest'}
 * means an already-visible row (e.g. a direct click) isn't moved.
 */
async function scrollSelectedIntoView() {
  await nextTick()
  const el = listEl.value?.querySelector<HTMLElement>('[data-selected]')
  el?.scrollIntoView?.({ block: 'nearest' })
}

function switchTab(tab: TabKey) {
  if (tab === activeTab.value) return
  activeTab.value = tab
  search.value = ''
  navStack.value = []
  if (selectedDirId.value) loadList()
}

/**
 * Open {@code name} in {@code tab}. When that means crossing from one tab to
 * the other (following a chip), remember where we were so {@link goBack} can
 * return there.
 */
async function navigateTo(tab: TabKey, name: string) {
  const crossing = tab !== activeTab.value
  if (crossing && selected.value) {
    navStack.value.push({ tab: activeTab.value, name: selected.value })
  }
  if (crossing) {
    activeTab.value = tab
    search.value = ''
    await loadList()
  }
  await loadDetail(name)
}

async function goBack() {
  const entry = navStack.value.pop()
  if (!entry) return
  if (entry.tab !== activeTab.value) {
    activeTab.value = entry.tab
    search.value = ''
    await loadList()
  }
  await loadDetail(entry.name)
}

onMounted(async () => {
  loadingDirs.value = true
  try {
    const { data } = await listDirectories()
    directories.value = (data as Directory[]).filter(d => d.directoryType !== 'ENTRA_ID')
    if (directories.value.length) {
      selectedDirId.value = directories.value[0].id
    }
  } catch (e) {
    notif.error(apiError(e))
  } finally {
    loadingDirs.value = false
  }
})
</script>

<style scoped>
@reference "tailwindcss";
</style>
