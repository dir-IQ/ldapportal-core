<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  What the set's reconcile runs actually did: the latest run's summary (trigger,
  outcome counts, managed before → after, and any incomplete-scan / delete-guard /
  mid-run-disable warnings), with the recent history behind a toggle. Reloads when
  the set changes or the parent bumps `refreshKey` (e.g. after "Reconcile now").
-->
<template>
  <div class="rounded border border-gray-200 px-3 py-2 text-sm space-y-1.5">
    <div class="flex items-center justify-between gap-2">
      <span class="text-base font-semibold text-gray-800">Last reconcile</span>
      <button v-if="runs.length > 1" type="button" class="btn-neutral btn-compact"
              :aria-expanded="showHistory" @click="showHistory = !showHistory">
        {{ showHistory ? 'Hide recent runs' : `Recent runs (${runs.length})` }}
      </button>
    </div>

    <p v-if="loading && !runs.length" class="text-gray-500">Loading…</p>
    <p v-else-if="loadError" class="text-rose-700" role="alert">{{ loadError }}</p>
    <p v-else-if="!last" class="text-gray-500">No reconcile has been recorded for this set yet.</p>

    <template v-else>
      <p class="text-gray-600 flex flex-wrap items-center gap-x-1.5">
        <span :class="statusBadge(last.status)">{{ statusLabel(last.status) }}</span>
        <span>{{ triggerLabel(last) }}</span> ·
        <span>started <RelativeTime :value="last.startedAt" /></span>
        <template v-if="last.finishedAt"> · took {{ duration(last) }}</template>
      </p>
      <p class="text-gray-600">
        <template v-for="(c, i) in outcomeCounts(last)" :key="c.label">
          <template v-if="i > 0"> · </template>
          {{ c.label }}: <span class="font-medium tabular-nums" :class="c.n > 0 ? c.tone : ''">{{ c.n }}</span>
        </template>
      </p>
      <p class="text-gray-600">
        Source entries: <span class="font-medium tabular-nums">{{ last.sourceCount }}</span> ·
        Managed: <span class="font-medium tabular-nums">{{ last.managedBefore }}</span>
        <template v-if="last.managedAfter !== null">
          → <span class="font-medium tabular-nums">{{ last.managedAfter }}</span>
        </template>
      </p>
      <p v-for="w in warnings(last)" :key="w" class="text-amber-700">{{ w }}</p>
      <p v-if="last.error" class="text-rose-700">{{ last.error }}</p>

      <DataTable v-if="showHistory" :columns="cols" :rows="runs" row-key="id"
                 empty-text="No reconcile runs." empty-icon="folder">
        <template #cell-startedAt="{ row }"><RelativeTime :value="row.startedAt" /></template>
        <template #cell-status="{ row }">
          <span :class="statusBadge(row.status)">{{ statusLabel(row.status) }}</span>
        </template>
        <template #cell-trigger="{ row }">{{ triggerLabel(row) }}</template>
        <template #cell-changes="{ row }">
          <span class="tabular-nums" :title="outcomeCounts(row).map((c) => `${c.label}: ${c.n}`).join(', ')">
            {{ changeSummary(row) }}
          </span>
        </template>
        <template #cell-managed="{ row }">
          <span class="tabular-nums">{{ row.managedBefore }} → {{ row.managedAfter ?? '—' }}</span>
        </template>
      </DataTable>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import DataTable from '@/components/DataTable.vue'
import RelativeTime from '@/components/RelativeTime.vue'
import { listReconcileRuns, type SyncReconcileRun } from '@/api/sync'

const props = defineProps<{
  setId: string | null
  /** Bump to reload, e.g. after a manual reconcile finished. */
  refreshKey?: number
}>()

const runs = ref<SyncReconcileRun[]>([])
const loading = ref(false)
const loadError = ref<string | null>(null)
const showHistory = ref(false)

const last = computed<SyncReconcileRun | null>(() => runs.value[0] ?? null)

