<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div class="p-6">
    <!-- Header -->
    <div class="flex items-center justify-between mb-6">
      <div>
        <h1 class="text-2xl font-bold text-gray-900">Users</h1>
        <p class="text-sm text-gray-500 mt-1">Manage users in this directory</p>
      </div>
      <div class="flex items-center gap-3">
        <div v-if="allProfiles.length > 1" class="flex items-center gap-2">
          <label for="ul-profile" class="text-sm text-gray-600 font-medium">Profile:</label>
          <select id="ul-profile" v-model="selectedProfileId" @change="onProfileChange"
            class="rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500">
            <option value="">All</option>
            <option v-for="p in allProfiles" :key="p.id" :value="p.id">{{ p.name }}</option>
          </select>
        </div>
        <!--
          Bulk Update only makes sense with ≥2 rows — for a single row the
          per-row Edit action is the right tool. Showing it with 1 selected
          was just noise.
        -->
        <button v-if="bulkSelectActive" @click="openBulkUpdate" class="btn-secondary">
          Bulk Update ({{ selectedDns.size }})
        </button>
        <button @click="openCreate" class="btn-primary">+ New User</button>
      </div>
    </div>

    <!-- Search bar -->
    <div class="flex gap-3 mb-2">
      <input
        v-model="filterText"
        placeholder="LDAP filter, e.g. (cn=john*)"
        aria-label="LDAP filter"
        class="flex-1 rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        @keyup.enter="search"
      />
      <button @click="search" class="btn-primary">Search</button>
    </div>

    <!-- Visual filter builder. Two-way bound to filterText so edits
         in either the input above or the builder below stay in sync.
         The builder owns its own collapse state. -->
    <LdapFilterBuilder
      v-model="filterText"
      :directory-id="dirId"
      class="mb-4"
    />

    <ResultsTable
      table-key="users-list"
      :columns="cols"
      :rows="users"
      row-key="dn"
      selectable
      v-model:selectedKeys="selectedDns"
      empty-text="No users found"
    >
      <template #toolbar>
        <button
          @click="doExportUsers"
          :disabled="exporting"
          class="btn-secondary text-xs"
        >{{ exporting ? 'Exporting…' : 'Export CSV' }}</button>
      </template>
      <template #cell-dn="{ value }">
        <span class="inline-flex items-center gap-1">
          <span class="text-xs truncate max-w-xs" :title="value as string">{{ value }}</span>
          <CopyButton :text="value as string" />
        </span>
      </template>
      <template #cell-enabled="{ value }">
        <span :class="value !== false ? 'badge-green' : 'badge-red'">
          {{ value !== false ? 'Active' : 'Disabled' }}
        </span>
      </template>
      <template #cell-actions="{ row }">
        <!--
          When the user is mid bulk-select (≥2 rows ticked), per-row actions
          yield to the bulk toolbar — the selection is clearly about
          multiple rows at that point, and letting an individual Delete /
          Move fire would either silently ignore the selection or act on
          just one row.
        -->
        <!-- inline-threshold=2 keeps the actions column compact:
             Edit (primary) + Disable (first item, prominent because
             it's the most-used non-edit action) + kebab for the
             rest. Default of 3 inlines Reset Password too, which
             pushes the column past the 200px width and clipped the
             kebab. -->
        <ActionMenu :disabled="bulkSelectActive"
                    :inline-threshold="2"
                    :items="[
          { label: (row as unknown as UserRow).enabled !== false ? 'Disable' : 'Enable', onClick: () => toggleEnabled(row as unknown as UserRow),
            variant: (row as unknown as UserRow).enabled !== false ? 'warning' : 'success' },
          { label: 'Reset password', onClick: () => openResetPassword(row as unknown as UserRow) },
          { label: 'Move',            onClick: () => openMove(row as unknown as UserRow) },
          { label: 'Run playbook',    onClick: () => openPlaybookRun(row as unknown as UserRow) },
          { label: 'View history',    onClick: () => { timelineTarget = row as unknown as UserRow; showTimeline = true }, variant: 'neutral' },
          { label: 'Delete',          onClick: () => confirmDelete(row as unknown as UserRow), danger: true },
        ]">
          <template #primary="{ disabled }">
            <button @click="openEdit(row as unknown as UserRow)" :disabled="disabled" class="btn-secondary btn-compact">Edit</button>
          </template>
        </ActionMenu>
      </template>
    </ResultsTable>

    <!-- Cap-hit banner. The directory has more entries matching the
         current filter than the per-fetch limit; user narrows the
         filter (or the visual builder above) to see what they're
         looking for. The in-table pager handles pagination across
         the loaded set. -->
    <div v-if="capHit" class="mt-3 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">
      Showing the first {{ limit }} matching users. The directory may have more —
      narrow the filter (or use the visual builder above) to focus the result set.
    </div>

    <!-- Profile picker modal (step 1 of create) -->
    <AppModal v-model="showTemplatePicker" title="Choose Profile" size="sm">
      <div class="space-y-2">
        <p class="text-sm text-gray-600 mb-3">Select a provisioning profile to define which attributes are available for the new user.</p>
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
          <span class="text-xs text-gray-500 ml-2">({{ (p.objectClassNames || []).join(', ') }})</span>
        </button>
      </div>
      <template #footer>
        <button @click="showTemplatePicker = false" class="btn-neutral">Cancel</button>
      </template>
    </AppModal>

    <!-- Create/Edit modal (step 2 of create, or edit) -->
    <AppModal v-model="showModal" size="lg">
      <template #title>
        <span>{{ editingDn ? 'Edit User' : 'New User' }}</span>
        <span v-if="profileConfig?.name" class="text-gray-500 font-normal"> — </span>
        <span v-if="profileConfig?.name" class="text-blue-600">{{ profileConfig.name }}</span>
        <span v-if="profileConfig?.name" class="text-gray-500 font-normal"> profile</span>
      </template>
      <UserForm :data="form" :is-edit="!!editingDn" :user-template-config="profileConfig ?? undefined" :dir-id="dirId" :profile-id="selectedProfileId" @update="(v: UserFormState) => form = v" />
      <template #footer>
        <button @click="showModal = false" class="btn-neutral">Cancel</button>
        <button @click="save" :disabled="saving" class="btn-primary">{{ saving ? 'Saving…' : 'Save' }}</button>
      </template>
    </AppModal>

    <!-- Move modal -->
    <AppModal v-model="showMove" title="Move User" size="sm">
      <FormField label="New Parent DN" v-model="newParentDn" placeholder="ou=people,dc=example,dc=com" required />
      <template #footer>
        <button @click="showMove = false" class="btn-neutral">Cancel</button>
        <button @click="doMove" :disabled="saving" class="btn-primary">Move</button>
      </template>
    </AppModal>

    <!-- Reset Password modal -->
    <AppModal v-model="showResetPassword" title="Reset Password" size="sm">
      <div class="space-y-4">
        <p class="text-sm text-gray-600">Reset password for:</p>
        <p class="text-sm font-mono text-gray-900 bg-gray-50 px-3 py-2 rounded-lg break-all">{{ resetPwTarget?.dn }}</p>
        <PasswordPolicyStatus v-if="resetPwTarget" :directory-id="dirId" :user-dn="resetPwTarget.dn" />
        <div>
          <label for="ul-pw-new" class="block text-sm font-medium text-gray-700 mb-1">New Password <span class="text-red-500">*</span></label>
          <div class="relative">
            <input id="ul-pw-new" v-model="resetPwNew" :type="resetPwVisibleNew ? 'text' : 'password'" required placeholder="Enter new password"
              class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 pr-9" />
            <button type="button" class="absolute right-2.5 top-1/2 -translate-y-1/2 text-gray-500 hover:text-gray-600"
              @mousedown.prevent="resetPwVisibleNew = true" @mouseup.prevent="resetPwVisibleNew = false"
              @mouseleave="resetPwVisibleNew = false" @touchstart.prevent="resetPwVisibleNew = true" @touchend.prevent="resetPwVisibleNew = false"
              title="Hold to show password">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path v-if="!resetPwVisibleNew" stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
                <path v-else stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M3 3l18 18" />
              </svg>
            </button>
          </div>
        </div>
        <div>
          <label for="ul-pw-confirm" class="block text-sm font-medium text-gray-700 mb-1">Confirm Password <span class="text-red-500">*</span></label>
          <div class="relative">
            <input id="ul-pw-confirm" v-model="resetPwConfirm" :type="resetPwVisibleConfirm ? 'text' : 'password'" required placeholder="Re-enter new password"
              class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 pr-9" />
            <button type="button" class="absolute right-2.5 top-1/2 -translate-y-1/2 text-gray-500 hover:text-gray-600"
              @mousedown.prevent="resetPwVisibleConfirm = true" @mouseup.prevent="resetPwVisibleConfirm = false"
              @mouseleave="resetPwVisibleConfirm = false" @touchstart.prevent="resetPwVisibleConfirm = true" @touchend.prevent="resetPwVisibleConfirm = false"
              title="Hold to show password">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path v-if="!resetPwVisibleConfirm" stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
                <path v-else stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M3 3l18 18" />
              </svg>
            </button>
          </div>
        </div>
        <div v-if="resetPwNew" class="flex items-center gap-2">
          <div class="flex-1 h-1.5 bg-gray-200 rounded-full overflow-hidden">
            <div class="h-full rounded-full transition-all" :class="pwStrengthColor" :style="{ width: pwStrengthPct + '%' }"></div>
          </div>
          <span class="text-xs font-medium" :class="pwStrengthTextColor">{{ pwStrengthLabel }}</span>
        </div>
        <p v-if="resetPwConfirm && resetPwNew !== resetPwConfirm" class="text-xs text-red-600">Passwords do not match.</p>
        <p v-if="resetPwError" class="text-sm text-red-600">{{ resetPwError }}</p>
      </div>
      <template #footer>
        <button @click="showResetPassword = false" class="btn-neutral">Cancel</button>
        <button @click="doResetPassword" :disabled="saving || !resetPwNew || resetPwNew !== resetPwConfirm" class="btn-primary">
          {{ saving ? 'Resetting…' : 'Reset Password' }}
        </button>
      </template>
    </AppModal>

    <!-- Bulk Attribute Update modal -->
    <AppModal v-model="showBulkUpdate" title="Bulk Attribute Update" size="lg">
      <div class="space-y-4">
        <p class="text-sm text-gray-600">
          Apply attribute changes to <strong>{{ selectedDns.size }}</strong> selected user(s).
        </p>
        <div v-for="(mod, i) in bulkMods" :key="i" class="flex gap-2 items-end">
          <div class="flex-1">
            <label v-if="i === 0" class="block text-xs font-medium text-gray-600 mb-1">Operation</label>
            <select v-model="mod.operation" aria-label="Operation" class="input w-full">
              <option value="REPLACE">Set (replace)</option>
              <option value="ADD">Add value</option>
              <option value="DELETE">Remove value</option>
            </select>
          </div>
          <div class="flex-1">
            <label v-if="i === 0" class="block text-xs font-medium text-gray-600 mb-1">Attribute</label>
            <input v-model="mod.attribute" placeholder="e.g. department" aria-label="Attribute" class="input w-full" />
          </div>
          <div class="flex-[2]">
            <label v-if="i === 0" class="block text-xs font-medium text-gray-600 mb-1">Value(s)</label>
            <input v-model="mod.value" :placeholder="mod.operation === 'DELETE' ? '(leave empty to remove all)' : 'e.g. Engineering'" aria-label="Value(s)" class="input w-full" />
          </div>
          <button @click="bulkMods.splice(i, 1)" aria-label="Remove modification" class="text-red-400 hover:text-red-600 text-lg leading-none pb-2"
            :class="{ 'invisible': bulkMods.length <= 1 }">&times;</button>
        </div>
        <button @click="addBulkMod" class="text-sm text-blue-600 hover:text-blue-800">+ Add another modification</button>
        <div v-if="bulkResult" class="p-3 rounded-lg text-sm" :class="bulkResult.errors ? 'bg-amber-50 border border-amber-200' : 'bg-green-50 border border-green-200'">
          <p><strong>{{ bulkResult.updated }}</strong> updated, <strong>{{ bulkResult.errors }}</strong> errors</p>
          <ul v-if="bulkResult.failures?.length" class="mt-1 space-y-0.5">
            <li v-for="f in bulkResult.failures" :key="f.dn" class="text-xs text-red-600">{{ f.dn }}: {{ f.message }}</li>
          </ul>
        </div>
      </div>
      <template #footer>
        <button @click="showBulkUpdate = false" class="btn-neutral">Close</button>
        <button @click="doBulkUpdate" :disabled="bulkUpdating || !canBulkUpdate" class="btn-primary">
          {{ bulkUpdating ? 'Updating…' : 'Apply Changes' }}
        </button>
      </template>
    </AppModal>

    <!-- Activity Timeline modal -->
    <AppModal v-model="showTimeline" title="Activity History" size="lg">
      <div v-if="timelineTarget" class="mb-3">
        <p class="text-xs font-mono text-gray-500 break-all">{{ timelineTarget.dn }}</p>
      </div>
      <EntryTimeline v-if="timelineTarget" :directory-id="dirId" :target-dn="timelineTarget.dn" />
      <template #footer>
        <button @click="showTimeline = false" class="btn-neutral">Close</button>
      </template>
    </AppModal>

    <ConfirmDialog v-model="showDelete" title="Delete User" :message="`Delete '${deleteTarget?.dn}'?`" confirm-label="Delete" danger @confirm="doDelete" />

    <!-- Playbook Run Modal -->
    <AppModal v-model="showPlaybookModal" title="Run Playbook" size="md">
      <div class="space-y-4">
        <p class="text-sm text-gray-600">Target: <code class="bg-gray-100 px-1 rounded text-xs">{{ playbookTarget?.dn }}</code></p>
        <div>
          <label for="ul-playbook" class="block text-sm font-medium text-gray-700 mb-1">Select Playbook</label>
          <select id="ul-playbook" v-model="selectedPlaybookId" @change="playbookPreview = null" class="input w-full">
            <option :value="null" disabled>Choose a playbook...</option>
            <option v-for="pb in availablePlaybooks" :key="pb.id" :value="pb.id">
              {{ pb.name }} ({{ pb.type }})
            </option>
          </select>
        </div>
        <button v-if="selectedPlaybookId" @click="doPlaybookPreview" class="btn-secondary text-sm">Preview Steps</button>
        <div v-if="playbookPreview" class="border rounded-lg divide-y">
          <div v-for="step in playbookPreview.steps" :key="step.stepOrder" class="px-3 py-2 text-sm flex items-start gap-2">
            <span class="text-gray-500 font-mono w-5 shrink-0">{{ step.stepOrder + 1 }}.</span>
            <div>
              <span class="font-medium">{{ step.description }}</span>
              <span v-if="!step.reversible" class="ml-2 text-red-500 text-xs font-medium">IRREVERSIBLE</span>
            </div>
          </div>
        </div>
      </div>
      <template #footer>
        <button @click="showPlaybookModal = false" class="btn-neutral">Cancel</button>
        <button @click="doPlaybookExecute" :disabled="playbookExecuting || !playbookPreview" class="btn-primary">
          {{ playbookExecuting ? 'Executing...' : 'Execute' }}
        </button>
      </template>
    </AppModal>

    <!-- Playbook Result Modal -->
    <AppModal v-model="showPlaybookResult" title="Playbook Result" size="md">
      <template v-if="playbookResult">
        <div class="mb-3">
          <span class="text-sm font-medium">Status: </span>
          <span :class="{ 'text-green-600': playbookResult.status === 'SUCCESS', 'text-red-600': playbookResult.status === 'FAILED', 'text-amber-600': playbookResult.status === 'PARTIAL' }" class="font-medium">
            {{ playbookResult.status }}
          </span>
        </div>
        <div class="border rounded-lg divide-y max-h-60 overflow-y-auto">
          <div v-for="step in parsedPlaybookResults(playbookResult.stepResults)" :key="step.stepOrder" class="px-3 py-2 text-sm flex items-center gap-2">
            <span class="font-mono text-gray-500 w-5">{{ step.stepOrder + 1 }}.</span>
            <span class="font-medium">{{ step.action }}</span>
            <span :class="{ 'text-green-600': step.status === 'SUCCESS', 'text-red-600': step.status === 'FAILED', 'text-gray-500': step.status === 'SKIPPED' }" class="ml-auto text-xs font-medium">{{ step.status }}</span>
          </div>
        </div>
        <div v-if="playbookResult.status === 'PARTIAL'" class="mt-4 flex justify-end">
          <button @click="doPlaybookRollback(playbookResult.id)" class="btn-secondary text-sm text-amber-600">Rollback Completed Steps</button>
        </div>
      </template>
    </AppModal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useNotificationStore } from '@/stores/notifications'
