<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div class="p-6">
    <div class="flex items-center justify-between mb-6">
      <div>
        <h1 class="text-2xl font-bold text-gray-900">Groups</h1>
        <p class="text-sm text-gray-500 mt-1">Manage groups in this directory</p>
      </div>
      <div class="flex items-center gap-2">
        <div v-if="allProfiles.length > 1" class="flex items-center gap-2">
          <label for="gl-profile" class="text-sm text-gray-600 font-medium">Profile:</label>
          <select id="gl-profile" v-model="selectedProfileId" @change="onProfileChange"
            class="rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500">
            <option value="">All</option>
            <option v-for="p in allProfiles" :key="p.id" :value="p.id">{{ p.name }}</option>
          </select>
        </div>
        <button v-if="can.createDelete" @click="openCreate" class="btn-primary">+ New Group</button>
      </div>
    </div>

    <!-- Search -->
    <div class="flex gap-2 mb-2">
      <input v-model="filterText" placeholder="Filter (e.g. cn=staff*)" aria-label="Filter groups" @keyup.enter="load"
        class="flex-1 rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500" />
      <button @click="load" class="btn-primary">Search</button>
    </div>

    <!-- Visual filter builder. Two-way bound to filterText so edits
         in either the input above or the builder below stay in sync.
         The builder owns its own collapse state. -->
    <LdapFilterBuilder
      v-model="filterText"
      :directory-id="dirId"
      :relevant-attributes="profileAttributeNames"
      class="mb-3"
    />

    <!-- Cap-hit banner. Backend caps at FETCH_LIMIT; user narrows
         the filter (or uses the visual builder above) to focus the
         result set when more entries exist. -->
    <div v-if="capHit" class="mb-3 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">
      Showing the first {{ FETCH_LIMIT }} matching groups. The directory may have more —
      narrow the filter (or use the visual builder above) to focus the result set.
    </div>

    <ResultsTable
      table-key="groups-list"
      :columns="cols"
      :rows="groups"
      row-key="dn"
      empty-text="No groups found"
    >
      <template #toolbar>
        <button
          v-if="can.exportCsv"
          @click="doExportGroups"
          :disabled="exporting"
          class="btn-secondary text-xs"
        >{{ exporting ? 'Exporting…' : 'Export CSV' }}</button>
      </template>
      <template #cell-dn="{ value }">
        <span class="inline-flex items-center gap-1">
          <!-- No max-width cap: the DN truncates against the column's actual
               width, so a widened column shows the full value. -->
          <span class="min-w-0 truncate" :title="value as string">{{ value }}</span>
          <CopyButton :text="value as string" />
        </span>
      </template>
      <template #cell-description="{ value }">
        <span class="cell-muted">{{ value }}</span>
      </template>
      <template #cell-actions="{ row }">
        <ActionMenu :items="[
          { label: 'Members', onClick: () => openMembers(row as unknown as GroupRow), hidden: !can.manageMembers },
          { label: 'Delete',  onClick: () => confirmDelete(row as unknown as GroupRow), danger: true, hidden: !can.createDelete },
        ]">
          <template #primary>
            <button v-if="can.edit" @click="openEdit(row as unknown as GroupRow)" class="btn-secondary btn-compact">Edit</button>
          </template>
        </ActionMenu>
      </template>
    </ResultsTable>

    <!-- Profile picker modal (step 1 of create) — matches the User
         create flow: prompt for a profile when more than one is
         available so the group lands under the right target OU. -->
    <AppModal v-model="showTemplatePicker" title="Choose Profile" size="sm">
      <div class="space-y-2">
        <p class="text-sm text-gray-600 mb-3">Select a provisioning profile to define the target OU for the new group.</p>
        <div v-if="allProfiles.length === 0" class="text-sm text-gray-500 py-4 text-center">
          No provisioning profiles are configured for this directory. Create a profile in the Profiles settings first.
        </div>
        <button
          v-for="p in allProfiles"
          :key="p.id"
          @click="selectProfileAndCreate(p)"
          class="w-full text-left px-4 py-3 border border-gray-200 rounded-lg hover:bg-blue-50 hover:border-blue-300 transition-colors"
        >
          <span class="font-medium text-gray-900 text-sm">{{ p.name }}</span>
          <span v-if="p.targetUserDn" class="text-[13px] text-gray-500 ml-2 font-mono">{{ p.targetUserDn }}</span>
        </button>
      </div>
      <template #footer>
        <button @click="showTemplatePicker = false" class="btn-neutral">Cancel</button>
      </template>
    </AppModal>

    <!-- Create group (step 2 of create) -->
    <AppModal v-model="showCreate" size="lg">
      <template #title>
        <span>New Group</span>
        <span v-if="createProfile?.name" class="text-gray-500 font-normal"> — </span>
        <span v-if="createProfile?.name" class="text-blue-600">{{ createProfile.name }}</span>
        <span v-if="createProfile?.name" class="text-gray-500 font-normal"> profile</span>
      </template>
      <div class="grid grid-cols-3 gap-2">
        <FormField label="Group Name (cn) (RDN)" v-model="createForm.cn" required />
        <div class="col-span-2">
          <FormField label="DN" :model-value="computedGroupDn" required disabled />
        </div>
      </div>
      <FormField label="Object Class" v-model="createForm.objectClass" />
      <FormField label="Owner" v-model="createForm.owner" placeholder="DN of the group owner" :error="createOwnerError" />
      <FormField label="Description" v-model="createForm.description" placeholder="Group description" />
      <template #footer>
        <button @click="showCreate = false" class="btn-neutral">Cancel</button>
        <button @click="doCreate" :disabled="saving" class="btn-primary">Create</button>
      </template>
    </AppModal>

    <!-- Edit group -->
    <AppModal v-model="showEdit" title="Edit Group" size="md">
      <FormField label="Owner" v-model="editForm.owner" placeholder="DN of the group owner" :error="editOwnerError" />
      <FormField label="Description" v-model="editForm.description" placeholder="Group description" />
      <template #footer>
        <button @click="showEdit = false" class="btn-neutral">Cancel</button>
        <button @click="doEdit" :disabled="saving" class="btn-primary">{{ saving ? 'Saving…' : 'Save' }}</button>
      </template>
    </AppModal>

    <!-- Members drawer -->
    <AppModal v-model="showMembers" :title="`Members — ${selectedGroup?.cn || ''}`" size="lg">
      <div class="mb-3 flex gap-2">
        <DnPicker v-model="newMemberDn" :directory-id="dirId" class="flex-1" />
        <button @click="addMember" class="btn-primary">Add</button>
        <button @click="showBulkAdd = !showBulkAdd" class="btn-secondary">Bulk Add</button>
      </div>
      <div v-if="showBulkAdd" class="mb-3 p-3 bg-gray-50 rounded-lg border border-gray-200">
        <label for="gl-bulk-members" class="block text-xs font-medium text-gray-600 mb-1">Add multiple members (one DN per line)</label>
        <textarea id="gl-bulk-members" v-model="bulkMemberDns" rows="4" placeholder="cn=Alice,ou=Users,dc=example,dc=com&#10;cn=Bob,ou=Users,dc=example,dc=com"
          class="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm font-mono focus:outline-none focus:ring-2 focus:ring-blue-500 mb-2"></textarea>
        <p v-if="bulkInvalidLines.length" class="mb-2 text-xs text-amber-700">
          {{ bulkInvalidLines.length }} line{{ bulkInvalidLines.length === 1 ? '' : 's' }} don't look like a DN
          (expected e.g. <span class="font-mono">cn=Alice,ou=Users,dc=example,dc=com</span>) — they'll be sent as-is and may fail.
        </p>
        <div v-if="bulkResult" class="mb-2 p-2 rounded-lg text-xs" :class="bulkResult.failed ? 'bg-amber-50 border border-amber-200 text-amber-800' : 'bg-green-50 border border-green-200 text-green-800'">
          Added {{ bulkResult.added }}, failed {{ bulkResult.failed }}.
          <ul v-if="bulkResult.errors?.length" class="mt-1 list-disc pl-4">
            <li v-for="(e, i) in bulkResult.errors" :key="i">{{ e.memberValue }}: {{ e.error }}</li>
          </ul>
        </div>
        <div class="flex justify-end">
          <button @click="doBulkAdd" :disabled="bulkAdding || !bulkMemberDns.trim()" class="btn-primary text-xs">
            {{ bulkAdding ? 'Adding…' : 'Add All' }}
          </button>
        </div>
      </div>
      <!-- Fills the modal's remaining height (scrolls only past it) instead
           of a fixed cap — the modal body is a flex column. -->
      <ul class="divide-y divide-gray-100 min-h-0 overflow-y-auto">
        <li v-for="dn in members" :key="dn" class="flex items-center justify-between py-2 text-sm">
          <span class="text-xs text-gray-700 truncate" :title="dn">{{ dn }}</span>
          <button @click="removeMember(dn)" class="text-red-500 hover:text-red-700 text-xs">Remove</button>
        </li>
        <li v-if="!members.length" class="py-4 text-center text-gray-500 text-sm">No members</li>
      </ul>
    </AppModal>

    <ConfirmDialog v-model="showDelete" title="Delete Group" :message="`Delete '${deleteTarget?.dn}'?`" confirm-label="Delete" danger @confirm="doDelete" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useNotificationStore } from '@/stores/notifications'
