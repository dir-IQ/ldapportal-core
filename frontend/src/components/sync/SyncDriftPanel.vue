<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  The Drift tab of the membership dialog: every item the set's last complete
  content verify found (missing on the target, orphaned on the target, content
  drift), stored server-side so it can be paged, filtered, searched and
  exported, with each content drift's differing attributes. Verifying is a
  live re-read of both directories that never writes; the dialog runs it (it
  owns the progress line) and bumps `refreshKey` when it's done.
-->
<template>
  <div class="space-y-3" data-testid="drift-panel">
    <div class="flex items-start justify-between gap-2 flex-wrap">
      <div class="text-sm space-y-0.5">
        <p v-if="data?.verifiedAt" class="text-gray-700">
          Verified <RelativeTime :value="data.verifiedAt" /> · complete scan
        </p>
        <p v-else-if="data" class="text-gray-700">This set hasn’t had a complete content check yet.</p>
        <p v-if="laterIncomplete" class="text-amber-700" data-testid="drift-incomplete">
          A later check (<RelativeTime :value="data!.incompleteAt!" />) couldn’t read both directories, so these are
          the results of the last complete one.
        </p>
        <p v-if="live" class="text-gray-600 text-xs">
          Just now: source <span class="tabular-nums">{{ live.sourceMembers }}</span> members ·
          target <span class="tabular-nums">{{ live.targetEntries }}</span> entries ·
          <span class="tabular-nums text-emerald-700">{{ live.inSync }}</span> in sync
          <template v-if="!live.sourceComplete || !live.targetComplete">
            · <span class="text-amber-700">a directory read failed, so this check wasn’t saved</span>
          </template>
        </p>
        <p v-if="live?.note" class="text-amber-700">{{ live.note }}</p>
      </div>
      <div class="flex items-center gap-2">
        <RouterLink v-if="canDiff" :to="{ path: '/superadmin/directory-diff', query: { verifySet: set.id } }"
                    class="btn-secondary btn-compact"
                    title="Compare the set's source and target entry by entry in Directory Diff, independently of the sync's own comparison: every attribute's values side by side, with a live re-read per entry">
          Compare in Directory Diff
        </RouterLink>
        <button type="button" class="btn-secondary btn-compact" :disabled="verifying"
                title="Check only, never writes: reads both directories and compares them directly, without trusting the sync's records, and lists every entry missing on the target, orphaned on the target, or with content drift"
                @click="emit('verify')">
          {{ verifying ? 'Verifying…' : 'Verify now' }}
        </button>
      </div>
    </div>

    <p v-if="data?.verifiedAt && !data.detailsRecorded" class="text-xs text-amber-700" data-testid="drift-no-details">
      These counts come from a check made before the details were kept. Verify now to list the entries.
    </p>

    <!-- Category cards: each filters the table. -->
    <div v-if="data?.verifiedAt" class="flex flex-wrap gap-2" role="group" aria-label="Filter by kind of drift">
      <button v-for="c in cards" :key="c.key" type="button"
              class="min-w-32 rounded-lg border px-3 py-2 text-left"
              :class="category === c.value ? 'border-blue-500 ring-2 ring-blue-500 bg-blue-50' : 'border-gray-200 bg-white hover:bg-gray-50'"
              :aria-pressed="category === c.value" @click="setCategory(c.value)">
        <span class="block text-xs text-gray-600">{{ c.label }}</span>
        <span class="block text-lg font-semibold tabular-nums" :class="c.n ? c.tone : 'text-gray-400'">{{ c.n }}</span>
      </button>
    </div>

    <p v-if="data?.verifiedAt && counts.ORPHAN" class="text-xs text-gray-500" data-testid="orphan-scope">
      {{ set.sourceAnchorAttribute
        ? 'Orphans are entries carrying the set’s source anchor that no source entry accounts for; entries without it, or that another set places there, aren’t counted.'
        : 'This set writes no source anchor, so every entry under the target base that matches its filter counts, including ones another set or nothing manages.' }}
    </p>

    <!-- Held references: from this session's check, else the last complete one's counts. -->
    <div v-if="live?.heldReferences?.entries" data-testid="held-references" class="text-sm text-gray-600">
      <p>
        Held references: <span class="font-medium tabular-nums">{{ live.heldReferences.entries }}</span>
        {{ live.heldReferences.entries === 1 ? 'entry has' : 'entries have' }} a reference the sync holds back
        (not counted as drift).
        <template v-if="live.heldReferences.dangling">
          <span class="text-amber-700">{{ live.heldReferences.dangling }} of them
            {{ live.heldReferences.dangling === 1 ? 'names' : 'name' }} an entry that doesn’t exist on the
            source</span>: fix those on the source; a reconcile can’t.
        </template>
        The rest name entries not synced yet, and fill in once those are.
        <template v-if="live.heldReferences.unchecked"> Not every referenced entry was looked up.</template>
      </p>
      <ul class="font-mono text-xs text-gray-500 list-disc pl-4">
        <li v-for="h in live.heldReferences.samples" :key="h.entryDn + h.attribute + h.reference">
          {{ h.entryDn }}: {{ h.attribute }} → {{ h.reference }}
          <span class="font-sans" :class="h.dangling ? 'text-amber-700' : 'text-gray-500'">
            ({{ h.dangling ? 'doesn’t exist' : 'not synced yet' }})
          </span>
        </li>
      </ul>
    </div>
    <p v-else-if="set.verify?.held" class="text-xs text-gray-600" data-testid="held-snapshot">
      At the last content check, {{ set.verify.held }}
      {{ set.verify.held === 1 ? 'entry had' : 'entries had' }} a held reference (a value naming an entry the sync
      hasn’t placed)<template v-if="set.verify.dangling">; <span class="text-amber-700">{{ set.verify.dangling }}
        {{ set.verify.dangling === 1 ? 'names' : 'name' }} an entry that doesn’t exist on the source</span></template>.
      Verify now lists them.
    </p>

    <template v-if="data?.verifiedAt && totalDrift > 0">
      <div class="flex items-center gap-2">
        <input v-model="search" class="input input-sm flex-1" placeholder="Filter by DN, attribute or value…"
               aria-label="Filter drift" />
        <button type="button" class="btn-secondary btn-compact" :disabled="exporting" @click="exportCsv">
          {{ exporting ? 'Exporting…' : 'Export CSV' }}
        </button>
      </div>

      <!-- Repair: missing and drifted entries, through the sync engine, after a live re-check and a preview. -->
      <div v-if="canManage && repairableOnPage.length" class="flex items-center gap-2 flex-wrap" data-testid="drift-bulk">
        <span class="text-xs text-gray-600 tabular-nums mr-1">
          {{ selected.size ? `${selected.size} selected` : 'Select missing or drifted entries to repair them' }}
        </span>
        <button type="button" class="btn-secondary btn-compact" :disabled="!selected.size"
                title="Re-check the selected entries live, then show what a repair would create or re-apply before anything is written"
                @click="openRepair([...selected])">
          Repair<template v-if="selected.size"> ({{ selected.size }})</template>…
        </button>
        <button v-if="selected.size" type="button" class="btn-neutral btn-compact" @click="selected = new Set()">
          Clear selection
        </button>
      </div>

      <div class="overflow-x-auto border border-gray-200 rounded-lg">
        <table class="min-w-full text-sm">
          <thead class="bg-gray-50 text-xs text-gray-500 uppercase tracking-wider">
            <tr>
              <th v-if="canManage" scope="col" class="w-8 pl-3">
                <input type="checkbox" class="rounded border-gray-300" :checked="allSelected"
                       :disabled="!repairableOnPage.length" aria-label="Select every repairable entry on this page"
                       @change="toggleAll(($event.target as HTMLInputElement).checked)" />
              </th>
              <th scope="col" class="w-8"><span class="sr-only">Details</span></th>
              <th scope="col" class="px-3 py-2 text-left">Kind</th>
              <th scope="col" class="px-3 py-2 text-left">Target DN</th>
              <th scope="col" class="px-3 py-2 text-left">Detail</th>
              <th v-if="canManage" scope="col" class="px-3 py-2"><span class="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody class="divide-y divide-gray-100">
            <tr v-if="loading && !entries.length"><td :colspan="colCount" class="px-3 py-4 text-center text-gray-500">Loading…</td></tr>
            <tr v-else-if="!entries.length">
              <td :colspan="colCount" class="px-3 py-4 text-center text-gray-500">No drift matches.</td>
            </tr>
            <template v-for="e in entries" :key="e.seq">
              <tr :data-testid="`drift-row-${e.seq}`">
                <td v-if="canManage" class="pl-3 align-top py-2">
                  <input v-if="repairable(e)" type="checkbox" class="rounded border-gray-300"
                         :checked="selected.has(e.seq)" :aria-label="`Select ${e.targetDn}`"
                         @change="toggleSelected(e.seq)" />
                </td>
                <td class="pl-2 align-top py-2">
                  <button v-if="expandable(e)" type="button" class="text-gray-500 hover:text-gray-700 px-1"
                          :aria-expanded="expanded.has(e.seq)"
                          :aria-label="`${expanded.has(e.seq) ? 'Hide' : 'Show'} details for ${e.targetDn}`"
                          @click="toggle(e.seq)">
                    <span aria-hidden="true">{{ expanded.has(e.seq) ? '▾' : '▸' }}</span>
                  </button>
                </td>
                <td class="px-3 py-2 align-top whitespace-nowrap">
                  <span :class="KIND[e.category].badge">{{ KIND[e.category].label }}</span>
                </td>
                <td class="px-3 py-2 align-top font-mono text-xs break-all">
                  <RouterLink v-if="targetDirectoryId && e.category !== 'MISSING'" :to="browserLink(targetDirectoryId, e.targetDn)"
                              class="text-blue-700 hover:underline">{{ e.targetDn }}</RouterLink>
                  <span v-else>{{ e.targetDn }}</span>
                </td>
                <td class="px-3 py-2 align-top text-xs text-gray-600">{{ detail(e) }}</td>
                <td v-if="canManage" class="px-3 py-2 align-top text-right whitespace-nowrap">
                  <button v-if="repairable(e)" type="button" class="btn-secondary btn-compact"
                          :title="e.category === 'MISSING' ? 'Create the entry on the target from its source, after a live re-check and a preview' : 'Bring the target entry back to its source, after a live re-check and a preview'"
                          @click="openRepair([e.seq])">
                    {{ e.category === 'MISSING' ? 'Create' : 'Re-apply' }}…
                  </button>
                </td>
              </tr>
              <tr v-if="expanded.has(e.seq)" :data-testid="`drift-detail-${e.seq}`" class="bg-gray-50">
                <td :colspan="canManage ? 2 : 1"></td>
                <td :colspan="canManage ? 4 : 3" class="px-3 py-2 space-y-2">
                  <table v-if="e.attributes.length" class="text-xs w-full">
                    <thead class="text-gray-500">
                      <tr>
                        <th scope="col" class="text-left pr-3 py-1 font-medium">Attribute</th>
                        <th scope="col" class="text-left pr-3 py-1 font-medium">Source (expected)</th>
                        <th scope="col" class="text-left py-1 font-medium">Target (actual)</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="a in e.attributes" :key="a.attribute" class="align-top">
                        <td class="pr-3 py-1 font-mono">{{ a.attribute }}</td>
                        <td class="pr-3 py-1 font-mono break-all">
                          <template v-if="a.hidden"><span class="italic text-gray-500">(hidden)</span></template>
                          <template v-else-if="!a.expected.length"><span class="italic text-gray-500">(none)</span></template>
                          <div v-for="(v, i) in a.expected" v-else :key="i">{{ v }}</div>
                        </td>
                        <td class="py-1 font-mono break-all">
                          <template v-if="a.hidden"><span class="italic text-gray-500">(hidden)</span></template>
                          <template v-else-if="!a.actual.length"><span class="italic text-gray-500">(none)</span></template>
                          <div v-for="(v, i) in a.actual" v-else :key="i">{{ v }}</div>
                        </td>
                      </tr>
                    </tbody>
                  </table>
                  <p v-if="e.attributes.some(a => a.truncated)" class="text-xs text-gray-500">
                    Long value lists show their first 50 values.
                  </p>
                  <p v-if="e.sourceDn" class="text-xs text-gray-600">
                    Source:
                    <RouterLink v-if="sourceDirectoryId" :to="browserLink(sourceDirectoryId, e.sourceDn)"
                                class="font-mono text-blue-700 hover:underline">{{ e.sourceDn }}</RouterLink>
                    <span v-else class="font-mono">{{ e.sourceDn }}</span>
                    <template v-if="e.identity"> · identity <span class="font-mono">{{ e.identity }}</span></template>
                  </p>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>

      <div class="flex items-center justify-between gap-2 flex-wrap text-xs text-gray-600">
        <label class="flex items-center gap-1.5">
          Rows
          <select v-model.number="size" class="input input-sm py-0.5 w-20" aria-label="Drift rows per page">
            <option :value="25">25</option>
            <option :value="50">50</option>
            <option :value="100">100</option>
            <option :value="200">200</option>
          </select>
          <span class="text-gray-500">{{ rangeLabel }}</span>
        </label>
        <div class="flex items-center gap-2">
          <button type="button" class="btn-neutral btn-compact" :disabled="page <= 0" @click="page--">Prev</button>
          <span class="tabular-nums">Page {{ page + 1 }} of {{ pageCount }}</span>
          <button type="button" class="btn-neutral btn-compact" :disabled="page >= pageCount - 1" @click="page++">Next</button>
        </div>
      </div>
    </template>
    <p v-else-if="data?.verifiedAt && data.detailsRecorded" class="text-sm text-emerald-700" data-testid="drift-none">
      ✓ Source and target agree: no drift at the last check.
    </p>

    <AppModal v-model="showRepair" title="Repair drift" size="lg">
      <div class="space-y-3 text-sm" data-testid="repair-preview">
        <p v-if="previewing" class="text-gray-600">Re-checking the selected entries against both directories…</p>
        <template v-else-if="preview">
          <p class="text-gray-600">Each entry was re-checked just now. A repair goes through the sync engine, as a
            reconcile would, and is recorded in the audit log under your name.</p>
          <div v-for="g in previewGroups" :key="g.key" :data-testid="`repair-group-${g.key}`">
            <template v-if="g.items.length">
              <p class="font-medium" :class="g.tone">{{ g.label(g.items.length) }}</p>
              <ul class="mt-1 space-y-1 text-xs">
                <li v-for="i in g.items" :key="i.seq">
                  <span class="font-mono break-all">{{ i.targetDn }}</span>
                  <span v-if="i.action === 'REAPPLY' && i.attributes.length" class="text-gray-500">
                    — {{ i.attributes.map(a => a.attribute).join(', ') }}</span>
                  <span v-if="i.reason" class="text-gray-500"> — {{ i.reason }}</span>
                </li>
              </ul>
            </template>
          </div>
        </template>
      </div>
      <template #footer>
        <button type="button" class="btn-neutral" :disabled="repairing" @click="showRepair = false">Cancel</button>
        <button type="button" class="btn-primary" :disabled="repairing || previewing || !toApply.length"
                @click="applyRepair">
          {{ repairing ? 'Repairing…' : `Repair ${toApply.length}` }}
        </button>
      </template>
    </AppModal>

    <p class="text-xs text-gray-500">
      Drift is a live comparison of the source and target, independent of what the reconciliation history says was
      applied. It’s checked after every reconcile and whenever you verify.
    </p>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import RelativeTime from '@/components/RelativeTime.vue'
