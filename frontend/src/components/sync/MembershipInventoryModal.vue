<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  Server-side paged + filtered membership inventory for one sync set, in a modal.
  State filter is a row of clickable count chips; the search box filters tracked
  rows and — when nothing matches — offers to recompute the query as an untracked
  source DN / identity (replacing the old standalone recompute field).
-->
<template>
  <AppModal v-model="show" :title="`Membership inventory — ${set?.name ?? ''}`" size="xl"
            fill storage-key="sync-membership-inventory">
    <div class="space-y-3">
      <div class="flex items-center justify-between gap-2 flex-wrap -mt-1">
        <p class="text-xs text-gray-500 font-mono">{{ sourceName }} → {{ targetName }}</p>
        <div class="flex items-center gap-2">
          <button class="btn-secondary btn-compact" :disabled="reconciling || reconcileRunning"
                  title="Sync the whole set now instead of waiting for the schedule. Skips entries whose source hasn't changed since the sync last wrote them, so it doesn't repair entries changed or deleted directly on the target"
                  @click="reconcile">
            {{ reconciling || reconcileRunning ? 'Reconciling…' : 'Reconcile now' }}
          </button>
          <button class="btn-secondary btn-compact" :disabled="rechecking"
                  title="Forget what the sync last wrote, so the next reconcile (within about a minute) re-reads every target entry and repairs those missing or changed on the target, e.g. after a restore from backup or edits made outside the sync. Doesn't remove entries the restore brought back; Verify contents lists them as orphaned"
                  @click="recheck">
            {{ rechecking ? 'Re-checking…' : 'Re-check target' }}
          </button>
        </div>
      </div>

      <!-- Live progress of Reconcile now / Re-check target / Verify contents (and a reconcile already running). -->
      <SyncActivityProgress v-if="progress" v-bind="progress" />

      <!--
        Two questions, two tabs. Reconciliation: what the sync did and what it
        manages (its runs, the membership index, what's not in the set). Drift:
        whether the target actually matches the source right now (a live
        comparison of both directories, independent of the index).
      -->
      <div class="flex gap-1 border-b border-gray-200" role="tablist" aria-label="Sync set views">
        <button v-for="t in tabs" :id="`sync-tab-${t.id}`" :key="t.id" type="button" role="tab"
                :aria-selected="tab === t.id" :aria-controls="`sync-panel-${t.id}`"
                :class="['px-4 py-2 text-sm font-medium border-b-2 -mb-px transition-colors inline-flex items-center gap-1.5',
                         tab === t.id ? 'border-blue-600 text-blue-600' : 'border-transparent text-gray-500 hover:text-gray-700']"
                @click="tab = t.id">
          {{ t.label }}
          <span v-if="t.id === 'drift' && driftTotal" class="badge-amber tabular-nums"
                :title="`${driftTotal} drifting at the last content check`">{{ driftTotal }}</span>
        </button>
      </div>

      <div v-if="tab === 'drift' && set" id="sync-panel-drift" role="tabpanel" aria-labelledby="sync-tab-drift">
        <SyncDriftPanel :set="set" :live="verifyResult" :verifying="verifying" :refresh-key="driftKey"
                        :can-diff="canDiff" :source-directory-id="sourceDirectoryId ?? null"
                        :target-directory-id="targetDirectoryId ?? null" @verify="verify" />
      </div>

      <div v-show="tab === 'reconciliation'" id="sync-panel-reconciliation" role="tabpanel"
           aria-labelledby="sync-tab-reconciliation" class="space-y-3">
      <!-- What the set's last reconcile runs did (counts, warnings, recent history). -->
      <ReconcileRunHistory :set-id="set?.id ?? null" :refresh-key="historyKey"
                           @show-not-in-set="setStateFilter(NOT_IN_SET)" />

      <!-- State filter chips with counts -->
      <div class="flex flex-wrap items-center gap-1.5">
        <button v-for="c in chips" :key="c.key" type="button"
                class="inline-flex items-center gap-1.5 text-xs font-semibold px-2.5 py-1 rounded-full"
                :class="[c.chip, stateFilter === c.value ? 'ring-2 ring-offset-1 ring-blue-500' : '']"
                :aria-pressed="stateFilter === c.value" :title="c.hint"
                @click="setStateFilter(c.value)">
          <span v-if="c.dot" class="w-1.5 h-1.5 rounded-full" :class="c.dot" aria-hidden="true"></span>
          {{ c.label }} <span class="tabular-nums">{{ c.n }}</span>
        </button>
      </div>

      <input v-model="search" class="input input-sm w-full"
             :placeholder="notInSetView ? 'Filter by DN…' : 'Filter by identity or DN…'"
             aria-label="Filter memberships" />

      <!-- In-scope source entries the set doesn't manage, from the latest complete reconcile. -->
      <template v-if="notInSetView">
        <div class="text-xs text-gray-600 space-y-0.5">
          <p v-if="!notInSet?.runId">No complete reconcile has been recorded for this set yet.</p>
          <template v-else>
            <p>
              As of the last complete reconcile<template v-if="notInSet.finishedAt">,
                <RelativeTime :value="notInSet.finishedAt" /></template>:
              <span class="font-medium tabular-nums">{{ notInSet.total }}</span> in-scope source
              {{ notInSet.total === 1 ? 'entry isn\'t' : 'entries aren\'t' }} managed by this set
              <template v-if="notInSet.reasons.length">
                —
                <template v-for="(g, i) in notInSet.reasons" :key="g.reason">
                  <template v-if="i > 0"> · </template>
                  <span class="tabular-nums">{{ g.count }}</span> {{ g.label.toLowerCase() }}
                </template>
              </template>
            </p>
            <p v-if="!notInSet.allDnsRecorded && notInSet.total > notInSet.matched && !search.trim()"
               class="text-gray-500">
              Showing up to {{ notInSet.sampleLimit }} examples per reason. To list them all, set the sync set's
              <span class="font-medium">Logging level</span> to <span class="font-medium">Detailed</span>; the next
              reconcile keeps every one.
            </p>
          </template>
        </div>
        <DataTable :columns="notInSetCols" :rows="notInSetRows" :loading="loading" row-key="sourceDn"
                   empty-text="No not-in-set entries match." empty-icon="users">
          <template #cell-sourceDn="{ row }">
            <span class="font-mono">{{ row.sourceDn }}</span>
          </template>
        </DataTable>
      </template>

      <template v-else>
        <!--
          Bulk actions on the selected rows (this page only). Recompute is named
          for what it does to the selection; Delete target only appears, and only
          acts on, held deletes among it.
        -->
        <div class="flex items-center gap-2 flex-wrap" data-testid="bulk-actions">
          <span class="text-xs text-gray-600 tabular-nums mr-1">
            {{ selectedRows.length ? `${selectedRows.length} selected` : 'Select rows to act on them' }}
          </span>
          <button class="btn-secondary btn-compact" :disabled="!selectedRows.length || bulkBusy"
                  :title="recomputeAction.hint" @click="bulkRecompute">
            {{ recomputeAction.label }}<template v-if="selectedRows.length"> ({{ selectedRows.length }})</template>
          </button>
          <button v-if="heldDeletes.length" class="btn-danger btn-compact" :disabled="bulkBusy"
                  title="The source entries left scope. Approve the held deletion of their target entries."
                  @click="bulkApproveDelete">
            Delete target ({{ heldDeletes.length }})
          </button>
          <button class="btn-secondary btn-compact" :disabled="!selectedRows.length || bulkBusy"
                  title="Forget the portal's record of these entries; the target isn't changed. Held deletes keep their target entry, which this set then no longer manages."
                  @click="bulkStopTracking">
            Stop tracking<template v-if="selectedRows.length"> ({{ selectedRows.length }})</template>
          </button>
          <button v-if="selectedRows.length" class="btn-neutral btn-compact" :disabled="bulkBusy"
                  @click="selected = new Set()">
            Clear selection
          </button>
        </div>

        <DataTable v-model:selected-keys="selected" :columns="cols" :rows="rows" :loading="loading" row-key="identity"
                   selectable empty-text="No membership rows match." empty-icon="users">
          <template #cell-identity="{ row }">
            <span class="font-mono">{{ row.identity }}</span>
          </template>
          <template #cell-state="{ row }">
            <span class="px-2 py-0.5 rounded text-xs" :class="stateClass(row.state)"
                  :title="STATE_META[row.state]?.hint">{{ stateLabel(row.state) }}</span>
          </template>
          <template #cell-sourceDn="{ row }">
            <span class="font-mono">{{ row.sourceDn }}</span>
          </template>
          <template #cell-targetDn="{ row }">
            <span class="font-mono">{{ row.targetDn }}</span>
          </template>
          <template #cell-failReason="{ row }">
            <span class="cell-muted line-clamp-2" :title="row.failReason || undefined">{{ row.failReason || '—' }}</span>
          </template>
        </DataTable>
      </template>

      <!-- Untracked-key affordance: recompute the search term directly. -->
      <div v-if="!notInSetView && !loading && !rows.length && search.trim()" class="text-center pb-1">
        <button class="btn-secondary btn-compact" @click="recomputeQuery">
          Recompute "{{ search.trim() }}"
        </button>
        <p class="text-xs text-gray-500 mt-1">
          No tracked row matches — recompute it as a source DN or identity to pull it in.
        </p>
      </div>

      <div class="flex items-center justify-between gap-2 flex-wrap text-xs text-gray-600 pt-1 border-t border-gray-100">
        <label class="flex items-center gap-1.5">
          Rows
          <select v-model.number="size" class="input input-sm py-0.5 w-20" aria-label="Rows per page">
            <option :value="25">25</option>
            <option :value="50">50</option>
            <option :value="100">100</option>
            <option :value="200">200</option>
          </select>
          <span class="text-gray-500">of {{ total }}</span>
        </label>
        <div class="flex items-center gap-2">
          <button class="btn-neutral btn-compact" :disabled="page <= 0" @click="page--">Prev</button>
          <span class="tabular-nums">Page {{ page + 1 }} of {{ Math.max(totalPages, 1) }}</span>
          <button class="btn-neutral btn-compact" :disabled="page >= totalPages - 1" @click="page++">Next</button>
        </div>
      </div>
      </div>
    </div>
  </AppModal>