import { useApi, downloadBlob } from '@/composables/useApi'
import * as groupsApi from '@/api/groups'
import { exportGroupCsv } from '@/api/csvTemplates'
import { listProfiles } from '@/api/profiles'
import { usePermissions } from '@/composables/usePermissions'
import ResultsTable from '@/components/ResultsTable.vue'
import LdapFilterBuilder from '@/components/LdapFilterBuilder.vue'
import ActionMenu from '@/components/ActionMenu.vue'
import AppModal from '@/components/AppModal.vue'
import FormField from '@/components/FormField.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import DnPicker from '@/components/DnPicker.vue'
import CopyButton from '@/components/CopyButton.vue'
import { validateDn } from '@/utils/attributeValidation'
import { resolveGroupMembers, type MemberAttr } from '@/utils/groupMembers'

interface ProfileLite {
  id: string
  name: string
  targetUserDn?: string | null
  targetGroupDn?: string | null
  enabled?: boolean
  attributeConfigs?: Array<{ attributeName: string, defaultValue?: string }>
}

interface GroupRow {
  dn: string
  cn?: string
  description?: string
  _owner: string
  _members: string[]
  _memberAttr: MemberAttr
  __entry: { dn: string, attributes?: Record<string, string[] | string | null> }
  [key: string]: unknown
}

