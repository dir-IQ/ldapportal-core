<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useNotificationStore } from '@/stores/notifications'
import { useDirectoryPicker } from '@/composables/useDirectoryPicker'
import { usePermissions } from '@/composables/usePermissions'
import {
  listPlaybooks, createPlaybook, updatePlaybook, deletePlaybook,
  previewPlaybook, executePlaybook, rollbackExecution, listExecutions
} from '@/api/playbooks'
import ActionMenu from '@/components/ActionMenu.vue'
import AppModal from '@/components/AppModal.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import DataTable from '@/components/DataTable.vue'
import DnPicker from '@/components/DnPicker.vue'

const { dirId, directories, selectedDir, loadingDirs, showPicker } = useDirectoryPicker({ ldapOnly: true })
const notif = useNotificationStore()
const { hasFeature } = usePermissions()
const canManage = computed(() => hasFeature('playbook.manage'))
const canExecute = computed(() => hasFeature('playbook.execute'))

const playbooks = ref([])
const loading = ref(false)
const saving = ref(false)

// Modal state
const showModal = ref(false)
const editing = ref(null)
const modalTab = ref('general')

// Delete confirm
const showDeleteConfirm = ref(false)
const deleteTarget = ref(null)

// Preview/execute
const showPreviewModal = ref(false)
const previewData = ref(null)
const executeTarget = ref({ playbookId: null, dn: '' })
const executing = ref(false)

// Execution result
const showResultModal = ref(false)
const executionResult = ref(null)

// History
const showHistoryModal = ref(false)
const historyData = ref([])
const historyPlaybookName = ref('')

const playbookTypes = [
  { value: 'ONBOARD', label: 'Onboard' },
  { value: 'OFFBOARD', label: 'Offboard' },
  { value: 'CUSTOM', label: 'Custom' },
]

const stepActions = [
  { value: 'ADD_TO_GROUP', label: 'Add to Group', params: ['groupDn', 'memberAttribute'] },
  { value: 'REMOVE_FROM_GROUP', label: 'Remove from Group', params: ['groupDn', 'memberAttribute'] },
  { value: 'REMOVE_ALL_GROUPS', label: 'Remove from All Groups', params: [] },
  { value: 'SET_ATTRIBUTE', label: 'Set Attribute', params: ['attributeName', 'values'] },
  { value: 'REMOVE_ATTRIBUTE', label: 'Remove Attribute', params: ['attributeName'] },
  { value: 'MOVE_OU', label: 'Move to OU', params: ['targetDn'] },
  { value: 'DISABLE', label: 'Disable Account', params: [] },
  { value: 'ENABLE', label: 'Enable Account', params: [] },
  { value: 'DELETE', label: 'Delete Account', params: [] },
  { value: 'NOTIFY', label: 'Send Notification', params: ['recipients', 'subject', 'body'] },
]

const cols = [
  { key: 'name', label: 'Name' },
  { key: 'type', label: 'Type' },
  { key: 'stepCount', label: 'Steps' },
  { key: 'enabled', label: 'Status' },
  { key: 'actions', label: '', align: 'right' },
]

function emptyPlaybook() {
  return {
    name: '', description: '', type: 'CUSTOM',
    profileId: null, requireApproval: false, enabled: true,
    steps: []
  }
}

const playbook = ref(emptyPlaybook())

watch(dirId, (v) => { if (v) reload() })
onMounted(() => { if (dirId.value) reload() })

