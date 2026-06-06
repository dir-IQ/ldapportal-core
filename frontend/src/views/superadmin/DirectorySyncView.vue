<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <PageContainer title="Directory Sync" subtitle="Membership-driven source→target synchronization">
    <div class="space-y-8">
      <!-- ── Links ───────────────────────────────────────────────────────── -->
      <section>
        <div class="flex items-center justify-between mb-2">
          <h2 class="text-base font-semibold text-gray-900">Sync links</h2>
          <button class="btn-primary text-sm" @click="openLinkModal()">New link</button>
        </div>
        <EmptyState v-if="!links.length" message="No sync links configured yet." />
        <table v-else class="w-full text-sm">
          <thead>
            <tr class="text-left text-gray-500 border-b">
              <th class="py-2">Name</th><th>Source</th><th>Target</th><th>Capture</th><th>Enabled</th><th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="l in links" :key="l.id"
                class="border-b hover:bg-gray-50 cursor-pointer"
                :class="{ 'bg-blue-50': selectedLinkId === l.id }"
                @click="selectLink(l.id)">
              <td class="py-2 font-medium">{{ l.displayName }}</td>
              <td>{{ dirName(l.sourceDirId) }}</td>
              <td>{{ dirName(l.targetDirId) }}</td>
              <td>{{ l.captureMode }}</td>
              <td>{{ l.enabled ? 'Yes' : 'No' }}</td>
              <td class="text-right whitespace-nowrap">
                <button class="btn-secondary text-xs" @click.stop="openLinkModal(l)">Edit</button>
                <button class="btn-neutral text-xs ml-1" @click.stop="removeLink(l)">Delete</button>
              </td>
            </tr>
          </tbody>
        </table>
      </section>

      <!-- ── Sets (for the selected link) ─────────────────────────────────── -->
      <section v-if="selectedLinkId">
        <div class="flex items-center justify-between mb-2">
          <h2 class="text-base font-semibold text-gray-900">
            Sync sets — {{ dirName(selectedLink?.sourceDirId) }} → {{ dirName(selectedLink?.targetDirId) }}
          </h2>
          <button class="btn-primary text-sm" @click="openSetModal()">New set</button>
        </div>
        <EmptyState v-if="!sets.length" message="No sync sets for this link yet." />
        <table v-else class="w-full text-sm">
          <thead>
            <tr class="text-left text-gray-500 border-b">
              <th class="py-2">Name</th><th>Scope</th><th>Target base</th><th>Delete policy</th><th>Enabled</th><th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in sets" :key="s.id"
                class="border-b hover:bg-gray-50 cursor-pointer"
                :class="{ 'bg-blue-50': selectedSetId === s.id }"
                @click="selectSet(s.id)">
              <td class="py-2 font-medium">{{ s.name }}</td>
              <td class="font-mono text-xs">{{ s.objectScopeBaseDn || '—' }}</td>
              <td class="font-mono text-xs">{{ s.targetBaseDn || '—' }}</td>
              <td>{{ s.deletePolicy }}</td>
              <td>{{ s.enabled ? 'Yes' : 'No' }}</td>
              <td class="text-right whitespace-nowrap">
                <button class="btn-secondary text-xs" @click.stop="openSetModal(s)">Edit</button>
                <button class="btn-neutral text-xs ml-1" @click.stop="removeSet(s)">Delete</button>
              </td>
            </tr>
          </tbody>
        </table>
      </section>

      <!-- ── Membership inventory (for the selected set) ──────────────────── -->
      <section v-if="selectedSetId">
        <div class="flex items-center justify-between mb-2 gap-2 flex-wrap">
          <h2 class="text-base font-semibold text-gray-900">Membership inventory — {{ selectedSet?.name }}</h2>
          <div class="flex items-center gap-2">
            <select v-model="stateFilter" aria-label="Filter by membership state" class="input text-sm w-40" @change="loadMemberships">
              <option value="">All states</option>
              <option value="APPLIED">Applied</option>
              <option value="FAILED">Failed</option>
              <option value="REVIEW">Review</option>
              <option value="PENDING">Pending</option>
            </select>
            <button class="btn-secondary text-sm" @click="doReconcile" :disabled="reconciling">
              {{ reconciling ? 'Reconciling…' : 'Reconcile now' }}
            </button>
          </div>
        </div>

        <div class="flex items-center gap-2 mb-3">
          <input v-model="recomputeInput" class="input text-sm flex-1"
                 placeholder="Recompute a source DN or identity…" />
          <button class="btn-secondary text-sm" @click="doRecompute" :disabled="!recomputeInput.trim()">
            Recompute
          </button>
        </div>

        <EmptyState v-if="!memberships.length" message="No membership rows match." />
        <table v-else class="w-full text-sm">
          <thead>
            <tr class="text-left text-gray-500 border-b">
              <th class="py-2">Identity</th><th>State</th><th>Source DN</th><th>Target DN</th><th>Reason</th><th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="m in memberships" :key="m.identity" class="border-b">
              <td class="py-2 font-mono text-xs">{{ m.identity }}</td>
              <td>
                <span class="px-2 py-0.5 rounded text-xs" :class="stateClass(m.state)">{{ m.state }}</span>
              </td>
              <td class="font-mono text-xs">{{ m.sourceDn }}</td>
              <td class="font-mono text-xs">{{ m.targetDn }}</td>
              <td class="text-xs text-gray-500">{{ m.failReason || '—' }}</td>
              <td class="text-right whitespace-nowrap">
                <button class="btn-secondary text-xs" @click="recomputeIdentity(m)">Recompute</button>
                <button class="btn-neutral text-xs ml-1" @click="dismiss(m)">Dismiss</button>
              </td>
            </tr>
          </tbody>
        </table>
      </section>
    </div>

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
          <FormField label="Name" v-model="setForm.name" required />
          <FormField label="Identity key (attribute)" v-model="setForm.identityKey"
                     placeholder="entryUUID / objectGUID (default by type)" />
          <FormField label="Source scope base DN" v-model="setForm.objectScopeBaseDn" />
          <FormField label="Target base DN" v-model="setForm.targetBaseDn" />
          <FormField label="Applicability filter (RFC 4515)" v-model="setForm.applicabilityFilter"
                     placeholder="(&(objectClass=inetOrgPerson)(employeeType=staff))" />
          <FormField label="Reference attributes (csv)" v-model="setForm.referenceAttributes"
                     placeholder="member,uniqueMember,manager" />
          <FormField label="Source anchor attribute" v-model="setForm.sourceAnchorAttribute" />
          <FormField label="Reconcile cadence (seconds)" v-model="setForm.reconcileCadenceSeconds"
                     type="number" placeholder="default" />
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">Delete policy</label>
            <select v-model="setForm.deletePolicy" aria-label="Delete policy" class="input">
              <option value="DELETE">Delete</option>
              <option value="REVIEW">Review (quarantine)</option>
            </select>
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">Source scope</label>
            <select v-model="setForm.objectScope" aria-label="Source scope" class="input">
              <option :value="null">Default (subtree)</option>
              <option value="SUB">Subtree</option>
              <option value="ONE">One level</option>
              <option value="BASE">Base</option>
            </select>
          </div>
        </div>
        <label class="flex items-center gap-2 text-sm text-gray-700">
          <input type="checkbox" v-model="setForm.enabled" class="rounded" /> Enabled
        </label>
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
import EmptyState from '@/components/EmptyState.vue'
import { useNotificationStore } from '@/stores/notifications'
import { listDirectories } from '@/api/directories'
import {
  listSyncLinks, createSyncLink, updateSyncLink, deleteSyncLink,
  listSyncSets, createSyncSet, updateSyncSet, deleteSyncSet,
  listMemberships, reconcileSet, recomputeKey, dismissMembership,
  type SyncLink, type SyncLinkPayload, type SyncSet, type SyncSetPayload,
  type Membership, type MembershipState,
} from '@/api/sync'