import AppModal from '@/components/AppModal.vue'
import { useNotificationStore } from '@/stores/notifications'
import { downloadBlob } from '@/composables/useApi'
import {
  getDrift, downloadDriftCsv, previewDriftRepair, repairDrift,
  type SyncSet, type SyncVerifyResult, type SyncDriftPage, type SyncDriftEntry, type SyncDriftCategory,
  type SyncDriftRepairPreviewItem,
} from '@/api/sync'

const props = defineProps<{
  set: SyncSet
  /** This session's live check, for its totals, notes and held references. */
  live: SyncVerifyResult | null
  verifying: boolean
  /** Bumped by the dialog when a check finishes, to reload the stored items. */
  refreshKey: number
  canDiff: boolean
  sourceDirectoryId?: string | null
  targetDirectoryId?: string | null
  /** May repair drift (Manage directory sync). */
  canManage?: boolean
}>()
const emit = defineEmits<{ verify: []; changed: [] }>()

const notif = useNotificationStore()

type CategoryFilter = '' | SyncDriftCategory
const category = ref<CategoryFilter>('')
const search = ref('')
const page = ref(0)
const size = ref(25)
const data = ref<SyncDriftPage | null>(null)
const loading = ref(false)
const exporting = ref(false)
const expanded = ref<Set<number>>(new Set())

