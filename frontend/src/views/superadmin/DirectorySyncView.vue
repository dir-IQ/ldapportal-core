<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <PageContainer>
    <div class="flex items-center justify-between mb-6">
      <div>
        <h1 class="text-2xl font-bold text-gray-900">Directory Synchronization</h1>
        <p class="text-sm text-gray-500 mt-1">Membership-driven source → target synchronization</p>
      </div>
      <button @click="openLinkModal()" class="btn-primary">+ New Link</button>
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
        <ActionMenu :items="[{ label: 'Delete', onClick: () => removeLink(row), danger: true }]">
          <template #primary>
            <button class="btn-secondary btn-compact" @click.stop="openLinkModal(row)">Edit</button>
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
            <button class="btn-secondary btn-compact" @click="openSetModal()">+ New Set</button>
          </div>
          <DataTable :columns="setCols" :rows="sets" :loading="loadingSets" row-key="id"
                     empty-text="No sync sets for this link yet." :highlight-key="selectedSetId ?? undefined"
                     @row-click="onSetRowClick">
            <template #cell-name="{ row: s }">
              <span class="font-medium text-gray-900">{{ s.name }}</span>
            </template>
            <template #cell-objectScopeBaseDn="{ row: s }">
              <span class="font-mono text-xs">{{ s.objectScopeBaseDn || '—' }}</span>
            </template>
            <template #cell-targetBaseDn="{ row: s }">
              <span class="font-mono text-xs">{{ s.targetBaseDn || '—' }}</span>
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
              <ActionMenu :items="[{ label: 'Delete', onClick: () => removeSet(s), danger: true }]">
                <template #primary>
                  <button class="btn-secondary btn-compact" @click.stop="openSetModal(s)">Edit</button>
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
              <option value="DSEE_CHANGELOG">DSEE / cn=changelog</option>
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
    <AppModal v-model="showSetModal" :title="editingSet ? 'Edit sync set' : 'New sync set'" size="lg">
      <form class="space-y-3" @submit.prevent="saveSet">
        <div class="grid grid-cols-2 gap-3">
          <FormField label="Name" v-model="setForm.name" required
                     help="A label for this sync set, shown in the inventory and logs. Does not affect matching." />
          <FormField label="Identity key (attribute)" v-model="setForm.identityKey"
                     placeholder="entryUUID / objectGUID (default by type)"
                     help="Source attribute used as each entry's stable identity (e.g. entryUUID, objectGUID). Leave blank to use the directory type's default. Avoid mutable attributes like mail." />
          <FormField label="Source scope base DN" v-model="setForm.objectScopeBaseDn"
                     help="Base DN under the source directory to enumerate entries from. Combined with the source scope below." />
          <FormField label="Target base DN" v-model="setForm.targetBaseDn"
                     help="Base DN under the target directory where matched entries are created and placed." />
          <FormField label="Applicability filter (RFC 4515)" v-model="setForm.applicabilityFilter"
                     placeholder="(&(objectClass=inetOrgPerson)(employeeType=staff))"
                     help="LDAP filter selecting which source entries belong to this set. Only matching entries are synced." />
          <FormField label="Reference attributes (csv)" v-model="setForm.referenceAttributes"
                     placeholder="member,uniqueMember,manager"
                     help="Comma-separated DN-valued attributes whose references are rewritten and closed across the set (e.g. member, uniqueMember, manager)." />
          <FormField label="Source anchor attribute" v-model="setForm.sourceAnchorAttribute"
                     help="Target attribute holding the source identity, used to adopt pre-existing target entries (brownfield). Ambiguous matches are quarantined for review." />
          <FormField label="Reconcile cadence (seconds)" v-model="setForm.reconcileCadenceSeconds"
                     type="number" placeholder="default"
                     help="How often this set is fully reconciled against the source. Leave blank to use the global default." />
          <div>
            <div class="flex items-center mb-1">
              <label class="block text-sm font-medium text-gray-700">Delete policy</label>
              <HelpTip text="What happens when an entry leaves scope: Delete removes the target entry; Review quarantines it for an operator to resolve." />
            </div>
            <select v-model="setForm.deletePolicy" aria-label="Delete policy" class="input">
              <option value="DELETE">Delete</option>
              <option value="REVIEW">Review (quarantine)</option>
            </select>
          </div>
          <div>
            <div class="flex items-center mb-1">
              <label class="block text-sm font-medium text-gray-700">Source scope</label>
              <HelpTip text="LDAP search scope under the base DN: Subtree (all descendants), One level (immediate children), or Base (the base entry only)." />
            </div>
            <select v-model="setForm.objectScope" aria-label="Source scope" class="input">
              <option :value="null">Default (subtree)</option>
              <option value="SUB">Subtree</option>
              <option value="ONE">One level</option>
              <option value="BASE">Base</option>
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
        <div class="flex items-center gap-1">
          <label class="flex items-center gap-2 text-sm text-gray-700">
            <input type="checkbox" v-model="setForm.enabled" class="rounded" /> Enabled
          </label>
          <HelpTip text="When off, this set is not reconciled or applied, and existing target entries are left untouched." />
        </div>
        <div class="flex justify-end gap-2 pt-2">
          <button type="button" class="btn-neutral" @click="showSetModal = false">Cancel</button>
          <button type="submit" class="btn-primary">{{ editingSet ? 'Save' : 'Create' }}</button>
        </div>
      </form>
    </AppModal>
  </PageContainer>
