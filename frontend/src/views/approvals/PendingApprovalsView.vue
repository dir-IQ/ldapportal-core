<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div class="p-6">
    <div class="mb-6">
      <h1 class="text-2xl font-bold text-gray-900">Pending Approvals</h1>
      <p class="text-sm text-gray-500 mt-1">Review and act on pending provisioning requests</p>
    </div>

    <!-- Directory picker on its own row matching the layout used by
         SOD Policies, Access Drift, etc. — top-left, label above select. -->
    <div v-if="showPicker" class="mb-4">
      <label for="pa-directory" class="block text-sm font-medium text-gray-700 mb-1">Directory</label>
      <select id="pa-directory" v-model="selectedDir" class="input w-64">
        <option value="" disabled>— Select directory —</option>
        <option v-for="d in directories" :key="d.id" :value="d.id">{{ d.displayName || d.name }}</option>
      </select>
    </div>

    <div v-if="showPicker && !selectedDir" class="text-center text-gray-500 py-12">Select a directory to view approvals.</div>

    <DataTable v-else :columns="cols" :rows="approvals" :loading="loading || loadingDirs" row-key="id"
      empty-text="No pending approvals" empty-icon="shield">
      <template #cell-status="{ value }">
        <span :class="statusClass(value)">{{ value }}</span>
      </template>
      <template #cell-requestType="{ value }">
        <span class="badge-gray">{{ formatType(value) }}</span>
      </template>
      <template #cell-createdAt="{ value }"><RelativeTime :value="value" /></template>
      <template #cell-actions="{ row }">
        <!--
          Approve/Reject are the core workflow of this page, so keep them
          inline next to View rather than hiding them behind a kebab — the
          extra click would hurt a task that's all about quick decisions.
          The ActionMenu wrapper still unifies alignment and spacing with
          the rest of the app's row actions.
        -->
        <ActionMenu :items="[]">
          <template #primary>
            <button @click="openDetail(row)" class="btn-secondary btn-compact">View</button>
            <template v-if="row.status === 'PENDING' && canAct && !isOwnRequest(row)">
              <button @click="handleApprove(row)" class="btn-success-soft btn-compact">Approve</button>
              <button @click="openReject(row)" class="btn-danger-soft btn-compact">Reject</button>
            </template>
            <span v-if="row.status === 'PENDING' && canAct && isOwnRequest(row)"
                  class="text-xs text-gray-500 italic self-center ml-2">Own request</span>
          </template>
        </ActionMenu>
      </template>
    </DataTable>

    <!-- Detail Modal -->
    <AppModal v-model="detailModal" title="Approval Details" size="lg">
      <div v-if="selectedApproval" class="space-y-3">
        <div><strong>Request Type:</strong> {{ formatType(selectedApproval.requestType) }}</div>
        <div><strong>Requester:</strong> {{ selectedApproval.requesterUsername }}</div>
        <div><strong>Status:</strong> <span :class="statusClass(selectedApproval.status)">{{ selectedApproval.status }}</span></div>
        <div><strong>Submitted:</strong> <RelativeTime :value="selectedApproval.createdAt" /></div>
        <div v-if="selectedApproval.reviewerUsername">
          <strong>Reviewed by:</strong> {{ selectedApproval.reviewerUsername }}
        </div>
        <div v-if="selectedApproval.reviewedAt">
          <strong>Reviewed at:</strong> <RelativeTime :value="selectedApproval.reviewedAt" />
        </div>
        <div v-if="selectedApproval.rejectReason">
          <strong>Reject Reason:</strong> {{ selectedApproval.rejectReason }}
        </div>

        <!-- Friendly request summary — the "what's changing", rendered from
             the payload per request type so reviewers don't have to read the
             raw JSON. Hidden in edit mode and for unmapped types (which fall
             back to the JSON block below). -->
        <div v-if="!editMode && summaryRows.length"
             data-testid="request-summary"
             class="rounded-lg border border-gray-200 bg-gray-50 px-4 py-3 space-y-3">
          <div v-for="row in summaryRows" :key="row.label">
            <div class="text-[11px] font-medium uppercase tracking-wide text-gray-500">{{ row.label }}</div>
            <template v-if="row.dn">
              <div class="flex items-center gap-1 text-sm font-medium text-gray-900">
                <span class="truncate" :title="row.value">{{ dnLeaf(row.value) }}</span>
                <CopyButton :text="row.value" />
              </div>
              <div class="text-xs text-gray-500 font-mono break-all">{{ row.value }}</div>
            </template>
            <div v-else class="text-sm text-gray-900 break-words">{{ row.value }}</div>
          </div>
        </div>

        <!-- Provisioning Error -->
        <div v-if="selectedApproval.provisionError"
          class="bg-red-50 border border-red-200 rounded-lg px-4 py-3 text-sm text-red-700">
          <strong>Provisioning Error:</strong> {{ selectedApproval.provisionError }}
          <p class="text-xs text-red-500 mt-1">Edit the request attributes below to fix the issue, then approve again.</p>
        </div>

        <!-- Edit mode for attributes -->
        <div v-if="editMode && isEditablePayload(selectedApproval)" class="space-y-3 border rounded-lg p-3 bg-gray-50">
          <div class="text-sm font-semibold text-gray-700">Edit Request Attributes</div>
          <div>
            <label for="pa-edit-dn" class="block text-xs text-gray-500 mb-1">DN</label>
            <input id="pa-edit-dn" v-model="editPayload.dn" class="input w-full text-sm" />
          </div>
          <div v-for="(values, attrName) in editPayload.attributes" :key="attrName">
            <label :for="`pa-attr-${attrName}`" class="block text-xs text-gray-500 mb-1">{{ attrName }}</label>
            <input :id="`pa-attr-${attrName}`" v-model="editPayload.attributes[attrName][0]" class="input w-full text-sm" />
          </div>
          <div class="flex gap-2">
            <button @click="savePayload" :disabled="savingPayload"
              class="btn-primary text-sm">{{ savingPayload ? 'Saving...' : 'Save Changes' }}</button>
            <button @click="editMode = false" class="btn-neutral text-sm">Cancel</button>
          </div>
        </div>

        <!-- Raw payload — demoted to an advanced toggle now that the summary
             above covers the common case. Opens by default only when there's
             no structured summary (an unmapped request type), so that case is
             never left blank. -->
        <details v-if="!editMode" class="mt-2" :open="summaryRows.length === 0">
          <summary class="cursor-pointer text-xs text-gray-500 hover:text-gray-700">Show raw JSON (advanced)</summary>
          <pre class="mt-2 bg-gray-50 border rounded p-3 text-xs overflow-auto max-h-64">{{ formatPayload(selectedApproval.payload) }}</pre>
        </details>

        <div v-if="selectedApproval.status === 'PENDING' && canAct" class="flex gap-2 mt-4 pt-4 border-t">
          <template v-if="!isOwnRequest(selectedApproval)">
            <button v-if="!editMode && isEditablePayload(selectedApproval)"
              @click="startEdit(selectedApproval)" class="btn-secondary">Edit</button>
            <button v-if="!editMode" @click="handleApprove(selectedApproval); detailModal = false" class="btn-success-soft">Approve</button>
            <button v-if="!editMode" @click="detailModal = false; openReject(selectedApproval)" class="btn-danger-soft">Reject</button>
          </template>
          <span v-else class="text-sm text-gray-500 italic">You cannot approve or reject your own request</span>
        </div>
      </div>
    </AppModal>

    <!-- Reject Modal -->
    <AppModal v-model="rejectModal" title="Reject Request">
      <div class="space-y-4">
        <p class="text-sm text-gray-600">Please provide a reason for rejecting this request.</p>
        <textarea v-model="rejectReason" rows="3" aria-label="Rejection reason"
          class="input w-full"
          placeholder="Enter rejection reason..."></textarea>
        <div class="flex gap-2 justify-end">
          <button @click="rejectModal = false" class="btn-neutral">Cancel</button>
          <button @click="handleReject" :disabled="!rejectReason.trim()"
            class="btn-danger-soft">
            Reject
          </button>
        </div>
      </div>
    </AppModal>

    <!-- Confirm Approve -->
    <ConfirmDialog
      v-model="confirmApprove"
      title="Approve Request"
      message="Are you sure you want to approve this request? The LDAP entry will be created immediately."
      confirm-label="Approve"
      confirm-class="bg-green-600 hover:bg-green-700"
      @confirm="doApprove" />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch } from 'vue'
