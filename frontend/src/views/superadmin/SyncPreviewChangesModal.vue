<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <AppModal :model-value="modelValue" :title="`Planned changes — ${setName}`" size="2xl"
            storage-key="sync-preview-changes" @update:model-value="emit('update:modelValue', $event)">
    <div class="flex-1 min-h-0 flex flex-col gap-3">
      <div class="flex flex-wrap items-center gap-3">
        <label class="flex items-center gap-2 text-sm text-gray-700">
          <input type="checkbox" v-model="changesOnly" class="rounded" :disabled="!complete || loading"
                 @change="load(0)" />
          Changes only
        </label>
        <label class="flex items-center gap-2 text-sm text-gray-700">
          Per page
          <select v-model.number="size" aria-label="Entries per page" class="input-sm"
                  :disabled="!complete || loading" @change="load(0)">
            <option v-for="n in PAGE_SIZES" :key="n" :value="n">{{ n }}</option>
          </select>
        </label>
        <button type="button" class="btn-secondary btn-sm" :disabled="running || starting" @click="start">
          {{ running || starting ? 'Running…' : 'Run again' }}
        </button>
      </div>

      <p v-if="running" class="text-sm text-gray-600" role="status">
        <template v-if="run && run.total > 0">
          Checking entries: {{ run.processed }} of {{ run.total }}…
        </template>
        <template v-else>Scanning the source…</template>
        A dry run of the sync engine: nothing is written.
      </p>

      <p v-if="run?.status === 'FAILED'" class="text-sm text-rose-700" role="alert">
        {{ run.error || 'The preview failed.' }}
      </p>

      <div v-if="complete && run" class="text-xs text-gray-600 space-y-1">
        <p>
          Source entries: <span class="font-medium">{{ run.sourceCount }}</span> ·
          Managed: <span class="font-medium">{{ run.managedCount }}</span> ·
          Would change: <span class="font-medium">{{ run.actionableCount }}</span> ·
          Unchanged: <span class="font-medium">{{ run.unchangedCount }}</span>
        </p>
        <p v-if="run.note" class="text-amber-600">{{ run.note }}</p>
        <p v-if="run.guardTripped"
           class="text-amber-700 bg-amber-50 border border-amber-200 rounded px-2 py-1">
          Delete guard would trip: {{ run.guardReason }}. Deletions are shown as held for review.
        </p>
        <p v-if="run.outdated"
           class="text-amber-700 bg-amber-50 border border-amber-200 rounded px-2 py-1">
          The set has been edited or reconciled since this preview ran. Run it again for current results.
        </p>
        <p class="text-gray-500">
          A dry run of the sync engine, computed {{ computedAt }}<template v-if="run.requestedBy">
          by {{ run.requestedBy }}</template>. Nothing was written.
        </p>
      </div>

      <div v-if="complete" class="flex-1 min-h-0 overflow-y-auto">
        <DataTable :columns="columns" :rows="rows" :loading="loading" row-key="identity"
                   :expanded-key="expanded ?? undefined" :highlight-key="expanded ?? undefined"
                   empty-text="Nothing to change." @row-click="toggle">
          <template #cell-action="{ row }">
            <span :class="ACTION_BADGE[row.action]">{{ ACTION_LABEL[row.action] }}</span>
          </template>
          <template #cell-targetDn="{ row }">
            <span class="font-mono break-all">{{ row.targetDn ?? row.sourceDn ?? row.identity }}</span>
          </template>
          <template #cell-detail="{ row }">
            <span class="cell-muted">{{ summary(row) }}</span>
          </template>
          <template #row-detail="{ row }">
            <div class="px-4 py-3 space-y-2 text-xs">
              <p v-if="row.previousTargetDn" class="text-gray-600">
                Moves from <span class="font-mono">{{ row.previousTargetDn }}</span>
              </p>
              <p v-if="row.sourceDn" class="text-gray-500">
                Source: <span class="font-mono">{{ row.sourceDn }}</span>
              </p>
              <p v-if="row.reason" class="text-gray-600">{{ row.reason }}</p>
              <dl v-if="row.changes.length" class="space-y-1.5">
                <div v-for="c in row.changes" :key="c.attribute">
                  <dt class="font-medium text-gray-700">{{ c.attribute }}</dt>
                  <dd class="font-mono pl-3 space-y-0.5">
                    <div v-for="(v, i) in c.removed" :key="`r${i}`" class="text-rose-700 break-all">− {{ v }}</div>
                    <div v-if="c.omittedRemoved" class="text-rose-700">− … and {{ c.omittedRemoved }} more</div>
                    <div v-for="(v, i) in c.added" :key="`a${i}`" class="text-emerald-700 break-all">+ {{ v }}</div>
                    <div v-if="c.omittedAdded" class="text-emerald-700">+ … and {{ c.omittedAdded }} more</div>
                  </dd>
                </div>
              </dl>
            </div>
          </template>
        </DataTable>
      </div>

      <div v-if="complete && result && result.totalPages > 1"
           class="flex items-center justify-between text-sm text-gray-600">
        <button type="button" class="btn-secondary btn-sm" :disabled="loading || page === 0"
                @click="load(page - 1)">Previous</button>
        <span>Page {{ page + 1 }} of {{ result.totalPages }}</span>
        <button type="button" class="btn-secondary btn-sm" :disabled="loading || page + 1 >= result.totalPages"
                @click="load(page + 1)">Next</button>
      </div>
    </div>
  </AppModal>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import AppModal from '@/components/AppModal.vue'