import { useApi, downloadBlob } from '@/composables/useApi'
import * as usersApi from '@/api/users'
import * as groupsApi from '@/api/groups'
import { exportCsv as exportUsersCsv } from '@/api/csvTemplates'
import { listProfiles, getProfile } from '@/api/profiles'
import { listEnabled as listEnabledPlaybooks, previewPlaybook, executePlaybook, rollbackExecution } from '@/api/playbooks'
import ResultsTable from '@/components/ResultsTable.vue'
import LdapFilterBuilder from '@/components/LdapFilterBuilder.vue'
import ActionMenu from '@/components/ActionMenu.vue'
import AppModal from '@/components/AppModal.vue'
import FormField from '@/components/FormField.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import UserForm from './UserForm.vue'
import CopyButton from '@/components/CopyButton.vue'
import EntryTimeline from '@/components/EntryTimeline.vue'
import PasswordPolicyStatus from '@/components/PasswordPolicyStatus.vue'

interface ProfileLite {
  id: string
  name: string
  targetOuDn?: string | null
  rdnAttribute?: string
  enabled?: boolean
  objectClassNames?: string[]
  attributeConfigs?: Array<{ attributeName: string, defaultValue?: string }>
  groupAssignments?: Array<{ groupDn: string, memberAttribute: string }>
}

