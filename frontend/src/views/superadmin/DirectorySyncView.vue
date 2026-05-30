<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <PageContainer>
    <div class="flex items-center justify-between mb-6">
      <div>
        <h1 class="text-2xl font-bold text-gray-900">Directory Sync</h1>
        <p class="text-sm text-gray-500 mt-1">
          Asynchronous replication of app-initiated changes between directories
        </p>
      </div>
      <button @click="openCreate" class="btn-primary">+ New Replication Link</button>
    </div>

    <div class="bg-white border border-gray-200 rounded-xl overflow-hidden">
      <div v-if="loading" class="p-8 text-center text-gray-500 text-sm">Loading…</div>
      <div v-else-if="links.length === 0" class="p-8 text-center text-gray-500 text-sm">
        No replication links configured.
      </div>
      <table v-else class="w-full text-sm">
        <thead class="bg-gray-50 border-b border-gray-100">
          <tr>
            <th class="px-4 py-3 text-left font-medium text-gray-500">Name</th>
            <th class="px-4 py-3 text-left font-medium text-gray-500">Source → Target</th>
            <th class="px-4 py-3 text-left font-medium text-gray-500">Status</th>
            <th class="px-4 py-3 text-left font-medium text-gray-500">Pending</th>
            <th class="px-4 py-3 text-left font-medium text-gray-500">Failed</th>
            <th class="px-4 py-3 text-left font-medium text-gray-500">Dead-lettered</th>
            <th class="px-4 py-3 text-left font-medium text-gray-500">Last delivered</th>
            <th class="px-4 py-3"></th>
          </tr>
        </thead>
        <tbody class="divide-y divide-gray-50">
          <tr v-for="link in links" :key="link.id" class="hover:bg-gray-50">
            <td class="px-4 py-3 font-medium text-gray-900">{{ link.displayName }}</td>
            <td class="px-4 py-3 text-gray-600 text-xs">
              {{ link.sourceDirectoryName }} → {{ link.targetDirectoryName }}
            </td>
            <td class="px-4 py-3">
              <span :class="link.enabled ? 'text-green-600' : 'text-gray-500'" class="text-xs font-medium">
                {{ link.enabled ? 'Enabled' : 'Disabled' }}
              </span>
            </td>
            <td class="px-4 py-3 text-gray-600">{{ link.pendingCount }}</td>
            <td class="px-4 py-3"
                :class="link.failedCount > 0 ? 'text-amber-700 font-medium' : 'text-gray-600'">
              {{ link.failedCount }}
            </td>
            <td class="px-4 py-3"
                :class="link.deadLetteredCount > 0 ? 'text-red-700 font-medium' : 'text-gray-600'">
              {{ link.deadLetteredCount }}
            </td>
            <td class="px-4 py-3 text-gray-600 text-xs">
              <RelativeTime v-if="link.lastDeliveredAt" :value="link.lastDeliveredAt" />
              <span v-else class="text-gray-400">—</span>
            </td>
            <td class="px-4 py-3 text-right whitespace-nowrap">
              <ActionMenu :items="[
                { label: 'View events', onClick: () => openEvents(link) },
                { label: 'Delete', onClick: () => confirmDelete(link), danger: true },
              ]">
                <template #primary>
                  <button @click="openEdit(link)" class="btn-secondary btn-compact">Edit</button>
                </template>
              </ActionMenu>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Create / Edit modal ───────────────────────────────────────────── -->
    <AppModal v-model="showForm"
              :title="editing ? 'Edit Replication Link' : 'New Replication Link'"
              size="lg"
              fixed-height="min(640px, 80vh)">
      <form @submit.prevent="save" class="space-y-3">
        <div class="grid grid-cols-2 gap-3">
          <FormField label="Display Name" v-model="form.displayName" required />
          <div></div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">Source Directory</label>
            <select v-model="form.sourceDirectoryId" class="input w-full" required>
              <option value="" disabled>Select source…</option>
              <option v-for="d in directoryOptions" :key="d.id" :value="d.id">{{ d.displayName }}</option>
            </select>
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">Target Directory</label>
            <select v-model="form.targetDirectoryId" class="input w-full" required>
              <option value="" disabled>Select target…</option>
              <option v-for="d in directoryOptions" :key="d.id" :value="d.id"
                      :disabled="d.id === form.sourceDirectoryId">{{ d.displayName }}</option>
            </select>
          </div>
          <FormField label="Source Base DN (optional)" v-model="form.sourceBaseDn"
                     placeholder="leave blank for identity mapping" />
          <FormField label="Target Base DN (optional)" v-model="form.targetBaseDn"
                     placeholder="required if source is set" />
        </div>

        <div class="flex items-center gap-4">
          <label class="flex items-center gap-2 text-sm text-gray-700">
            <input type="checkbox" v-model="form.enabled" class="rounded" />
            Enabled
          </label>
          <label class="flex items-center gap-2 text-sm text-gray-700"
                 title="When MODIFY targets a missing entry, auto-create from source first.">
            <input type="checkbox" v-model="form.autoCreateOnMissing" class="rounded" />
            Auto-create on missing target
          </label>
        </div>

        <details class="border border-gray-200 rounded-lg">
          <summary class="px-3 py-2 cursor-pointer text-sm text-gray-700 select-none">
            Attribute mappings ({{ form.attributeMappings.length }})
          </summary>
          <div class="p-3 space-y-2">
            <p class="text-xs text-gray-500">
              Leave empty for identity mapping (same attribute names, same values).
              <code>${value}</code> in the template substitutes the source value.
            </p>
            <table v-if="form.attributeMappings.length > 0" class="w-full text-xs">
              <thead class="text-gray-500">
                <tr>
                  <th class="text-left pb-1">Source attr</th>
                  <th class="text-left pb-1">Target attr</th>
                  <th class="text-left pb-1">Value template</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(m, i) in form.attributeMappings" :key="i">
                  <td class="pr-2 py-1"><input v-model="m.sourceAttr" class="input w-full text-xs" /></td>
                  <td class="pr-2 py-1"><input v-model="m.targetAttr" class="input w-full text-xs" /></td>
                  <td class="pr-2 py-1"><input v-model="m.valueTemplate"
                                               placeholder="${value}"
                                               class="input w-full text-xs" /></td>
                  <td class="py-1 text-right">
                    <button type="button" @click="form.attributeMappings.splice(i, 1)"
                            class="text-red-600 hover:text-red-700 text-xs px-1">×</button>
                  </td>
                </tr>
              </tbody>
            </table>
            <button type="button" @click="addMappingRow" class="btn-secondary btn-compact text-xs">
              + Add mapping
            </button>
          </div>
        </details>
      </form>

      <template #footer>
        <button @click="showForm = false" class="btn-secondary">Cancel</button>
        <button @click="save" :disabled="saving" class="btn-primary">
          {{ saving ? 'Saving…' : 'Save' }}
        </button>
      </template>
    </AppModal>

    <!-- Event log modal ────────────────────────────────────────────────── -->
    <AppModal v-model="showEvents" :title="`Events — ${eventsLink?.displayName ?? ''}`"
              size="xl" fixed-height="min(720px, 85vh)">
      <div class="space-y-3">
        <div class="flex gap-3 items-center text-sm">
          <label class="text-gray-700">Status:</label>
          <select v-model="eventStatusFilter" @change="loadEvents()" class="input">
            <option value="">All</option>
            <option value="PENDING">Pending</option>
            <option value="IN_FLIGHT">In flight</option>
            <option value="DELIVERED">Delivered</option>
            <option value="FAILED">Failed</option>
            <option value="DEAD_LETTERED">Dead-lettered</option>
            <option value="SKIPPED">Skipped</option>
            <option value="ACKNOWLEDGED">Acknowledged</option>
          </select>
          <button @click="loadEvents()" class="btn-secondary btn-compact text-xs">Refresh</button>
        </div>
        <div v-if="loadingEvents" class="text-center text-gray-500 py-4 text-sm">Loading…</div>
        <div v-else-if="events.length === 0" class="text-center text-gray-500 py-4 text-sm">
          No events match the filter.
        </div>
        <table v-else class="w-full text-xs">
          <thead class="bg-gray-50 text-gray-500">
            <tr>
              <th class="text-left px-2 py-1">When</th>
              <th class="text-left px-2 py-1">Op</th>
              <th class="text-left px-2 py-1">Target DN</th>
              <th class="text-left px-2 py-1">Status</th>
              <th class="text-left px-2 py-1">Attempts</th>
              <th class="text-left px-2 py-1">Last error</th>
              <th class="px-2 py-1"></th>
            </tr>
          </thead>
          <tbody class="divide-y divide-gray-50">
            <tr v-for="e in events" :key="e.id">
              <td class="px-2 py-1"><RelativeTime :value="e.enqueuedAt" /></td>
              <td class="px-2 py-1 font-mono text-[10px]">{{ e.operation }}</td>
              <td class="px-2 py-1 font-mono text-[10px] truncate max-w-xs" :title="e.targetDn">
                {{ e.targetDn }}
              </td>
              <td class="px-2 py-1">
                <span :class="statusClass(e.status)" class="px-1.5 py-0.5 rounded text-[10px] font-medium">
                  {{ e.status }}
                </span>
              </td>
              <td class="px-2 py-1">{{ e.attempts }}</td>
              <td class="px-2 py-1 text-[10px] text-gray-600 truncate max-w-xs" :title="e.lastError">
                {{ e.lastError || '—' }}
              </td>
              <td class="px-2 py-1 text-right whitespace-nowrap">
                <button v-if="canRetry(e.status)" @click="doEventAction(e, 'retry')"
                        class="text-blue-600 hover:text-blue-700 text-[10px] px-1">retry</button>
                <button v-if="canSkip(e.status)" @click="doEventAction(e, 'skip')"
                        class="text-gray-600 hover:text-gray-700 text-[10px] px-1">skip</button>
                <button v-if="e.status === 'DEAD_LETTERED'" @click="doEventAction(e, 'ack')"
                        class="text-gray-600 hover:text-gray-700 text-[10px] px-1">ack</button>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="eventsTotalPages > 1" class="flex justify-between items-center text-xs text-gray-500">
          <button :disabled="eventsPage === 0" @click="eventsPage--; loadEvents()" class="btn-secondary btn-compact">← Prev</button>
          <span>Page {{ eventsPage + 1 }} of {{ eventsTotalPages }}</span>
          <button :disabled="eventsPage >= eventsTotalPages - 1" @click="eventsPage++; loadEvents()" class="btn-secondary btn-compact">Next →</button>
        </div>
      </div>
    </AppModal>

    <ConfirmDialog v-if="deleteTarget"
                   v-model="confirmDeleteOpen"
                   title="Delete replication link?"
                   :message="`Delete '${deleteTarget.displayName}'? Pending and dead-lettered events will be removed too.`"
                   confirm-label="Delete"
                   danger
                   @confirm="doDelete"
                   @cancel="deleteTarget = null" />
  </PageContainer>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useNotificationStore } from '@/stores/notifications'