</template>

<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import AppModal from '@/components/AppModal.vue'
import DataTable from '@/components/DataTable.vue'
import ReconcileRunHistory from '@/components/sync/ReconcileRunHistory.vue'
import SyncDriftPanel from '@/components/sync/SyncDriftPanel.vue'
import SyncActivityProgress from '@/components/sync/SyncActivityProgress.vue'
import RelativeTime from '@/components/RelativeTime.vue'
import { useNotificationStore } from '@/stores/notifications'
import { useConfirm } from '@/composables/useConfirm'
import { useAuthStore } from '@/stores/auth'
import {
  listMemberships, startReconcile, recheckTarget, recomputeKey, dismissMembership, verifyContents, approveDelete,
  getSyncSetActivity, listNotInSet,
  type SyncSet, type Membership, type MembershipState, type SyncVerifyResult, type SyncSetActivity,
  type SyncNotInSetPage,
} from '@/api/sync'

const show = defineModel<boolean>('show', { default: false })
// `initialState` seeds the state filter when the modal opens — used by the
// dashboard deep links (e.g. land already filtered to FAILED / REVIEW).
const props = defineProps<{
  set: SyncSet | null
  sourceName: string
  targetName: string
  initialState?: '' | MembershipState
  /** The tab to open on (the drifting-sets list opens on Drift). */
  initialTab?: 'reconciliation' | 'drift'
  /** For links to entries in the Directory Browser from the Drift tab. */
  sourceDirectoryId?: string | null
  targetDirectoryId?: string | null
}>()
const emit = defineEmits<{ changed: [] }>()