import DataTable from '@/components/DataTable.vue'
import {
  getPreviewRun, previewEntries, startPreviewRun,
  type SyncPreviewAction, type SyncPreviewEntry, type SyncPreviewPage, type SyncPreviewStatus,
} from '@/api/sync'
import { useNotificationStore } from '@/stores/notifications'

const props = defineProps<{
  modelValue: boolean
  setId: string
  setName: string
}>()
const emit = defineEmits<{ 'update:modelValue': [boolean] }>()

const notif = useNotificationStore()

const PAGE_SIZES = [25, 50, 100, 200]
/** How often a running preview's progress is re-read. */
const POLL_MS = 1500

const ACTION_LABEL: Record<SyncPreviewAction, string> = {
  ADD: 'Add', MODIFY: 'Modify', MOVE: 'Move', DELETE: 'Delete', REVIEW: 'Review',
  NO_CHANGE: 'No change', FORGET: 'Forget', UNCHANGED: 'Unchanged', ERROR: 'Error',
}
const ACTION_BADGE: Record<SyncPreviewAction, string> = {
  ADD: 'badge-green', MODIFY: 'badge-blue', MOVE: 'badge-yellow', DELETE: 'badge-red', REVIEW: 'badge-amber',
  NO_CHANGE: 'badge-gray', FORGET: 'badge-gray', UNCHANGED: 'badge-gray', ERROR: 'badge-red',
}

const columns = [
  { key: 'action', label: 'Action' },
  { key: 'targetDn', label: 'Target DN' },
  { key: 'detail', label: 'Details' },
]

const changesOnly = ref<boolean>(true)
const size = ref<number>(50)
const page = ref<number>(0)
const loading = ref<boolean>(false)
const starting = ref<boolean>(false)
const run = ref<SyncPreviewStatus | null>(null)
const result = ref<SyncPreviewPage | null>(null)
const expanded = ref<string | null>(null)

const running = computed<boolean>(() => run.value?.status === 'RUNNING')
const complete = computed<boolean>(() => run.value?.status === 'COMPLETE')
const rows = computed<SyncPreviewEntry[]>(() => result.value?.entries ?? [])
const computedAt = computed<string>(() => {
  const at = run.value?.finishedAt
  return at ? new Date(at).toLocaleString() : ''
})

// Each page request takes a ticket; a response for an older ticket is dropped,
// so a slow earlier request can't overwrite what a later one asked for.
let pageTicket = 0
let pollTimer: ReturnType<typeof setTimeout> | null = null

function summary(e: SyncPreviewEntry): string {
  if (e.reason) return e.reason
  const n = e.changes.length
  switch (e.action) {
    case 'ADD': return `${n} attribute${n === 1 ? '' : 's'}`
    case 'MODIFY': return `${n} attribute${n === 1 ? '' : 's'} changed`
    case 'MOVE': return n ? `renamed; ${n} attribute${n === 1 ? '' : 's'} changed` : 'renamed'
    case 'NO_CHANGE': return 'target already matches'
    case 'DELETE': return 'removed from the target'
    default: return ''
  }
}

function toggle(row: SyncPreviewEntry) {
  expanded.value = expanded.value === row.identity ? null : row.identity
}

function errorMessage(e: unknown, fallback: string): string {
  const err = e as {
    response?: { data?: { detail?: string, message?: string } },
    message?: string,
  }
  return err.response?.data?.detail || err.response?.data?.message || err.message || fallback
}

function stopPolling() {
  if (pollTimer !== null) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

/** Adopt a run status: poll while it runs, load the first page once it completes. */
function adopt(status: SyncPreviewStatus) {
  // Load when this status is a completed run whose entries aren't on screen yet.
  const needsEntries = status.status === 'COMPLETE' && result.value?.runId !== status.runId
  run.value = status
  stopPolling()
  if (status.status === 'RUNNING' && props.modelValue) {
    pollTimer = setTimeout(() => { void refresh() }, POLL_MS)
  } else if (needsEntries) {
    result.value = null
    void load(0)
  }
}

async function refresh() {
  try {
    const { data } = await getPreviewRun(props.setId)
    if (!props.modelValue) return
    if (data.status === null) {
      await start()
    } else {
      adopt(data)
    }
  } catch (e) {
    notif.error(errorMessage(e, 'Could not load the preview'))
  }
}

async function start() {
  starting.value = true
  try {
    const { data } = await startPreviewRun(props.setId)
    adopt(data)
  } catch (e) {
    notif.error(errorMessage(e, 'Could not start the preview'))
  } finally {
    starting.value = false
  }
}

async function load(target: number) {
  const ticket = ++pageTicket
  loading.value = true
  expanded.value = null
  try {
    const { data } = await previewEntries(props.setId, target, size.value, changesOnly.value)
    if (ticket !== pageTicket) return
    if (data.runId !== run.value?.runId) {
      // A newer run finished since this dialog last looked: show that one instead.
      await refresh()
      return
    }
    result.value = data
    page.value = data.page
  } catch (e) {
    if (ticket !== pageTicket) return
    if ((e as { response?: { status?: number } }).response?.status === 404) {
      // Someone started a new run, so this one's entries are gone: follow the new run.
      await refresh()
    } else {
      notif.error(errorMessage(e, 'Could not load the planned changes'))
    }
  } finally {
    if (ticket === pageTicket) loading.value = false
  }
}

// Read the latest run each time the dialog opens; start one if the set has none.
watch(() => props.modelValue, (open) => {
  stopPolling()
  pageTicket++
  loading.value = false
  run.value = null
  result.value = null
  if (open) void refresh()
}, { immediate: true })

onBeforeUnmount(stopPolling)
</script>