const KIND: Record<SyncDriftCategory, { label: string; badge: string }> = {
  MISSING: { label: 'Missing', badge: 'badge-red' },
  ORPHAN: { label: 'Orphaned', badge: 'badge-amber' },
  MISMATCH: { label: 'Content drift', badge: 'badge-yellow' },
}

const counts = computed<Record<SyncDriftCategory, number>>(() =>
  data.value?.counts ?? { MISSING: 0, ORPHAN: 0, MISMATCH: 0 })
const totalDrift = computed<number>(() => counts.value.MISSING + counts.value.ORPHAN + counts.value.MISMATCH)
const entries = computed<SyncDriftEntry[]>(() => data.value?.entries ?? [])
const pageCount = computed<number>(() => Math.max(1, Math.ceil((data.value?.matched ?? 0) / size.value)))
const rangeLabel = computed<string>(() => {
  const n = data.value?.matched ?? 0
  if (!n) return 'none'
  const from = page.value * size.value + 1
  return `${from}–${Math.min(n, from + size.value - 1)} of ${n}`
})
const laterIncomplete = computed<boolean>(() => {
  const d = data.value
  return !!d?.incompleteAt && (!d.verifiedAt || d.incompleteAt > d.verifiedAt)
})

interface Card { key: string; label: string; value: CategoryFilter; n: number; tone: string }
const cards = computed<Card[]>(() => [
  { key: 'all', label: 'All', value: '', n: totalDrift.value, tone: 'text-gray-900' },
  { key: 'missing', label: 'Missing on target', value: 'MISSING', n: counts.value.MISSING, tone: 'text-rose-700' },
  { key: 'orphan', label: 'Orphaned on target', value: 'ORPHAN', n: counts.value.ORPHAN, tone: 'text-amber-700' },
  { key: 'mismatch', label: 'Content drift', value: 'MISMATCH', n: counts.value.MISMATCH, tone: 'text-amber-700' },
])