interface UserRow {
  dn: string
  enabled?: boolean | string
  __entry: { dn: string, attributes?: Record<string, string[] | string | null> }
  _raw?: { attributes?: Record<string, string[] | string | null> }
  [key: string]: unknown
}

interface UserFormState {
  dn?: string
  parentDn?: string
  rdnAttribute?: string
  rdnValue?: string
  attributes?: Record<string, unknown>
  _pendingGroups?: Array<{ dn: string, memberAttr: string }>
}

interface BulkMod {
  operation: 'REPLACE' | 'ADD' | 'DELETE'
  attribute: string
  value: string
}

interface BulkResult {
  updated: number
  errors: number
  failures?: Array<{ dn: string, message: string }>
}

interface Playbook { id: string, name: string, type: string }
interface PlaybookStep { stepOrder: number, description?: string, reversible?: boolean, action?: string, status?: string }
interface PlaybookPreview { steps: PlaybookStep[] }
interface PlaybookResult { id: string, status: 'SUCCESS' | 'FAILED' | 'PARTIAL', stepResults: string }

// Tier-2 pagination model: server returns up to FETCH_LIMIT entries
// in a single shot (the backend hard-caps at MAX_LIMIT=2000); the
// table paginates client-side. When the cap is hit we surface a
// banner asking the user to narrow the filter. No "Load more" — it
// composed badly with the in-table pager (two layers of pagination
// disagreeing about "Page 1 of 1" vs "of 4" depending on which had
// just acted).
const FETCH_LIMIT = 1000