import {
  listReplicationLinks, createReplicationLink, updateReplicationLink, deleteReplicationLink,
  listReplicationEvents, retryReplicationEvent, skipReplicationEvent, acknowledgeReplicationEvent,
} from '@/api/replication'
import { listDirectories } from '@/api/directories'
import PageContainer from '@/components/PageContainer.vue'
import AppModal from '@/components/AppModal.vue'
import FormField from '@/components/FormField.vue'
import ActionMenu from '@/components/ActionMenu.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import RelativeTime from '@/components/RelativeTime.vue'

const notif = useNotificationStore()

const links     = ref([])
const directoryOptions = ref([])
const loading   = ref(false)

const showForm  = ref(false)
const editing   = ref(null)
const saving    = ref(false)
const form      = ref(emptyForm())

const deleteTarget      = ref(null)
const confirmDeleteOpen = computed({
  get: () => !!deleteTarget.value,
  set: (v) => { if (!v) deleteTarget.value = null },
})

// Event log state
const showEvents       = ref(false)
const eventsLink       = ref(null)
const events           = ref([])
const eventStatusFilter = ref('')
const eventsPage       = ref(0)
const eventsTotalPages = ref(1)
const loadingEvents    = ref(false)

function emptyForm() {
  return {
    displayName: '',
    sourceDirectoryId: '',
    targetDirectoryId: '',
    sourceBaseDn: '',
    targetBaseDn: '',
    enabled: true,
    autoCreateOnMissing: false,
    attributeMappings: [],
  }
}