const notif = useNotificationStore()
const confirm = useConfirm()
const auth = useAuthStore()
/** Directory Diff is gated by its own superadmin permission. */
const canDiff = computed(() => auth.hasSuperadminPermission('superadmin.manage_directory_diff'))

const rows = ref<Membership[]>([])
const total = ref(0)
const totalPages = ref(0)
const page = ref(0)
const size = ref(50)
/** The chip that lists in-scope source entries the set doesn't manage, instead of membership rows. */
const NOT_IN_SET = 'NOT_IN_SET' as const
type StateFilter = '' | MembershipState | typeof NOT_IN_SET
const stateFilter = ref<StateFilter>('')
const notInSet = ref<SyncNotInSetPage | null>(null)
const notInSetView = computed<boolean>(() => stateFilter.value === NOT_IN_SET)
const notInSetRows = computed(() => notInSet.value?.entries ?? [])
const notInSetCols = [
  { key: 'sourceDn', label: 'Source DN' },
  { key: 'label', label: 'Reason' },
]
const search = ref('')
const loading = ref(false)
const reconciling = ref(false)
const rechecking = ref(false)

/** Identities of the rows selected for a bulk action (kept to rows on the current page). */
const selected = ref<Set<unknown>>(new Set())
const selectedRows = computed<Membership[]>(() => rows.value.filter((r) => selected.value.has(r.identity)))
const heldDeletes = computed<Membership[]>(() => selectedRows.value.filter(isHeldDelete))
/** A bulk action is sending its requests. */
const bulkBusy = ref(false)

/** How long a recompute watches its rows for the worker's result, and how often it looks. */
const ROW_WATCH_MS = 20_000
const ROW_POLL_MS = 2_000
// The latest recompute watch: a newer one, or closing the inventory, ends the older one.
let batchSeq = 0
let unmounted = false
const verifying = ref(false)
const verifyResult = ref<SyncVerifyResult | null>(null)

type Tab = 'reconciliation' | 'drift'
const tab = ref<Tab>('reconciliation')
const tabs: Array<{ id: Tab; label: string }> = [
  { id: 'reconciliation', label: 'Reconciliation' },
  { id: 'drift', label: 'Drift' },
]
// Bumped when a content check finishes, so the Drift tab reloads its stored items.
const driftKey = ref(0)
/** Drift at the last complete content check, for the tab's badge. */
const driftTotal = computed<number>(() => {
  const v = props.set?.verify
  return v?.at ? (v.missing ?? 0) + (v.orphan ?? 0) + (v.mismatch ?? 0) : 0
})
// Bumped to make ReconcileRunHistory reload (on open, and after a reconcile).
const historyKey = ref(0)

const cols = [
  { key: 'identity', label: 'Identity' },
  { key: 'state', label: 'State' },
  { key: 'sourceDn', label: 'Source DN' },
  { key: 'targetDn', label: 'Target DN' },
  { key: 'failReason', label: 'Reason' },
]

interface ChipMeta { label: string; chip: string; dot: string; hint: string }
// Labels for the API's states. APPLIED shows as "Synced": the sync wrote the
// entry and tracks it; whether the target still matches is what Verify contents checks.
const STATE_META: Record<string, ChipMeta> = {
  APPLIED: {
    label: 'Synced', chip: 'bg-green-50 text-green-700', dot: 'bg-green-600',
    hint: 'Written by the sync and tracked. Verify contents checks that the target still matches.',
  },
  PENDING: {
    label: 'Pending', chip: 'bg-gray-100 text-gray-600', dot: 'bg-gray-400',
    hint: 'Queued: the sync hasn\'t written it yet.',
  },
  REVIEW: {
    label: 'Review', chip: 'bg-amber-50 text-amber-800', dot: 'bg-amber-500',
    hint: 'Held for an operator decision: an ambiguous match, a placement conflict or a held delete.',
  },
  FAILED: {
    label: 'Failed', chip: 'bg-red-50 text-red-700', dot: 'bg-red-600',
    hint: 'The last write failed; the Reason column says why. Retried on the next reconcile.',
  },
}