const route  = useRoute()
const notif  = useNotificationStore()
const { loading, call } = useApi()

const dirId          = route.params.dirId as string
const users          = ref<UserRow[]>([])
const filterText     = ref('')
const limit          = ref(FETCH_LIMIT)
const selectedDns    = ref<Set<unknown>>(new Set())

/**
 * ≥2 rows ticked = "bulk select mode": the Bulk Update button is visible
 * and per-row actions (Edit / Disable / Delete / …) are disabled so the
 * user can't accidentally act on just one row while the selection
 * suggests otherwise.
 */
const bulkSelectActive = computed(() => selectedDns.value.size >= 2)
const showTemplatePicker = ref(false)
const showModal      = ref(false)
const showMove       = ref(false)
const showDelete     = ref(false)
const editingDn      = ref<string | null>(null)
const deleteTarget   = ref<UserRow | null>(null)
const moveTarget     = ref<UserRow | null>(null)
const newParentDn    = ref('')
const showTimeline   = ref(false)
const timelineTarget = ref<UserRow | null>(null)
const saving             = ref(false)
const exporting          = ref(false)
const showResetPassword  = ref(false)
const resetPwTarget      = ref<UserRow | null>(null)
const resetPwNew         = ref('')
const resetPwConfirm     = ref('')
const resetPwError       = ref('')
const resetPwVisibleNew     = ref(false)
const resetPwVisibleConfirm = ref(false)