interface CreateForm {
  parentDn: string
  cn: string
  objectClass: string
  owner: string
  description: string
}

interface BulkResult {
  added: number
  failed: number
  errors?: Array<{ memberValue: string, error: string }>
}

// Tier-2 pagination: server returns up to FETCH_LIMIT groups
// matching the filter; the table paginates the result set
// client-side. When the cap is hit, capHit drives the banner that
// asks the user to narrow.
const FETCH_LIMIT = 1000

const route = useRoute()
const notif = useNotificationStore()
const { loading, call } = useApi()

// Feature gating for the action surface. Mirrors the backend
// @RequiresFeature checks on GroupController so an admin only sees the
// verbs they're granted (the server still enforces; this just avoids
// showing actions that would 403). The effective feature set comes from
// /auth/me — superadmins get every feature, admins their base-role
// defaults + overrides — so gating never hides an action the caller can
// actually perform.
const { hasFeature } = usePermissions()
const can = computed(() => ({
  createDelete:  hasFeature('group.create_delete'),
  edit:          hasFeature('group.edit'),
  manageMembers: hasFeature('group.manage_members'),
  exportCsv:     hasFeature('bulk.export'),
}))

const dirId         = route.params.dirId as string
const groups        = ref<GroupRow[]>([])
// Cap-hit signal — directory had at least FETCH_LIMIT matching
// groups. >= guards against future drift; equality is what the
// backend actually produces.
const capHit = computed(() => groups.value.length >= FETCH_LIMIT)
const filterText    = ref('')
const showCreate    = ref(false)
// Step-1 profile picker for "New Group" — mirrors UserListView so an
// admin with more than one profile picks the target OU explicitly
// before the form opens.
const showTemplatePicker = ref(false)
// Profile chosen for the current create flow. Distinct from
// profileData (which tracks the *sidebar* picker for list scoping),
// so opening "New Group" from a different profile than the one
// driving the list view works correctly.
const createProfile  = ref<ProfileLite | null>(null)
const showEdit      = ref(false)
const showMembers   = ref(false)
const showDelete    = ref(false)
const selectedGroup = ref<GroupRow | null>(null)
const deleteTarget  = ref<GroupRow | null>(null)
const members       = ref<string[]>([])
const newMemberDn   = ref('')
const saving        = ref(false)
const exporting     = ref(false)
const allProfiles     = ref<ProfileLite[]>([])
const selectedProfileId = ref('')
const profileData     = ref<ProfileLite | null>(null)

