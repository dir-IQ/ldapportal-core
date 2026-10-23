<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <PageContainer>
    <div class="flex items-center justify-between mb-6">
      <div>
        <h1 class="text-2xl font-bold text-gray-900">Directory Synchronization</h1>
        <p class="text-sm text-gray-500 mt-1">Membership-driven source → target synchronization</p>
      </div>
      <button v-if="canManage" @click="openLinkModal()" class="btn-primary">+ New Link</button>
    </div>

    <!-- ── Links (each expands to its nested sync sets) ─────────────────── -->
    <DataTable :columns="linkCols" :rows="links" :loading="loadingLinks" row-key="id"
               empty-text="No sync links configured yet."
               :highlight-key="selectedLinkId ?? undefined" :expanded-key="selectedLinkId ?? undefined"
               @row-click="onLinkRowClick">
      <template #cell-displayName="{ row }">
        <span class="inline-flex items-center gap-1.5 font-medium text-gray-900">
          <svg class="w-3 h-3 text-gray-400 transition-transform"
               :class="{ 'rotate-90': selectedLinkId === row.id }"
               viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
            <path stroke-linecap="round" stroke-linejoin="round" d="M7 5l6 5-6 5" />
          </svg>
          {{ row.displayName }}
        </span>
      </template>
      <template #cell-sourceDirId="{ row }">{{ dirName(row.sourceDirId) }}</template>
      <template #cell-targetDirId="{ row }">{{ dirName(row.targetDirId) }}</template>
      <template #cell-health="{ row }">
        <span class="inline-flex items-center gap-1.5 text-xs font-medium"
              :class="healthSummary(linkCounts(row.id)).tone">
          <span class="w-2 h-2 rounded-full shrink-0" :class="healthSummary(linkCounts(row.id)).dot" aria-hidden="true"></span>
          {{ healthSummary(linkCounts(row.id)).label }}
        </span>
      </template>
      <template #cell-enabled="{ row }">
        <span class="text-xs font-medium" :class="row.enabled ? 'text-green-600' : 'text-gray-500'">
          {{ row.enabled ? 'Yes' : 'No' }}
        </span>
      </template>
      <template #actions="{ row }">
        <ActionMenu :items="[{ label: 'Delete', onClick: () => removeLink(row), danger: true, hidden: !canManage }]">
          <template #primary>
            <button v-if="canManage" class="btn-secondary btn-compact" @click.stop="openLinkModal(row)">Edit</button>
          </template>
        </ActionMenu>
      </template>

      <!-- A link's sync sets, nested directly beneath it. -->
      <template #row-detail="{ row }">
        <div class="px-4 py-4 space-y-3">
          <div class="flex items-center justify-between">
            <h3 class="text-sm font-semibold text-gray-700">
              Sync sets — {{ dirName(row.sourceDirId) }} → {{ dirName(row.targetDirId) }}
            </h3>
            <button v-if="canManage" class="btn-secondary btn-compact" @click="openSetModal()">+ New Set</button>
          </div>
          <DataTable :columns="setCols" :rows="sets" :loading="loadingSets" row-key="id"
                     empty-text="No sync sets for this link yet." :highlight-key="selectedSetId ?? undefined"
                     @row-click="onSetRowClick">
            <template #cell-name="{ row: s }">
              <span class="font-medium text-gray-900">{{ s.name }}</span>
            </template>
            <template #cell-objectScopeBaseDn="{ row: s }">
              <span class="font-mono">{{ s.objectScopeBaseDn || '—' }}</span>
            </template>
            <template #cell-targetBaseDn="{ row: s }">
              <span class="font-mono">{{ s.targetBaseDn || '—' }}</span>
            </template>
            <template #cell-health="{ row: s }">
              <span class="inline-flex items-center gap-1.5 text-xs font-medium"
                    :class="healthSummary(setCounts(s)).tone">
                <span class="w-2 h-2 rounded-full shrink-0" :class="healthSummary(setCounts(s)).dot" aria-hidden="true"></span>
                {{ healthSummary(setCounts(s)).label }}
              </span>
            </template>
            <template #cell-enabled="{ row: s }">
              <span class="text-xs font-medium" :class="s.enabled ? 'text-green-600' : 'text-gray-500'">
                {{ s.enabled ? 'Yes' : 'No' }}
              </span>
            </template>
            <template #actions="{ row: s }">
              <ActionMenu :items="[{ label: 'Delete', onClick: () => removeSet(s), danger: true, hidden: !canManage }]">
                <template #primary>
                  <button v-if="canManage" class="btn-secondary btn-compact" @click.stop="openSetModal(s)">Edit</button>
                </template>
              </ActionMenu>
            </template>
          </DataTable>
        </div>
      </template>
    </DataTable>

    <!-- ── Membership inventory (modal for the selected set) ────────────── -->
    <MembershipInventoryModal v-model:show="showMembershipModal" :set="selectedSet ?? null"
                              :source-name="dirName(selectedLink?.sourceDirId)"
                              :target-name="dirName(selectedLink?.targetDirId)"
                              :initial-state="pendingState"
                              @changed="refreshHealth" />

    <!-- ── Link editor ─────────────────────────────────────────────────────── -->
    <AppModal v-model="showLinkModal" :title="editingLink ? 'Edit sync link' : 'New sync link'" size="md">
      <form class="space-y-3" @submit.prevent="saveLink">
        <FormField label="Display name" v-model="linkForm.displayName" required />
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">Source directory</label>
          <select v-model="linkForm.sourceDirId" aria-label="Source directory" class="input" required>
            <option value="" disabled>Select…</option>
            <option v-for="d in directories" :key="d.id" :value="d.id">{{ d.displayName }}</option>
          </select>
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">Target directory</label>
          <select v-model="linkForm.targetDirId" aria-label="Target directory" class="input" required>
            <option value="" disabled>Select…</option>
            <option v-for="d in directories" :key="d.id" :value="d.id">{{ d.displayName }}</option>
          </select>
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">Capture mode</label>
          <select v-model="linkForm.captureMode" aria-label="Capture mode" class="input">
            <option value="APP_INTERCEPT">App-intercept (capture portal writes)</option>
            <option value="CHANGELOG">Changelog (poll source changelog)</option>
          </select>
        </div>
        <template v-if="linkForm.captureMode === 'CHANGELOG'">
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">Changelog format</label>
            <select v-model="linkForm.changelogFormat" aria-label="Changelog format" class="input">
              <option :value="null" disabled>Select…</option>
              <option value="DSEE_CHANGELOG">DSEE / cn=changelog (changeNumber, single node)</option>
              <option value="OUD_COOKIE_CHANGELOG">OUD / cn=changelog (cookie, replication-safe)</option>
            </select>
          </div>
          <FormField label="Changelog base DN" v-model="changelogBaseDnModel" placeholder="cn=changelog" />
        </template>
        <label class="flex items-center gap-2 text-sm text-gray-700">
          <input type="checkbox" v-model="linkForm.enabled" class="rounded" /> Enabled
        </label>
        <div class="flex justify-end gap-2 pt-2">
          <button type="button" class="btn-neutral" @click="showLinkModal = false">Cancel</button>
          <button type="submit" class="btn-primary">{{ editingLink ? 'Save' : 'Create' }}</button>
        </div>
      </form>
    </AppModal>

    <!-- ── Set editor ──────────────────────────────────────────────────────── -->
    <AppModal v-model="showSetModal" :title="editingSet ? 'Edit sync set' : 'New sync set'" size="lg"
              :dirty="setDirty">
      <form id="sync-set-form" class="space-y-3" @submit.prevent="saveSet">
        <div class="grid grid-cols-2 gap-3">
          <FormField label="Name" v-model="setForm.name" required :help="help.name" />
          <FormField label="Identity key (attribute)" v-model="setForm.identityKey"
                     :placeholder="DEFAULT_PLACEHOLDER" :help="help.identityKey" />
          <FormField label="Source base DN" v-model="setForm.objectScopeBaseDn" required
                     :help="help.sourceBaseDn" />
          <FormField label="Target base DN" v-model="setForm.targetBaseDn"
                     :placeholder="DEFAULT_PLACEHOLDER" :help="help.targetBaseDn" />
          <FormField class="col-span-2" label="Applicability filter (RFC 4515)" v-model="setForm.applicabilityFilter"
                     type="textarea" :rows="3" :placeholder="DEFAULT_PLACEHOLDER" :help="help.applicabilityFilter" />
          <FormField label="Reference attributes (csv)" v-model="setForm.referenceAttributes"
                     :placeholder="DEFAULT_PLACEHOLDER" :help="help.referenceAttributes" />
          <FormField label="Source anchor attribute" v-model="setForm.sourceAnchorAttribute"
                     :placeholder="DEFAULT_PLACEHOLDER" :help="help.sourceAnchorAttribute" />
          <FormField label="Reconcile interval (seconds)" v-model="setForm.reconcileCadenceSeconds"
                     type="number" :placeholder="DEFAULT_PLACEHOLDER" :help="help.reconcileInterval" />
          <div>
            <div class="flex items-center mb-1">
              <label for="sync-set-delete-policy" class="block text-sm font-medium text-gray-700">Delete policy</label>
              <HelpTip :text="help.deletePolicy" />
            </div>
            <select id="sync-set-delete-policy" v-model="setForm.deletePolicy" class="input">
              <option value="DELETE">Delete</option>
              <option value="REVIEW">Review (quarantine)</option>
            </select>
          </div>
          <div>
            <div class="flex items-center mb-1">
              <label for="sync-set-source-scope" class="block text-sm font-medium text-gray-700">Source scope</label>
              <HelpTip :text="help.sourceScope" />
            </div>
            <select id="sync-set-source-scope" v-model="setForm.objectScope" class="input">
              <option :value="null">Default (subtree)</option>
              <option value="SUB">Subtree</option>
              <option value="ONE">One level</option>
              <option value="BASE">Base</option>
            </select>
          </div>
          <div>
            <div class="flex items-center mb-1">
              <label for="sync-set-logging-level" class="block text-sm font-medium text-gray-700">Logging level</label>
              <HelpTip :text="help.loggingLevel" />
            </div>
            <select id="sync-set-logging-level" v-model="setForm.loggingLevel" class="input">
              <option value="SUMMARY">Summary</option>
              <option value="DETAILED">Detailed</option>
            </select>
          </div>
        </div>
        <details class="border border-gray-200 rounded-lg">
          <summary class="px-4 py-2 text-sm font-medium text-gray-700 cursor-pointer">
            Attribute mapping <span class="text-gray-400 font-normal">(optional)</span>
          </summary>
          <div class="px-4 pb-4 pt-2 space-y-2">
            <p class="text-xs text-gray-500">
              Rename attributes or template their values on the way to the target. By default every
              source attribute is synced unchanged. Leave <span class="font-medium">Target</span> blank
              to keep the source name; use <code class="font-mono">${value}</code> in the template to
              insert the original value (blank = passthrough). The first rule matching a source
              attribute wins.
            </p>
            <TransformRulesEditor v-model:rules="setForm.transformRules" />
          </div>
        </details>
        <details class="border border-gray-200 rounded-lg">
          <summary class="px-4 py-2 text-sm font-medium text-gray-700 cursor-pointer">
            Excluded attributes <span class="text-gray-400 font-normal">(optional)</span>
          </summary>
          <div class="px-4 pb-4 pt-2 space-y-2">
            <p class="text-xs text-gray-500">
              Attributes never copied from the source nor deleted from the target. Defaults to
              server-maintained operational attributes plus password values
              (<span class="font-mono">userPassword</span>, …) — passwords belong to native directory
              replication, not attribute sync. Edit the list to suit this sync set.
            </p>
            <ExcludedAttributesEditor
              v-model:attributes="setForm.excludedAttributes"
              :defaults="excludedDefaults"
            />
          </div>
        </details>
        <div class="flex items-center gap-1">
          <label class="flex items-center gap-2 text-sm text-gray-700">
            <input type="checkbox" v-model="setForm.enabled" class="rounded" /> Enabled
          </label>
          <HelpTip text="New sets start disabled. When off, this set is not reconciled or applied, and existing target entries are left untouched." />
        </div>

        <!-- Dry-run preview (existing sets only — needs a persisted set to plan
             against). Preview impact opens the per-entry view, which runs on the
             saved set, so it waits for a save while the form holds edits. This
             panel shows the latest run's counts: the entries that view lists. -->
        <div v-if="editingSet" class="rounded-lg border border-gray-200 p-3 space-y-2">
          <div class="flex items-center justify-between">
            <span class="text-sm font-medium text-gray-700">Impact preview</span>
            <button v-if="canManage" type="button" class="btn-secondary btn-compact" :disabled="setDirty"
                    @click="showChanges = true">Preview impact</button>
          </div>
          <p v-if="setDirty" class="text-xs text-gray-500">
            Save your changes to preview them. The preview runs on the saved set.
          </p>
          <p v-else-if="!impact" class="text-xs text-gray-500">
            No preview yet. Preview impact checks every entry against the target and lists what a reconcile would do.
          </p>
          <p v-else-if="impact?.status === 'RUNNING'" class="text-xs text-gray-500">
            Checking every entry against the target… {{ impact.processed }} of {{ impact.total || '…' }}
          </p>
          <p v-else-if="impact?.status === 'FAILED'" class="text-xs text-rose-700">
            The preview failed: {{ impact.error }}
          </p>
          <div v-else-if="impact?.status === 'COMPLETE'" class="text-xs text-gray-600 space-y-1">
            <p class="text-gray-500">{{ impactRanAt }}</p>
            <p>
              Source entries: <span class="font-medium">{{ impact.sourceCount }}</span> ·
              Managed: <span class="font-medium">{{ impact.managedCount }}</span>
            </p>
            <p data-testid="impact-counts">
              <template v-for="(c, i) in impactCounts" :key="c.label">
                <template v-if="i"> · </template>{{ c.label }}:
                <span class="font-medium" :class="c.n > 0 ? c.tone : 'text-gray-700'">{{ c.n }}</span>
              </template>
            </p>
            <p v-if="impact.outdated" class="text-amber-700">
              The set or its directories changed after this preview ran. Preview again for current counts.
            </p>
            <p v-if="!impact.completeScan" class="text-amber-600">
              {{ impact.note ?? 'Source enumeration failed.' }} No deletions would run this cycle.
            </p>
            <p v-if="impact.guardTripped" class="text-amber-700 bg-amber-50 border border-amber-200 rounded px-2 py-1">
              Delete guard would trip: {{ impact.guardReason }}. The deletions would be held for review, not applied.
            </p>
          </div>
        </div>

        <!-- Enable confirmation: surfaced when turning a set on would delete entries. -->
        <div v-if="confirmEnable && impact?.status === 'COMPLETE'"
             class="rounded-lg border border-rose-200 bg-rose-50 p-3 text-sm text-rose-800 space-y-2">
          <p class="font-semibold">Enabling this set will affect existing target entries.</p>
          <p>
            {{ impact.guardTripped
              ? 'The delete guard would trip, so the planned deletions would be held for review.'
              : `${impactCount('DELETE')} target entry(ies) would be deleted.` }}
            Review the preview above before continuing.
          </p>
          <div class="flex justify-end gap-2">
            <button type="button" class="btn-neutral btn-compact" @click="confirmEnable = false">Back</button>
            <button type="button" class="btn-danger btn-compact" @click="doSaveSet">Enable anyway</button>
          </div>
        </div>

      </form>
      <!-- Save keeps the dialog open so the impact preview can run on what was
           just saved; Close goes through AppModal's unsaved-changes guard. -->
      <template #footer="{ close }">
        <button type="button" class="btn-neutral" @click="close()">Close</button>
        <button type="submit" form="sync-set-form" class="btn-primary"
                :disabled="savingSet || (!!editingSet && !setDirty)">
          {{ savingSet ? 'Saving…' : editingSet ? 'Save' : 'Create' }}
        </button>
      </template>
    </AppModal>

    <SyncPreviewChangesModal v-if="editingSet" v-model="showChanges"
                             :set-id="editingSet.id" :set-name="editingSet.name" />
  </PageContainer>