function computePasswordStrength(pw: string): number {
  if (!pw) return 0
  let score = 0
  if (pw.length >= 8) score++
  if (pw.length >= 12) score++
  if (/[a-z]/.test(pw) && /[A-Z]/.test(pw)) score++
  if (/\d/.test(pw)) score++
  if (/[^a-zA-Z0-9]/.test(pw)) score++
  return score
}

const pwStrength = computed(() => computePasswordStrength(resetPwNew.value))
const pwStrengthPct = computed(() => Math.min(100, pwStrength.value * 20))
const pwStrengthLabel = computed(() => {
  const s = pwStrength.value
  if (s <= 1) return 'Weak'
  if (s <= 2) return 'Fair'
  if (s <= 3) return 'Good'
  return 'Strong'
})
const pwStrengthColor = computed(() => {
  const s = pwStrength.value
  if (s <= 1) return 'bg-red-500'
  if (s <= 2) return 'bg-amber-500'
  if (s <= 3) return 'bg-blue-500'
  return 'bg-green-500'
})
const pwStrengthTextColor = computed(() => {
  const s = pwStrength.value
  if (s <= 1) return 'text-red-600'
  if (s <= 2) return 'text-amber-600'
  if (s <= 3) return 'text-blue-600'
  return 'text-green-600'
})

// Curated default-visible attributes for user lists. Anything in this
// list shows by default if the search returned it; everything else
// the search surfaces is registered as a column too but starts
// hidden — users can opt in via the column picker. Order here is
// also the column order shown in the table.
const DEFAULT_USER_COLUMNS = [
  'cn', 'sn', 'givenName', 'displayName', 'mail', 'uid', 'title',
]

// Lower-cased lookup. LDAP attributes are case-insensitive at the
// protocol level but servers may return mixed case (givenName vs
// givenname). The backend lower-cases keys, so we compare against
// the lower-case form.
const DEFAULT_USER_COLUMNS_LC = new Set(DEFAULT_USER_COLUMNS.map(s => s.toLowerCase()))

/**
 * Union of attribute keys appearing on any returned user row. The
 * page used to flatten to four hard-coded fields (cn / mail /
 * enabled / dn) and discard the rest, which left the column picker
 * with nothing to offer. Now every attribute the search surfaces
 * becomes a column, ordered: dn → curated defaults → other
 * discovered → actions.
 */
const discoveredAttrs = computed<string[]>(() => {
  const seen = new Set<string>()
  for (const row of users.value) {
    const entry = row.__entry
    if (!entry?.attributes) continue
    for (const k of Object.keys(entry.attributes)) seen.add(k)
  }
  return [...seen]
})

// ResultsTable doesn't expose a per-row `actions` slot the way the
// thinner DataTable did; the row-action menu renders via a synthetic
// `actions` column. alwaysVisible keeps it from being hidden by the
// column picker; sortable=false because there's nothing to sort on.
const cols = computed(() => {
  // Match the curated order; only emit a column if the search
  // actually returned it (so picker doesn't list attributes the user
  // can never populate).
  const discoveredSet = new Set(discoveredAttrs.value.map(s => s.toLowerCase()))
  const defaults = DEFAULT_USER_COLUMNS.filter(k => discoveredSet.has(k.toLowerCase()))
  const extras = discoveredAttrs.value.filter(k => !DEFAULT_USER_COLUMNS_LC.has(k.toLowerCase()))
  return [
    { key: 'dn', label: 'DN', alwaysVisible: true },
    ...defaults.map(k => ({ key: k, label: k })),
    ...extras.map(k => ({ key: k, label: k, defaultHidden: true })),
    // The actions cell renders three elements side-by-side: the
    // primary Edit button, the variant Disable/Enable button (the
    // first ActionMenu item), and the kebab dropdown trigger. 140px
    // fits roughly the first two but cuts off the kebab; 200px gives
    // breathing room across the longest variant labels (e.g.
    // "Disable" + "Reset password" surfaces).
    { key: 'actions', label: '', alwaysVisible: true, sortable: false, defaultWidth: 200 },
  ]
})