const cols = [
  { key: 'startedAt', label: 'Started' },
  { key: 'status', label: 'Status' },
  { key: 'trigger', label: 'Trigger' },
  { key: 'changes', label: 'Changes' },
  { key: 'managed', label: 'Managed' },
]

interface OutcomeCount { label: string; n: number; tone: string }

function outcomeCounts(r: SyncReconcileRun): OutcomeCount[] {
  return [
    { label: 'Added', n: r.added, tone: 'text-emerald-700' },
    { label: 'Modified', n: r.modified, tone: 'text-blue-700' },
    { label: 'Moved', n: r.moved, tone: 'text-blue-700' },
    { label: 'Deleted', n: r.deleted, tone: 'text-rose-700' },
    { label: 'Held for review', n: r.heldForReview, tone: 'text-amber-700' },
    { label: 'Already matched', n: r.noChange, tone: '' },
    { label: 'Unchanged', n: r.unchanged, tone: '' },
    { label: 'Failed', n: r.failed + r.faulted, tone: 'text-rose-700' },
  ]
}

/** Compact "+3 ~1 −0 …" summary for the history table. */
function changeSummary(r: SyncReconcileRun): string {
  const parts = [`+${r.added}`, `~${r.modified + r.moved}`, `−${r.deleted}`]
  if (r.heldForReview) parts.push(`${r.heldForReview} review`)
  if (r.failed + r.faulted) parts.push(`${r.failed + r.faulted} failed`)
  return parts.join('  ')
}

function warnings(r: SyncReconcileRun): string[] {
  const w: string[] = []
  if (r.skipped > 0) {
    w.push(`${r.skipped} entr${r.skipped === 1 ? 'y was' : 'ies were'} skipped: the set or link was `
      + 'disabled while the run was in progress.')
  }
  if (r.status !== 'RUNNING' && !r.completeScan) {
    w.push('The source scan did not complete, so no deletions were made.')
  }
  if (r.guardTripped) {
    w.push(`Delete guard tripped${r.guardReason ? `: ${r.guardReason}` : ''}. Deletions were held for review.`)
  }
  if (r.faulted > 0) {
    w.push(`${r.faulted} entr${r.faulted === 1 ? 'y' : 'ies'} hit an error and will be retried on the next run.`)
  }
  return w
}

function statusLabel(s: SyncReconcileRun['status']): string {
  return { RUNNING: 'Running', COMPLETE: 'Completed', FAILED: 'Failed' }[s]
}

function statusBadge(s: SyncReconcileRun['status']): string {
  return { RUNNING: 'badge-blue', COMPLETE: 'badge-green', FAILED: 'badge-red' }[s]
}

function triggerLabel(r: SyncReconcileRun): string {
  if (r.trigger === 'SCHEDULED') return 'Scheduled'
  return r.requestedBy ? `Manual (${r.requestedBy})` : 'Manual'
}

function duration(r: SyncReconcileRun): string {
  if (!r.finishedAt) return '—'
  const secs = Math.max(0, Math.round((Date.parse(r.finishedAt) - Date.parse(r.startedAt)) / 1000))
  if (secs < 60) return `${secs}s`
  const mins = Math.floor(secs / 60)
  return mins < 60 ? `${mins}m ${secs % 60}s` : `${Math.floor(mins / 60)}h ${mins % 60}m`
}

function errMsg(e: unknown): string {
  const err = e as { response?: { data?: { detail?: string; message?: string } } }
  return err.response?.data?.detail || err.response?.data?.message || 'Could not load reconcile history'
}

async function load() {
  if (!props.setId) {
    runs.value = []
    return
  }
  loading.value = true
  loadError.value = null
  try {
    const { data } = await listReconcileRuns(props.setId)
    runs.value = data
  } catch (e) {
    loadError.value = errMsg(e)
  } finally {
    loading.value = false
  }
}

watch(() => props.setId, () => { showHistory.value = false; load() }, { immediate: true })
watch(() => props.refreshKey, load)
</script>
