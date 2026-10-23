<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  What the set's reconcile runs actually did: the latest run's summary (trigger,
  instance, timing, outcome counts, managed before → after, drift after the run,
  and any incomplete-scan / delete-guard / mid-run-disable warnings), with the
  recent history behind a toggle. The changes applied between reconciles
  (incremental passes: changelog, in-app edits) are listed behind their own
  toggle, so they never push the reconciles out of view. "Details" or a click
  on a history row opens ReconcileRunDetail for that run. Reloads when the set
  changes or the parent bumps `refreshKey` (e.g. after "Reconcile now").
-->
<template>
  <div class="rounded border border-gray-200 px-3 py-2 text-sm space-y-1.5">
    <div class="flex items-center justify-between gap-2">
      <span class="text-base font-semibold text-gray-800">Last reconcile</span>
      <div class="flex items-center gap-2">
        <button v-if="last" type="button" class="btn-neutral btn-compact" @click="selectedRunId = last.id">
          Details
        </button>
        <button v-if="runs.length > 1" type="button" class="btn-neutral btn-compact"
                :aria-expanded="showHistory" @click="showHistory = !showHistory">
          {{ showHistory ? 'Hide recent runs' : `Recent runs (${runs.length})` }}
        </button>
        <button v-if="incrementalRuns.length" type="button" class="btn-neutral btn-compact"
                :aria-expanded="showIncremental" @click="showIncremental = !showIncremental">
          {{ showIncremental ? 'Hide incremental changes' : `Incremental changes (${incrementalRuns.length})` }}
        </button>
      </div>
    </div>

    <p v-if="loading && !runs.length" class="text-gray-500">Loading…</p>
    <p v-else-if="loadError" class="text-rose-700" role="alert">{{ loadError }}</p>
    <p v-else-if="!last" class="text-gray-500">No reconcile has been recorded for this set yet.</p>

    <template v-else>
      <p class="text-gray-600 flex flex-wrap items-center gap-x-1.5">
        <span :class="statusBadge(last.status)">{{ statusLabel(last.status) }}</span>
        <span>{{ triggerLabel(last) }}</span> ·
        <template v-if="last.instanceName"><span>on {{ last.instanceName }}</span> ·</template>
        <span>started <RelativeTime :value="last.startedAt" /></span>
        <template v-if="last.finishedAt">
          · took {{ elapsed(last.startedAt, last.finishedAt) }}
          <template v-if="last.plannedAt">
            (scan {{ elapsed(last.startedAt, last.plannedAt) }}, apply {{ elapsed(last.plannedAt, last.finishedAt) }})
          </template>
        </template>
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
      <p v-if="driftSummary(last)" class="text-gray-600">{{ driftSummary(last) }}</p>
      <p v-for="w in runWarnings(last)" :key="w" class="text-amber-700">{{ w }}</p>
      <p v-if="last.error" class="text-rose-700">{{ last.error }}</p>

      <!-- Click a run for its details. -->
      <DataTable v-if="showHistory" :columns="cols" :rows="runs" row-key="id"
                 empty-text="No reconcile runs." empty-icon="folder"
                 :highlight-key="selectedRunId ?? undefined" @row-click="(r) => (selectedRunId = r.id)">
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
        <template #cell-instance="{ row }">{{ row.instanceName ?? '—' }}</template>
      </DataTable>
    </template>

    <!-- Changes applied between reconciles; shown even before the first reconcile. -->
    <template v-if="showIncremental && incrementalRuns.length">
      <p class="text-gray-600">Changes applied between reconciles (changelog, in-app edits), one row per pass.</p>
      <DataTable :columns="incrementalCols" :rows="incrementalRuns" row-key="id"
                 empty-text="No incremental changes." empty-icon="folder"
                 :highlight-key="selectedRunId ?? undefined" @row-click="(r) => (selectedRunId = r.id)">
        <template #cell-startedAt="{ row }"><RelativeTime :value="row.startedAt" /></template>
        <template #cell-instance="{ row }">{{ row.instanceName ?? '—' }}</template>
        <template #cell-changes="{ row }">
          <span class="tabular-nums" :title="outcomeCounts(row).map((c) => `${c.label}: ${c.n}`).join(', ')">
            {{ changeSummary(row) }}
          </span>
        </template>
        <template #cell-managed="{ row }"><span class="tabular-nums">{{ row.managedAfter ?? '—' }}</span></template>
      </DataTable>
    </template>

    <ReconcileRunDetail v-if="selectedRunId && setId" :set-id="setId" :run-id="selectedRunId"
                        @close="selectedRunId = null" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import DataTable from '@/components/DataTable.vue'
import RelativeTime from '@/components/RelativeTime.vue'
import ReconcileRunDetail from '@/components/sync/ReconcileRunDetail.vue'
import { listReconcileRuns, type SyncReconcileRun } from '@/api/sync'
import {
  driftSummary, elapsed, errMsg, outcomeCounts, runWarnings, statusBadge, statusLabel, triggerLabel,
} from './reconcileRunFormat'

const props = defineProps<{
  setId: string | null
  /** Bump to reload, e.g. after a manual reconcile finished. */
  refreshKey?: number
}>()

/** Reconciles (scheduled / manual), newest first. */
const runs = ref<SyncReconcileRun[]>([])
/** Incremental passes between reconciles, newest first. */
const incrementalRuns = ref<SyncReconcileRun[]>([])
const loading = ref(false)
const loadError = ref<string | null>(null)
const showHistory = ref(false)
const showIncremental = ref(false)
const selectedRunId = ref<string | null>(null)

const last = computed<SyncReconcileRun | null>(() => runs.value[0] ?? null)

const cols = [
  { key: 'startedAt', label: 'Started' },
  { key: 'status', label: 'Status' },
  { key: 'trigger', label: 'Trigger' },
  { key: 'instance', label: 'Ran on' },
  { key: 'changes', label: 'Changes' },
  { key: 'managed', label: 'Managed' },
]

const incrementalCols = [
  { key: 'startedAt', label: 'Started' },
  { key: 'instance', label: 'Ran on' },
  { key: 'changes', label: 'Changes' },
  { key: 'managed', label: 'Managed after' },
]

/** Compact "+3 ~1 −0 …" summary for the history table. */
function changeSummary(r: SyncReconcileRun): string {
  const parts = [`+${r.added}`, `~${r.modified + r.moved}`, `−${r.deleted}`]
  if (r.heldForReview) parts.push(`${r.heldForReview} review`)
  if (r.failed + r.faulted) parts.push(`${r.failed + r.faulted} failed`)
  return parts.join('  ')
}

async function load() {
  if (!props.setId) {
    runs.value = []
    incrementalRuns.value = []
    return
  }
  loading.value = true
  loadError.value = null
  try {
    const [reconciles, incremental] = await Promise.all([
      listReconcileRuns(props.setId, 10, 'RECONCILE'),
      listReconcileRuns(props.setId, 20, 'INCREMENTAL'),
    ])
    runs.value = reconciles.data
    incrementalRuns.value = incremental.data
  } catch (e) {
    loadError.value = errMsg(e, 'Could not load reconcile history')
  } finally {
    loading.value = false
  }
}

watch(() => props.setId, () => {
  showHistory.value = false
  showIncremental.value = false
  selectedRunId.value = null
  load()
}, { immediate: true })
watch(() => props.refreshKey, load)
</script>
