<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  Everything recorded about one reconcile run: where and when it ran (scan vs.
  apply time), every count, warnings, the drift the verification after it found,
  failures grouped by reason, sample target DNs per outcome, and the set's
  settings at the time. Shown inline under the run summary; emits `close`.
-->
<template>
  <section class="rounded border border-gray-200 bg-gray-50 px-3 py-2 space-y-3" aria-label="Reconcile run details">
    <div class="flex items-center justify-between">
      <h3 class="text-sm font-semibold text-gray-800">
        Run details<template v-if="detail"> — {{ formatAbsoluteTime(detail.run.startedAt) }}</template>
      </h3>
      <button type="button" class="text-gray-400 hover:text-gray-600" aria-label="Close run details"
              @click="emit('close')">✕</button>
    </div>

    <p v-if="loading" class="text-gray-500">Loading…</p>
    <p v-else-if="loadError" class="text-rose-700" role="alert">{{ loadError }}</p>

    <template v-else-if="detail">
      <dl class="grid grid-cols-2 sm:grid-cols-4 gap-x-4 gap-y-1">
        <div v-for="f in overview" :key="f.label">
          <dt class="text-xs text-gray-500">{{ f.label }}</dt>
          <dd class="text-gray-800 tabular-nums">{{ f.value }}</dd>
        </div>
      </dl>

      <div>
        <h4 class="text-xs font-semibold uppercase text-gray-500 mb-1">Entries</h4>
        <dl class="grid grid-cols-2 sm:grid-cols-4 gap-x-4 gap-y-1">
          <div v-for="c in counts" :key="c.label">
            <dt class="text-xs text-gray-500">{{ c.label }}</dt>
            <dd class="font-medium tabular-nums" :class="c.n > 0 ? c.tone : 'text-gray-800'">{{ c.n }}</dd>
          </div>
        </dl>
      </div>

      <p v-for="w in runWarnings(detail.run)" :key="w" class="text-amber-700">{{ w }}</p>
      <p v-if="detail.run.error" class="text-rose-700">{{ detail.run.error }}</p>
      <p :class="drift ? 'text-gray-700' : 'text-gray-500'">
        {{ drift ?? 'No content verification was recorded after this run.' }}
      </p>

      <div v-if="detail.failureReasons.length">
        <h4 class="text-xs font-semibold uppercase text-gray-500 mb-1">Failures by reason</h4>
        <ul class="space-y-1">
          <li v-for="f in detail.failureReasons" :key="f.reason">
            <span class="font-medium tabular-nums text-rose-700">{{ f.count }} ×</span> {{ f.reason }}
            <p class="font-mono text-xs text-gray-500 break-all">e.g. {{ f.example }}</p>
          </li>
        </ul>
      </div>

      <div v-if="sampleGroups.length">
        <h4 class="text-xs font-semibold uppercase text-gray-500 mb-1">Sample entries</h4>
        <details v-for="g in sampleGroups" :key="g.action" class="mb-1">
          <summary class="cursor-pointer text-gray-700">
            {{ g.label }} — {{ g.dns.length < g.total ? `first ${g.dns.length} of ${g.total}` : g.total }}
          </summary>
          <ul class="font-mono text-xs text-gray-600 list-disc pl-5 mt-1">
            <li v-for="dn in g.dns" :key="dn">{{ dn }}</li>
          </ul>
        </details>
      </div>

      <div>
        <h4 class="text-xs font-semibold uppercase text-gray-500 mb-1">Settings at run time</h4>
        <dl v-if="settingsRows.length" class="grid grid-cols-1 sm:grid-cols-2 gap-x-4 gap-y-1">
          <div v-for="s in settingsRows" :key="s.label">
            <dt class="text-xs text-gray-500">{{ s.label }}</dt>
            <dd class="font-mono text-xs text-gray-800 break-all">{{ s.value }}</dd>
          </div>
        </dl>
        <p v-else class="text-gray-500">Not recorded for this run.</p>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { formatAbsoluteTime } from '@/composables/useRelativeTime'
import { getReconcileRun, type SyncPreviewAction, type SyncReconcileRunDetail } from '@/api/sync'
import {
  OUTCOME_LABELS, driftSummary, elapsed, errMsg, outcomeTotal, runWarnings, statusLabel, triggerLabel,
} from './reconcileRunFormat'