function setCategory(c: CategoryFilter): void {
  category.value = c
  page.value = 0
  void load()
}

// ── Repair ──────────────────────────────────────────────────────────────────
// Missing and drifted entries can be repaired (an orphan has no source member).
// The preview re-checks each live; Repair re-checks again and writes through
// the sync engine.

const selected = ref<Set<number>>(new Set())
const showRepair = ref(false)
const previewing = ref(false)
const repairing = ref(false)
const preview = ref<SyncDriftRepairPreviewItem[] | null>(null)

function repairable(e: SyncDriftEntry): boolean {
  return e.category !== 'ORPHAN' && !!e.sourceDn
}
const repairableOnPage = computed<SyncDriftEntry[]>(() => entries.value.filter(repairable))
const allSelected = computed<boolean>(() =>
  repairableOnPage.value.length > 0 && repairableOnPage.value.every(e => selected.value.has(e.seq)))
const colCount = computed<number>(() => (props.canManage ? 6 : 4))

function toggleSelected(seq: number): void {
  const next = new Set(selected.value)
  if (next.has(seq)) next.delete(seq)
  else next.add(seq)
  selected.value = next
}

function toggleAll(on: boolean): void {
  selected.value = on ? new Set(repairableOnPage.value.map(e => e.seq)) : new Set()
}