function stateLabel(state: MembershipState): string {
  return STATE_META[state]?.label ?? state
}
const STATE_ORDER = ['FAILED', 'REVIEW', 'PENDING', 'APPLIED'] as const

function stateClass(state: MembershipState): string {
  return {
    APPLIED: 'bg-green-100 text-green-800',
    FAILED: 'bg-red-100 text-red-800',
    REVIEW: 'bg-amber-100 text-amber-800',
    PENDING: 'bg-gray-100 text-gray-700',
  }[state]
}

const chips = computed(() => {
  const c = props.set?.stateCounts ?? {}
  // REVIEW_DELETE counts the held deletes among the REVIEW rows: a subset, not a state.
  const all = Object.entries(c).reduce((a, [k, v]) => (k === 'REVIEW_DELETE' ? a : a + v), 0)
  return [
    { key: 'all', value: '' as StateFilter, label: 'All', n: all, dot: '', chip: 'bg-gray-100 text-gray-700',
      hint: 'Every entry this set tracks.' },
    ...STATE_ORDER.map((k) => ({
      key: k, value: k as StateFilter, label: STATE_META[k].label,
      n: c[k] ?? 0, dot: STATE_META[k].dot, chip: STATE_META[k].chip, hint: STATE_META[k].hint,
    })),
    // Not membership rows: source entries the last complete reconcile found outside the set.
    { key: NOT_IN_SET, value: NOT_IN_SET as StateFilter, label: 'Not in set', n: notInSet.value?.total ?? 0,
      dot: 'bg-slate-400', chip: 'bg-slate-50 text-slate-700',
      hint: 'In-scope source entries this set doesn\'t manage, and why (as of the last complete reconcile).' },
  ]
})

function errMsg(e: unknown): string {
  const err = e as { response?: { data?: { detail?: string; message?: string } } }
  return err.response?.data?.detail || err.response?.data?.message || 'Request failed'
}

/** The filter, search and page the table shows, as one comparable key. */
function viewKey(): string {
  return JSON.stringify([stateFilter.value, search.value.trim(), page.value, size.value])
}

/** The current page of membership rows, as the table's filter, search and paging ask for it. */
async function fetchPage(setId: string) {
  const { data } = await listMemberships(setId, {
    state: (stateFilter.value || undefined) as MembershipState | undefined,
    q: search.value.trim() || undefined,
    page: page.value,
    size: size.value,
  })
  return data
}

/** Show a page of rows; the selection keeps only the rows still on it. */
function applyPage(data: { content: Membership[]; totalElements: number; totalPages: number }) {
  rows.value = data.content
  total.value = data.totalElements
  totalPages.value = data.totalPages
  const onPage = new Set<unknown>(data.content.map((r) => r.identity))
  const kept = [...selected.value].filter((k) => onPage.has(k))
  if (kept.length !== selected.value.size) selected.value = new Set(kept)
}

async function load() {
  if (!props.set) return
  if (notInSetView.value) return loadNotInSet()
  loading.value = true
  try {
    applyPage(await fetchPage(props.set.id))
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    loading.value = false
  }
}

/** The set's not-in-set entries (the chip's count, or the table when its chip is selected). */
async function loadNotInSet() {
  if (!props.set) return
  const listing = notInSetView.value
  if (listing) loading.value = true
  try {
    const { data } = await listNotInSet(props.set.id, listing
      ? { q: search.value.trim() || undefined, page: page.value, size: size.value }
      : { page: 0, size: 1 })
    notInSet.value = data
    if (listing) {
      total.value = data.matched
      totalPages.value = Math.ceil(data.matched / Math.max(1, data.size))
    }
  } catch (e) {
    if (listing) notif.error(errMsg(e))
  } finally {
    if (listing) loading.value = false
  }
}

function setStateFilter(v: StateFilter) {
  stateFilter.value = v
  page.value = 0
  load()
}

watch([size], () => { page.value = 0; load() })
watch(page, load)

let searchTimer: ReturnType<typeof setTimeout> | undefined
watch(search, () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => { page.value = 0; load() }, 250)
})

/**
 * Start a reconcile and follow it through the set's activity: the request
 * returns at once, so a long run can't be cut off by a proxy's timeout. Its
 * outcome is reported, and the inventory refreshed, when the run finishes.
 */
async function reconcile() {
  if (!props.set) return
  reconciling.value = true
  const since = serverNow()
  watchSince.value = since
  try {
    await startReconcile(props.set.id)
    awaitingRun.value = { since, label: 'Reconcile' }
    watchActivity()
  } catch (e) {
    reconciling.value = false
    notif.error(errMsg(e))
  }
}
/**
 * Forget what the set last wrote, so the next reconcile re-reads every target
 * entry and re-applies the ones that differ (missing → added, stale → modified).
 * For a target restored from a backup, which a normal reconcile trusts the
 * index over.
 */