const allProfiles       = ref<ProfileLite[]>([])
const selectedProfileId = ref('')
const profileData       = ref<ProfileLite | null>(null)
const profileConfig     = ref<ProfileLite | null>(null)

const emptyForm = (): UserFormState => {
  // Pre-populate default values from profile attribute configs
  const attributes: Record<string, unknown> = {}
  if (profileData.value?.attributeConfigs) {
    for (const attr of profileData.value.attributeConfigs) {
      if (attr.defaultValue) {
        attributes[attr.attributeName] = attr.defaultValue
      }
    }
  }
  return {
    parentDn: profileData.value?.targetOuDn || '',
    rdnAttribute: profileData.value?.rdnAttribute || 'uid',
    rdnValue: '',
    attributes,
  }
}
const form = ref<UserFormState>(emptyForm())

function search() { limit.value = FETCH_LIMIT; load() }

async function load() {
  await call(async () => {
    const params = {
      filter: filterText.value || undefined,
      baseDn: profileData.value?.targetOuDn || undefined,
      limit:  limit.value,
    }
    const { data } = await usersApi.searchUsers(dirId, params)
    const entries = Array.isArray(data) ? data : (data.entries || [])
    users.value = entries.map((e: { dn: string, attributes?: Record<string, string[] | string | null> }) => {
      // Flatten every returned attribute to a string-or-string[] cell
      // value so the dynamic columns from `cols` can read them as
      // `row[attr]`. Multi-valued attrs join with ", " — matches what
      // the superadmin Directory Search results table does. The full
      // entry is preserved on `__entry` for callers that need it
      // (toggleEnabled / openMove / etc.).
      const row: Record<string, unknown> = { dn: e.dn, __entry: e }
      const attrs = e.attributes ?? {}
      for (const [attr, vals] of Object.entries(attrs)) {
        const arr = Array.isArray(vals) ? vals : (vals == null ? [] : [vals])
        row[attr] = arr.join(', ')
      }
      // Backwards-compat with the row.enabled lookup the
      // toggleEnabled action menu still reads. The backend doesn't
      // return an `enabled` attribute today; this stays undefined,
      // matching the prior behaviour.
      row.enabled = attrs.enabled
      return row as unknown as UserRow
    })
  })
}

// Cap-hit signal for the in-template banner. Equality is correct
// here: the backend truncates at `limit` exactly when there are at
// least that many matching entries, so users.length === limit
// reliably means "more entries existed beyond the cap." Strict
// `===` would also work; using `>=` is defensive against future
// off-by-one drift.
const capHit = computed(() => users.value.length >= limit.value)

/**
 * Streams the current filter's full result set as CSV through the
 * backend's bulk-export endpoint (rate-limited; gated by
 * BULK_EXPORT). The export honours the same filter / baseDn the
 * page is viewing, but is independent of the table's in-memory
 * pagination — it returns every matching entry, not just what's
 * loaded into the table.
 */
async function doExportUsers() {
  if (exporting.value) return
  exporting.value = true
  try {
    const params = {
      filter: filterText.value || undefined,
      baseDn: profileData.value?.targetOuDn || undefined,
    }
    const { data } = await exportUsersCsv(dirId, params)
    downloadBlob(data, 'users.csv')
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    notif.error(err.response?.data?.detail || err.message || 'Export failed')
  } finally {
    exporting.value = false
  }
}

async function openCreate() {
  editingDn.value = null
  profileConfig.value = null
  if (allProfiles.value.length === 1) {
    await selectProfileAndCreate(allProfiles.value[0])
  } else if (allProfiles.value.length > 1) {
    showTemplatePicker.value = true
  } else {
    form.value = emptyForm()
    showModal.value = true
  }
}

async function selectProfileAndCreate(p: ProfileLite) {
  showTemplatePicker.value = false
  try {
    const { data } = await getProfile(dirId, p.id)
    profileData.value = data
    profileConfig.value = data
  } catch (e) {
    const err = e as { message?: string }
    console.warn('Failed to load profile:', err.message ?? e)
    profileConfig.value = null
  }
  const f = emptyForm()
  // Auto-queue group assignments defined on the profile
  if (profileConfig.value?.groupAssignments?.length) {
    f._pendingGroups = profileConfig.value.groupAssignments.map(g => ({
      dn: g.groupDn,
      memberAttr: g.memberAttribute,
    }))
  }
  form.value = f
  showModal.value = true
}

