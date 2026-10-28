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
            <option v-if="loadingDirs" value="" disabled>Loading…</option>
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

      <EmptyState
        v-if="report.issues.length === 0"
        icon="shield"
        title="No integrity issues found. Everything looks good!"
      />

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
            <!-- Broken references can be removed from their groups (re-checked server-side first). -->
            <div v-if="group.type === 'BROKEN_MEMBER' && canRemove"
                 class="flex items-center justify-between gap-3 px-5 py-2 bg-red-50 border-t border-red-100">
              <span class="text-xs text-gray-600">
                {{ selectedCount }} of {{ removableIssues.length }} selected
              </span>
              <button class="btn-danger btn-sm" :disabled="!selectedCount || removing"
                      @click="confirmRemove">
                {{ removing ? 'Removing\u2026' : 'Remove broken references' }}
              </button>
            </div>
            <table class="w-full text-sm">
              <thead>
                <tr class="bg-gray-50 border-y border-gray-100">
                  <th v-if="group.type === 'BROKEN_MEMBER' && canRemove" class="w-8 py-2 pl-5">
                    <input type="checkbox" class="rounded border-gray-300" aria-label="Select all broken references"
                           :checked="allSelected" :indeterminate="selectedCount > 0 && !allSelected"
                           @change="toggleAll(($event.target as HTMLInputElement).checked)" />
                  </th>
                  <th class="text-left py-2 px-5 text-xs font-semibold text-gray-500 uppercase tracking-wider w-1/2">DN</th>
                  <th class="text-left py-2 px-5 text-xs font-semibold text-gray-500 uppercase tracking-wider w-1/2">Description</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-gray-50">
                <tr v-for="(issue, i) in group.issues" :key="i" class="hover:bg-blue-50">
                  <td v-if="group.type === 'BROKEN_MEMBER' && canRemove" class="w-8 py-2 pl-5">
                    <input v-if="isRemovable(issue)" type="checkbox" class="rounded border-gray-300"
                           :aria-label="`Select ${issue.value} in ${issue.dn}`"
                           :checked="selected.has(issueKey(issue))" @change="toggle(issue)" />
                  </td>
                  <td class="py-2 px-5 font-mono text-[13px] text-blue-600 break-all cursor-pointer" @click="goToBrowser(issue.dn)">
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

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useNotificationStore } from '@/stores/notifications'
import { useAuthStore } from '@/stores/auth'
import { useConfirm } from '@/composables/useConfirm'
import { listDirectories } from '@/api/directories'
import { checkIntegrity, removeBrokenReferences } from '@/api/browse'
import DnPicker from '@/components/DnPicker.vue'
import EmptyState from '@/components/EmptyState.vue'
import type { components } from '@/api/openapi'

type Directory = components['schemas']['DirectorySummaryResponse']

interface IntegrityIssue {
  type: string
  dn: string
  description: string
  /** BROKEN_MEMBER only: the attribute holding the reference, and the missing DN as stored. */
  attribute?: string | null
  value?: string | null
}

type RemoveStatus = 'REMOVED' | 'KEPT_ENTRY_EXISTS' | 'ALREADY_GONE' | 'FAILED'

interface RemoveOutcome {
  groupDn: string
  attribute: string
  value: string
  status: RemoveStatus
  message: string | null
}

interface RemoveResult {
  removed: number
  outcomes: RemoveOutcome[]
}

interface IntegrityReport {
  issues: IntegrityIssue[]
}

interface IssueGroup {
  type: string
  issues: IntegrityIssue[]
}

interface CheckForm {
  directoryId: string
  baseDn: string
  checks: string[]
}

// Repo-standard axios/native error narrowing (see docs/frontend-conventions.md).
function errMsg(e: unknown, fallback = 'Something went wrong'): string {
  const err = e as { response?: { data?: { detail?: string } }; message?: string }
  return err.response?.data?.detail || err.message || fallback
}

const router = useRouter()
const notif  = useNotificationStore()
const auth   = useAuthStore()
const confirm = useConfirm()

// Removing references writes to the directory, which takes MANAGE_DIRECTORY_DATA
// (the API enforces it too); running the check doesn't.
const canRemove = computed<boolean>(() => auth.hasSuperadminPermission('superadmin.manage_directory_data'))

const directories = ref<Directory[]>([])
const loadingDirs = ref(false)
const running     = ref(false)
const hasRun      = ref(false)
const report      = ref<IntegrityReport>({ issues: [] })
const expandedGroups = ref<Set<string>>(new Set())
// The report and directory the selection belongs to: the check that produced it.
const checkedDirectoryId = ref<string>('')
const selected = ref<Set<string>>(new Set())
const removing = ref<boolean>(false)

const availableChecks = [
  { value: 'BROKEN_MEMBER',  label: 'Broken member references' },
  { value: 'ORPHANED_ENTRY', label: 'Orphaned entries (missing parent)' },
  { value: 'EMPTY_GROUP',    label: 'Empty groups (no members)' },
]