function addMappingRow() {
  form.value.attributeMappings.push({ sourceAttr: '', targetAttr: '', valueTemplate: '' })
}

async function load() {
  loading.value = true
  try {
    const [linksRes, dirsRes] = await Promise.all([listReplicationLinks(), listDirectories()])
    links.value = linksRes.data
    directoryOptions.value = dirsRes.data
  } catch (e) {
    notif.error(`Failed to load: ${e?.response?.data?.detail || e.message}`)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  form.value = emptyForm()
  showForm.value = true
}

function openEdit(link) {
  editing.value = link
  form.value = {
    displayName: link.displayName,
    sourceDirectoryId: link.sourceDirectoryId,
    targetDirectoryId: link.targetDirectoryId,
    sourceBaseDn: link.sourceBaseDn ?? '',
    targetBaseDn: link.targetBaseDn ?? '',
    enabled: link.enabled,
    autoCreateOnMissing: link.autoCreateOnMissing,
    attributeMappings: (link.attributeMappings ?? []).map(m => ({
      sourceAttr: m.sourceAttr, targetAttr: m.targetAttr, valueTemplate: m.valueTemplate ?? '',
    })),
  }
  showForm.value = true
}

async function save() {
  saving.value = true
  try {
    const payload = {
      ...form.value,
      sourceBaseDn: form.value.sourceBaseDn || null,
      targetBaseDn: form.value.targetBaseDn || null,
      attributeMappings: form.value.attributeMappings
        .filter(m => m.sourceAttr && m.targetAttr)
        .map(m => ({ ...m, valueTemplate: m.valueTemplate || null })),
    }
    if (editing.value) {
      await updateReplicationLink(editing.value.id, payload)
      notif.success('Replication link updated')
    } else {
      await createReplicationLink(payload)
      notif.success('Replication link created')
    }
    showForm.value = false
    await load()
  } catch (e) {
    notif.error(`Save failed: ${e?.response?.data?.detail || e.message}`)
  } finally {
    saving.value = false
  }
}

function confirmDelete(link) { deleteTarget.value = link }

async function doDelete() {
  if (!deleteTarget.value) return
  try {
    await deleteReplicationLink(deleteTarget.value.id)
    notif.success('Replication link deleted')
    deleteTarget.value = null
    await load()
  } catch (e) {
    notif.error(`Delete failed: ${e?.response?.data?.detail || e.message}`)
  }
}

async function openEvents(link) {
  eventsLink.value = link
  eventStatusFilter.value = ''
  eventsPage.value = 0
  showEvents.value = true
  await loadEvents()
}

async function loadEvents() {
  if (!eventsLink.value) return
  loadingEvents.value = true
  try {
    const params = { page: eventsPage.value, size: 50 }
    if (eventStatusFilter.value) params.status = eventStatusFilter.value
    const { data } = await listReplicationEvents(eventsLink.value.id, params)
    events.value = data.content || []
    eventsTotalPages.value = data.totalPages || 1
  } catch (e) {
    notif.error(`Failed to load events: ${e?.response?.data?.detail || e.message}`)
  } finally {
    loadingEvents.value = false
  }
}

async function doEventAction(event, kind) {
  try {
    if (kind === 'retry') await retryReplicationEvent(event.id)
    else if (kind === 'skip') await skipReplicationEvent(event.id)
    else if (kind === 'ack')  await acknowledgeReplicationEvent(event.id)
    notif.success(`Event ${kind} succeeded`)
    await loadEvents()
    await load()  // refresh link health counts
  } catch (e) {
    notif.error(`Event ${kind} failed: ${e?.response?.data?.detail || e.message}`)
  }
}

function canRetry(status) {
  return ['FAILED', 'DEAD_LETTERED', 'SKIPPED', 'ACKNOWLEDGED'].includes(status)
}
function canSkip(status) {
  return ['PENDING', 'FAILED', 'DEAD_LETTERED'].includes(status)
}
function statusClass(status) {
  switch (status) {
    case 'DELIVERED':     return 'bg-green-50 text-green-700'
    case 'PENDING':       return 'bg-blue-50 text-blue-700'
    case 'IN_FLIGHT':     return 'bg-blue-50 text-blue-700'
    case 'FAILED':        return 'bg-amber-50 text-amber-700'
    case 'DEAD_LETTERED': return 'bg-red-50 text-red-700'
    case 'SKIPPED':       return 'bg-gray-100 text-gray-600'
    case 'ACKNOWLEDGED':  return 'bg-gray-100 text-gray-600'
    default:              return 'bg-gray-100 text-gray-600'
  }
}

onMounted(load)
</script>

<style scoped>
@reference "tailwindcss";
</style>