</template>

<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import PageContainer from '@/components/PageContainer.vue'
import AppModal from '@/components/AppModal.vue'
import FormField from '@/components/FormField.vue'
import HelpTip from '@/components/HelpTip.vue'
import DataTable from '@/components/DataTable.vue'
import ActionMenu from '@/components/ActionMenu.vue'
import TransformRulesEditor from '@/components/TransformRulesEditor.vue'
import MembershipInventoryModal from '@/components/sync/MembershipInventoryModal.vue'
import { useNotificationStore } from '@/stores/notifications'
import { listDirectories } from '@/api/directories'
import {
  listSyncLinks, createSyncLink, updateSyncLink, deleteSyncLink,
  listSyncSets, createSyncSet, updateSyncSet, deleteSyncSet,
  type SyncLink, type SyncLinkPayload, type SyncSet, type SyncSetPayload,
  type SyncTransformRule,
} from '@/api/sync'

interface DirOption { id: string; displayName: string }

const notif = useNotificationStore()

const directories = ref<DirOption[]>([])
const links = ref<SyncLink[]>([])
const sets = ref<SyncSet[]>([])
// Every set (across all links) with its membership state counts — drives the
// at-a-glance health rollup on the links/sets tables without drilling in.
const allSets = ref<SyncSet[]>([])

const selectedLinkId = ref<string | null>(null)
const selectedSetId = ref<string | null>(null)
const showMembershipModal = ref(false)
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
    captureMode: 'APP_INTERCEPT', changelogFormat: null, changelogBaseDn: null,
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
  enabled: boolean
}
const showSetModal = ref(false)
const editingSet = ref<SyncSet | null>(null)
const setForm = ref<SetForm>(blankSet())
function blankSet(): SetForm {
  return {
    name: '', identityKey: '', objectScopeBaseDn: '', targetBaseDn: '', applicabilityFilter: '',
    referenceAttributes: '', sourceAnchorAttribute: '', reconcileCadenceSeconds: '',
    deletePolicy: 'DELETE', objectScope: null, transformRules: [], enabled: true,
  }
}
function openSetModal(s?: SyncSet) {
  editingSet.value = s ?? null
  setForm.value = s
    ? {
        name: s.name, identityKey: s.identityKey ?? '', objectScopeBaseDn: s.objectScopeBaseDn ?? '',
        targetBaseDn: s.targetBaseDn ?? '', applicabilityFilter: s.applicabilityFilter ?? '',
        referenceAttributes: s.referenceAttributes ?? '', sourceAnchorAttribute: s.sourceAnchorAttribute ?? '',
        reconcileCadenceSeconds: s.reconcileCadenceSeconds != null ? String(s.reconcileCadenceSeconds) : '',
        deletePolicy: s.deletePolicy, objectScope: s.objectScope,
        transformRules: (s.transformRules ?? []).map((r) => ({ ...r })),
        enabled: s.enabled,
      }
    : blankSet()
  showSetModal.value = true
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
    reconcileCadenceSeconds: f.reconcileCadenceSeconds.trim() ? Number(f.reconcileCadenceSeconds) : null,
    enabled: f.enabled,
  }
}
async function saveSet() {
  try {
    const payload = toPayload(setForm.value)
    if (editingSet.value) await updateSyncSet(editingSet.value.id, payload)
    else await createSyncSet(payload)
    notif.success(editingSet.value ? 'Sync set updated' : 'Sync set created')
    showSetModal.value = false
    await refreshHealth()
  } catch (e) {
    notif.error(errMsg(e))
  }
}
async function removeSet(s: SyncSet) {
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
function selectSet(id: string) {
  selectedSetId.value = id
  showMembershipModal.value = true
}

onMounted(async () => {
  try {
    const [dirs] = await Promise.all([listDirectories(), loadLinks(), loadSetHealth()])
    directories.value = dirs.data.map((d) => ({ id: d.id ?? '', displayName: d.displayName ?? '' }))
  } catch (e) {
    notif.error(errMsg(e))
  }
})
</script>