async function recheck() {
  if (!props.set) return
  const ok = await confirm({
    title: 'Re-check the target?',
    message: `The next reconcile of "${props.set.name}" re-reads every entry it manages on ${props.targetName} `
      + 'instead of trusting what it last wrote, and re-applies any that are missing or differ — use this '
      + 'after the target was restored from a backup. It takes longer than a normal reconcile. Entries the '
      + 'sync had deleted that a restore brought back are not removed: Verify contents lists them as orphaned.',
    confirmLabel: 'Re-check target',
  })
  if (!ok) return
  rechecking.value = true
  try {
    const since = serverNow()
    const { data } = await recheckTarget(props.set.id)
    if (data.reconcileQueued) {
      notif.success(`Re-check queued: the next reconcile (within about a minute) re-reads ${data.entries} entries`)
      awaitingRun.value = { since, label: 'Re-check' }
      watchActivity()
    } else {
      notif.success(`Re-check set up for ${data.entries} entries; it runs once the set and its link are enabled`)
    }
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    rechecking.value = false
  }
}

async function verify() {
  if (!props.set) return
  verifying.value = true
  watchSince.value = serverNow()
  watchActivity()
  try {
    const { data } = await verifyContents(props.set.id)
    verifyResult.value = data
    const mismatches = data.missingOnTarget + data.orphanOnTarget + data.contentMismatches
    if (mismatches > 0) {
      notif.error(`Verification found ${mismatches} mismatch(es) between source and target`)
    } else {
      notif.success('Verification passed — source and target agree')
    }
    // The set's counts and the Drift tab's stored items changed.
    driftKey.value++
    emit('changed')
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    verifying.value = false
    void refreshActivity()
  }
}

// ── Live progress ───────────────────────────────────────────────────────────
// While an action runs (or a reconcile already is), the set's activity is
// polled: the latest reconcile run and content check, each with its phase,
// counts and the entry it's on. Re-check target only queues a reconcile, so it
// waits for that run to start and finish.

const POLL_MS = 1500
/** How long Re-check target waits for its reconcile to start before giving up watching. */
const RECHECK_WAIT_MS = 5 * 60 * 1000
const activity = ref<SyncSetActivity | null>(null)
/** Browser-to-server clock offset, from the last activity read. */
const clockOffset = ref<number>(0)
/** Server time when the current action was started: older runs aren't its progress. */
const watchSince = ref<number>(0)
/** A run an action started (Reconcile now) or queued (Re-check target), until it finishes. */
const awaitingRun = ref<{ since: number; label: 'Reconcile' | 'Re-check' } | null>(null)
let pollTimer: ReturnType<typeof setTimeout> | null = null

function serverNow(): number {
  return Date.now() + clockOffset.value
}

/** Started at or after `since` (with a little slack for clocks and rounding). */
function startedSince(at: string | null | undefined, since: number): boolean {
  return !!at && Date.parse(at) >= since - 5000
}

const reconcileRunning = computed<boolean>(() => activity.value?.reconcile?.status === 'RUNNING')
const verifyRunning = computed<boolean>(() =>
  activity.value?.verify?.status === 'RUNNING' && (verifying.value || reconciling.value))
const needsWatch = computed<boolean>(() =>
  reconciling.value || verifying.value || !!awaitingRun.value || reconcileRunning.value)

async function refreshActivity() {
  if (!props.set || !show.value) return
  try {
    const { data } = await getSyncSetActivity(props.set.id)
    const wasRunning = reconcileRunning.value
    activity.value = data
    clockOffset.value = data.asOf ? Date.parse(data.asOf) - Date.now() : 0
    if (awaitingRun.value) {
      checkRunDone(data)
    } else if (wasRunning && !reconcileRunning.value) {
      // A run this inventory didn't start (a scheduled one, or one started
      // before it opened) finished: its counts changed too.
      refreshAfterRun()
    }
  } catch {
    // Progress is a nicety: a failed read leaves the last one on screen.
  }
  if (needsWatch.value) watchActivity()
}

function watchActivity() {
  if (pollTimer !== null || !show.value) return
  pollTimer = setTimeout(() => {
    pollTimer = null
    void refreshActivity()
  }, POLL_MS)
}