async function openEdit(row: UserRow) {
  editingDn.value = row.dn
  // Fetch full entry from LDAP to get all attributes (search results may be incomplete)
  let attrs: Record<string, string[] | string | null> = row._raw?.attributes || {}
  try {
    const { data } = await usersApi.getUser(dirId, row.dn)
    if (data?.attributes) attrs = data.attributes
  } catch (e) {
    const err = e as { message?: string }
    console.warn('Failed to fetch full user entry, using search data:', err.message ?? e)
  }

  // Try to resolve a matching profile from the available profiles
  profileConfig.value = null
  const ocRaw = attrs.objectClass || attrs.objectclass || []
  const ocArr = Array.isArray(ocRaw) ? ocRaw : [ocRaw]
  const userOCs = ocArr.filter((s): s is string => typeof s === 'string').map(s => s.toLowerCase())
  if (userOCs.length && allProfiles.value.length) {
    const match = allProfiles.value.find(p =>
      (p.objectClassNames || []).every(oc => userOCs.includes(oc.toLowerCase()))
    )
    if (match) {
      try {
        const { data } = await getProfile(dirId, match.id)
        profileConfig.value = data
      } catch (e) {
        const err = e as { message?: string }
        console.warn('Failed to load profile for edit:', err.message ?? e)
      }
    }
  }

  // The backend lower-cases all LDAP attribute names, but the profile config
  // stores them with original casing (e.g. "givenName" vs "givenname").
  // Build a lookup to re-key the attributes to the profile-configured casing
  // so that v-model bindings in edit mode find the values.
  const caseMap: Record<string, string> = {}
  if (profileConfig.value?.attributeConfigs) {
    for (const ac of profileConfig.value.attributeConfigs) {
      caseMap[ac.attributeName.toLowerCase()] = ac.attributeName
    }
  }
  form.value = { dn: row.dn, attributes: Object.fromEntries(
    Object.entries(attrs).map(([k, v]) => [
      caseMap[k.toLowerCase()] || k,
      Array.isArray(v) ? v.join('\n') : v,
    ])
  )}
  showModal.value = true
}

async function save() {
  saving.value = true
  try {
    if (editingDn.value) {
      const mods = Object.entries(form.value.attributes || {})
        .filter(([attr]) => attr.toLowerCase() !== 'objectclass')
        .map(([attr, val]) => ({
          operation: 'REPLACE',
          attribute: attr,
          values: typeof val === 'string'
            ? val.split('\n').map(v => v.trim()).filter(v => v.length > 0)
            : [String(val)],
        }))
        .filter(m => m.values.length > 0)
      await usersApi.updateUser(dirId, editingDn.value, { modifications: mods })
      notif.success('User updated')
    } else {
      const f = form.value
      const dn = `${f.rdnAttribute}=${f.rdnValue},${f.parentDn}`
      const attributes: Record<string, string[]> = {}
      for (const [k, v] of Object.entries(f.attributes || {})) {
        if (!v && v !== false) continue
        // Multi-value / textarea fields are newline-separated
        if (typeof v === 'string' && v.includes('\n')) {
          attributes[k] = v.split('\n').map(s => s.trim()).filter(Boolean)
        } else {
          attributes[k] = [String(v)]
        }
      }
      // Include the RDN attribute in the attributes map
      if (f.rdnAttribute && f.rdnValue !== undefined) {
        attributes[f.rdnAttribute] = [f.rdnValue]
      }
      // Include objectClasses from the selected user template
      if (profileConfig.value?.objectClassNames?.length) {
        attributes.objectClass = profileConfig.value.objectClassNames
      }
      const createRes = await usersApi.createUser(dirId, { dn, attributes })
      if (createRes.status === 202) {
        // Approval workflow intercepted — user creation is pending approval
        notif.success('User creation submitted for approval')
      } else {
        // Group assignments are applied server-side by UserController.create
        // (it consults the matched profile's effective group set). The
        // previous per-group addGroupMember loop here is gone — the
        // backend handles it for manual create, bulk import, and the
        // approval-approved path uniformly.
        const expected = (f._pendingGroups || []).length
        notif.success(expected ? `User created and added to ${expected} group(s)` : 'User created')
      }
    }
    showModal.value = false
    await load()
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    notif.error(err.response?.data?.detail || err.message)
  } finally {
    saving.value = false
  }
}

async function toggleEnabled(row: UserRow) {
  const fn = row.enabled !== false ? usersApi.disableUser : usersApi.enableUser
  await call(() => fn(dirId, row.dn), { successMsg: 'Status updated' })
  await load()
}

function openMove(row: UserRow) {
  moveTarget.value  = row
  newParentDn.value = ''
  showMove.value    = true
}

async function doMove() {
  if (!moveTarget.value) return
  saving.value = true
  try {
    const res = await usersApi.moveUser(dirId, moveTarget.value.dn, { newParentDn: newParentDn.value })
    if (res.status === 202) {
      notif.success('User move submitted for approval')
    } else {
      notif.success('User moved')
    }
    showMove.value = false
    await load()
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    notif.error(err.response?.data?.detail || err.message)
  } finally { saving.value = false }
}

function openResetPassword(row: UserRow) {
  resetPwTarget.value  = row
  resetPwNew.value     = ''
  resetPwConfirm.value = ''
  resetPwError.value   = ''
  resetPwVisibleNew.value = false
  resetPwVisibleConfirm.value = false
  showResetPassword.value = true
}