</template>

<script setup lang="ts">
import { onMounted, ref, computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import PageContainer from '@/components/PageContainer.vue'
import AppModal from '@/components/AppModal.vue'
import FormField from '@/components/FormField.vue'
import HelpTip from '@/components/HelpTip.vue'
import DataTable from '@/components/DataTable.vue'
import SyncPreviewChangesModal from './SyncPreviewChangesModal.vue'
import ActionMenu from '@/components/ActionMenu.vue'
import TransformRulesEditor from '@/components/TransformRulesEditor.vue'
import ExcludedAttributesEditor from '@/components/sync/ExcludedAttributesEditor.vue'
import MembershipInventoryModal from '@/components/sync/MembershipInventoryModal.vue'
import { useNotificationStore } from '@/stores/notifications'
import { useAuthStore } from '@/stores/auth'
import { useConfirm } from '@/composables/useConfirm'
import { listDirectories } from '@/api/directories'
import {
  listSyncLinks, createSyncLink, updateSyncLink, deleteSyncLink,
  listSyncSets, createSyncSet, updateSyncSet, deleteSyncSet, getSyncSetDeleteImpact, getSyncSetDefaults,
  getPreviewRun, startPreviewRun,
  type SyncLink, type SyncLinkPayload, type SyncSet, type SyncSetPayload,
  type SyncTransformRule, type SyncPreviewStatus, type SyncPreviewAction, type MembershipState, type SyncSetDeleteImpact,
  type SyncSetDefaults, type SyncLoggingLevel,
} from '@/api/sync'
import { DEFAULT_PLACEHOLDER, syncSetHelp } from '@/components/sync/syncSetHelp'

interface DirOption { id: string; displayName: string }

const notif = useNotificationStore()
const confirm = useConfirm()
const auth = useAuthStore()
// VIEW_DIRECTORY_SYNC shows links, sets, and previews; every write control
// needs MANAGE_DIRECTORY_SYNC (enforced server-side as well).
const canManage = computed(() => auth.hasSuperadminPermission('superadmin.manage_directory_sync'))
const route = useRoute()

const directories = ref<DirOption[]>([])
const links = ref<SyncLink[]>([])
const sets = ref<SyncSet[]>([])
// Every set (across all links) with its membership state counts — drives the
// at-a-glance health rollup on the links/sets tables without drilling in.
const allSets = ref<SyncSet[]>([])

const selectedLinkId = ref<string | null>(null)
const selectedSetId = ref<string | null>(null)
const showMembershipModal = ref(false)
// Seeds the inventory modal's state filter; '' for a normal open, a state for a
// dashboard deep link (e.g. ?state=FAILED). Reset on every manual selection.
const pendingState = ref<'' | MembershipState>('')
const loadingLinks = ref(false)
const loadingSets = ref(false)

const linkCols = [
  { key: 'displayName', label: 'Name' },
  { key: 'sourceDirId', label: 'Source' },
  { key: 'targetDirId', label: 'Target' },
  { key: 'captureMode', label: 'Capture' },
  { key: 'health', label: 'Health' },
  { key: 'enabled', label: 'Enabled' },
]
const setCols = [
  { key: 'name', label: 'Name' },
  { key: 'objectScopeBaseDn', label: 'Scope' },
  { key: 'targetBaseDn', label: 'Target base' },
  { key: 'deletePolicy', label: 'Delete policy' },
  { key: 'health', label: 'Health' },
  { key: 'enabled', label: 'Enabled' },
]

// ── Health rollup (membership state counts) ──
type StateCounts = Record<string, number>

function countTotal(c: StateCounts | undefined): number {
  return Object.values(c ?? {}).reduce((a, b) => a + b, 0)
}
function setCounts(s: SyncSet | undefined): StateCounts {
  return s?.stateCounts ?? {}
}
function linkCounts(linkId: string): StateCounts {
  const acc: StateCounts = {}
  for (const s of allSets.value) {
    if (s.linkId !== linkId) continue
    for (const [k, v] of Object.entries(s.stateCounts ?? {})) acc[k] = (acc[k] ?? 0) + v
  }
  return acc
}
// A single dot + label summarizing health (worst non-zero state wins).
function healthSummary(c: StateCounts): { dot: string; label: string; tone: string } {
  if ((c.FAILED ?? 0) > 0) return { dot: 'bg-red-600', label: `${c.FAILED} failed`, tone: 'text-red-700' }
  if ((c.REVIEW ?? 0) > 0) return { dot: 'bg-amber-500', label: `${c.REVIEW} review`, tone: 'text-amber-800' }
  if (countTotal(c) > 0) return { dot: 'bg-green-600', label: 'Healthy', tone: 'text-green-700' }
  return { dot: 'bg-gray-300', label: 'No data', tone: 'text-gray-400' }
}

function onLinkRowClick(row: SyncLink) {
  // Toggle: clicking the expanded link collapses it (and its nested sets).
  if (selectedLinkId.value === row.id) {
    selectedLinkId.value = null
    selectedSetId.value = null
    sets.value = []
  } else {
    selectLink(row.id)
  }
}
function onSetRowClick(row: SyncSet) { selectSet(row.id) }

const selectedSet = computed(() => allSets.value.find((s) => s.id === selectedSetId.value)
  ?? sets.value.find((s) => s.id === selectedSetId.value))
const selectedLink = computed(() => links.value.find((l) => l.id === selectedLinkId.value))

function dirName(id?: string | null): string {
  return directories.value.find((d) => d.id === id)?.displayName ?? (id ? id.slice(0, 8) : '—')
}

function errMsg(e: unknown): string {
  const err = e as { response?: { data?: { detail?: string; message?: string } } }
  return err.response?.data?.detail || err.response?.data?.message || 'Request failed'
}

// ── Links ──
async function loadLinks() {
  loadingLinks.value = true
  try {
    links.value = (await listSyncLinks()).data
  } finally {
    loadingLinks.value = false
  }
}
// All sets (with state counts) for the health rollup; refreshed after anything
// that can change membership state (reconcile, recompute, dismiss, set edits).
async function loadSetHealth() {
  allSets.value = (await listSyncSets()).data
}
// Refresh the health rollup plus the currently-expanded link's nested sets.
async function refreshHealth() {
  await loadSetHealth()
  if (selectedLinkId.value) sets.value = (await listSyncSets(selectedLinkId.value)).data
}

const showLinkModal = ref(false)
const editingLink = ref<SyncLink | null>(null)
const linkForm = ref<SyncLinkPayload>(blankLink())
// FormField binds a non-null string; proxy the nullable changelogBaseDn.
const changelogBaseDnModel = computed<string>({
  get: () => linkForm.value.changelogBaseDn ?? '',
  set: (v: string) => { linkForm.value.changelogBaseDn = v.trim() ? v.trim() : null },
})
function blankLink(): SyncLinkPayload {
  return {
    displayName: '', sourceDirId: '', targetDirId: '', enabled: true,
    captureMode: 'CHANGELOG', changelogFormat: null, changelogBaseDn: null,
  }
}
function openLinkModal(link?: SyncLink) {
  editingLink.value = link ?? null
  linkForm.value = link
    ? {
        displayName: link.displayName, sourceDirId: link.sourceDirId, targetDirId: link.targetDirId,
        enabled: link.enabled, captureMode: link.captureMode,
        changelogFormat: link.changelogFormat, changelogBaseDn: link.changelogBaseDn,
      }
    : blankLink()
  showLinkModal.value = true
}
async function saveLink() {
  try {
    if (editingLink.value) await updateSyncLink(editingLink.value.id, linkForm.value)
    else await createSyncLink(linkForm.value)
    notif.success(editingLink.value ? 'Link updated' : 'Link created')
    showLinkModal.value = false
    await loadLinks()
  } catch (e) {
    notif.error(errMsg(e))
  }
}
async function removeLink(link: SyncLink) {
  // The server refuses to delete a link that still has sets; say so up
  // front rather than asking to confirm a delete that would fail.
  let setCount: number | null = null
  try {
    setCount = (await listSyncSets(link.id)).data.length
  } catch {
    // Unknown: confirm anyway; the server still refuses if there are sets.
  }
  if (setCount) {
    notif.error(`Delete the link's ${setCount === 1 ? 'sync set' : `${setCount} sync sets`} first.`)
    return
  }
  const lines = [
    `Delete sync link "${link.displayName}" (${dirName(link.sourceDirId)} → ${dirName(link.targetDirId)})?`,
    '',
    '• Neither directory is changed.',
  ]
  if (link.captureMode === 'CHANGELOG') {
    lines.push('• Its position in the source changelog is discarded: a link created to replace it '
      + 'starts afresh rather than resuming where this one stopped.')
  }
  lines.push('', 'This can\'t be undone.')
  const ok = await confirm({
    title: 'Delete sync link',
    message: lines.join('\n'),
    confirmLabel: 'Delete link',
    danger: true,
  })
  if (!ok) return
  try {
    await deleteSyncLink(link.id)
    notif.success('Link deleted')
    if (selectedLinkId.value === link.id) { selectedLinkId.value = null; sets.value = [] }
    await loadLinks()
  } catch (e) {
    notif.error(errMsg(e))
  }
}
async function selectLink(id: string) {
  selectedLinkId.value = id
  selectedSetId.value = null
  loadingSets.value = true
  try {
    sets.value = (await listSyncSets(id)).data
  } finally {
    loadingSets.value = false
  }
}

// ── Sets ──
// The form binds plain strings (FormField rejects null); '' maps to null on save.
interface SetForm {
  name: string
  identityKey: string
  objectScopeBaseDn: string
  targetBaseDn: string
  applicabilityFilter: string
  referenceAttributes: string
  sourceAnchorAttribute: string
  reconcileCadenceSeconds: string
  deletePolicy: 'DELETE' | 'REVIEW'
  objectScope: SyncSet['objectScope']
  transformRules: SyncTransformRule[]
  // null ⇒ use the engine defaults (operational + password values).
  excludedAttributes: string[] | null
  enabled: boolean
  loggingLevel: SyncLoggingLevel
}
const showSetModal = ref(false)
const editingSet = ref<SyncSet | null>(null)
const setForm = ref<SetForm>(blankSet())
// What the engine uses for blank fields (backend-driven): the info tips list
// these, and the excluded-attribute editor seeds from its list.
const setDefaults = ref<SyncSetDefaults | null>(null)
const help = computed(() => syncSetHelp(setDefaults.value))
const excludedDefaults = computed<string[]>(() => setDefaults.value?.excludedAttributes ?? [])
function blankSet(): SetForm {
  return {
    name: '', identityKey: '', objectScopeBaseDn: '', targetBaseDn: '', applicabilityFilter: '',
    referenceAttributes: '', sourceAnchorAttribute: '', reconcileCadenceSeconds: '',
    // Safe defaults: quarantine deletes for review, and start disabled so the
    // operator validates (and can Preview the impact) before turning it on.
    deletePolicy: 'REVIEW', objectScope: null, transformRules: [], excludedAttributes: null, enabled: false,
    loggingLevel: 'SUMMARY',
  }
}
function formFromSet(s: SyncSet): SetForm {
  return {
    name: s.name, identityKey: s.identityKey ?? '', objectScopeBaseDn: s.objectScopeBaseDn ?? '',
    targetBaseDn: s.targetBaseDn ?? '', applicabilityFilter: s.applicabilityFilter ?? '',
    referenceAttributes: s.referenceAttributes ?? '', sourceAnchorAttribute: s.sourceAnchorAttribute ?? '',
    reconcileCadenceSeconds: s.reconcileCadenceSeconds != null ? String(s.reconcileCadenceSeconds) : '',
    deletePolicy: s.deletePolicy, objectScope: s.objectScope,
    transformRules: (s.transformRules ?? []).map((r) => ({ ...r })),
    excludedAttributes: s.excludedAttributes ? [...s.excludedAttributes] : null,
    enabled: s.enabled,
    loggingLevel: s.loggingLevel ?? 'SUMMARY',
  }
}
// Unsaved-changes tracking: the form is dirty while what it would send differs
// from what it held when it was opened or last saved.
const savedSetSnapshot = ref<string>('')
const savingSet = ref<boolean>(false)
function setSnapshot(f: SetForm): string {
  return JSON.stringify(toPayload(f))
}
const setDirty = computed<boolean>(() => showSetModal.value && setSnapshot(setForm.value) !== savedSetSnapshot.value)
function openSetModal(s?: SyncSet) {
  editingSet.value = s ?? null
  setForm.value = s ? formFromSet(s) : blankSet()
  // A new set is dirty from the start: nothing has been saved yet.
  savedSetSnapshot.value = s ? setSnapshot(setForm.value) : ''
  impact.value = null
  confirmEnable.value = false
  showChanges.value = false
  showSetModal.value = true
  void loadLatestPreview()
}
// Trim rows, drop blank-source rows, and collapse blank target/template to null
// (the engine reads null target as "same name" and null template as passthrough).
// An empty list serializes as null. The backend re-validates and re-normalizes.
function cleanRules(rows: SyncTransformRule[]): SyncTransformRule[] | null {
  const cleaned = rows
    .map((r) => ({
      sourceAttr: r.sourceAttr.trim(),
      targetAttr: r.targetAttr?.trim() ? r.targetAttr.trim() : null,
      valueTemplate: r.valueTemplate?.trim() ? r.valueTemplate.trim() : null,
    }))
    .filter((r) => r.sourceAttr)
  return cleaned.length ? cleaned : null
}
function toPayload(f: SetForm): SyncSetPayload {
  const nn = (v: string) => (v.trim() ? v.trim() : null)
  return {
    linkId: selectedLinkId.value ?? '',
    name: f.name.trim(),
    objectScopeBaseDn: nn(f.objectScopeBaseDn),
    objectScope: f.objectScope,
    identityKey: nn(f.identityKey),
    targetBaseDn: nn(f.targetBaseDn),
    applicabilityFilter: nn(f.applicabilityFilter),
    referenceAttributes: nn(f.referenceAttributes),
    sourceAnchorAttribute: nn(f.sourceAnchorAttribute),
    deletePolicy: f.deletePolicy,
    transformRules: cleanRules(f.transformRules),
    excludedAttributes: f.excludedAttributes,
    reconcileCadenceSeconds: f.reconcileCadenceSeconds.trim() ? Number(f.reconcileCadenceSeconds) : null,
    enabled: f.enabled,
    loggingLevel: f.loggingLevel,
  }
}
// ── Dry-run preview + enable confirmation ──
// One per-entry preview run backs the summary here and the Preview impact list,
// so the two can't disagree.
const impact = ref<SyncPreviewStatus | null>(null)
const impactRanAt = computed<string>(() => {
  const at = impact.value?.finishedAt ?? impact.value?.startedAt
  if (!at) return ''
  const by = impact.value?.requestedBy
  return `Last preview: ${new Date(at).toLocaleString()}${by ? ` by ${by}` : ''}.`
})
// Per-entry dry run (what a reconcile would do to each entry), in its own dialog.
const showChanges = ref<boolean>(false)
const confirmEnable = ref(false) // showing the "enable will delete N" confirmation
const PREVIEW_POLL_MS = 1000
// Bumped whenever the run being followed stops mattering (dialog reopened or
// closed, set saved), so a poll still in flight drops its result.
let previewTicket = 0

function impactCount(action: SyncPreviewAction): number {
  return impact.value?.actionCounts?.[action] ?? 0
}
const impactCounts = computed(() => [
  { label: 'Would add', n: impactCount('ADD'), tone: 'text-emerald-700' },
  { label: 'Would modify', n: impactCount('MODIFY'), tone: 'text-sky-700' },
  { label: 'Would move', n: impactCount('MOVE'), tone: 'text-sky-700' },
  { label: 'Would delete', n: impactCount('DELETE'), tone: 'text-rose-700' },
  { label: 'Held for review', n: impactCount('REVIEW'), tone: 'text-amber-700' },
  { label: 'Errors', n: impactCount('ERROR'), tone: 'text-rose-700' },
  { label: 'No change', n: impactCount('NO_CHANGE') + impactCount('UNCHANGED') + impactCount('FORGET'),
    tone: 'text-gray-700' },
])

/** Follow a run until it finishes (or stops mattering); resolves with its last status. */
async function followPreview(status: SyncPreviewStatus, ticket: number): Promise<SyncPreviewStatus | null> {
  let current = status
  while (current.status === 'RUNNING') {
    await new Promise((resolve) => setTimeout(resolve, PREVIEW_POLL_MS))
    if (ticket !== previewTicket || !editingSet.value) return null
    current = (await getPreviewRun(editingSet.value.id)).data
    if (ticket !== previewTicket) return null
    impact.value = current
  }
  return current
}

/** Start a fresh run (or join the one running) and wait for it: the enable confirmation's check. */
async function runPreview(): Promise<SyncPreviewStatus | null> {
  if (!editingSet.value) return null
  const ticket = ++previewTicket
  try {
    const { data } = await startPreviewRun(editingSet.value.id)
    if (ticket !== previewTicket) return null
    impact.value = data
    return await followPreview(data, ticket)
  } catch (e) {
    notif.error(errMsg(e))
    return null
  }
}

/** Show the set's latest run, if any (following it when it's still running). */
async function loadLatestPreview(): Promise<void> {
  if (!editingSet.value) return
  const ticket = ++previewTicket
  try {
    const { data } = await getPreviewRun(editingSet.value.id)
    if (ticket !== previewTicket) return
    impact.value = data.status ? data : null
    if (data.status === 'RUNNING') await followPreview(data, ticket)
  } catch {
    // The summary is a convenience: without it the panel just offers Preview impact.
  }
}

// The per-entry view starts a fresh run when the latest is missing or outdated:
// show what it ran once it closes.
watch(showChanges, (open) => {
  if (!open && showSetModal.value) void loadLatestPreview()
})
watch(showSetModal, (open) => {
  if (!open) previewTicket++
})

async function saveSet() {
  // Turning an existing set on is the moment deletions could start. Preview the
  // impact first and make the operator confirm if anything would be deleted (or
  // the guard would quarantine).
  const enabling = !!editingSet.value && setForm.value.enabled && !editingSet.value.enabled
  if (enabling && !confirmEnable.value) {
    const p = await runPreview()
    if (p?.status === 'COMPLETE' && ((p.actionCounts?.DELETE ?? 0) > 0 || p.guardTripped)) {
      confirmEnable.value = true
      return
    }
  }
  await doSaveSet()
}

// Saves and keeps the dialog open: the form is reseeded from what the server
// stored, and a new set becomes an existing one, so the impact preview can run
// on it straight away.
async function doSaveSet() {
  const creating = !editingSet.value
  savingSet.value = true
  try {
    const payload = toPayload(setForm.value)
    const { data } = editingSet.value
      ? await updateSyncSet(editingSet.value.id, payload)
      : await createSyncSet(payload)
    editingSet.value = data
    setForm.value = formFromSet(data)
    savedSetSnapshot.value = setSnapshot(setForm.value)
    previewTicket++
    impact.value = null // it described the configuration before this save
    confirmEnable.value = false
    notif.success(creating ? 'Sync set created' : 'Sync set updated')
    await refreshHealth()
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    savingSet.value = false
  }
}
function plural(n: number, one: string, many = `${one}s`): string {
  return `${n.toLocaleString()} ${n === 1 ? one : many}`
}

/** The confirmation text: what the delete discards and what it leaves behind. */
function deleteSetMessage(name: string, impact: SyncSetDeleteImpact | null): string {
  if (!impact) {
    return `Delete sync set "${name}"? Its membership index, reconcile history and any entries held `
      + 'for review are deleted. Entries it wrote stay on the target, no longer managed. '
      + 'This can\'t be undone.'
  }
  const lines = [`Delete sync set "${impact.name}"?`, '']
  lines.push(impact.managed > 0
    ? `• ${plural(impact.managed, 'entry', 'entries')} it manages stay on the target, no longer managed: `
      + 'nothing will update or delete them.'
    : '• It manages no target entries.')
  if (impact.pendingReview > 0) {
    lines.push(`• ${plural(impact.pendingReview, 'entry', 'entries')} held for review `
      + `${impact.pendingReview === 1 ? 'is' : 'are'} discarded without a decision.`)
  }
  if (impact.reconcileRuns > 0) {
    lines.push(`• ${plural(impact.reconcileRuns, 'recorded reconcile run')} ${impact.reconcileRuns === 1 ? 'is' : 'are'} deleted.`)
  }
  if (impact.otherSets.length > 0 && impact.managed > 0) {
    const shown = impact.otherSets.slice(0, 5).join(', ')
    const more = impact.otherSets.length > 5 ? ` and ${impact.otherSets.length - 5} more` : ''
    lines.push(`• Other sync sets (${shown}${more}) remap DN references (member, manager, …) through `
      + 'every set\'s entries: a reference to an entry only this set mirrors is dropped from their '
      + 'targets on their next recompute.')
  }
  lines.push('', 'This can\'t be undone.')
  return lines.join('\n')
}

async function removeSet(s: SyncSet) {
  let impact: SyncSetDeleteImpact | null = null
  try {
    impact = (await getSyncSetDeleteImpact(s.id)).data
  } catch {
    // Still confirm, without the counts.
  }
  const ok = await confirm({
    title: 'Delete sync set',
    message: deleteSetMessage(s.name, impact),
    confirmLabel: 'Delete set',
    danger: true,
  })
  if (!ok) return
  try {
    await deleteSyncSet(s.id)
    notif.success('Sync set deleted')
    if (selectedSetId.value === s.id) { selectedSetId.value = null; showMembershipModal.value = false }
    await refreshHealth()
  } catch (e) {
    notif.error(errMsg(e))
  }
}
// Selecting a set opens its membership inventory modal (paged + filtered there).
// A manual click always opens unfiltered — deep links go through openSetById.
function selectSet(id: string) {
  pendingState.value = ''
  selectedSetId.value = id
  showMembershipModal.value = true
}

// ── Dashboard deep links ──
// Open a set's inventory by id, expanding its parent link first so the row is
// visible behind the modal; optionally seed the inventory's state filter.
async function openSetById(setId: string, state: '' | MembershipState = '') {
  const target = allSets.value.find((s) => s.id === setId)
  if (!target) return
  if (selectedLinkId.value !== target.linkId) await selectLink(target.linkId)
  pendingState.value = state
  selectedSetId.value = setId
  showMembershipModal.value = true
}

// The set with the most memberships in a given state — where the FAILED / REVIEW
// dashboard actions land, since they carry a state but not a specific set.
function worstSetByState(state: MembershipState): SyncSet | undefined {
  let best: SyncSet | undefined
  let bestN = 0
  for (const s of allSets.value) {
    const n = s.stateCounts?.[state] ?? 0
    if (n > bestN) { bestN = n; best = s }
  }
  return best
}

const VALID_STATES = ['APPLIED', 'PENDING', 'FAILED', 'REVIEW']
function queryParam(v: unknown): string | undefined {
  const raw = Array.isArray(v) ? v[0] : v
  return typeof raw === 'string' ? raw : undefined
}

// Honor dashboard deep links once links + set health have loaded:
//   ?set=<id>            → open that set's inventory
//   ?state=FAILED|REVIEW → open the worst set in that state, pre-filtered
//   ?link=<id>           → expand + highlight that link
async function applyDeepLink() {
  const setId = queryParam(route.query.set)
  const linkId = queryParam(route.query.link)
  const stateRaw = queryParam(route.query.state)
  const state: '' | MembershipState =
    stateRaw && VALID_STATES.includes(stateRaw) ? (stateRaw as MembershipState) : ''

  if (setId) {
    await openSetById(setId, state)
  } else if (state) {
    const target = worstSetByState(state)
    if (target) await openSetById(target.id, state)
  } else if (linkId && links.value.some((l) => l.id === linkId)) {
    await selectLink(linkId)
  }
}

onMounted(async () => {
  try {
    const [dirs, defaults] = await Promise.all([
      listDirectories(), getSyncSetDefaults(), loadLinks(), loadSetHealth(),
    ])
    directories.value = dirs.data.map((d) => ({ id: d.id ?? '', displayName: d.displayName ?? '' }))
    setDefaults.value = defaults.data
    // links + set health are loaded above, so the deep link can resolve its target.
    await applyDeepLink()
  } catch (e) {
    notif.error(errMsg(e))
  }
})
</script>
