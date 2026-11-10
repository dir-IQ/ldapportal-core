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
    <!-- What the last complete content check found, laid out like the Reconciliation tab's "Last reconcile". -->
    <div class="rounded border border-gray-200 px-3 py-2 text-sm space-y-1.5">
      <div class="flex items-center justify-between gap-2 flex-wrap">
        <span class="text-base font-semibold text-gray-800">Last content check</span>
        <div class="flex items-center gap-2">
          <!-- A new tab, so the inventory stays open. -->
          <a v-if="canDiff" :href="appHref('/superadmin/directory-diff', { verifySet: set.id })" target="_blank"
             rel="noopener" class="btn-neutral btn-compact"
             title="Compare the set's source and target entry by entry in Directory Diff (opens a new tab), independently of the sync's own comparison: every attribute's values side by side, with a live re-read per entry">
            Compare in Directory Diff <span aria-hidden="true">↗</span>
          </a>
          <button v-if="data?.verifiedAt && (totalDrift > 0 || heldTotal > 0)" type="button"
                  class="btn-neutral btn-compact" :disabled="exporting"
                  :title="heldView ? 'Download every held reference as CSV' : 'Download every drift item as CSV'"
                  @click="exportCsv">
            {{ exporting ? 'Exporting…' : 'Export CSV' }}
          </button>
          <button type="button" class="btn-secondary btn-compact" :disabled="verifying"
                  title="Check only, never writes: reads both directories and compares them directly, without trusting the sync's records, and lists every entry missing on the target, orphaned on the target, or with content drift"
                  @click="emit('verify')">
            {{ verifying ? 'Verifying…' : 'Verify now' }}
          </button>
        </div>
      </div>

      <p v-if="data?.verifiedAt" class="text-gray-600">
        Verified <RelativeTime :value="data.verifiedAt" /> · complete scan
      </p>
      <p v-else-if="data" class="text-gray-500">This set hasn’t had a complete content check yet.</p>
      <p v-if="laterIncomplete" class="text-amber-700" data-testid="drift-incomplete">
        A later check (<RelativeTime :value="data!.incompleteAt!" />) couldn’t read both directories, so these are
        the results of the last complete one.
      </p>
      <p v-if="live" class="text-gray-600">
        Just now: source <span class="font-medium tabular-nums">{{ live.sourceMembers }}</span> members ·
        target <span class="font-medium tabular-nums">{{ live.targetEntries }}</span> entries ·
        <span class="font-medium tabular-nums text-emerald-700">{{ live.inSync }}</span> in sync
        <template v-if="live.leavingSet">
          · <span data-testid="leaving-set"
                  title="Target entries that left the source scope but this set still tracks: a delete held for review, or one the next reconcile makes. Not counted as orphans."><span
            class="font-medium tabular-nums">{{ live.leavingSet }}</span> leaving the set (held for delete or awaiting the
            next reconcile)</span>
        </template>
        <template v-if="!live.sourceComplete || !live.targetComplete">
          · <span class="text-amber-700">a directory read failed, so this check wasn’t saved</span>
        </template>
      </p>
      <p v-if="live?.note" class="text-amber-700">{{ live.note }}</p>
      <p v-if="data?.verifiedAt && !data.detailsRecorded" class="text-amber-700" data-testid="drift-no-details">
        These counts come from a check made before the details were kept. Verify now to list the entries.
      </p>
      <p v-if="data?.verifiedAt && data.detailsRecorded && totalDrift === 0" class="text-emerald-700"
         data-testid="drift-none">
        ✓ Source and target agree: no drift at the last check.
      </p>

      <!-- Held references: not drift, but listed (every one) under their own chips below. -->
      <p v-if="heldTotal" class="text-gray-600" data-testid="held-references">
        Held references (not drift): <span class="font-medium tabular-nums">{{ heldTotal }}</span>
        {{ heldTotal === 1 ? 'value names' : 'values name' }} an entry the sync hasn’t placed, so the target keeps its
        own.<template v-if="heldCounts.DANGLING">
          <button type="button" class="text-amber-700 hover:underline" @click="setCategory('HELD_DANGLING')">
            <span class="font-medium tabular-nums">{{ heldCounts.DANGLING }}</span>
            {{ heldCounts.DANGLING === 1 ? 'names an entry that doesn’t' : 'name entries that don’t' }} exist on the
            source</button>:
          fix those on the source; a reconcile can’t.</template>
        The rest fill in once the entries they name are synced.
      </p>
      <p v-else-if="set.verify?.held" class="text-gray-600" data-testid="held-snapshot">
        At the last content check, {{ set.verify.held }}
        {{ set.verify.held === 1 ? 'entry had' : 'entries had' }} a held reference (a value naming an entry the sync
        hasn’t placed)<template v-if="set.verify.dangling">; <span class="text-amber-700">{{ set.verify.dangling }}
          {{ set.verify.dangling === 1 ? 'names' : 'name' }} an entry that doesn’t exist on the source</span></template>.
        Verify now to list them all.
      </p>
    </div>

    <template v-if="data?.verifiedAt && (totalDrift > 0 || heldTotal > 0)">
      <!--
        Kind filter chips with counts, as the Reconciliation tab's state chips.
        Held references aren't drift: their chips come after a divider, as "Not
        in set" does there, and list them instead of drift items.
      -->
      <div class="flex flex-wrap items-center gap-1.5" role="group" aria-label="Filter by kind of drift">
        <template v-for="c in chips" :key="c.key">
          <span v-if="c.key === firstHeldKey" class="w-px h-4 bg-gray-300 mx-1" aria-hidden="true"></span>
          <button type="button"
                  class="inline-flex items-center gap-1.5 text-xs font-semibold px-2.5 py-1 rounded-full"
                  :class="[c.chip, category === c.value ? 'ring-2 ring-offset-1 ring-blue-500' : '']"
                  :aria-pressed="category === c.value" :title="c.hint" @click="setCategory(c.value)">
            <span v-if="c.dot" class="w-1.5 h-1.5 rounded-full" :class="c.dot" aria-hidden="true"></span>
            {{ c.label }} <span class="tabular-nums">{{ c.n }}</span>
          </button>
        </template>
      </div>

      <p v-if="!heldView && counts.ORPHAN" class="text-xs text-gray-500" data-testid="orphan-scope">
        {{ set.sourceAnchorAttribute
          ? 'Orphans are entries carrying the set’s source anchor that no source entry accounts for; entries without it, or that another set places there, aren’t counted.'
          : 'This set writes no source anchor, so every entry under the target base that matches its filter counts, including ones another set or nothing manages.' }}
        <template v-if="canManage && orphanDeleteBlocked">
          <span data-testid="orphan-no-delete">{{ orphanDeleteBlocked }}</span>
        </template>
      </p>

      <input v-model="search" class="input input-sm w-full" placeholder="Filter by DN, attribute or value…"
             aria-label="Filter drift" />

      <!-- Held references: one row per held value, with the source entry that holds it. -->
      <template v-if="heldView">
        <p class="text-xs text-gray-500" data-testid="held-help">
          Each row is a reference value (a group member, a manager, a secDN) that names an entry no sync set has placed,
          so the target keeps its own value. <span class="text-amber-700">Doesn’t exist on source</span>: the entry it
          names is gone, so fix the source entry shown (remove or correct the value); a reconcile can’t.
          <span class="font-medium">Not synced yet</span>: the entry exists, and the value fills in once a sync set
          places it.
          <template v-if="heldPage?.counts.UNCHECKED">
            <span class="font-medium">Not checked</span>: the entry wasn’t looked up (over
            {{ HELD_CHECK_LIMIT.toLocaleString() }} distinct entries, or the read failed).
          </template>
        </p>
        <p v-if="heldPage && !heldPage.detailsRecorded" class="text-xs text-amber-700" data-testid="held-no-details">
          These counts come from a check made before every held reference was kept. Verify now to list them.
        </p>
        <div class="overflow-x-auto rounded-lg border border-gray-200">
          <table class="min-w-full divide-y divide-gray-200 text-sm" data-testid="held-table">
            <thead class="bg-gray-50">
              <tr>
                <th v-for="h in ['Status', 'Source entry', 'Attribute', 'Names']" :key="h" scope="col"
                    class="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">{{ h }}</th>
              </tr>
            </thead>
            <tbody class="bg-white divide-y divide-gray-100">
              <tr v-if="loading && !heldEntries.length">
                <td colspan="4" class="px-4 py-8 text-center text-gray-500">Loading…</td>
              </tr>
              <tr v-else-if="!heldEntries.length">
                <td colspan="4" class="px-4 py-8 text-center text-gray-500">No held references match.</td>
              </tr>
              <tr v-for="h in heldEntries" :key="h.seq" :data-testid="`held-row-${h.seq}`">
                <td class="px-4 py-3 align-top whitespace-nowrap">
                  <span :class="HELD[h.status].badge">{{ HELD[h.status].label }}</span>
                </td>
                <td class="px-4 py-3 align-top text-[13px] font-mono break-all">
                  <button v-if="sourceDirectoryId || targetDirectoryId" type="button"
                          class="text-left text-blue-700 hover:underline break-all"
                          :title="`Show the source and target entries (on the target: ${h.entryDn})`"
                          @click="viewHeld(h)">{{ h.sourceDn ?? h.entryDn }}</button>
                  <span v-else class="text-gray-700" :title="`On the target: ${h.entryDn}`">{{ h.sourceDn ?? h.entryDn }}</span>
                </td>
                <td class="px-4 py-3 align-top text-[13px] font-mono text-gray-700">{{ h.attribute }}</td>
                <td class="px-4 py-3 align-top text-[13px] font-mono break-all">
                  <button v-if="sourceDirectoryId && h.status === 'UNSYNCED'" type="button"
                          class="text-left text-blue-700 hover:underline break-all"
                          title="Show the entry this value names, on the source" @click="viewReference(h)">{{ h.reference }}</button>
                  <span v-else class="text-gray-700">{{ h.reference }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <template v-else>
      <!--
        Bulk actions on the selected rows (this page only): Repair takes the
        missing and drifted ones, Delete the orphans this set can prove are its own.
      -->
      <div v-if="canManage && selectableOnPage.length" class="flex items-center gap-2 flex-wrap" data-testid="drift-bulk">
        <span class="text-xs text-gray-600 tabular-nums mr-1">
          {{ selected.size ? `${selected.size} selected` : 'Select rows to act on them' }}
        </span>
        <button type="button" class="btn-secondary btn-compact" :disabled="!selectedRepairable.length"
                title="Re-check the selected missing and drifted entries live, then show what a repair would create or re-apply before anything is written"
                @click="openRepair(selectedRepairable)">
          Repair<template v-if="selectedRepairable.length"> ({{ selectedRepairable.length }})</template>…
        </button>
        <button v-if="selectedOrphans.length" type="button" class="btn-danger btn-compact"
                title="Re-check the selected orphans live, then show which would be deleted from the target before anything is"
                @click="openDelete(selectedOrphans)">
          Delete ({{ selectedOrphans.length }})…
        </button>
        <button v-if="selected.size" type="button" class="btn-neutral btn-compact" @click="selected = new Set()">
          Clear selection
        </button>
      </div>

      <div class="overflow-x-auto rounded-lg border border-gray-200">
        <table class="min-w-full divide-y divide-gray-200 text-sm">
          <thead class="bg-gray-50">
            <tr>
              <th v-if="showSelect" scope="col" class="px-3 py-3 w-10">
                <input type="checkbox" class="rounded text-blue-600 focus:ring-blue-500" :checked="allSelected"
                       :indeterminate="selected.size > 0 && !allSelected"
                       aria-label="Select every entry on this page that can be repaired or deleted"
                       @change="toggleAll(($event.target as HTMLInputElement).checked)" />
              </th>
              <th scope="col" class="w-8"><span class="sr-only">Details</span></th>
              <th v-for="h in ['Kind', 'Target DN', 'Detail']" :key="h" scope="col"
                  class="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">{{ h }}</th>
            </tr>
          </thead>
          <tbody class="bg-white divide-y divide-gray-100">
            <tr v-if="loading && !entries.length">
              <td :colspan="colCount" class="px-4 py-8 text-center text-gray-500">Loading…</td>
            </tr>
            <tr v-else-if="!entries.length">
              <td :colspan="colCount" class="px-4 py-8 text-center text-gray-500">No drift matches.</td>
            </tr>
            <template v-for="e in entries" :key="e.seq">
              <tr :data-testid="`drift-row-${e.seq}`" :class="selected.has(e.seq) ? 'bg-blue-50' : ''">
                <td v-if="showSelect" class="px-3 py-3 w-10 align-top">
                  <input v-if="selectable(e)" type="checkbox" class="rounded text-blue-600 focus:ring-blue-500"
                         :checked="selected.has(e.seq)" :aria-label="`Select ${e.targetDn}`"
                         @change="toggleSelected(e.seq)" />
                </td>
                <td class="pl-2 py-3 align-top">
                  <button v-if="expandable(e)" type="button" class="text-gray-500 hover:text-gray-700 px-1"
                          :aria-expanded="expanded.has(e.seq)"
                          :aria-label="`${expanded.has(e.seq) ? 'Hide' : 'Show'} details for ${e.targetDn}`"
                          @click="toggle(e.seq)">
                    <span aria-hidden="true">{{ expanded.has(e.seq) ? '▾' : '▸' }}</span>
                  </button>
                </td>
                <td class="px-4 py-3 align-top whitespace-nowrap">
                  <span :class="KIND[e.category].badge">{{ KIND[e.category].label }}</span>
                </td>
                <td class="px-4 py-3 align-top text-[13px] font-mono break-all">
                  <button v-if="targetDirectoryId || sourceDirectoryId" type="button"
                          class="text-left text-blue-700 hover:underline break-all"
                          title="Show the entry on both sides, without leaving the inventory" @click="viewDrift(e)">{{ e.targetDn }}</button>
                  <span v-else class="text-gray-700">{{ e.targetDn }}</span>
                </td>
                <td class="px-4 py-3 align-top text-[13px] text-gray-600">{{ detail(e) }}</td>
              </tr>
              <tr v-if="expanded.has(e.seq)" :data-testid="`drift-detail-${e.seq}`" class="bg-gray-50">
                <td :colspan="showSelect ? 2 : 1"></td>
                <td colspan="3" class="px-4 py-2 space-y-2">
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
                    <button v-if="sourceDirectoryId" type="button" class="font-mono text-left text-blue-700 hover:underline break-all"
                            title="Show the entry on both sides, without leaving the inventory" @click="viewDrift(e)">{{ e.sourceDn }}</button>
                    <span v-else class="font-mono">{{ e.sourceDn }}</span>
                    <template v-if="e.identity"> · identity <span class="font-mono">{{ e.identity }}</span></template>
                  </p>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>

      </template>

      <div class="flex items-center justify-between gap-2 flex-wrap text-xs text-gray-600 pt-1 border-t border-gray-100">
        <label class="flex items-center gap-1.5">
          Rows
          <select v-model.number="size" class="input input-sm py-0.5 w-20" aria-label="Drift rows per page">
            <option :value="25">25</option>
            <option :value="50">50</option>
            <option :value="100">100</option>
            <option :value="200">200</option>
          </select>
          <span class="text-gray-500">of {{ matched }}</span>
        </label>
        <div class="flex items-center gap-2">
          <button type="button" class="btn-neutral btn-compact" :disabled="page <= 0" @click="page--">Prev</button>
          <span class="tabular-nums">Page {{ page + 1 }} of {{ pageCount }}</span>
          <button type="button" class="btn-neutral btn-compact" :disabled="page >= pageCount - 1" @click="page++">Next</button>
        </div>
      </div>
    </template>

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

    <AppModal v-model="showDelete" title="Delete orphaned entries" size="lg">
      <div class="space-y-3 text-sm" data-testid="delete-preview">
        <p v-if="deletePreviewing" class="text-gray-600">Re-checking the selected entries against both directories…</p>
        <template v-else-if="deletePreview">
          <p class="text-gray-600">Each entry was re-checked just now. Only an entry that carries this set’s source anchor,
            that no sync set manages, and whose source entry isn’t in the set is deleted. A delete can’t be undone, and
            is recorded in the audit log under your name.</p>
          <div v-if="toDelete.length" data-testid="delete-group-delete">
            <p class="font-medium text-rose-700">{{ toDelete.length }} to delete from the target</p>
            <ul class="mt-1 space-y-1 text-xs font-mono break-all">
              <li v-for="i in toDelete" :key="i.seq">{{ i.targetDn }}</li>
            </ul>
          </div>
          <div v-if="deleteSkipped.length" data-testid="delete-group-skip">
            <p class="font-medium text-gray-500">{{ deleteSkipped.length }} left alone</p>
            <ul class="mt-1 space-y-1 text-xs">
              <li v-for="i in deleteSkipped" :key="i.seq">
                <span class="font-mono break-all">{{ i.targetDn }}</span>
                <span v-if="i.reason" class="text-gray-500"> — {{ i.reason }}</span>
              </li>
            </ul>
          </div>
          <div v-if="toDelete.length">
            <label for="drift-delete-confirm" class="block text-sm font-medium text-gray-700 mb-1">
              Type DELETE to confirm
            </label>
            <input id="drift-delete-confirm" v-model="deleteConfirm" class="input w-40 font-mono" autocomplete="off" />
          </div>
        </template>
      </div>
      <template #footer>
        <button type="button" class="btn-neutral" :disabled="deleting" @click="showDelete = false">Cancel</button>
        <button type="button" class="btn-danger"
                :disabled="deleting || deletePreviewing || !toDelete.length || deleteConfirm !== 'DELETE'"
                @click="applyDelete">
          {{ deleting ? 'Deleting…' : `Delete ${toDelete.length}` }}
        </button>
      </template>
    </AppModal>

    <SyncEntryViewer :show="!!viewing" :title="viewing?.title ?? ''" :sides="viewing?.sides ?? []"
                     :flagged-attributes="viewing?.flagged" :flag-label="viewing?.flagLabel"
                     @close="viewing = null" />

    <p class="text-xs text-gray-500">
      Drift is a live comparison of the source and target, independent of what the reconciliation history says was
      applied. It’s checked after every reconcile and whenever you verify.
    </p>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import RelativeTime from '@/components/RelativeTime.vue'
import AppModal from '@/components/AppModal.vue'
import SyncEntryViewer, { type EntrySide } from './SyncEntryViewer.vue'
import { appHref } from './syncLinks'
import { useNotificationStore } from '@/stores/notifications'
import { downloadBlob } from '@/composables/useApi'
import {
  getDrift, downloadDriftCsv, previewDriftRepair, repairDrift, previewOrphanDelete, deleteOrphans,
  getHeldReferences, downloadHeldCsv, type SyncHeldStatus, type SyncHeldPage, type SyncHeldEntry,
  type SyncSet, type SyncVerifyResult, type SyncDriftPage, type SyncDriftEntry, type SyncDriftCategory,
  type SyncDriftRepairPreviewItem, type SyncOrphanDeletePreviewItem,
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

/** A held-reference chip: lists held references of one status instead of drift items. */
type HeldFilter = 'HELD_DANGLING' | 'HELD_UNSYNCED' | 'HELD_UNCHECKED'
type CategoryFilter = '' | SyncDriftCategory | HeldFilter
/** Mirrors SyncContentVerifier.HELD_CHECK_LIMIT: distinct referenced entries looked up per check. */
const HELD_CHECK_LIMIT = 10_000
const category = ref<CategoryFilter>('')
const search = ref('')
/** The search as sent: `search`, trimmed, once typing pauses. */
const query = ref('')
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
/** The held references of the selected status, when a held chip is selected. */
const heldPage = ref<SyncHeldPage | null>(null)
const heldView = computed<boolean>(() => category.value.startsWith('HELD_'))
const heldStatus = computed<SyncHeldStatus | undefined>(() =>
  heldView.value ? (category.value.slice('HELD_'.length) as SyncHeldStatus) : undefined)
const heldCounts = computed<Record<SyncHeldStatus, number>>(() => ({
  DANGLING: 0, UNSYNCED: 0, UNCHECKED: 0, ...(data.value?.held ?? {}),
}))
const heldTotal = computed<number>(() =>
  heldCounts.value.DANGLING + heldCounts.value.UNSYNCED + heldCounts.value.UNCHECKED)
const heldEntries = computed<SyncHeldEntry[]>(() => (heldView.value ? heldPage.value?.entries ?? [] : []))
const entries = computed<SyncDriftEntry[]>(() => (heldView.value ? [] : data.value?.entries ?? []))
const matched = computed<number>(() =>
  (heldView.value ? heldPage.value?.matched : data.value?.matched) ?? 0)
const pageCount = computed<number>(() => Math.max(1, Math.ceil(matched.value / size.value)))

const HELD: Record<SyncHeldStatus, { label: string; badge: string }> = {
  DANGLING: { label: 'Doesn’t exist on source', badge: 'badge-amber' },
  UNSYNCED: { label: 'Not synced yet', badge: 'badge-gray' },
  UNCHECKED: { label: 'Not checked', badge: 'badge-gray' },
}
const laterIncomplete = computed<boolean>(() => {
  const d = data.value
  return !!d?.incompleteAt && (!d.verifiedAt || d.incompleteAt > d.verifiedAt)
})

interface Chip { key: string; label: string; value: CategoryFilter; n: number; chip: string; dot: string; hint: string }
const chips = computed<Chip[]>(() => [
  { key: 'all', label: 'All', value: '', n: totalDrift.value, chip: 'bg-gray-100 text-gray-700', dot: '',
    hint: 'Every drift item the last complete check found (held references aren’t drift).' },
  { key: 'missing', label: 'Missing on target', value: 'MISSING', n: counts.value.MISSING,
    chip: 'bg-red-50 text-red-700', dot: 'bg-red-600',
    hint: 'In the set on the source, but not on the target: new in the source, or deleted on the target.' },
  { key: 'orphan', label: 'Orphaned on target', value: 'ORPHAN', n: counts.value.ORPHAN,
    chip: 'bg-amber-50 text-amber-800', dot: 'bg-amber-500',
    hint: 'On the target, but no source entry in the set accounts for it.' },
  { key: 'mismatch', label: 'Content drift', value: 'MISMATCH', n: counts.value.MISMATCH,
    chip: 'bg-yellow-50 text-yellow-800', dot: 'bg-yellow-500',
    hint: 'On both sides, but the target\'s attributes differ from what the source projects.' },
  ...heldChips.value,
])

/** Held-reference chips, each only when the last check found some of that status. */
const heldChips = computed<Chip[]>(() => ([
  { key: 'held-dangling', label: 'Held: doesn’t exist on source', value: 'HELD_DANGLING' as CategoryFilter,
    n: heldCounts.value.DANGLING, chip: 'bg-orange-50 text-orange-800', dot: 'bg-orange-500',
    hint: 'Reference values naming an entry that doesn’t exist on the source. Not drift: fix them on the source.' },
  { key: 'held-unsynced', label: 'Held: not synced yet', value: 'HELD_UNSYNCED' as CategoryFilter,
    n: heldCounts.value.UNSYNCED, chip: 'bg-slate-50 text-slate-700', dot: 'bg-slate-400',
    hint: 'Reference values naming a source entry no sync set places yet. Not drift: they fill in once one does.' },
  { key: 'held-unchecked', label: 'Held: not checked', value: 'HELD_UNCHECKED' as CategoryFilter,
    n: heldCounts.value.UNCHECKED, chip: 'bg-gray-50 text-gray-600', dot: 'bg-gray-400',
    hint: 'Reference values whose named entry wasn’t looked up on the source.' },
]).filter(c => c.n > 0))
const firstHeldKey = computed<string | undefined>(() => heldChips.value[0]?.key)

function setCategory(c: CategoryFilter): void {
  category.value = c
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
/**
 * An orphan can be deleted only when the set writes a source anchor and keys on
 * its directory's own identity attribute, so the anchor proves it's this set's
 * (a custom identity attribute's values have no shape to check).
 */
function deletable(e: SyncDriftEntry): boolean {
  return e.category === 'ORPHAN' && !!props.set.sourceAnchorAttribute && !props.set.identityKey
}
function selectable(e: SyncDriftEntry): boolean {
  return repairable(e) || deletable(e)
}
const selectableOnPage = computed<SyncDriftEntry[]>(() => entries.value.filter(selectable))
const selectedRepairable = computed<number[]>(() =>
  entries.value.filter(e => repairable(e) && selected.value.has(e.seq)).map(e => e.seq))
const selectedOrphans = computed<number[]>(() =>
  entries.value.filter(e => deletable(e) && selected.value.has(e.seq)).map(e => e.seq))
const allSelected = computed<boolean>(() =>
  selectableOnPage.value.length > 0 && selectableOnPage.value.every(e => selected.value.has(e.seq)))
/** The selection column shows only when something on the page can be acted on. */
const showSelect = computed<boolean>(() => !!props.canManage && selectableOnPage.value.length > 0)
const colCount = computed<number>(() => (showSelect.value ? 5 : 4))

/**
 * Why orphans can't be deleted from this tab, or '' when they can: the set must
 * write a source anchor, and key on its directory's own identity, for an anchor
 * value to prove an entry is this set's.
 */
const orphanDeleteBlocked = computed<string>(() => {
  if (!props.set.sourceAnchorAttribute) {
    return 'So orphans can’t be deleted here: without an anchor nothing proves they’re this set’s. Delete any '
      + 'that shouldn’t be there in the Directory Browser.'
  }
  if (props.set.identityKey) {
    return 'Orphans can’t be deleted here: this set keys on a custom identity attribute, whose anchor values can’t '
      + 'prove an entry is this set’s. Delete any that shouldn’t be there in the Directory Browser.'
  }
  return ''
})

function toggleSelected(seq: number): void {
  const next = new Set(selected.value)
  if (next.has(seq)) next.delete(seq)
  else next.add(seq)
  selected.value = next
}

function toggleAll(on: boolean): void {
  selected.value = on ? new Set(selectableOnPage.value.map(e => e.seq)) : new Set()
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

/** The stored verify the page's items come from; sent with every repair or delete. */
function verifiedAt(): string | null {
  return data.value?.verifiedAt ?? null
}

/** A failed preview or apply: say why and reload, since a stale selection is the usual cause. */
function actionFailed(e: unknown): void {
  notif.error(errMsg(e))
  void load()
}

async function openRepair(seqs: number[]): Promise<void> {
  const at = verifiedAt()
  if (!seqs.length || !at) return
  preview.value = null
  showRepair.value = true
  previewing.value = true
  try {
    const { data: d } = await previewDriftRepair(props.set.id, seqs, at)
    preview.value = d.items
  } catch (e) {
    showRepair.value = false
    actionFailed(e)
  } finally {
    previewing.value = false
  }
}

async function applyRepair(): Promise<void> {
  const seqs = toApply.value.map(i => i.seq)
  const at = verifiedAt()
  if (!seqs.length || !at) return
  repairing.value = true
  try {
    const { data: r } = await repairDrift(props.set.id, seqs, at)
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
    showRepair.value = false
    actionFailed(e)
  } finally {
    repairing.value = false
  }
}

// ── Delete orphans ──────────────────────────────────────────────────────────
// Destructive, so its own button, a live re-check, a preview and a typed
// confirmation (the server requires it too).

const showDelete = ref(false)
const deletePreviewing = ref(false)
const deleting = ref(false)
const deletePreview = ref<SyncOrphanDeletePreviewItem[] | null>(null)
const deleteConfirm = ref('')
const toDelete = computed<SyncOrphanDeletePreviewItem[]>(() =>
  (deletePreview.value ?? []).filter(i => i.action === 'DELETE'))
const deleteSkipped = computed<SyncOrphanDeletePreviewItem[]>(() =>
  (deletePreview.value ?? []).filter(i => i.action === 'SKIP'))

async function openDelete(seqs: number[]): Promise<void> {
  const at = verifiedAt()
  if (!seqs.length || !at) return
  deletePreview.value = null
  deleteConfirm.value = ''
  showDelete.value = true
  deletePreviewing.value = true
  try {
    const { data: d } = await previewOrphanDelete(props.set.id, seqs, at)
    deletePreview.value = d.items
  } catch (e) {
    showDelete.value = false
    actionFailed(e)
  } finally {
    deletePreviewing.value = false
  }
}

async function applyDelete(): Promise<void> {
  const seqs = toDelete.value.map(i => i.seq)
  const at = verifiedAt()
  if (!seqs.length || !at || deleteConfirm.value !== 'DELETE') return
  deleting.value = true
  try {
    const { data: r } = await deleteOrphans(props.set.id, seqs, at, deleteConfirm.value)
    const parts = [
      r.deleted ? `${r.deleted} deleted` : '',
      r.skipped ? `${r.skipped} left alone` : '',
      r.failed ? `${r.failed} failed` : '',
    ].filter(Boolean)
    const msg = `Delete finished: ${parts.join(', ') || 'nothing to do'}`
    if (r.failed || r.skipped) {
      const why = r.items.find(i => i.outcome !== 'DELETED' && i.message)?.message
      notif.warning(why ? `${msg}. ${why}` : msg)
    } else {
      notif.success(msg)
    }
    showDelete.value = false
    selected.value = new Set()
    emit('changed')
    await load()
  } catch (e) {
    showDelete.value = false
    actionFailed(e)
  } finally {
    deleting.value = false
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

// ── Viewing entries in place ────────────────────────────────────────────────
// A DN opens the entry (or both sides of it) over the inventory, never a page
// that replaces it; the viewer links on to the Directory Browser in a new tab.

interface Viewing { title: string; sides: EntrySide[]; flagged?: string[]; flagLabel?: string }
const viewing = ref<Viewing | null>(null)

/** Both sides of a drift item: the source member (if any) and the target entry. */
function viewDrift(e: SyncDriftEntry): void {
  const sides: EntrySide[] = []
  if (e.sourceDn) {
    sides.push({ key: 'source', label: 'Source', directoryId: props.sourceDirectoryId ?? null, dn: e.sourceDn,
      missingText: 'No longer on the source.' })
  }
  sides.push({ key: 'target', label: 'Target', directoryId: props.targetDirectoryId ?? null, dn: e.targetDn,
    missingText: e.category === 'MISSING' ? 'Not on the target: that’s the drift.' : 'No longer on the target.' })
  viewing.value = {
    title: `${KIND[e.category].label}: ${e.targetDn}`, sides,
    flagged: e.attributes.map(a => a.attribute),
  }
}

/** The member holding a held reference, on both sides, with the held attribute flagged. */
function viewHeld(h: SyncHeldEntry): void {
  viewing.value = {
    title: `Held reference: ${h.attribute}`,
    sides: [
      { key: 'source', label: 'Source', directoryId: props.sourceDirectoryId ?? null, dn: h.sourceDn,
        missingText: 'No longer on the source.' },
      { key: 'target', label: 'Target', directoryId: props.targetDirectoryId ?? null, dn: h.entryDn,
        missingText: 'Not on the target.' },
    ],
    flagged: [h.attribute],
    flagLabel: 'The held attribute: the target keeps its own value until the entry it names is synced.',
  }
}

/** The entry a held value names, on the source (it isn't synced, so there's no target side). */
function viewReference(h: SyncHeldEntry): void {
  viewing.value = {
    title: `Named by ${h.attribute}`,
    sides: [{ key: 'source', label: 'Source', directoryId: props.sourceDirectoryId ?? null, dn: h.reference,
      missingText: 'Doesn’t exist on the source.' }],
  }
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
    if (heldView.value) {
      const { data: d } = await getHeldReferences(props.set.id, {
        status: heldStatus.value, q: query.value || undefined, page: page.value, size: size.value,
      })
      if (seq !== loadSeq) return
      heldPage.value = d
    } else {
      const { data: d } = await getDrift(props.set.id, {
        category: (category.value || undefined) as SyncDriftCategory | undefined,
        q: query.value || undefined,
        page: page.value,
        size: size.value,
      })
      if (seq !== loadSeq) return
      data.value = d
    }
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
    const name = props.set.name.replace(/[^A-Za-z0-9._-]+/g, '-')
    if (heldView.value) {
      const { data: blob } = await downloadHeldCsv(props.set.id)
      downloadBlob(blob, `sync-held-references-${name}.csv`)
    } else {
      const { data: blob } = await downloadDriftCsv(props.set.id)
      downloadBlob(blob, `sync-drift-${name}.csv`)
    }
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    exporting.value = false
  }
}

// One loader: every change below lands in a single reload, however many of
// them one action makes. The watchers that only adjust state are registered
// first, so they run before it in the same flush.
let searchTimer: ReturnType<typeof setTimeout> | null = null
function stopSearchTimer(): void {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = null
}
watch(search, v => {
  stopSearchTimer()
  searchTimer = setTimeout(() => { query.value = v.trim() }, 300)
})
// Another set, or a finished check: start over.
watch([() => props.set.id, () => props.refreshKey], () => {
  stopSearchTimer()
  category.value = ''
  search.value = ''
  query.value = ''
  page.value = 0
})
// A new filter or page size starts at its first page.
watch([category, query, size], () => { page.value = 0 })
watch([() => props.set.id, () => props.refreshKey, category, query, page, size], () => { void load() },
  { immediate: true })
onBeforeUnmount(stopSearchTimer)
</script>