interface DirOption { id: string; displayName: string }

const notif = useNotificationStore()

const directories = ref<DirOption[]>([])
const links = ref<SyncLink[]>([])
const sets = ref<SyncSet[]>([])
const memberships = ref<Membership[]>([])

const selectedLinkId = ref<string | null>(null)
const selectedSetId = ref<string | null>(null)
const stateFilter = ref<'' | MembershipState>('')
const recomputeInput = ref('')
const reconciling = ref(false)

const selectedLink = computed(() => links.value.find((l) => l.id === selectedLinkId.value))
const selectedSet = computed(() => sets.value.find((s) => s.id === selectedSetId.value))

function dirName(id?: string | null): string {
  return directories.value.find((d) => d.id === id)?.displayName ?? (id ? id.slice(0, 8) : '—')
}

function stateClass(state: MembershipState): string {
  return {
    APPLIED: 'bg-green-100 text-green-800',
    FAILED: 'bg-red-100 text-red-800',
    REVIEW: 'bg-amber-100 text-amber-800',
    PENDING: 'bg-gray-100 text-gray-700',
  }[state]
}

function errMsg(e: unknown): string {
  const err = e as { response?: { data?: { detail?: string; message?: string } } }
  return err.response?.data?.detail || err.response?.data?.message || 'Request failed'
}

