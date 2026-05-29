<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div class="p-6">
    <div class="flex items-center justify-between mb-6">
      <div>
        <h1 class="text-2xl font-bold text-gray-900">Manage Accounts</h1>
        <p class="text-sm text-gray-500 mt-1">Create and manage superadmin and admin accounts</p>
      </div>
      <button @click="openCreate" class="btn-primary">+ New Admin</button>
    </div>

    <DataTable :columns="cols" :rows="admins" :loading="loading" row-key="id" empty-text="No admin users found.">
      <template #cell-displayName="{ row }">
        <div>
          <p class="font-medium text-gray-900">{{ (row as AdminRow).displayName || (row as AdminRow).username }}</p>
          <p class="text-xs text-gray-500">{{ (row as AdminRow).username }}</p>
        </div>
      </template>
      <template #cell-active="{ value }">
        <span :class="value ? 'badge-green' : 'badge-gray'">{{ value ? 'Active' : 'Inactive' }}</span>
      </template>
      <template #cell-role="{ value }">
        <span :class="value === 'SUPERADMIN' ? 'badge-blue' : 'badge-gray'">{{ value }}</span>
      </template>
      <template #cell-authType="{ value }">
        <span class="text-xs text-gray-500 uppercase">{{ value }}</span>
      </template>
      <template #cell-lastLoginAt="{ value }">
        <span class="text-xs text-gray-500">{{ value ? new Date(value as string).toLocaleString() : '—' }}</span>
      </template>
      <template #actions="{ row }">
        <ActionMenu :items="[
          { label: 'Permissions',      onClick: () => openEditWithPermissions(row as AdminRow), hidden: (row as AdminRow).role !== 'ADMIN' },
          { label: 'What can they do?', onClick: () => openEffectivePermissions(row as AdminRow),
            title: 'Show the computed ‘what can this admin actually do?’ breakdown per profile' },
          { label: 'Delete',           onClick: () => confirmDelete(row as AdminRow), danger: true,
            hidden: (row as AdminRow).id === currentPrincipalId },
        ]">
          <template #primary>
            <button @click="openEdit(row as AdminRow)" class="btn-secondary btn-compact">Edit</button>
          </template>
        </ActionMenu>
      </template>
    </DataTable>

    <!-- Combined Edit / Permissions modal: details tab carries the
         account fields (the previous Edit modal); permissions tab
         carries the profile roles + feature overrides UI (the
         previous standalone Permissions modal). One <AppModal> with
         shared footer; tab visibility logic is in `tabs` below. -->
    <AppModal
      v-model="showForm"
      :title="editing ? 'Edit Admin User' : 'New Admin User'"
      size="lg"
      fixed-height="min(640px, 80vh)"
    >
      <!-- Tab nav. Permissions tab is hidden for SUPERADMIN role
           (their access isn't profile- or feature-scoped). For
           create flows on ADMIN it's available too — selections are
           kept in a local draft and committed alongside the account
           via POST /admins/with-permissions. -->
      <div
        v-if="tabs.length > 1"
        class="flex gap-1 mb-4 bg-gray-100 p-1 rounded-lg w-fit"
      >
        <button
          v-for="tab in tabs"
          :key="tab.key"
          type="button"
          @click="switchTab(tab.key)"
          :class="activeTab === tab.key
            ? 'bg-white text-gray-900 shadow-sm'
            : 'text-gray-500 hover:text-gray-700'"
          class="px-4 py-1.5 rounded-md text-sm font-medium transition-colors"
        >{{ tab.label }}</button>
      </div>

      <!-- ── Details tab ─────────────────────────────────────── -->
      <form v-show="activeTab === 'details'" @submit.prevent="save" class="space-y-0.5">
        <FormField label="Username" v-model="form.username" required :disabled="!!editing"
          hint="Used to log in. Cannot be changed after creation." />
        <FormField label="Display name" v-model="form.displayName" placeholder="Optional" />
        <FormField label="Email" v-model="form.email" type="email" placeholder="Optional" />
        <FormField label="Role" v-model="form.role" type="select" required
          :options="[{ value: 'ADMIN', label: 'Admin' }, { value: 'SUPERADMIN', label: 'Superadmin' }]"
          hint="Superadmins have full platform access. Admins have profile-scoped permissions." />
        <FormField label="Auth type" v-model="form.authType" type="select" required
          :options="authTypeOptions"
          hint="LOCAL uses a portal password. LDAP authenticates against the configured LDAP directory. OIDC authenticates via SSO. WEBSEAL trusts the iv-user header from IBM Verify Identity Access. Only methods configured in Settings are shown." />
        <FormField v-if="form.authType === 'LOCAL'" label="Password" v-model="form.password" type="password"
          :placeholder="editing ? 'Leave blank to keep current' : 'Enter password'"
          :hint="editing ? 'Only fill in to change the password.' : 'Set the initial password for this account.'" />
        <FormField v-if="form.authType === 'LDAP'" label="LDAP DN" v-model="form.ldapDn"
          placeholder="e.g. uid=jdoe,ou=People,dc=example,dc=com"
          hint="Distinguished name used to bind against the LDAP auth directory." />
        <p v-if="form.authType === 'OIDC'" class="text-xs text-gray-500 bg-gray-50 rounded-lg px-3 py-2">
          OIDC accounts are matched by username against the configured ID token claim. No password is needed.
        </p>
        <p v-if="form.authType === 'WEBSEAL'" class="text-xs text-gray-500 bg-gray-50 rounded-lg px-3 py-2">
          WebSEAL accounts are matched by username against the <code class="bg-white px-1 rounded">iv-user</code>
          HTTP header forwarded by IBM Verify Identity Access. Set <strong>Username</strong> to the
          exact value WebSEAL will send. No password is needed.
        </p>
        <div class="flex items-center gap-2 py-2">
          <input id="active-toggle" type="checkbox" v-model="form.active"
            class="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500" />
          <label for="active-toggle" class="text-sm font-medium text-gray-700">Active</label>
        </div>
      </form>

      <!-- ── Permissions tab (visible on create + edit for ADMIN). -->
      <div v-show="activeTab === 'permissions'" class="space-y-4 text-sm">
        <div v-if="permsLoading" class="py-8 text-center text-sm text-gray-500">Loading…</div>
        <template v-else-if="perms">
          <p v-if="!editing" class="text-xs text-gray-500 bg-gray-50 rounded-lg px-3 py-2">
            Permission selections here are committed together with the new account when you click Create.
          </p>
          <!-- Profile roles -->
          <section>
            <div class="flex items-center justify-between mb-2">
              <h3 class="font-semibold text-gray-700">Profile roles</h3>
            </div>
            <div v-if="perms.profileRoles.length === 0" class="text-gray-500 mb-2">None assigned.</div>
            <table v-else class="w-full text-xs border border-gray-100 rounded-lg overflow-hidden mb-2">
              <thead class="bg-gray-50">
                <tr>
                  <th class="px-3 py-2 text-left text-gray-500 font-medium">Profile</th>
                  <th class="px-3 py-2 text-left text-gray-500 font-medium">Role</th>
                  <th class="px-3 py-2"></th>
                </tr>
              </thead>
              <tbody class="divide-y divide-gray-50">
                <tr v-for="r in perms.profileRoles" :key="r.profileId" class="hover:bg-gray-50">
                  <td class="px-3 py-2 text-gray-700">{{ r.profileName }}</td>
                  <td class="px-3 py-2">
                    <select :value="r.baseRole" @change="changeProfileRole(r.profileId, ($event.target as HTMLSelectElement).value as BaseRole)" :aria-label="`Role for ${r.profileName}`" class="input text-xs py-1">
                      <option value="ADMIN">ADMIN</option>
                      <option value="READ_ONLY">READ_ONLY</option>
                    </select>
                  </td>
                  <td class="px-3 py-2 text-right">
                    <button @click="doRemoveProfileRole(r.profileId)" class="text-red-500 hover:text-red-700 text-xs font-medium">Remove</button>
                  </td>
                </tr>
              </tbody>
            </table>
            <!-- Add profile role -->
            <div class="flex items-center gap-2">
              <select v-model="newProfileId" aria-label="Profile to add" class="input text-xs py-1 flex-1">
                <option value="" disabled>— Add profile —</option>
                <option v-for="p in availableProfiles" :key="p.id" :value="p.id">{{ p.name }}</option>
              </select>
              <select v-model="newProfileRole" aria-label="Role for new profile" class="input text-xs py-1">
                <option value="ADMIN">ADMIN</option>
                <option value="READ_ONLY">READ_ONLY</option>
              </select>
              <button @click="doAddProfileRole" :disabled="!newProfileId" class="btn-primary text-xs">Add</button>
            </div>
          </section>

          <!-- Feature overrides -->
          <section>
            <h3 class="font-semibold text-gray-700 mb-2">Feature permission overrides</h3>
            <p class="text-xs text-gray-500 mb-2">Override the default feature permissions for this admin. Leave as "Default" to use the role-based default.</p>
            <div class="grid grid-cols-2 gap-x-6 gap-y-2">
              <div v-for="fk in allFeatureKeys" :key="fk" class="flex items-center justify-between">
                <span class="text-xs text-gray-700 font-mono">{{ fk }}</span>
                <select :value="featureState(fk)" @change="changeFeature(fk, ($event.target as HTMLSelectElement).value as 'default' | 'enabled' | 'disabled')" :aria-label="`Permission for ${fk}`" class="input text-xs py-0.5 w-28">
                  <option value="default">Default</option>
                  <option value="enabled">Enabled</option>
                  <option value="disabled">Disabled</option>
                </select>
              </div>
            </div>
          </section>
        </template>
      </div>

      <template #footer>
        <!-- Footer is consistent across tabs so the operator can fill
             Details, switch to Permissions, and commit from there
             without a forced trip back. save() already routes
             correctly per (editing × role): edit re-PUTs account
             details (perms are inline-saved as the operator
             interacts); create+ADMIN posts account + draft perms
             atomically via createAdminWithPermissions; create+SUPERADMIN
             posts just the account (the Permissions tab is hidden for
             SUPERADMIN anyway). -->
        <button @click="showForm = false" class="btn-neutral">Cancel</button>
        <button
          @click="save"
          :disabled="saving || !form.username.trim()"
          class="btn-primary"
        >{{ saving ? 'Saving…' : (editing ? 'Save changes' : 'Create') }}</button>
      </template>
    </AppModal>

    <!-- Delete confirm -->
    <ConfirmDialog
      v-model="showDelete"
      title="Delete admin user"
      :message="`Delete '${deleteTarget?.username}'? This cannot be undone.`"
      confirm-label="Delete"
      danger
      @confirm="doDelete"
    />

    <EffectivePermissionsDialog v-model="showEffective"
                                :admin-id="effectiveAdmin.id ?? undefined"
                                :admin-label="effectiveAdmin.username" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import type { Ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'
import { useSettingsStore } from '@/stores/settings'
import {
  listAdmins,
  createAdmin,
  createAdminWithPermissions,
  updateAdmin,
  deleteAdmin,
  getPermissions,
} from '@/api/adminManagement'
import {
  setProfileRole,
  removeProfileRole,
  setFeaturePermissions,
  clearFeaturePermission,
} from '@/api/adminPermissions'
import { listAllProfiles } from '@/api/profiles'
import DataTable from '@/components/DataTable.vue'
import ActionMenu from '@/components/ActionMenu.vue'
import AppModal from '@/components/AppModal.vue'
import FormField from '@/components/FormField.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EffectivePermissionsDialog from '@/components/EffectivePermissionsDialog.vue'

// ── Types ────────────────────────────────────────────────────────────────────

type Role = 'ADMIN' | 'SUPERADMIN'
type AuthType = 'LOCAL' | 'LDAP' | 'OIDC' | 'WEBSEAL'
type BaseRole = 'ADMIN' | 'READ_ONLY'

interface AdminForm {
  username: string
  displayName: string
  email: string
  role: Role
  authType: AuthType
  password: string
  ldapDn: string
  active: boolean
}

interface AdminRow {
  id: string
  username: string
  displayName?: string | null
  email?: string | null
  role: Role
  authType: AuthType
  ldapDn?: string | null
  active: boolean
  lastLoginAt?: string | null
}

interface ProfileRoleEntry {
  profileId: string
  profileName: string
  baseRole: BaseRole
}

interface FeatureOverride {
  featureKey: string
  enabled: boolean
  profileId?: string | null
}

interface PermissionsDraft {
  profileRoles: ProfileRoleEntry[]
  featurePermissions: FeatureOverride[]
}

interface ProfileSummary {
  id: string
  name: string
}

interface ApiError {
  response?: { data?: { detail?: string } }
  message?: string
}

function errMsg(e: unknown): string {
  const err = e as ApiError
  return err.response?.data?.detail || err.message || 'Unknown error'
}

// ── Stores & top-level state ─────────────────────────────────────────────────

const auth     = useAuthStore()
const notif    = useNotificationStore()
const settings = useSettingsStore()

// Auth-type options for the create/edit dropdown. Filtered by
// settings.enabledAuthTypes so operators don't see LDAP / OIDC /
// WEBSEAL as choices when the backend doesn't have those methods
// configured — picking one would create an account that can't sign
// in. LOCAL is always offered; it's the always-available fallback.
//
// Edge case: when editing an existing account whose authType is
// currently disabled in settings (admin disconfigured the method
// after the account was created), surface the value anyway so the
// form has a matching dropdown option and the operator can either
// re-save unchanged or pick a configured replacement.
const ALL_AUTH_TYPE_OPTIONS = [
  { value: 'LOCAL',   label: 'Local' },
  { value: 'LDAP',    label: 'LDAP' },
  { value: 'OIDC',    label: 'OIDC' },
  { value: 'WEBSEAL', label: 'WebSEAL' },
] as const

const authTypeOptions = computed(() => {
  const enabled = new Set(settings.enabledAuthTypes)
  enabled.add('LOCAL') // belt-and-suspenders — LOCAL is always available
  // Preserve the editing-account's current type even if it's no
  // longer enabled, so the dropdown has a valid selection.
  if (form.value.authType) enabled.add(form.value.authType)
  return ALL_AUTH_TYPE_OPTIONS.filter(o => enabled.has(o.value))
})

// Hide the row-level Delete on the operator's own account so they
// can't accidentally lock themselves out. The auth store is plain
// JS; type the principal lookup locally.
const currentPrincipalId = computed<string | null>(
  () => (auth.principal as { id?: string } | null)?.id ?? null,
)

const loading = ref<boolean>(false)
const saving  = ref<boolean>(false)
const admins: Ref<AdminRow[]> = ref([])

const showForm   = ref<boolean>(false)
const showEffective = ref<boolean>(false)
const effectiveAdmin = ref<{ id: string | null; username: string }>({ id: null, username: '' })

function openEffectivePermissions(row: AdminRow): void {
  effectiveAdmin.value = { id: row.id, username: row.username }
  showEffective.value = true
}

const editing: Ref<string | null> = ref(null) // admin id when editing, null when creating
const form: Ref<AdminForm> = ref(emptyForm())

const showDelete   = ref<boolean>(false)
const deleteTarget: Ref<AdminRow | null> = ref(null)

// Permissions state. In edit mode this is fetched from the server on
// first tab activation and each mutating handler calls the API
// immediately. In create mode it's a local draft initialized empty;
// nothing is persisted until save() POSTs to the with-permissions
// endpoint.
const activeTab    = ref<'details' | 'permissions'>('details')
const permsLoading = ref<boolean>(false)
const perms: Ref<PermissionsDraft | null> = ref(null)

// Tabs visibility:
//   - Details: always.
//   - Permissions: visible for ADMIN role (both create + edit).
//     SUPERADMIN access isn't profile- or feature-scoped so the tab
//     stays hidden there.
const tabs = computed(() => {
  const out: Array<{ key: 'details' | 'permissions'; label: string }> = [
    { key: 'details', label: 'Details' },
  ]
  if (form.value.role === 'ADMIN') {
    out.push({ key: 'permissions', label: 'Permissions' })
  }
  return out
})

function switchTab(key: 'details' | 'permissions'): void {
  activeTab.value = key
  if (key === 'permissions' && perms.value === null && !permsLoading.value && editing.value) {
    void loadPerms()
  }
}

// If the operator flips role from ADMIN → SUPERADMIN while the
// Permissions tab is open, the tab disappears (SUPERADMINs aren't
// profile-scoped). Fall back to Details so the modal body isn't
// empty.
watch(() => form.value.role, (role) => {
  if (role !== 'ADMIN' && activeTab.value === 'permissions') {
    activeTab.value = 'details'
  }
})

const cols = [
  { key: 'displayName', label: 'Name / Username' },
  { key: 'email',       label: 'Email' },
  { key: 'role',        label: 'Role' },
  { key: 'authType',    label: 'Auth type' },
  { key: 'active',      label: 'Status' },
  { key: 'lastLoginAt', label: 'Last login' },
]

function emptyForm(): AdminForm {
  return {
    username: '',
    displayName: '',
    email: '',
    role: 'ADMIN',
    authType: 'LOCAL',
    password: '',
    ldapDn: '',
    active: true,
  }
}

function emptyPermsDraft(): PermissionsDraft {
  return { profileRoles: [], featurePermissions: [] }
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const { data } = await listAdmins()
    admins.value = data as AdminRow[]
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    loading.value = false
  }
}

onMounted(load)

function resetModalState(): void {
  perms.value = null
  newProfileId.value = ''
  newProfileRole.value = 'ADMIN'
  activeTab.value = 'details'
}

function openCreate(): void {
  resetModalState()
  editing.value = null
  form.value = emptyForm()
  // Initialize an empty draft up-front so the Permissions tab is
  // usable without a "loading" flash. Nothing reaches the server
  // until save().
  perms.value = emptyPermsDraft()
  showForm.value = true
}

function openEdit(row: AdminRow): void {
  resetModalState()
  editing.value = row.id
  form.value = {
    username: row.username,
    displayName: row.displayName || '',
    email: row.email || '',
    role: row.role || 'ADMIN',
    authType: row.authType || 'LOCAL',
    password: '',
    ldapDn: row.ldapDn || '',
    active: row.active,
  }
  showForm.value = true
}

/**
 * Shortcut for the row-action "Permissions" menu item: opens the
 * combined modal pre-switched to the Permissions tab. Equivalent to
 * openEdit(row) followed by clicking the Permissions tab — the
 * lazy-load happens via switchTab.
 */
function openEditWithPermissions(row: AdminRow): void {
  openEdit(row)
  switchTab('permissions')
}

async function save(): Promise<void> {
  if (!form.value.username.trim()) return
  saving.value = true
  try {
    if (editing.value) {
      await updateAdmin(editing.value, form.value)
      notif.success('Admin user updated')
    } else if (form.value.role === 'ADMIN') {
      // Create-with-permissions: account fields + the draft profile
      // roles and feature overrides in a single transactional call.
      const draft = perms.value ?? emptyPermsDraft()
      await createAdminWithPermissions({
        account: form.value,
        profileRoles: draft.profileRoles.map(r => ({
          profileId: r.profileId, baseRole: r.baseRole,
        })),
        featurePermissions: draft.featurePermissions.map(f => ({
          featureKey: f.featureKey,
          enabled: f.enabled,
          profileId: f.profileId ?? null,
        })),
      })
      notif.success('Admin user created')
    } else {
      // SUPERADMIN create — no profile/feature scoping applies.
      await createAdmin(form.value)
      notif.success('Admin user created')
    }

    // Profile-less reminder. ADMINs without at least one profile
    // role can sign in but have no scope, so the UI presents this
    // as an informational warning rather than blocking the save.
    // SUPERADMINs are unrestricted; skip the check for them.
    if (form.value.role === 'ADMIN') {
      const hasProfileRole = (perms.value?.profileRoles?.length ?? 0) > 0
      if (!hasProfileRole) {
        notif.warning(
          'No provisioning profile is assigned. This admin will not be '
          + 'able to log in until a profile role is assigned under the '
          + 'Permissions tab.',
          { duration: 8000 },
        )
      }
    }

    showForm.value = false
    await load()
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    saving.value = false
  }
}

function confirmDelete(row: AdminRow): void {
  deleteTarget.value = row
  showDelete.value = true
}

async function doDelete(): Promise<void> {
  if (!deleteTarget.value) return
  try {
    await deleteAdmin(deleteTarget.value.id)
    notif.success('Admin user deleted')
    await load()
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    deleteTarget.value = null
  }
}

// ── All profiles (for the profile picker in permissions tab) ─────────────────

const allProfiles: Ref<ProfileSummary[]> = ref([])

onMounted(async () => {
  try {
    const { data } = await listAllProfiles()
    allProfiles.value = data as ProfileSummary[]
  } catch (e) { console.warn('Failed to load profiles:', e) }
})

// Profiles not already assigned in the current draft.
const availableProfiles = computed<ProfileSummary[]>(() => {
  if (!perms.value) return allProfiles.value
  const assigned = new Set(perms.value.profileRoles.map(r => r.profileId))
  return allProfiles.value.filter(p => !assigned.has(p.id))
})

const newProfileId   = ref<string>('')
const newProfileRole = ref<BaseRole>('ADMIN')
const allFeatureKeys: string[] = [
  'USER_CREATE', 'USER_EDIT', 'USER_DELETE', 'USER_ENABLE_DISABLE', 'USER_MOVE',
  'GROUP_MANAGE_MEMBERS', 'GROUP_CREATE_DELETE',
  'BULK_IMPORT', 'BULK_EXPORT',
  'REPORTS_RUN', 'REPORTS_EXPORT', 'REPORTS_SCHEDULE',
]

function featureState(fk: string): 'default' | 'enabled' | 'disabled' {
  const f = perms.value?.featurePermissions?.find(p => p.featureKey === fk && !p.profileId)
  if (!f) return 'default'
  return f.enabled ? 'enabled' : 'disabled'
}

async function loadPerms(): Promise<void> {
  // Loaded against the admin currently being edited. Caller is
  // switchTab('permissions') on first activation; subsequent
  // mutating handlers refresh via reloadPerms.
  if (!editing.value) return
  permsLoading.value = true
  try {
    const { data } = await getPermissions(editing.value)
    perms.value = data as PermissionsDraft
  } catch (e) {
    notif.error(errMsg(e))
    activeTab.value = 'details'
  } finally {
    permsLoading.value = false
  }
}

async function reloadPerms(): Promise<void> {
  if (!editing.value) return
  try {
    const { data } = await getPermissions(editing.value)
    perms.value = data as PermissionsDraft
  } catch (e) {
    notif.error(errMsg(e))
  }
}

function profileName(profileId: string): string {
  return allProfiles.value.find(p => p.id === profileId)?.name ?? profileId
}

async function doAddProfileRole(): Promise<void> {
  if (!newProfileId.value) return
  if (editing.value) {
    try {
      await setProfileRole(editing.value, {
        profileId: newProfileId.value, baseRole: newProfileRole.value,
      })
      newProfileId.value = ''
      await reloadPerms()
    } catch (e) {
      notif.error(errMsg(e))
    }
  } else {
    // Draft mode: mutate locally; committed on save().
    const draft = perms.value ?? emptyPermsDraft()
    draft.profileRoles.push({
      profileId: newProfileId.value,
      profileName: profileName(newProfileId.value),
      baseRole: newProfileRole.value,
    })
    perms.value = draft
    newProfileId.value = ''
  }
}

async function changeProfileRole(profileId: string, baseRole: BaseRole): Promise<void> {
  if (editing.value) {
    try {
      await setProfileRole(editing.value, { profileId, baseRole })
      await reloadPerms()
    } catch (e) {
      notif.error(errMsg(e))
    }
  } else if (perms.value) {
    const row = perms.value.profileRoles.find(r => r.profileId === profileId)
    if (row) row.baseRole = baseRole
  }
}

async function doRemoveProfileRole(profileId: string): Promise<void> {
  if (editing.value) {
    try {
      await removeProfileRole(editing.value, profileId)
      await reloadPerms()
    } catch (e) {
      notif.error(errMsg(e))
    }
  } else if (perms.value) {
    perms.value.profileRoles = perms.value.profileRoles.filter(r => r.profileId !== profileId)
    // Drop any per-profile feature overrides tied to the removed role.
    perms.value.featurePermissions = perms.value.featurePermissions
      .filter(f => f.profileId !== profileId)
  }
}

async function changeFeature(
  featureKey: string,
  state: 'default' | 'enabled' | 'disabled',
): Promise<void> {
  if (editing.value) {
    try {
      if (state === 'default') {
        await clearFeaturePermission(editing.value, featureKey)
      } else {
        await setFeaturePermissions(editing.value, [
          { featureKey, enabled: state === 'enabled' },
        ])
      }
      await reloadPerms()
    } catch (e) {
      notif.error(errMsg(e))
    }
  } else if (perms.value) {
    // Draft mode: admin-wide override only (profileId null) — the
    // per-profile override UI in this view is read-from-server.
    const draft = perms.value
    draft.featurePermissions = draft.featurePermissions
      .filter(f => !(f.featureKey === featureKey && !f.profileId))
    if (state !== 'default') {
      draft.featurePermissions.push({ featureKey, enabled: state === 'enabled' })
    }
  }
}
</script>

<style scoped>
@reference "tailwindcss";
</style>