import type { Ref, ComputedRef } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'
import { useApi } from '@/composables/useApi'
import { useDirectoryPicker } from '@/composables/useDirectoryPicker'
import { listPendingApprovals, approveRequest, rejectRequest, updateApprovalPayload } from '@/api/approvals'
import type { components } from '@/api/openapi'
import DataTable from '@/components/DataTable.vue'
import ActionMenu from '@/components/ActionMenu.vue'
import AppModal from '@/components/AppModal.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import RelativeTime from '@/components/RelativeTime.vue'
import CopyButton from '@/components/CopyButton.vue'

type PendingApproval = components['schemas']['PendingApprovalResponse']

interface DirectoryOption {
  id: string
  displayName?: string
  name?: string
}

interface EditPayloadForm {
  dn: string
  attributes: Record<string, string[]>
}

function errMsg(e: unknown, fallback = 'Something went wrong'): string {
  const detail = (e as { response?: { data?: { detail?: string } } })?.response?.data?.detail
  return detail || (e instanceof Error ? e.message : fallback)
}

const auth = useAuthStore()
const { loading, call } = useApi()
const notif = useNotificationStore()
// useDirectoryPicker is plain JS; annotate the slice we consume so the
// template's directory option fields stay strictly typed.
const { dirId, directories, selectedDir, loadingDirs, showPicker } = useDirectoryPicker({ ldapOnly: true }) as {
  dirId: ComputedRef<string>
  directories: Ref<DirectoryOption[]>
  selectedDir: Ref<string>
  loadingDirs: Ref<boolean>
  showPicker: ComputedRef<boolean>
}