async function reload() {
  loading.value = true
  try {
    const { data } = await listPlaybooks(dirId.value)
    playbooks.value = data.map(p => ({ ...p, stepCount: p.steps?.length || 0 }))
  } catch (e) {
    notif.error(e.response?.data?.detail || e.message)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  playbook.value = emptyPlaybook()
  modalTab.value = 'general'
  showModal.value = true
}

function openEdit(p) {
  editing.value = p.id
  playbook.value = {
    name: p.name, description: p.description || '',
    type: p.type, profileId: p.profileId,
    requireApproval: p.requireApproval, enabled: p.enabled,
    steps: (p.steps || []).map(s => ({
      action: s.action,
      parameters: s.parameters ? JSON.parse(s.parameters) : {},
      continueOnError: s.continueOnError
    }))
  }
  modalTab.value = 'general'
  showModal.value = true
}

async function save() {
  if (!playbook.value.name) { notif.error('Name is required'); return }
  saving.value = true
  try {
    const payload = {
      ...playbook.value,
      steps: playbook.value.steps.map(s => ({
        action: s.action,
        parameters: JSON.stringify(s.parameters || {}),
        continueOnError: s.continueOnError
      }))
    }
    if (editing.value) {
      await updatePlaybook(dirId.value, editing.value, payload)
      notif.success('Playbook updated')
    } else {
      await createPlaybook(dirId.value, payload)
      notif.success('Playbook created')
    }
    showModal.value = false
    await reload()
  } catch (e) {
    notif.error(e.response?.data?.detail || e.response?.data?.message || e.message)
  } finally {
    saving.value = false
  }
}

function confirmDelete(p) { deleteTarget.value = p; showDeleteConfirm.value = true }

async function doDelete() {
  try {
    await deletePlaybook(dirId.value, deleteTarget.value.id)
    notif.success('Playbook deleted')
    showDeleteConfirm.value = false
    await reload()
  } catch (e) { notif.error(e.response?.data?.detail || e.message) }
}

// Steps management
function addStep() {
  playbook.value.steps.push({ action: 'ADD_TO_GROUP', parameters: {}, continueOnError: false })
}

function removeStep(i) { playbook.value.steps.splice(i, 1) }

function moveStep(i, dir) {
  const arr = playbook.value.steps
  const j = i + dir
  if (j < 0 || j >= arr.length) return
  ;[arr[i], arr[j]] = [arr[j], arr[i]]
}

function actionDef(action) {
  return stepActions.find(a => a.value === action) || stepActions[0]
}

// Preview and execute
function openRunDialog(p) {
  executeTarget.value = { playbookId: p.id, dn: '' }
  previewData.value = null
  showPreviewModal.value = true
}

async function doPreview() {
  if (!executeTarget.value.dn) { notif.error('Enter a user DN'); return }
  try {
    const { data } = await previewPlaybook(dirId.value, executeTarget.value.playbookId, executeTarget.value.dn)
    previewData.value = data
  } catch (e) { notif.error(e.response?.data?.detail || e.message) }
}

async function doExecute() {
  executing.value = true
  try {
    const { data } = await executePlaybook(dirId.value, executeTarget.value.playbookId, [executeTarget.value.dn])
    executionResult.value = data[0]
    showPreviewModal.value = false
    showResultModal.value = true
  } catch (e) { notif.error(e.response?.data?.detail || e.message) }
  finally { executing.value = false }
}

async function doRollback(executionId) {
  try {
    await rollbackExecution(dirId.value, executionId)
    notif.success('Rollback completed')
    showResultModal.value = false
  } catch (e) { notif.error(e.response?.data?.detail || e.message) }
}

async function openHistory(p) {
  historyPlaybookName.value = p.name
  try {
    const { data } = await listExecutions(dirId.value, p.id)
    historyData.value = data
    showHistoryModal.value = true
  } catch (e) { notif.error(e.response?.data?.detail || e.message) }
}

function parsedStepResults(json) {
  try { return JSON.parse(json) } catch { return [] }
}
</script>

<template>
  <div class="p-6">
    <div class="flex items-center justify-between mb-6">
      <div>
        <h1 class="text-2xl font-bold text-gray-900">Lifecycle Playbooks</h1>
        <p class="text-sm text-gray-500 mt-1">Automate lifecycle operations with reusable playbooks</p>
      </div>
      <button class="btn-primary" @click="openCreate" v-if="dirId && canManage">New Playbook</button>
    </div>

    <!-- Directory picker -->
    <div v-if="showPicker" class="mb-4">
      <label for="pb-directory" class="block text-sm font-medium text-gray-700 mb-1">Directory</label>
      <select id="pb-directory" v-model="selectedDir" class="input w-64">
        <option value="" disabled>{{ loadingDirs ? 'Loading…' : '— Select directory —' }}</option>
        <option v-for="d in directories" :key="d.id" :value="d.id">{{ d.displayName }}</option>
      </select>
    </div>

    <DataTable :columns="cols" :rows="playbooks" :loading="loading" empty-icon="clipboard">
      <template #cell-type="{ value }">
        <span class="badge" :class="{ 'badge-blue': value === 'ONBOARD', 'badge-red': value === 'OFFBOARD', 'badge-gray': value === 'CUSTOM' }">{{ value }}</span>
      </template>
      <template #cell-enabled="{ value }">
        <span :class="value ? 'badge-green' : 'badge-red'">{{ value ? 'Enabled' : 'Disabled' }}</span>
      </template>
      <template #cell-actions="{ row }">
        <ActionMenu :items="[
          { label: 'View history', onClick: () => openHistory(row) },
          { label: 'Edit',         onClick: () => openEdit(row),       hidden: !canManage },
          { label: 'Delete',       onClick: () => confirmDelete(row),  hidden: !canManage, danger: true },
        ]">
          <template #primary>
            <button v-if="canExecute" @click="openRunDialog(row)" class="btn-success-soft btn-compact">Run</button>
            <button v-else-if="canManage" @click="openEdit(row)" class="btn-secondary btn-compact">Edit</button>
          </template>
        </ActionMenu>
      </template>
    </DataTable>

    <!-- Create/Edit Modal -->
    <AppModal v-model="showModal" :title="editing ? 'Edit Playbook' : 'New Playbook'" size="lg">
      <div class="flex border-b gap-1 mb-4">
        <button v-for="tab in [{ id: 'general', label: 'General' }, { id: 'steps', label: 'Steps' }]" :key="tab.id"
          :class="['px-4 py-2 text-sm font-medium border-b-2 -mb-px', modalTab === tab.id ? 'border-blue-600 text-blue-600' : 'border-transparent text-gray-500 hover:text-gray-700']"
          @click="modalTab = tab.id">{{ tab.label }}</button>
      </div>

      <!-- General Tab -->
      <div v-if="modalTab === 'general'" class="space-y-4">
        <div>
          <label for="pb-name" class="block text-sm font-medium text-gray-700 mb-1">Name</label>
          <input id="pb-name" v-model="playbook.name" class="input w-full" />
        </div>
        <div>
          <label for="pb-description" class="block text-sm font-medium text-gray-700 mb-1">Description</label>
          <textarea id="pb-description" v-model="playbook.description" rows="2" class="input w-full" />
        </div>
        <div class="grid grid-cols-2 gap-4">
          <div>
            <label for="pb-type" class="block text-sm font-medium text-gray-700 mb-1">Type</label>
            <select id="pb-type" v-model="playbook.type" class="input w-full">
              <option v-for="t in playbookTypes" :key="t.value" :value="t.value">{{ t.label }}</option>
            </select>
          </div>
        </div>
        <div class="space-y-2">
          <label class="flex items-center gap-2 text-sm">
            <input type="checkbox" v-model="playbook.requireApproval" /> Require approval before execution
          </label>
          <label class="flex items-center gap-2 text-sm">
            <input type="checkbox" v-model="playbook.enabled" /> Enabled
          </label>
        </div>
      </div>

      <!-- Steps Tab -->
      <div v-if="modalTab === 'steps'" class="space-y-3">
        <div v-for="(step, i) in playbook.steps" :key="i" class="border rounded-lg p-3 space-y-2 bg-gray-50">
          <div class="flex items-center gap-2">
            <span class="text-xs font-bold text-gray-500 w-6">{{ i + 1 }}</span>
            <select v-model="step.action" :aria-label="`Step ${i + 1} action`" class="input flex-1 text-sm">
              <option v-for="a in stepActions" :key="a.value" :value="a.value">{{ a.label }}</option>
            </select>
            <button @click="moveStep(i, -1)" :disabled="i === 0" class="text-gray-500 hover:text-gray-600 text-xs">Up</button>
            <button @click="moveStep(i, 1)" :disabled="i === playbook.steps.length - 1" class="text-gray-500 hover:text-gray-600 text-xs">Down</button>
            <button @click="removeStep(i)" class="text-red-500 hover:text-red-700 text-xs">Remove</button>
          </div>

          <!-- Dynamic params based on action -->
          <div v-if="actionDef(step.action).params.includes('groupDn')" class="pl-8">
            <label class="block text-xs text-gray-500">Group DN</label>
            <DnPicker v-model="step.parameters.groupDn" :directory-id="dirId" scope="group" />
            <div class="mt-1">
              <label :for="`pb-step-${i}-member-attr`" class="block text-xs text-gray-500">Member Attribute</label>
              <select :id="`pb-step-${i}-member-attr`" v-model="step.parameters.memberAttribute" class="input w-40 text-sm">
                <option value="member">member</option>
                <option value="uniqueMember">uniqueMember</option>
                <option value="memberUid">memberUid</option>
              </select>
            </div>
          </div>
          <div v-if="actionDef(step.action).params.includes('attributeName')" class="pl-8">
            <label :for="`pb-step-${i}-attr-name`" class="block text-xs text-gray-500">Attribute Name</label>
            <input :id="`pb-step-${i}-attr-name`" v-model="step.parameters.attributeName" class="input w-full text-sm" placeholder="e.g. description" />
            <div v-if="actionDef(step.action).params.includes('values')" class="mt-1">
              <label :for="`pb-step-${i}-value`" class="block text-xs text-gray-500">Value</label>
              <input :id="`pb-step-${i}-value`" v-model="step.parameters.values" class="input w-full text-sm" placeholder="Attribute value" />
            </div>
          </div>
          <div v-if="actionDef(step.action).params.includes('targetDn')" class="pl-8">
            <label class="block text-xs text-gray-500">Target OU DN</label>
            <DnPicker v-model="step.parameters.targetDn" :directory-id="dirId" />
          </div>
          <div v-if="actionDef(step.action).params.includes('recipients')" class="pl-8 space-y-1">
            <div>
              <label :for="`pb-step-${i}-recipients`" class="block text-xs text-gray-500">Recipients <span class="text-gray-500">(comma-separated, use ${user.mail} for the user)</span></label>
              <input :id="`pb-step-${i}-recipients`" v-model="step.parameters.recipients" class="input w-full text-sm" placeholder="admin@example.com, ${user.mail}" />
            </div>
            <div>
              <label :for="`pb-step-${i}-subject`" class="block text-xs text-gray-500">Subject</label>
              <input :id="`pb-step-${i}-subject`" v-model="step.parameters.subject" class="input w-full text-sm" />
            </div>
            <div>
              <label :for="`pb-step-${i}-body`" class="block text-xs text-gray-500">Body</label>
              <textarea :id="`pb-step-${i}-body`" v-model="step.parameters.body" rows="2" class="input w-full text-sm" />
            </div>
          </div>

          <div class="pl-8">
            <label class="flex items-center gap-1 text-xs text-gray-500">
              <input type="checkbox" v-model="step.continueOnError" /> Continue on error
            </label>
          </div>

          <div v-if="step.action === 'DELETE'" class="pl-8 text-xs text-red-600 font-medium">
            This action is irreversible and cannot be rolled back.
          </div>
        </div>

        <button class="btn-secondary text-sm" @click="addStep">Add Step</button>
      </div>

      <template #footer>
        <button @click="showModal = false" class="btn-neutral">Cancel</button>
        <button @click="save" :disabled="saving" class="btn-primary">
          {{ saving ? 'Saving...' : (editing ? 'Update' : 'Create') }}
        </button>
      </template>
    </AppModal>

    <!-- Run / Preview Modal -->
    <AppModal v-model="showPreviewModal" title="Run Playbook" size="md">
      <div class="space-y-4">
        <div>
          <label for="pb-target-dn" class="block text-sm font-medium text-gray-700 mb-1">Target User DN</label>
          <input id="pb-target-dn" v-model="executeTarget.dn" class="input w-full" placeholder="uid=jdoe,ou=people,dc=example,dc=com" />
        </div>

        <button @click="doPreview" class="btn-secondary text-sm">Preview Steps</button>

        <div v-if="previewData" class="border rounded-lg divide-y">
          <div v-for="step in previewData.steps" :key="step.stepOrder"
            class="px-3 py-2 text-sm flex items-start gap-2">
            <span class="text-gray-500 font-mono w-5 shrink-0">{{ step.stepOrder + 1 }}.</span>
            <div>
              <span class="font-medium">{{ step.description }}</span>
              <span v-if="!step.reversible" class="ml-2 text-red-500 text-xs font-medium">IRREVERSIBLE</span>
              <span v-if="step.continueOnError" class="ml-2 text-gray-500 text-xs">(continue on error)</span>
            </div>
          </div>
        </div>
      </div>

      <template #footer>
        <button @click="showPreviewModal = false" class="btn-neutral">Cancel</button>
        <button @click="doExecute" :disabled="executing || !previewData" class="btn-primary">
          {{ executing ? 'Executing...' : 'Execute' }}
        </button>
      </template>
    </AppModal>

    <!-- Execution Result Modal -->
    <AppModal v-model="showResultModal" title="Execution Result" size="md">
      <template v-if="executionResult">
        <div class="mb-3">
          <span class="text-sm font-medium">Status: </span>
          <span :class="{ 'badge-green': executionResult.status === 'SUCCESS', 'badge-red': executionResult.status === 'FAILED', 'badge-yellow': executionResult.status === 'PARTIAL' }">
            {{ executionResult.status }}
          </span>
        </div>
        <div class="border rounded-lg divide-y max-h-60 overflow-y-auto">
          <div v-for="step in parsedStepResults(executionResult.stepResults)" :key="step.stepOrder"
            class="px-3 py-2 text-sm flex items-center gap-2">
            <span class="font-mono text-gray-500 w-5">{{ step.stepOrder + 1 }}.</span>
            <span class="font-medium">{{ step.action }}</span>
            <span :class="{ 'text-green-600': step.status === 'SUCCESS', 'text-red-600': step.status === 'FAILED', 'text-gray-500': step.status === 'SKIPPED' }" class="ml-auto text-xs font-medium">
              {{ step.status }}
            </span>
            <span v-if="step.error" class="text-red-500 text-xs block w-full pl-7">{{ step.error }}</span>
          </div>
        </div>
        <div v-if="executionResult.status === 'PARTIAL'" class="mt-4 flex justify-end">
          <button @click="doRollback(executionResult.id)" class="btn-secondary text-sm text-amber-600 border-amber-300 hover:bg-amber-50">
            Rollback Completed Steps
          </button>
        </div>
      </template>
    </AppModal>

    <!-- History Modal -->
    <AppModal v-model="showHistoryModal" :title="`Execution History — ${historyPlaybookName}`" size="lg">
      <div v-if="historyData.length === 0" class="text-gray-500 text-sm text-center py-8">No executions yet.</div>
      <div v-else class="divide-y max-h-96 overflow-y-auto">
        <div v-for="exec in historyData" :key="exec.id" class="py-3 flex items-center gap-4 text-sm">
          <span :class="{ 'badge-green': exec.status === 'SUCCESS', 'badge-red': exec.status === 'FAILED', 'badge-yellow': exec.status === 'PARTIAL', 'badge-gray': exec.status === 'ROLLED_BACK' }">
            {{ exec.status }}
          </span>
          <span class="font-mono text-gray-600 truncate flex-1">{{ exec.targetDn }}</span>
          <span class="text-gray-500 text-xs whitespace-nowrap">{{ new Date(exec.startedAt).toLocaleString() }}</span>
          <button v-if="exec.status === 'PARTIAL'" @click="doRollback(exec.id)"
            class="text-amber-600 hover:text-amber-800 text-xs font-medium">Rollback</button>
        </div>
      </div>
    </AppModal>

    <ConfirmDialog v-model="showDeleteConfirm"
      :message="`Delete playbook '${deleteTarget?.name}'? This cannot be undone.`"
      confirmLabel="Delete" :danger="true" @confirm="doDelete" />
  </div>
</template>

<style scoped>
@reference "tailwindcss";
.badge { @apply inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium; }
</style>