async function doResetPassword() {
  if (!resetPwTarget.value) return
  resetPwError.value = ''
  saving.value = true
  try {
    await usersApi.resetPassword(dirId, resetPwTarget.value.dn, resetPwNew.value)
    notif.success('Password reset successfully')
    showResetPassword.value = false
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    resetPwError.value = err.response?.data?.detail || err.message || ''
  } finally {
    saving.value = false
  }
}

// ── Bulk Attribute Update ────────────────────────────────────────────────────

const showBulkUpdate = ref(false)
const bulkUpdating   = ref(false)
const bulkResult     = ref<BulkResult | null>(null)
const bulkMods       = ref<BulkMod[]>([{ operation: 'REPLACE', attribute: '', value: '' }])

const canBulkUpdate = computed(() =>
  bulkMods.value.some(m => m.attribute && m.attribute.trim())
)

function addBulkMod() {
  bulkMods.value.push({ operation: 'REPLACE', attribute: '', value: '' })
}

function openBulkUpdate() {
  bulkMods.value = [{ operation: 'REPLACE', attribute: '', value: '' }]
  bulkResult.value = null
  showBulkUpdate.value = true
}

async function doBulkUpdate() {
  bulkUpdating.value = true
  bulkResult.value = null
  try {
    const modifications = bulkMods.value
      .filter(m => m.attribute && m.attribute.trim())
      .map(m => ({
        operation: m.operation,
        attribute: m.attribute.trim(),
        values: m.value ? m.value.split('|').map(v => v.trim()).filter(Boolean) : [],
      }))
    const { data } = await usersApi.bulkUpdateAttributes(dirId, {
      dns: [...selectedDns.value],
      modifications,
    })
    bulkResult.value = data
    if (data.errors === 0) {
      notif.success(`${data.updated} user(s) updated successfully`)
      showBulkUpdate.value = false
      selectedDns.value = new Set()
      await load()
    } else {
      notif.warning(`${data.updated} updated, ${data.errors} failed`)
      await load()
    }
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    notif.error(err.response?.data?.detail || err.message)
  } finally {
    bulkUpdating.value = false
  }
}

function confirmDelete(row: UserRow) { deleteTarget.value = row; showDelete.value = true }

async function doDelete() {
  if (!deleteTarget.value) return
  await call(() => usersApi.deleteUser(dirId, deleteTarget.value!.dn), { successMsg: 'User deleted' })
  await load()
}

// ── Playbook run from user actions ────────────────────────────────────────

const showPlaybookModal = ref(false)
const availablePlaybooks = ref<Playbook[]>([])
const playbookTarget = ref<UserRow | null>(null)
const selectedPlaybookId = ref<string | null>(null)
const playbookPreview = ref<PlaybookPreview | null>(null)
const playbookExecuting = ref(false)
const playbookResult = ref<PlaybookResult | null>(null)
const showPlaybookResult = ref(false)

async function openPlaybookRun(row: UserRow) {
  playbookTarget.value = row
  selectedPlaybookId.value = null
  playbookPreview.value = null
  playbookResult.value = null
  try {
    const { data } = await listEnabledPlaybooks(dirId)
    availablePlaybooks.value = data
    showPlaybookModal.value = true
  } catch (e) {
    void e
    notif.error('Failed to load playbooks')
  }
}

async function doPlaybookPreview() {
  if (!selectedPlaybookId.value || !playbookTarget.value) return
  try {
    const { data } = await previewPlaybook(dirId, selectedPlaybookId.value, playbookTarget.value.dn)
    playbookPreview.value = data
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    notif.error(err.response?.data?.detail || err.message)
  }
}

async function doPlaybookExecute() {
  if (!selectedPlaybookId.value || !playbookTarget.value) return
  playbookExecuting.value = true
  try {
    const { data } = await executePlaybook(dirId, selectedPlaybookId.value, [playbookTarget.value.dn])
    playbookResult.value = data[0]
    showPlaybookModal.value = false
    showPlaybookResult.value = true
    await load()
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    notif.error(err.response?.data?.detail || err.message)
  }
  finally { playbookExecuting.value = false }
}

async function doPlaybookRollback(executionId: string) {
  try {
    await rollbackExecution(dirId, executionId)
    notif.success('Rollback completed')
    showPlaybookResult.value = false
    await load()
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    notif.error(err.response?.data?.detail || err.message)
  }
}

function parsedPlaybookResults(json: string): PlaybookStep[] {
  try { return JSON.parse(json) } catch { return [] }
}

async function loadProfiles() {
  try {
    const { data: profiles } = await listProfiles(dirId)
    // Picker is "which profile do I provision through" — disabled
    // profiles can't, so hide them here. Symmetric to the sidebar
    // Profile picker (/auth/me/profiles is filtered server-side)
    // and the admin dashboard's Profiles panel. Superadmin manages
    // disabled state from SuperadminProfilesView, which uses a
    // separate (unfiltered) list endpoint.
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
  limit.value = FETCH_LIMIT
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