const approvals = ref<PendingApproval[]>([])
const selectedApproval = ref<PendingApproval | null>(null)
const detailModal = ref(false)
const rejectModal = ref(false)
const rejectReason = ref('')
const confirmApprove = ref(false)
const approvalToAction = ref<PendingApproval | null>(null)
const editMode = ref(false)
const editPayload = reactive<EditPayloadForm>({ dn: '', attributes: {} })
const savingPayload = ref(false)

const cols = [
  { key: 'requestType', label: 'Type' },
  { key: 'requesterUsername', label: 'Requester' },
  { key: 'status', label: 'Status' },
  { key: 'createdAt', label: 'Submitted' },
  { key: 'actions', label: '' }
]

function formatType(type: string | undefined): string {
  if (!type) return ''
  const labels: Record<string, string> = {
    USER_CREATE: 'User Create',
    BULK_IMPORT: 'Bulk Import',
    USER_MOVE: 'User Move',
    GROUP_MEMBER_ADD: 'Group Member Add',
    SELF_REGISTRATION: 'Self-Registration',
    PLAYBOOK_EXECUTE: 'Playbook Execution',
  }
  return labels[type] || type
}

function statusClass(status: string | undefined): string {
  const base = 'px-2 py-0.5 rounded-full text-xs font-medium'
  switch (status) {
    case 'PENDING': return base + ' bg-yellow-100 text-yellow-800'
    case 'APPROVED': return base + ' bg-green-100 text-green-800'
    case 'REJECTED': return base + ' bg-red-100 text-red-800'
    default: return base + ' bg-gray-100 text-gray-800'
  }
}

function formatPayload(payload: string | undefined): string {
  if (!payload) return ''
  try {
    return JSON.stringify(JSON.parse(payload), null, 2)
  } catch {
    return payload
  }
}

// ── Friendly request summary ────────────────────────────────────────────────
// Renders the request payload as a few readable rows per request type so a
// reviewer sees *what's changing* (which member, which group, …) without
// reading raw JSON. Unmapped types yield no rows and fall back to the JSON
// block, so a new ApprovalRequestType never shows blank.
interface SummaryRow {
  label: string
  value: string
  /** When true, `value` is a DN: render its readable leaf + full DN + copy. */
  dn?: boolean
}

/**
 * Readable leaf of a DN — the value of its first RDN, preferring the cn=
 * component of a multi-valued RDN (e.g. "o=0001+cn=Jim Moffett" → "Jim
 * Moffett", "cn=SecurityAdmins,ou=Groups,…" → "SecurityAdmins"). Display
 * only; the full DN is always shown alongside it.
 */
function dnLeaf(dn: string): string {
  if (!dn) return ''
  const firstRdn = dn.split(',')[0] ?? ''
  const parts = firstRdn.split('+').map(s => s.trim())
  const chosen = parts.find(p => /^cn=/i.test(p)) ?? parts[0] ?? ''
  const eq = chosen.indexOf('=')
  return eq >= 0 ? chosen.slice(eq + 1).trim() : chosen
}

// Don't surface password values in the read-only summary.
function maskAttr(name: string, value: string): string {
  return /password/i.test(name) ? '••••••••' : value
}