const toApply = computed<SyncDriftRepairPreviewItem[]>(() =>
  (preview.value ?? []).filter(i => i.action !== 'SKIP'))

interface PreviewGroup {
  key: string
  items: SyncDriftRepairPreviewItem[]
  label: (n: number) => string
  tone: string
}
const previewGroups = computed<PreviewGroup[]>(() => {
  const items = preview.value ?? []
  return [
    { key: 'create', items: items.filter(i => i.action === 'CREATE'), tone: 'text-gray-900',
      label: (n: number) => `${n} to create on the target` },
    { key: 'reapply', items: items.filter(i => i.action === 'REAPPLY'), tone: 'text-gray-900',
      label: (n: number) => `${n} to re-apply from the source` },
    { key: 'skip', items: items.filter(i => i.action === 'SKIP'), tone: 'text-gray-500',
      label: (n: number) => `${n} skipped` },
  ]
})

async function openRepair(seqs: number[]): Promise<void> {
  if (!seqs.length) return
  preview.value = null
  showRepair.value = true
  previewing.value = true
  try {
    const { data: d } = await previewDriftRepair(props.set.id, seqs)
    preview.value = d.items
  } catch (e) {
    notif.error(errMsg(e))
    showRepair.value = false
  } finally {
    previewing.value = false
  }
}