function stopWatching() {
  if (pollTimer !== null) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

/** The rows, chips and Not in set as a finished run left them. */
function refreshAfterRun() {
  historyKey.value++
  void load()
  void loadNotInSet()
  emit('changed')
}

/** The run an action started or queued finished: say what it did and refresh the inventory. */
function checkRunDone(data: SyncSetActivity) {
  const waiting = awaitingRun.value
  if (!waiting) return
  const run = data.reconcile
  if (run && startedSince(run.startedAt, waiting.since) && run.status !== 'RUNNING') {
    awaitingRun.value = null
    reconciling.value = false
    if (run.status === 'COMPLETE') {
      notif.success(`${waiting.label} finished: ${run.added} added, ${run.modified + run.moved} updated, `
        + `${run.noChange + run.unchanged} already matched`)
    } else {
      notif.error(run.error || `The ${waiting.label === 'Re-check' ? 're-check ' : ''}reconcile failed`)
    }
    refreshAfterRun()
  } else if (!run || !startedSince(run.startedAt, waiting.since)) {
    if (serverNow() - waiting.since > RECHECK_WAIT_MS) {
      awaitingRun.value = null
      reconciling.value = false
      notif.warning(waiting.label === 'Re-check'
        ? 'The re-check reconcile hasn\'t started yet. It runs on the next scheduler tick; check Last reconcile later.'
        : 'The reconcile hasn\'t started yet. Check Last reconcile later.')
    }
  }
}

const RECONCILE_HINT = 'A large group can take a while on its own: each member is looked up.'

/** What the progress line shows now, or null when nothing is running. */
const progress = computed(() => {
  const a = activity.value
  const asOf = a?.asOf ?? null
  const v = a?.verify
  if (v && verifyRunning.value && startedSince(v.startedAt, watchSince.value)) {
    const after = reconciling.value ? ' after the reconcile' : ''
    const heading = v.phase === 'SOURCE' ? `Checking contents${after}: reading the source (${v.processed} entries read)…`
      : v.phase === 'TARGET' ? `Checking contents${after}: reading the target…`
        : `Checking contents${after}: comparing entries: ${v.processed} of ${v.total}…`
    return {
      heading, processed: v.processed, total: v.phase === 'COMPARING' ? v.total : null,
      currentDn: v.currentDn, currentLabel: 'Now comparing',
      startedAt: v.startedAt, heartbeatAt: v.heartbeatAt, asOf,
    }
  }
  const r = a?.reconcile
  if (r && r.status === 'RUNNING') {
    const who = awaitingRun.value?.label === 'Re-check' ? 'Re-check'
      : r.trigger === 'SCHEDULED' ? 'Scheduled reconcile' : 'Reconcile'
    const heading = r.phase === 'APPLYING' ? `${who}: applying entries: ${r.processed} of ${r.sourceCount}…`
      : r.phase === 'SWEEPING'
        ? `${who}: checking managed entries no longer in the source (${Math.max(0, r.processed - r.sourceCount)} checked)…`
        : r.phase === 'REFERENCES'
          ? `${who}: queuing entries that refer to changed ones…`
          : `${who}: scanning the source…`
    return {
      heading, processed: r.processed, total: r.phase === 'APPLYING' ? r.sourceCount : null,
      currentDn: r.currentDn, currentLabel: r.phase === 'REFERENCES' ? 'Finding referrers of' : 'Now checking',
      startedAt: r.startedAt, heartbeatAt: r.heartbeatAt ?? r.startedAt, asOf,
      hint: r.phase === 'APPLYING' ? RECONCILE_HINT : '',
    }
  }
  if (awaitingRun.value?.label === 'Re-check') {
    return { heading: 'Re-check queued: waiting for the next reconcile to start (within about a minute)…', asOf }
  }
  if (reconciling.value) return { heading: 'Starting the reconcile…', asOf }
  if (verifying.value) return { heading: 'Starting the content check…', asOf }
  return null
})

// Reset and load whenever the modal opens or the set changes. The state filter
// seeds from `initialState` (deep links open pre-filtered); '' means show all.
watch([show, () => props.set?.id], ([open]) => {
  if (open && props.set) {
    stateFilter.value = props.initialState ?? ''
    tab.value = props.initialTab ?? 'reconciliation'
    search.value = ''
    page.value = 0
    verifyResult.value = null
    notInSet.value = null
    awaitingRun.value = null
    reconciling.value = false
    historyKey.value++
    selected.value = new Set()
    load()
    if (!notInSetView.value) void loadNotInSet()
    void refreshActivity()
  } else {
    stopWatching()
    batchSeq++
  }
}, { immediate: true })
onBeforeUnmount(() => {
  unmounted = true
  stopWatching()
  batchSeq++
})
// ── Bulk actions on the selected rows ───────────────────────────────────────

function isHeldDelete(row: Membership): boolean {
  return row.state === 'REVIEW' && row.reviewKind === 'PENDING_DELETE'
}

type RecomputeKind = 'RECHECK' | 'RETRY_MATCH' | 'RETRY' | 'RESYNC' | 'MIXED'
interface NamedAction { label: string; hint: string }

const RECOMPUTE: Record<RecomputeKind, NamedAction> = {
  RECHECK: {
    label: 'Re-check',
    hint: 'Re-read the source entries: any back in scope are re-applied; the rest stay held for deletion.',
  },
  RETRY_MATCH: {
    label: 'Retry match',
    hint: 'Run target matching again, once the conflicting target entries are resolved.',
  },
  RETRY: { label: 'Retry', hint: 'Retry the failed writes once their cause is fixed.' },
  RESYNC: { label: 'Re-sync', hint: 'Re-read the source entries and bring the target entries in line now.' },
  MIXED: {
    label: 'Recompute',
    hint: 'Re-read the source entries and bring the target in line now: retries failed writes and target '
      + 'matching, and re-checks held deletes.',
  },
}

function recomputeKind(row: Membership): RecomputeKind {
  if (isHeldDelete(row)) return 'RECHECK'
  if (row.state === 'REVIEW' && (row.reviewKind === 'AMBIGUOUS_MATCH' || row.reviewKind === 'PLACEMENT_CONFLICT')) {
    return 'RETRY_MATCH'
  }
  if (row.state === 'FAILED') return 'RETRY'
  if (row.state === 'REVIEW') return 'MIXED'
  return 'RESYNC'
}

/**
 * The recompute, named for what it does to the selection: Re-check for held
 * deletes, Retry match, Retry for failed writes, Re-sync for the rest, and
 * Recompute when the selection mixes them.
 */
const recomputeAction = computed<NamedAction>(() => {
  const kinds = new Set(selectedRows.value.map(recomputeKind))
  return kinds.size === 1 ? RECOMPUTE[[...kinds][0]] : RECOMPUTE.MIXED
})

/**
 * Run `fn` for each row in turn (one request at a time, so a large selection
 * doesn't flood the server). A row fails when `fn` throws or returns a message.
 */
async function eachRow(
  list: Membership[], fn: (row: Membership) => Promise<string | void>,
): Promise<{ row: Membership; error: string }[]> {
  const failures: { row: Membership; error: string }[] = []
  for (const row of list) {
    if (unmounted) break
    try {
      const error = await fn(row)
      if (error) failures.push({ row, error })
    } catch (e) {
      failures.push({ row, error: errMsg(e) })
    }
  }
  return failures
}

/** "2 of 5 …: <first reason>" for the rows a bulk action couldn't do. */
function failureMessage(what: string, failures: { error: string }[], of: number): string {
  return `${failures.length} of ${of} ${what}: ${failures[0].error}`
}

/**
 * Queue a recompute of each selected row, then watch them: the worker picks
 * the requests up within about ten seconds, and the table only shows the
 * result once reloaded.
 */
async function bulkRecompute() {
  const setId = props.set?.id
  const batch = selectedRows.value
  if (!setId || !batch.length) return
  const { label } = recomputeAction.value
  bulkBusy.value = true
  let failures: { row: Membership; error: string }[]
  try {
    failures = await eachRow(batch, async (row) => { await recomputeKey(setId, row.identity) })
  } finally {
    bulkBusy.value = false
  }
  if (failures.length) notif.error(failureMessage(`couldn't be queued (${label})`, failures, batch.length))
  const queued = batch.filter((r) => !failures.some((f) => f.row === r))
  if (!queued.length) return
  selected.value = new Set()
  notif.success(queued.length === 1
    ? `${label} queued: watching the row for the result…`
    : `${label} queued for ${queued.length} rows: watching them for the result…`)
  emit('changed')
  await watchBatch(queued, label)
}

/** The row as the server has it now; null when the set no longer tracks it. */
async function fetchRow(setId: string, identity: string): Promise<Membership | null> {
  const { data } = await listMemberships(setId, { q: identity, size: 20 })
  return data.content.find((r) => r.identity === identity) ?? null
}

function rowChanged(before: Membership, now: Membership | null): boolean {
  return !now || now.state !== before.state || now.reviewKind !== before.reviewKind
    || (now.failReason ?? '') !== (before.failReason ?? '')
    || now.sourceDn !== before.sourceDn || now.targetDn !== before.targetDn
}

/**
 * Reload the page until every row in the batch has changed or {@link ROW_WATCH_MS}
 * passes, then say what happened. A row that left the page (another state now,
 * or no longer tracked) is looked up on its own. Changing the page, filter or
 * search, a newer recompute, or closing the inventory ends the watch.
 */
async function watchBatch(before: Membership[], label: string) {
  const setId = props.set?.id
  if (!setId) return
  const ticket = ++batchSeq
  const view = viewKey()
  const current = () => !unmounted && show.value && batchSeq === ticket
    && props.set?.id === setId && viewKey() === view
  const outcomes = new Map<string, Membership | null>()
  const deadline = Date.now() + ROW_WATCH_MS
  while (outcomes.size < before.length && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, ROW_POLL_MS))
    if (!current()) return
    try {
      const data = await fetchPage(setId)
      if (!current()) return
      applyPage(data)
      for (const row of before) {
        if (outcomes.has(row.identity)) continue
        const now = data.content.find((r) => r.identity === row.identity) ?? await fetchRow(setId, row.identity)
        if (rowChanged(row, now)) outcomes.set(row.identity, now)
      }
    } catch {
      continue // a failed look isn't the result; try again
    }
  }
  if (!current()) return
  if (outcomes.size) emit('changed')
  reportBatch(before, outcomes, label)
}

