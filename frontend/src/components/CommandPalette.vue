<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <Teleport to="body">
    <div v-if="open" class="fixed inset-0 z-50 flex items-start justify-center pt-[15vh]" @click.self="open = false">
      <div class="fixed inset-0 bg-black/40" @click="open = false" />
      <div v-dialog-a11y role="dialog" aria-modal="true" aria-label="Command palette"
           class="relative w-full max-w-lg bg-white rounded-xl shadow-2xl border border-gray-200 overflow-hidden" @keydown.escape="open = false">
        <!-- Search input -->
        <div class="flex items-center gap-3 px-4 py-3 border-b border-gray-200">
          <svg class="w-5 h-5 text-gray-500 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5">
            <path stroke-linecap="round" stroke-linejoin="round" d="M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 0010.607 10.607z" />
          </svg>
          <input ref="inputRef" v-model="query" type="text" placeholder="Search pages, actions..."
                 class="flex-1 text-sm outline-none bg-transparent placeholder-gray-400"
                 @keydown.down.prevent="moveDown" @keydown.up.prevent="moveUp"
                 @keydown.enter.prevent="selectCurrent" />
          <kbd class="text-[10px] text-gray-600 bg-gray-100 px-1.5 py-0.5 rounded font-mono">ESC</kbd>
        </div>

        <!-- Results -->
        <div class="max-h-[300px] overflow-y-auto py-1" v-if="filtered.length > 0">
          <button v-for="(item, i) in filtered" :key="item.path"
                  @click="go(item)" @mouseenter="activeIndex = i"
                  :class="['w-full text-left px-4 py-2.5 flex items-center gap-3 transition-colors',
                    i === activeIndex ? 'bg-blue-50 text-blue-700' : 'text-gray-700 hover:bg-gray-50']">
            <span class="text-xs w-5 h-5 flex items-center justify-center rounded bg-gray-100 text-gray-600 shrink-0" v-html="item.icon || '&#9656;'" />
            <div class="min-w-0">
              <div class="text-sm font-medium truncate">{{ item.label }}</div>
              <div v-if="item.section" class="text-[10px] text-gray-500 truncate">{{ item.section }}</div>
            </div>
          </button>
        </div>
        <div v-else-if="query" class="px-4 py-6 text-center text-sm text-gray-500">No results for "{{ query }}"</div>

        <!-- Footer -->
        <div class="px-4 py-2 border-t border-gray-100 flex items-center gap-4 text-[10px] text-gray-500">
          <span><kbd class="bg-gray-100 px-1 rounded font-mono">↑↓</kbd> navigate</span>
          <span><kbd class="bg-gray-100 px-1 rounded font-mono">↵</kbd> select</span>
          <span><kbd class="bg-gray-100 px-1 rounded font-mono">esc</kbd> close</span>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { ref, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const open = ref(false)
const query = ref('')
const activeIndex = ref(0)
const inputRef = ref(null)

// Label map for route path segments
const LABEL_MAP = {
  'dashboard': 'Dashboard',
  'admins': 'Application Accounts',
  'directories': 'Directory Connections',
  'directory-browser': 'Directory Browser',
  'directory-search': 'Directory Search',
  'directory-schema': 'Schema Browser',
  'integrity': 'Integrity Check',
  'audit-log': 'Audit Log',
  'audit-sources': 'Audit Sources',
  'audit-reports': 'Compliance Reports',
  'auditor-links': 'Auditor Links',
  'profiles': 'Provisioning Profiles',
  'playbooks': 'Lifecycle Playbooks',
  'reports': 'Reports',
  'settings': 'Application Settings',
  'sod-policies': 'SoD Policies',
  'sod-violations': 'SoD Violations',
  'access-drift': 'Access Drift',
  'access-reviews': 'Access Reviews',
  'campaign-templates': 'Campaign Templates',
  'hr': 'HR Integration',
  'users': 'Users',
  'groups': 'Groups',
  'audit': 'Audit Log',
  'bulk': 'Bulk Import/Export',
  'approvals': 'Approvals',
  'notifications': 'Notifications',
  'cross-campaign-report': 'Cross-Campaign Report',
}

// Section map for grouping in command palette
const SECTION_MAP = {
  'superadmin/dashboard': 'Overview',
  'superadmin/directory-browser': 'Explore',
  'superadmin/directory-search': 'Explore',
  'superadmin/directory-schema': 'Explore',
  'superadmin/reports': 'Report',
  'superadmin/audit-reports': 'Report',
  'superadmin/auditor-links': 'Report',
  'settings': 'Configure',
}

// Auto-derive commands from the router
const commands = computed(() => {
  const dirId = route.params.dirId || ''
  const items = []
  const seen = new Set()

  // Walk all resolved routes in the app shell (children of '/')
  const appRoutes = router.getRoutes().filter(r => {
    const p = r.path
    // Skip parameterized detail routes, catch-alls, empty redirects, and auditor/self-service
    if (p.includes(':campaignId') || p.includes(':groupId') || p.includes(':templateId')) return false
    if (p.includes(':token') || p.includes(':requestId')) return false
    if (p === '/' || p.includes('pathMatch') || p.includes('no-access')) return false
    if (p.startsWith('/login') || p.startsWith('/oidc') || p.startsWith('/setup')) return false
    if (p.startsWith('/self-service') || p.startsWith('/register') || p.startsWith('/auditor')) return false
    return true
  })

  for (const r of appRoutes) {
    let path = r.path

    // Skip superadmin routes for non-superadmins
    if (path.includes('superadmin') && !auth.isSuperadmin) continue
    if (r.meta?.requiresSuperadmin && !auth.isSuperadmin) continue
    // Skip compliance-gated routes (access reviews, campaign templates,
    // cross-campaign reports) for deployments without the GOVERNANCE
    // entitlement — otherwise they show up in the palette only to bounce
    // back to /dashboard via the route guard, which reads as broken UX.
    if (r.meta?.requiresCompliance && !auth.isComplianceEnabled) continue
    // Same idea for the ALERTING entitlement.
    if (r.meta?.requiresAlerting && !auth.isAlertingEnabled) continue
    // ...and HR_SYNC.
    if (r.meta?.requiresHr && !auth.isHrEnabled) continue

    // For directory-scoped routes, substitute the current dirId
    if (path.includes(':dirId')) {
      if (!dirId) continue
      path = path.replace(':dirId', dirId)
    }

    if (seen.has(path)) continue
    seen.add(path)

    // Derive label from the last meaningful segment
    const segments = path.split('/').filter(Boolean)
    const lastSeg = segments[segments.length - 1]
    const label = LABEL_MAP[lastSeg] || lastSeg.replace(/-/g, ' ').replace(/\b\w/g, c => c.toUpperCase())

    // Derive section
    const pathKey = segments.filter(s => !s.match(/^[0-9a-f]{8}-/)).join('/')
    let section = SECTION_MAP[pathKey]
    if (!section) {
      if (path.includes('superadmin')) section = 'Configure'
      else if (path.includes('directories/')) section = 'Directory'
      else section = 'General'
    }

    items.push({ label, path, section })
  }

  return items
})

const filtered = computed(() => {
  if (!query.value) return commands.value
  const q = query.value.toLowerCase()
  return commands.value.filter(c =>
    c.label.toLowerCase().includes(q) ||
    (c.section && c.section.toLowerCase().includes(q))
  )
})

watch(query, () => { activeIndex.value = 0 })

watch(open, async (isOpen) => {
  if (isOpen) {
    query.value = ''
    activeIndex.value = 0
    await nextTick()
    inputRef.value?.focus()
  }
})

function moveDown() {
  activeIndex.value = Math.min(activeIndex.value + 1, filtered.value.length - 1)
}

function moveUp() {
  activeIndex.value = Math.max(activeIndex.value - 1, 0)
}

function selectCurrent() {
  const item = filtered.value[activeIndex.value]
  if (item) go(item)
}

function go(item) {
  open.value = false
  router.push(item.path)
}

function handleKeydown(e) {
  if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
    e.preventDefault()
    open.value = !open.value
  }
}

onMounted(() => document.addEventListener('keydown', handleKeydown))
onBeforeUnmount(() => document.removeEventListener('keydown', handleKeydown))

defineExpose({ open })
</script>