const props = defineProps<{ setId: string; runId: string }>()
const emit = defineEmits<{ close: [] }>()

const detail = ref<SyncReconcileRunDetail | null>(null)
const loading = ref(false)
const loadError = ref<string | null>(null)

const overview = computed(() => {
  const r = detail.value?.run
  if (!r) return []
  return [
    { label: 'Status', value: statusLabel(r.status) },
    { label: 'Trigger', value: triggerLabel(r) },
    { label: 'Ran on', value: r.instanceName ?? '—' },
    { label: 'Finished', value: r.finishedAt ? formatAbsoluteTime(r.finishedAt) : '—' },
    { label: 'Source scan', value: elapsed(r.startedAt, r.plannedAt) },
    { label: 'Applying', value: elapsed(r.plannedAt, r.finishedAt) },
    { label: 'Total', value: elapsed(r.startedAt, r.finishedAt) },
  ]
})

const counts = computed(() => {
  const r = detail.value?.run
  if (!r) return []
  return [
    { label: 'Source entries', n: r.sourceCount, tone: '' },
    { label: 'Processed', n: r.processed, tone: '' },
    { label: 'Managed before', n: r.managedBefore, tone: '' },
    { label: 'Managed after', n: r.managedAfter ?? 0, tone: '' },
    { label: 'Added', n: r.added, tone: 'text-emerald-700' },
    { label: 'Modified', n: r.modified, tone: 'text-blue-700' },
    { label: 'Moved', n: r.moved, tone: 'text-blue-700' },
    { label: 'Deleted', n: r.deleted, tone: 'text-rose-700' },
    { label: 'Held for review', n: r.heldForReview, tone: 'text-amber-700' },
    { label: 'Already matched', n: r.noChange, tone: '' },
    { label: 'Unchanged', n: r.unchanged, tone: '' },
    { label: 'Forgotten', n: r.forgotten, tone: '' },
    { label: 'Failed', n: r.failed, tone: 'text-rose-700' },
    { label: 'Errored (retried)', n: r.faulted, tone: 'text-rose-700' },
    { label: 'Skipped (disabled)', n: r.skipped, tone: 'text-amber-700' },
  ]
})

const drift = computed(() => (detail.value ? driftSummary(detail.value.run) : null))

const SAMPLE_ORDER: SyncPreviewAction[] = ['ERROR', 'REVIEW', 'DELETE', 'ADD', 'MODIFY', 'MOVE', 'FORGET', 'NO_CHANGE']

const sampleGroups = computed(() => {
  const d = detail.value
  if (!d) return []
  return SAMPLE_ORDER
    .filter((a) => (d.samples[a]?.length ?? 0) > 0)
    .map((a) => ({ action: a, label: OUTCOME_LABELS[a], dns: d.samples[a] ?? [], total: outcomeTotal(d.run, a) }))
})

const settingsRows = computed(() => {
  const s = detail.value?.settings
  if (!s) return []
  const rows: { label: string; value: string }[] = [
    { label: 'Delete policy', value: s.deletePolicy ?? '—' },
    { label: 'Source scope base DN', value: s.objectScopeBaseDn ?? '(directory base)' },
    { label: 'Source scope', value: s.objectScope ?? 'SUB (default)' },
    { label: 'Applicability filter', value: s.applicabilityFilter ?? '—' },
    { label: 'Target base DN', value: s.targetBaseDn ?? '(directory base)' },
    { label: 'Identity key', value: s.identityKey ?? '(type default)' },
    { label: 'Source anchor attribute', value: s.sourceAnchorAttribute ?? '—' },
    { label: 'Reconcile cadence', value: s.reconcileCadenceSeconds != null ? `${s.reconcileCadenceSeconds}s` : '(global default)' },
    {
      label: 'Delete guard',
      value: `more than ${s.guardMaxDeletes} deletes, or more than ${s.guardMaxDeletePercent}% of a managed set `
        + `of at least ${s.guardMinPopulation}`,
    },
  ]
  return rows
})

async function load() {
  loading.value = true
  loadError.value = null
  try {
    const { data } = await getReconcileRun(props.setId, props.runId)
    detail.value = data
  } catch (e) {
    detail.value = null
    loadError.value = errMsg(e, 'Could not load the run details')
  } finally {
    loading.value = false
  }
}

watch(() => [props.setId, props.runId], load, { immediate: true })
</script>