/** Say what a watched recompute did: what each changed row became, and how many didn't change. */
function reportBatch(before: Membership[], outcomes: Map<string, Membership | null>, label: string) {
  if (before.length === 1) {
    const row = before[0]
    if (!outcomes.has(row.identity)) {
      notif.info(unchangedMessage(row, label))
      return
    }
    const now = outcomes.get(row.identity)
    notif.success(now
      ? `${label} done: the row is now ${stateLabel(now.state)}${now.failReason ? ` (${now.failReason})` : ''}`
      : `${label} done: the set no longer tracks this entry`)
    return
  }
  const unchanged = before.filter((r) => !outcomes.has(r.identity))
  const heldNote = unchanged.some(isHeldDelete)
    ? ' Held deletes stay held while the source entry is outside the set\'s filter or scope.'
    : ''
  if (!outcomes.size) {
    notif.info(`${label}: no change after ${ROW_WATCH_MS / 1000} seconds for any of the ${before.length} rows. `
      + `If a reconcile is running, the recomputes may still be queued behind it.${heldNote}`)
    return
  }
  const counts = new Map<string, number>()
  for (const now of outcomes.values()) {
    const what = now ? `now ${stateLabel(now.state)}` : 'no longer tracked'
    counts.set(what, (counts.get(what) ?? 0) + 1)
  }
  const parts = [...counts].map(([what, n]) => `${n} ${what}`)
  if (unchanged.length) {
    notif.info(`${label} done: ${parts.join(', ')}; ${unchanged.length} unchanged after `
      + `${ROW_WATCH_MS / 1000} seconds.${heldNote}`)
  } else {
    notif.success(`${label} done: ${parts.join(', ')}`)
  }
}