// Attribute names the active profile declares — fed to LdapFilterBuilder
// so the attribute picker defaults to this subset instead of the
// directory's full schema. Builder exposes a "Show all attributes"
// toggle for power users; empty array disables scoping.
const profileAttributeNames = computed<string[]>(() =>
  (profileData.value?.attributeConfigs ?? [])
    .map(a => a.attributeName)
    .filter((n): n is string => typeof n === 'string' && n.length > 0),
)
const showBulkAdd   = ref(false)
const bulkMemberDns = ref('')
const bulkAdding    = ref(false)
const bulkResult    = ref<BulkResult | null>(null)
const createForm    = ref<CreateForm>({ parentDn: '', cn: '', objectClass: 'groupOfNames', owner: '', description: '' })
const editForm      = ref({ owner: '', description: '' })
const editingDn     = ref<string | null>(null)
// Inline validation for the DN-valued owner field — mirrors the server's
// DN-syntax check on group create/update for instant feedback.
const createOwnerError = ref<string | null>(null)
const editOwnerError   = ref<string | null>(null)

/**
 * Escape an RDN attribute value per RFC 4514 so a group name containing
 * DN-special characters (comma, +, ", \, <, >, ;, =, leading/trailing
 * space, leading #) produces a valid DN rather than a malformed one.
 * The attribute value itself (sent in `attributes.cn`) stays unescaped —
 * only the copy embedded in the DN needs escaping.
 */