const form = ref<CheckForm>({
  directoryId: '',
  baseDn: '',
  checks: ['BROKEN_MEMBER', 'ORPHANED_ENTRY', 'EMPTY_GROUP'],
})

const groupedIssues = computed<IssueGroup[]>(() => {
  const groups = new Map<string, IssueGroup>()
  for (const issue of report.value.issues) {
    if (!groups.has(issue.type)) {
      groups.set(issue.type, { type: issue.type, issues: [] })
    }
    groups.get(issue.type)!.issues.push(issue)
  }
  return [...groups.values()]
})

function typeLabel(type: string) {
  switch (type) {
    case 'BROKEN_MEMBER':  return 'Broken Member References'
    case 'ORPHANED_ENTRY': return 'Orphaned Entries'
    case 'EMPTY_GROUP':    return 'Empty Groups'
    default:               return type
  }
}

function typeColor(type: string) {
  switch (type) {
    case 'BROKEN_MEMBER':  return 'text-red-700'
    case 'ORPHANED_ENTRY': return 'text-amber-700'
    case 'EMPTY_GROUP':    return 'text-blue-700'
    default:               return 'text-gray-700'
  }
}

function isRemovable(issue: IntegrityIssue): boolean {
  return issue.type === 'BROKEN_MEMBER' && !!issue.attribute && !!issue.value
}

function issueKey(issue: IntegrityIssue): string {
  return `${issue.dn}\u0000${issue.attribute}\u0000${issue.value}`
}

const removableIssues = computed<IntegrityIssue[]>(() => report.value.issues.filter(isRemovable))
const selectedCount = computed<number>(() => removableIssues.value.filter(i => selected.value.has(issueKey(i))).length)
const allSelected = computed<boolean>(() =>
  removableIssues.value.length > 0 && selectedCount.value === removableIssues.value.length)

function toggle(issue: IntegrityIssue) {
  const s = new Set(selected.value)
  const key = issueKey(issue)
  if (s.has(key)) s.delete(key)
  else s.add(key)
  selected.value = s
}

function toggleAll(on: boolean) {
  selected.value = on ? new Set(removableIssues.value.map(issueKey)) : new Set()
}

function plural(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`
}

async function confirmRemove() {
  const refs = removableIssues.value.filter(i => selected.value.has(issueKey(i)))
  if (!refs.length || !checkedDirectoryId.value) return
  const groups = new Set(refs.map(i => i.dn)).size
  const ok = await confirm({
    title: 'Remove broken references?',
    message: `This removes ${plural(refs.length, 'member reference', 'member references')} from `
      + `${plural(groups, 'group', 'groups')}. Each one is checked again first: a reference whose entry `
      + 'exists now is kept. A group that would be left with no members may be refused by the server.',
    confirmLabel: 'Remove references',
    danger: true,
  })
  if (!ok) return
  removing.value = true
  try {
    const { data } = await removeBrokenReferences(checkedDirectoryId.value,
      refs.map(i => ({ groupDn: i.dn, attribute: i.attribute, value: i.value })))
    reportRemoval(data as RemoveResult)
    selected.value = new Set()
    await runCheck()
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    removing.value = false
  }
}

function reportRemoval(result: RemoveResult) {
  const count = (status: RemoveStatus) => result.outcomes.filter(o => o.status === status).length
  const parts = [`Removed ${plural(result.removed, 'reference', 'references')}.`]
  const kept = count('KEPT_ENTRY_EXISTS')
  const gone = count('ALREADY_GONE')
  if (kept) parts.push(`Kept ${kept}: the entry exists now.`)
  if (gone) parts.push(`${gone} already gone.`)
  const failed = result.outcomes.filter(o => o.status === 'FAILED')
  if (failed.length) {
    const groups = new Set(failed.map(o => o.groupDn))
    notif.error(`${parts.join(' ')} ${plural(failed.length, 'reference', 'references')} in `
      + `${plural(groups.size, 'group', 'groups')} couldn't be removed: ${failed[0].message ?? 'the server refused'}`)
  } else {
    notif.success(parts.join(' '))
  }
}

function toggleGroup(type: string) {
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
    checkedDirectoryId.value = form.value.directoryId
    selected.value = new Set()
    hasRun.value = true
    // Auto-expand all groups that have issues
    expandedGroups.value = new Set(groupedIssues.value.map(g => g.type))
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    running.value = false
  }
}

function goToBrowser(dn: string) {
  router.push({ path: '/superadmin/browser', query: { dn } })
}

onMounted(async () => {
  loadingDirs.value = true
  try {
    const { data } = await listDirectories()
    directories.value = data.filter((d) => d.directoryType !== 'ENTRA_ID')
    if (directories.value.length) form.value.directoryId = directories.value[0].id ?? ''
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    loadingDirs.value = false
  }
})
</script>

<style scoped>
@reference "tailwindcss";
</style>