/** What a recompute that changed nothing means for the row. */
function unchangedMessage(row: Membership, label: string): string {
  if (isHeldDelete(row)) {
    return `${label}: no change. The source entry is still outside the set's filter or scope, so its `
      + 'deletion stays held for review. If you changed the filter, check it against this entry.'
  }
  return `${label}: no change after ${ROW_WATCH_MS / 1000} seconds; the row is still `
    + `${stateLabel(row.state)}. If a reconcile is running, the recompute may still be queued behind it.`
}

/** Approve the held deletes among the selection: deletes their target entries now. */
async function bulkApproveDelete() {
  const setId = props.set?.id
  const batch = heldDeletes.value
  if (!setId || !batch.length) return
  const n = batch.length
  const others = selectedRows.value.length - n
  const ok = await confirm({
    title: n === 1 ? 'Delete target entry?' : `Delete ${n} target entries?`,
    message: (n === 1
      ? `This deletes ${batch[0].targetDn} from ${props.targetName} now. The source entry has left this `
        + "set's scope, and this approves the held deletion. It can't be undone."
      : `This deletes ${n} entries from ${props.targetName} now. Their source entries have left this `
        + "set's scope, and this approves the held deletions. It can't be undone.")
      + (others ? ` The other ${others} selected ${others === 1 ? 'row isn\'t a held delete and is'
        : 'rows aren\'t held deletes and are'} left alone.` : ''),
    confirmLabel: n === 1 ? 'Delete entry' : `Delete ${n} entries`,
    danger: true,
  })
  if (!ok) return
  bulkBusy.value = true
  const deleted: string[] = []
  const kept: string[] = []
  try {
    const failures = await eachRow(batch, async (row) => {
      const { data } = await approveDelete(setId, row.identity)
      if (data.outcome === 'DELETE') deleted.push(data.targetDn)
      else if (data.outcome === 'FORGET') kept.push(data.targetDn)
      else return data.message || 'The deletion failed'
    })
    if (n === 1) {
      if (deleted.length) notif.success(`Deleted ${deleted[0]}`)
      if (kept.length) notif.success(`Kept ${kept[0]}: another sync identity owns it. Only this row was removed.`)
      if (failures.length) notif.error(failures[0].error)
    } else {
      if (deleted.length || kept.length) {
        notif.success(`Deleted ${deleted.length} target ${deleted.length === 1 ? 'entry' : 'entries'}`
          + (kept.length ? `; kept ${kept.length} that another sync identity owns (only their rows were removed)` : ''))
      }
      if (failures.length) notif.error(failureMessage('deletions failed', failures, n))
    }
    selected.value = new Set()
    await load()
    emit('changed')
  } finally {
    bulkBusy.value = false
  }
}

/**
 * Forget the portal's record of the selected rows; the target isn't changed.
 * For a held delete that keeps the target entry, which the set then no longer manages.
 */
async function bulkStopTracking() {
  const setId = props.set?.id
  const batch = selectedRows.value
  if (!setId || !batch.length) return
  const n = batch.length
  const held = batch.filter(isHeldDelete).length
  const ok = await confirm({
    title: n === 1 ? 'Stop tracking this entry?' : `Stop tracking ${n} entries?`,
    message: `The portal forgets its record of ${n === 1 ? 'this entry' : `these ${n} entries`}; `
      + `${props.targetName} isn't changed. If a source entry is still in scope, the next reconcile picks it `
      + 'up again from scratch.'
      + (held ? ` ${held === 1 ? 'The held delete keeps its target entry' : `The ${held} held deletes keep their `
        + 'target entries'}, which this set then no longer manages: content verification lists `
        + `${held === 1 ? 'it' : 'them'} as orphaned on target.` : ''),
    confirmLabel: 'Stop tracking',
  })
  if (!ok) return
  bulkBusy.value = true
  try {
    const failures = await eachRow(batch, async (row) => { await dismissMembership(setId, row.identity) })
    const done = n - failures.length
    if (done) notif.success(n === 1 ? 'Stopped tracking the entry' : `Stopped tracking ${done} entries`)
    if (failures.length) notif.error(n === 1 ? failures[0].error : failureMessage('couldn\'t be dismissed', failures, n))
    selected.value = new Set()
    await load()
    emit('changed')
  } finally {
    bulkBusy.value = false
  }
}

async function recomputeQuery() {
  if (!props.set) return
  const key = search.value.trim()
  if (!key) return
  const setId = props.set.id
  try {
    await recomputeKey(setId, key)
  } catch (e) {
    notif.error(errMsg(e))
    return
  }
  notif.success('Recompute queued: watching for the entry…')
  emit('changed')
  // Nothing matched the search, so the result shows as rows appearing.
  const deadline = Date.now() + ROW_WATCH_MS
  while (Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, ROW_POLL_MS))
    if (unmounted || props.set?.id !== setId || search.value.trim() !== key || !show.value) return
    await load()
    if (rows.value.length > 0) {
      emit('changed')
      return
    }
  }
  notif.info(`Recompute of "${key}" tracked nothing: it isn't a source entry in this set's scope and filter.`)
}
</script>