// ── Links ──
async function loadLinks() {
  links.value = (await listSyncLinks()).data
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
  memberships.value = []
  sets.value = (await listSyncSets(id)).data
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
  enabled: boolean
}
const showSetModal = ref(false)
const editingSet = ref<SyncSet | null>(null)
const setForm = ref<SetForm>(blankSet())
function blankSet(): SetForm {
  return {
    name: '', identityKey: '', objectScopeBaseDn: '', targetBaseDn: '', applicabilityFilter: '',
    referenceAttributes: '', sourceAnchorAttribute: '', reconcileCadenceSeconds: '',
    deletePolicy: 'DELETE', objectScope: null, enabled: true,
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
        deletePolicy: s.deletePolicy, objectScope: s.objectScope, enabled: s.enabled,
      }
    : blankSet()
  showSetModal.value = true
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
    transformRules: editingSet.value?.transformRules ?? null,
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
    if (selectedLinkId.value) sets.value = (await listSyncSets(selectedLinkId.value)).data
  } catch (e) {
    notif.error(errMsg(e))
  }
}
async function removeSet(s: SyncSet) {
  try {
    await deleteSyncSet(s.id)
    notif.success('Sync set deleted')
    if (selectedSetId.value === s.id) { selectedSetId.value = null; memberships.value = [] }
    if (selectedLinkId.value) sets.value = (await listSyncSets(selectedLinkId.value)).data
  } catch (e) {
    notif.error(errMsg(e))
  }
}
async function selectSet(id: string) {
  selectedSetId.value = id
  await loadMemberships()
}

// ── Inventory + triggers ──
async function loadMemberships() {
  if (!selectedSetId.value) return
  memberships.value = (await listMemberships(selectedSetId.value, stateFilter.value || undefined)).data
}
async function doReconcile() {
  if (!selectedSetId.value) return
  reconciling.value = true
  try {
    const { data } = await reconcileSet(selectedSetId.value)
    notif.success(`Reconcile enumerated ${data.enumerated} source identities`)
    await loadMemberships()
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    reconciling.value = false
  }
}
async function doRecompute() {
  if (!selectedSetId.value || !recomputeInput.value.trim()) return
  try {
    await recomputeKey(selectedSetId.value, recomputeInput.value.trim())
    notif.success('Recompute enqueued')
    recomputeInput.value = ''
  } catch (e) {
    notif.error(errMsg(e))
  }
}
async function recomputeIdentity(m: Membership) {
  if (!selectedSetId.value) return
  try {
    await recomputeKey(selectedSetId.value, m.identity)
    notif.success('Recompute enqueued')
  } catch (e) {
    notif.error(errMsg(e))
  }
}
async function dismiss(m: Membership) {
  if (!selectedSetId.value) return
  try {
    await dismissMembership(selectedSetId.value, m.identity)
    notif.success('Membership dismissed')
    await loadMemberships()
  } catch (e) {
    notif.error(errMsg(e))
  }
}

onMounted(async () => {
  try {
    const [dirs] = await Promise.all([listDirectories(), loadLinks()])
    directories.value = dirs.data.map((d) => ({ id: d.id ?? '', displayName: d.displayName ?? '' }))
  } catch (e) {
    notif.error(errMsg(e))
  }
})
</script>
