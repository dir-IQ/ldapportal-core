<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div class="p-6">
    <h1 class="text-2xl font-bold text-gray-900 mb-6">Referential Integrity Checker</h1>
    <p class="text-sm text-gray-500 mt-1">Detect broken group membership references and orphaned entries</p>

    <!-- Configuration form -->
    <div class="bg-white border border-gray-200 rounded-xl p-5 mb-6">
      <div class="grid grid-cols-3 gap-4 mb-4">
        <div>
          <label for="icv-directory" class="block text-sm font-medium text-gray-700 mb-1">Directory</label>
          <select id="icv-directory" v-model="form.directoryId" class="input w-full">
            <option value="" disabled>{{ loadingDirs ? 'Loading…' : '— Select directory —' }}</option>
            <option v-for="d in directories" :key="d.id" :value="d.id">{{ d.displayName }}</option>
          </select>
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">Base DN</label>
          <DnPicker v-model="form.baseDn" :directory-id="form.directoryId" placeholder="dc=example,dc=com (optional)" />
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">Checks to Run</label>
          <div class="flex flex-col gap-1.5 mt-1">
            <label v-for="c in availableChecks" :key="c.value" class="flex items-center gap-2 text-sm text-gray-700">
              <input type="checkbox" v-model="form.checks" :value="c.value" class="rounded border-gray-300" />
              {{ c.label }}
            </label>
          </div>
        </div>
      </div>

      <button @click="runCheck" :disabled="!form.directoryId || !form.checks.length || running" class="btn-primary">
        {{ running ? 'Running\u2026' : 'Run Check' }}
      </button>
    </div>

    <!-- Results -->
    <div v-if="hasRun" class="bg-white border border-gray-200 rounded-xl overflow-hidden">
      <div class="px-5 py-3 border-b border-gray-200">
        <span class="text-sm text-gray-600">{{ report.issues.length }} issue{{ report.issues.length !== 1 ? 's' : '' }} found</span>
      </div>

      <div v-if="report.issues.length === 0" class="p-8 text-center text-sm text-gray-500">
        No integrity issues found. Everything looks good!
      </div>

      <template v-else>
        <!-- Group by issue type -->
        <div v-for="group in groupedIssues" :key="group.type" class="border-b border-gray-100 last:border-b-0">
          <button
            @click="toggleGroup(group.type)"
            class="w-full flex items-center justify-between px-5 py-3 hover:bg-gray-50 transition-colors"
          >
            <div class="flex items-center gap-2">
              <span class="text-sm font-semibold" :class="typeColor(group.type)">{{ typeLabel(group.type) }}</span>
              <span class="text-xs bg-gray-100 text-gray-600 px-2 py-0.5 rounded-full">{{ group.issues.length }}</span>
            </div>
            <svg class="w-4 h-4 text-gray-500 transition-transform" :class="{ 'rotate-180': expandedGroups.has(group.type) }" viewBox="0 0 20 20" fill="currentColor">
              <path fill-rule="evenodd" d="M5.23 7.21a.75.75 0 011.06.02L10 11.168l3.71-3.938a.75.75 0 111.08 1.04l-4.25 4.5a.75.75 0 01-1.08 0l-4.25-4.5a.75.75 0 01.02-1.06z" clip-rule="evenodd"/>
            </svg>
          </button>

          <div v-if="expandedGroups.has(group.type)">
            <table class="w-full text-sm">
              <thead>
                <tr class="bg-gray-50 border-y border-gray-100">
                  <th class="text-left py-2 px-5 text-xs font-semibold text-gray-500 uppercase tracking-wider w-1/2">DN</th>
                  <th class="text-left py-2 px-5 text-xs font-semibold text-gray-500 uppercase tracking-wider w-1/2">Description</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-gray-50">
                <tr v-for="(issue, i) in group.issues" :key="i" class="hover:bg-blue-50">
                  <td class="py-2 px-5 font-mono text-xs text-blue-600 break-all cursor-pointer" @click="goToBrowser(issue.dn)">
                    {{ issue.dn }}
                  </td>
                  <td class="py-2 px-5 text-xs text-gray-600 break-all">{{ issue.description }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useNotificationStore } from '@/stores/notifications'
import { listDirectories } from '@/api/directories'
import { checkIntegrity } from '@/api/browse'
import DnPicker from '@/components/DnPicker.vue'

const router = useRouter()
const notif  = useNotificationStore()

const directories = ref([])
const loadingDirs = ref(false)
const running     = ref(false)
const hasRun      = ref(false)
const report      = ref({ issues: [] })
const expandedGroups = ref(new Set())

const availableChecks = [
  { value: 'BROKEN_MEMBER',  label: 'Broken member references' },
  { value: 'ORPHANED_ENTRY', label: 'Orphaned entries (missing parent)' },
  { value: 'EMPTY_GROUP',    label: 'Empty groups (no members)' },
]

const form = ref({
  directoryId: '',
  baseDn: '',
  checks: ['BROKEN_MEMBER', 'ORPHANED_ENTRY', 'EMPTY_GROUP'],
})

const groupedIssues = computed(() => {
  const groups = new Map()
  for (const issue of report.value.issues) {
    if (!groups.has(issue.type)) {
      groups.set(issue.type, { type: issue.type, issues: [] })
    }
    groups.get(issue.type).issues.push(issue)
  }
  return [...groups.values()]
})

function typeLabel(type) {
  switch (type) {
    case 'BROKEN_MEMBER':  return 'Broken Member References'
    case 'ORPHANED_ENTRY': return 'Orphaned Entries'
    case 'EMPTY_GROUP':    return 'Empty Groups'
    default:               return type
  }
}

function typeColor(type) {
  switch (type) {
    case 'BROKEN_MEMBER':  return 'text-red-700'
    case 'ORPHANED_ENTRY': return 'text-amber-700'
    case 'EMPTY_GROUP':    return 'text-blue-700'
    default:               return 'text-gray-700'
  }
}

function toggleGroup(type) {
  const s = new Set(expandedGroups.value)
  if (s.has(type)) s.delete(type)
  else s.add(type)
  expandedGroups.value = s
}

async function runCheck() {
  if (!form.value.directoryId || !form.value.checks.length) return
  running.value = true
  hasRun.value = false
  try {
    const { data } = await checkIntegrity(form.value.directoryId, form.value.baseDn, form.value.checks)
    report.value = data
    hasRun.value = true
    // Auto-expand all groups that have issues
    expandedGroups.value = new Set(groupedIssues.value.map(g => g.type))
  } catch (e) {
    notif.error(e.response?.data?.detail || e.message)
  } finally {
    running.value = false
  }
}

function goToBrowser(dn) {
  router.push({ path: '/superadmin/browser', query: { dn } })
}

onMounted(async () => {
  loadingDirs.value = true
  try {
    const { data } = await listDirectories()
    directories.value = data.filter(d => d.directoryType !== 'ENTRA_ID')
    if (directories.value.length) form.value.directoryId = directories.value[0].id
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