const summaryRows = computed<SummaryRow[]>(() => {
  const a = selectedApproval.value
  if (!a) return []
  let p: Record<string, unknown>
  try { p = JSON.parse(a.payload ?? '{}') } catch { return [] }
  const str = (v: unknown): string => (v == null ? '' : String(v))
  const rows: SummaryRow[] = []
  switch (a.requestType) {
    case 'GROUP_MEMBER_ADD':
      if (p.memberValue) rows.push({ label: 'Add member', value: str(p.memberValue), dn: true })
      if (p.groupDn) rows.push({ label: 'To group', value: str(p.groupDn), dn: true })
      if (p.memberAttribute) rows.push({ label: 'Via attribute', value: str(p.memberAttribute) })
      break
    case 'USER_CREATE':
    case 'SELF_REGISTRATION': {
      if (p.dn) rows.push({ label: 'New user', value: str(p.dn), dn: true })
      const attrs = (p.attributes ?? {}) as Record<string, unknown>
      for (const [k, v] of Object.entries(attrs)) {
        const joined = Array.isArray(v) ? v.join(', ') : str(v)
        if (joined) rows.push({ label: k, value: maskAttr(k, joined) })
      }
      break
    }
    case 'USER_MOVE':
      if (p.dn) rows.push({ label: 'Move user', value: str(p.dn), dn: true })
      if (p.destinationProfileName) rows.push({ label: 'To profile', value: str(p.destinationProfileName) })
      if (p.newParentDn) rows.push({ label: 'New location', value: str(p.newParentDn), dn: true })
      break
    case 'PLAYBOOK_EXECUTE':
      if (p.playbookName) rows.push({ label: 'Playbook', value: str(p.playbookName) })
      if (p.targetDn) rows.push({ label: 'Target', value: str(p.targetDn), dn: true })
      break
    case 'BULK_IMPORT':
      if (p.count != null) rows.push({ label: 'Entries', value: str(p.count) })
      if (p.operation) rows.push({ label: 'Operation', value: str(p.operation) })
      break
  }
  return rows
})

function isOwnRequest(approval: PendingApproval): boolean {
  const myId = (auth.principal as { id?: string } | null)?.id
  return !!myId && myId === approval.requestedBy
}

function openDetail(approval: PendingApproval): void {
  selectedApproval.value = approval
  editMode.value = false
  detailModal.value = true
}

function isEditablePayload(approval: PendingApproval): boolean {
  return ['USER_CREATE', 'SELF_REGISTRATION'].includes(approval.requestType ?? '')
}

function startEdit(approval: PendingApproval): void {
  try {
    const parsed = JSON.parse(approval.payload ?? '{}')
    editPayload.dn = parsed.dn || ''
    // Deep copy attributes so edits don't mutate the original
    const attrs: Record<string, string[]> = {}
    for (const [key, val] of Object.entries(parsed.attributes || {})) {
      attrs[key] = Array.isArray(val) ? [...val] : [val as string]
    }
    editPayload.attributes = attrs
    editMode.value = true
  } catch {
    editPayload.dn = ''
    editPayload.attributes = {}
  }
}

async function savePayload(): Promise<void> {
  if (!selectedApproval.value) return
  savingPayload.value = true
  try {
    // Remove attributes with no value
    const cleanAttrs: Record<string, string[]> = {}
    for (const [key, vals] of Object.entries(editPayload.attributes)) {
      const filtered = vals.filter(v => v != null && v !== '')
      if (filtered.length > 0) cleanAttrs[key] = filtered
    }
    const newPayload = JSON.stringify({
      dn: editPayload.dn,
      attributes: cleanAttrs
    })
    const { data } = await updateApprovalPayload(dirId.value, selectedApproval.value.id, newPayload)
    // Update the local approval data
    selectedApproval.value = data
    const idx = approvals.value.findIndex(a => a.id === data.id)
    if (idx >= 0) approvals.value[idx] = data
    editMode.value = false
  } catch (e) {
    notif.error(errMsg(e, 'Failed to save changes'))
  } finally {
    savingPayload.value = false
  }
}

function openReject(approval: PendingApproval): void {
  approvalToAction.value = approval
  rejectReason.value = ''
  rejectModal.value = true
}

function handleApprove(approval: PendingApproval): void {
  approvalToAction.value = approval
  confirmApprove.value = true
}

async function doApprove(): Promise<void> {
  confirmApprove.value = false
  if (!approvalToAction.value) return
  const res = await call(() => approveRequest(dirId.value, approvalToAction.value!.id))
  if (res?.data?.provisionError) {
    // Provisioning failed — reload list and open detail to show the error
    await loadApprovals()
    const updated = approvals.value.find(a => a.id === res.data.id)
    if (updated) openDetail(updated)
    notif.error('Provisioning failed: ' + res.data.provisionError)
  } else {
    notif.success('Request approved')
    await loadApprovals()
  }
}

async function handleReject(): Promise<void> {
  rejectModal.value = false
  if (!approvalToAction.value) return
  await call(() => rejectRequest(dirId.value, approvalToAction.value!.id, rejectReason.value), { successMsg: 'Request rejected' })
  await loadApprovals()
}

// Anyone who can reach this feature-gated view may act on requests —
// superadmins included. The backend independently enforces approver
// scoping and the no-self-approval rule, so this is purely a UX gate.
const canAct = computed(() => auth.isLoggedIn)

async function loadApprovals(): Promise<void> {
  if (!dirId.value) { approvals.value = []; return }
  const res = await call(() => listPendingApprovals(dirId.value))
  approvals.value = res.data
}

watch(dirId, (v) => { if (v) loadApprovals() })
onMounted(() => { if (dirId.value) loadApprovals() })
</script>

<style scoped>
@reference "tailwindcss";
</style>