function escapeRdnValue(v: string): string {
  return v
    .replace(/([\\,+"<>;=])/g, '\\$1')
    .replace(/^([ #])/, '\\$1')
    .replace(/ $/, '\\ ')
}

// `cn` is the RDN attribute for every group objectClass this app
// supports (groupOfNames / groupOfUniqueNames / posixGroup / group /
// groupOfURLs all key on cn), so the RDN type is fixed; only the value
// is variable and must be escaped.
const computedGroupDn = computed(() => {
  const cn = createForm.value.cn?.trim()
  const base = createForm.value.parentDn
  if (!cn || !base) return ''
  return `cn=${escapeRdnValue(cn)},${base}`
})

// Bulk member values target the group's member / uniqueMember attribute,
// which are DN-valued — flag lines that don't look like a DN so typos
// (e.g. a pasted bare username) are caught before the round-trip. Soft
// warning only: the lines are still submitted, since the heuristic can't
// be authoritative.
// Shared lightweight DN check (server stays authoritative). Used for the
// bulk-member warning and the owner-field inline validation below.
function looksLikeDn(s: string): boolean {
  return validateDn(s)
}
const bulkInvalidLines = computed(() =>
  bulkMemberDns.value
    .split('\n')
    .map(s => s.trim())
    .filter(Boolean)
    .filter(line => !looksLikeDn(line)),
)

// Curated default-visible attributes for group lists. See the
// matching block in UserListView.vue for the full rationale —
// every attribute the search returns becomes a column, but only
// these show by default; the rest surface in the column picker.
const DEFAULT_GROUP_COLUMNS = ['cn', 'description', 'owner']
const DEFAULT_GROUP_COLUMNS_LC = new Set(DEFAULT_GROUP_COLUMNS.map(s => s.toLowerCase()))

const discoveredAttrs = computed<string[]>(() => {
  const seen = new Set<string>()
  for (const row of groups.value) {
    const entry = row.__entry
    if (!entry?.attributes) continue
    for (const k of Object.keys(entry.attributes)) seen.add(k)
  }
  return [...seen]
})

// Synthetic actions column matches the UserListView pattern; see
// the comment there for why ResultsTable's per-row action menu is
// rendered as a column rather than a slot.
const cols = computed(() => {
  const discoveredSet = new Set(discoveredAttrs.value.map(s => s.toLowerCase()))
  const defaults = DEFAULT_GROUP_COLUMNS.filter(k => discoveredSet.has(k.toLowerCase()))
  const extras = discoveredAttrs.value.filter(k => !DEFAULT_GROUP_COLUMNS_LC.has(k.toLowerCase()))
  return [
    { key: 'dn', label: 'DN', alwaysVisible: true },
    ...defaults.map(k => ({ key: k, label: k === 'cn' ? 'Name' : k })),
    ...extras.map(k => ({ key: k, label: k, defaultHidden: true })),
    // See UserListView for the rationale on 200 — three side-by-side
    // elements (Edit + first menu-item button + kebab trigger) don't
    // fit in 140.
    // Edit (primary) + Members + Delete = 3 inline btn-compact
    // buttons. Each ~70px with the gap-1 spacing; 240 leaves
    // breathing room. Smaller than UserListView's actions column
    // because there's no kebab to host overflow items here.
    { key: 'actions', label: '', alwaysVisible: true, sortable: false, defaultWidth: 240 },
  ]
})

async function load() {
  await call(async () => {
    const { data } = await groupsApi.searchGroups(dirId, {
      filter: filterText.value || undefined,
      // Groups live under the profile's group container, which may be a
      // subtree separate from users. Fall back to the user DN for
      // robustness (the backend backfills targetGroupDn, so this is
      // only a guard against an older cached profile payload).
      baseDn: profileData.value?.targetGroupDn || profileData.value?.targetUserDn || undefined,
      limit:  FETCH_LIMIT,
    })
    const entries = Array.isArray(data) ? data : (data?.entries || [])
    groups.value = entries.map((e: { dn: string, attributes?: Record<string, string[] | string | null> }) => {
      // Flatten every returned attribute so dynamic columns can read
      // them as `row[attr]`. Multi-valued attrs join with ", ".
      // Underscored helper fields (used by the action menu / member
      // viewer) stay on the row alongside the flattened keys; they
      // don't conflict because LDAP attribute names don't start with
      // underscore. The full entry is preserved on `__entry` for
      // anything that needs raw access.
      const row: Record<string, unknown> = { dn: e.dn, __entry: e }
      const attrs = e.attributes ?? {}
      for (const [attr, vals] of Object.entries(attrs)) {
        const arr = Array.isArray(vals) ? vals : (vals == null ? [] : [vals])
        row[attr] = arr.join(', ')
      }
      const owner = attrs.owner
      // resolveGroupMembers handles the backend's lower-cased attribute
      // keys (`uniquemember`) — a camelCase lookup here rendered every
      // groupOfUniqueNames group's member list as empty.
      const { members, memberAttr } = resolveGroupMembers(attrs)
      row._owner      = Array.isArray(owner) ? (owner[0] || '') : (owner || '')
      row._members    = members
      row._memberAttr = memberAttr
      return row as unknown as GroupRow
    })
  })
}

/**
 * Streams the full group list under the active filter as CSV via
 * the backend's bulk-export endpoint (gated by BULK_EXPORT). Honours
 * the same filter / baseDn the page is viewing.
 */
async function doExportGroups() {
  if (exporting.value) return
  exporting.value = true
  try {
    const params = {
      filter: filterText.value || undefined,
      baseDn: profileData.value?.targetGroupDn || profileData.value?.targetUserDn || undefined,
    }
    const { data } = await exportGroupCsv(dirId, params)
    downloadBlob(data, 'groups.csv')
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    notif.error(err.response?.data?.detail || err.message || 'Export failed')
  } finally {
    exporting.value = false
  }
}

async function doCreate() {
  createOwnerError.value = null
  const ownerVal = createForm.value.owner?.trim()
  if (ownerVal && !validateDn(ownerVal)) {
    createOwnerError.value = 'Not a valid DN'
    return
  }
  saving.value = true
  try {
    const f = createForm.value
    const dn = computedGroupDn.value
    const attributes: Record<string, string[]> = {
      cn: [f.cn],
      objectClass: [f.objectClass],
    }
    if (f.owner?.trim()) attributes.owner = [f.owner.trim()]
    if (f.description?.trim()) attributes.description = [f.description.trim()]
    await groupsApi.createGroup(dirId, { dn, attributes })
    notif.success('Group created')
    showCreate.value = false
    await load()
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    notif.error(err.response?.data?.detail || err.message)
  }
  finally { saving.value = false }
}

function openCreate() {
  // Mirror UserListView's two-step flow: when multiple enabled profiles
  // exist, force an explicit pick so the operator sees which group
  // container the group will be created under. Single profile auto-picks;
  // no profile falls back to the bare form using the directory base.
  createProfile.value = null
  createOwnerError.value = null
  if (allProfiles.value.length === 1) {
    selectProfileAndCreate(allProfiles.value[0])
  } else if (allProfiles.value.length > 1) {
    showTemplatePicker.value = true
  } else {
    createForm.value = emptyCreateForm('')
    showCreate.value = true
  }
}

function selectProfileAndCreate(p: ProfileLite) {
  showTemplatePicker.value = false
  createProfile.value = p
  createOwnerError.value = null
  createForm.value = emptyCreateForm(p?.targetGroupDn || p?.targetUserDn || '')
  showCreate.value = true
}

function emptyCreateForm(parentDn: string): CreateForm {
  return {
    parentDn,
    cn: '',
    objectClass: 'groupOfNames',
    owner: '',
    description: '',
  }
}

function openEdit(row: GroupRow) {
  editingDn.value = row.dn
  editOwnerError.value = null
  editForm.value = {
    owner: row._owner,
    description: row.description === '—' ? '' : (row.description ?? ''),
  }
  showEdit.value = true
}

async function doEdit() {
  if (!editingDn.value) return
  editOwnerError.value = null
  const ownerVal = editForm.value.owner?.trim()
  if (ownerVal && !validateDn(ownerVal)) {
    editOwnerError.value = 'Not a valid DN'
    return
  }
  saving.value = true
  try {
    const mods = [
      { operation: 'REPLACE', attribute: 'owner', values: editForm.value.owner?.trim() ? [editForm.value.owner.trim()] : [] },
      { operation: 'REPLACE', attribute: 'description', values: editForm.value.description?.trim() ? [editForm.value.description.trim()] : [] },
    ]
    await groupsApi.updateGroup(dirId, editingDn.value, { modifications: mods })
    notif.success('Group updated')
    showEdit.value = false
    await load()
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    notif.error(err.response?.data?.detail || err.message)
  }
  finally { saving.value = false }
}

function openMembers(row: GroupRow) {
  selectedGroup.value = row
  members.value       = [...row._members]
  newMemberDn.value   = ''
  showBulkAdd.value   = false
  bulkMemberDns.value = ''
  bulkResult.value    = null
  showMembers.value   = true
}

async function addMember() {
  if (!newMemberDn.value.trim() || !selectedGroup.value) return
  const grp = selectedGroup.value
  const res = await call(
    () => groupsApi.addGroupMember(dirId, grp.dn, { memberAttribute: grp._memberAttr, memberValue: newMemberDn.value }),
  )
  if (res?.status === 202) {
    notif.success('Group member addition submitted for approval')
  } else {
    notif.success('Member added')
    members.value.push(newMemberDn.value)
  }
  newMemberDn.value = ''
}

async function doBulkAdd() {
  if (!selectedGroup.value) return
  const dns = bulkMemberDns.value.split('\n').map(s => s.trim()).filter(Boolean)
  if (!dns.length) return
  bulkAdding.value = true
  bulkResult.value = null
  try {
    const { data } = await groupsApi.addGroupMembersBulk(dirId, selectedGroup.value.dn, {
      memberAttribute: selectedGroup.value._memberAttr,
      memberValues: dns,
    })
    bulkResult.value = data
    // Refresh members list
    for (const d of dns) {
      if (!members.value.includes(d)) members.value.push(d)
    }
    if (data.added > 0) bulkMemberDns.value = ''
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    notif.error(err.response?.data?.detail || err.message)
  } finally {
    bulkAdding.value = false
  }
}

async function removeMember(dn: string) {
  if (!selectedGroup.value) return
  const grp = selectedGroup.value
  await call(
    () => groupsApi.removeGroupMember(dirId, grp.dn, { memberAttribute: grp._memberAttr, memberValue: dn }),
    { successMsg: 'Member removed' }
  )
  members.value = members.value.filter(m => m !== dn)
}

function confirmDelete(row: GroupRow) { deleteTarget.value = row; showDelete.value = true }
async function doDelete() {
  if (!deleteTarget.value) return
  await call(() => groupsApi.deleteGroup(dirId, deleteTarget.value!.dn), { successMsg: 'Group deleted' })
  await load()
}

async function loadProfiles() {
  try {
    const { data: profiles } = await listProfiles(dirId)
    // Filter disabled profiles — same logic as the UserListView picker
    // and the sidebar Profile picker. Picker means "provision through";
    // disabled profiles can't.
    const enabled: ProfileLite[] = profiles.filter((p: ProfileLite) => p.enabled !== false)
    allProfiles.value = enabled
    if (enabled.length === 1) {
      selectedProfileId.value = enabled[0].id
      profileData.value = enabled[0]
    }
  } catch (e) {
    const err = e as { message?: string }
    console.warn('Failed to load profiles:', err.message ?? e)
  }
}

function onProfileChange() {
  const p = allProfiles.value.find((p: ProfileLite) => p.id === selectedProfileId.value)
  profileData.value = p || null
  load()
}

onMounted(async () => {
  await loadProfiles()
  load()
})
</script>

<style scoped>
@reference "tailwindcss";
</style>