async function applyRepair(): Promise<void> {
  const seqs = toApply.value.map(i => i.seq)
  if (!seqs.length) return
  repairing.value = true
  try {
    const { data: r } = await repairDrift(props.set.id, seqs)
    const parts = [
      r.created ? `${r.created} created` : '',
      r.reapplied ? `${r.reapplied} re-applied` : '',
      r.alreadyInSync ? `${r.alreadyInSync} already matched` : '',
      r.skipped ? `${r.skipped} skipped` : '',
      r.held ? `${r.held} held for review` : '',
      r.failed ? `${r.failed} failed` : '',
    ].filter(Boolean)
    const msg = `Repair finished: ${parts.join(', ') || 'nothing to do'}`
    if (r.failed || r.held) {
      const why = r.items.find(i => (i.outcome === 'FAILED' || i.outcome === 'HELD') && i.message)?.message
      notif.warning(why ? `${msg}. ${why}` : msg)
    } else {
      notif.success(msg)
    }
    showRepair.value = false
    selected.value = new Set()
    emit('changed')
    await load()
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    repairing.value = false
  }
}

function expandable(e: SyncDriftEntry): boolean {
  return e.attributes.length > 0 || !!e.sourceDn
}

function toggle(seq: number): void {
  const next = new Set(expanded.value)
  if (next.has(seq)) next.delete(seq)
  else next.add(seq)
  expanded.value = next
}

function detail(e: SyncDriftEntry): string {
  if (e.category === 'MISMATCH') return e.attributes.map(a => a.attribute).join(', ') || 'Attributes differ'
  if (e.category === 'MISSING') return 'New in the source, or deleted on the target'
  return props.set.sourceAnchorAttribute ? 'Carries this set’s anchor; no source member' : 'No source member'
}

function browserLink(dir: string, dn: string) {
  return { path: '/superadmin/directory-browser', query: { dir, dn } }
}

function errMsg(e: unknown): string {
  const err = e as { response?: { data?: { detail?: string } }, message?: string }
  return err.response?.data?.detail || err.message || 'Request failed'
}

let loadSeq = 0
async function load(): Promise<void> {
  const seq = ++loadSeq
  loading.value = true
  try {
    const { data: d } = await getDrift(props.set.id, {
      category: category.value || undefined,
      q: search.value.trim() || undefined,
      page: page.value,
      size: size.value,
    })
    if (seq !== loadSeq) return
    data.value = d
    expanded.value = new Set()
    selected.value = new Set()
  } catch (e) {
    if (seq === loadSeq) notif.error(errMsg(e))
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

async function exportCsv(): Promise<void> {
  exporting.value = true
  try {
    const { data: blob } = await downloadDriftCsv(props.set.id)
    downloadBlob(blob, `sync-drift-${props.set.name.replace(/[^A-Za-z0-9._-]+/g, '-')}.csv`)
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    exporting.value = false
  }
}

let searchTimer: ReturnType<typeof setTimeout> | null = null
watch(search, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => { page.value = 0; void load() }, 300)
})
watch(page, () => { void load() })
watch(size, () => { page.value = 0; void load() })
watch([() => props.set.id, () => props.refreshKey], () => {
  category.value = ''
  search.value = ''
  page.value = 0
  void load()
}, { immediate: true })
</script>
