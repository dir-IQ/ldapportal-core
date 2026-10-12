<!-- SPDX-License-Identifier: Apache-2.0 -->
<!--
  Bulk group membership from a CSV: each row names a user (uid or DN) and the
  groups (cn or DN, separated by ; or |) to add them to, with an optional op
  column for removals. Two-step like the other bulk tabs: Preview resolves and
  classifies every user-group pair without writing, then Apply writes the
  actionable ones. Adds may be queued for approval, exactly like adding a
  member by hand. The backend needs bulk.import + group.manage_members.
-->
<template>
  <section class="bg-white border border-gray-200 border-t-0 rounded-b-xl p-6">
    <h2 class="text-lg font-semibold mb-1">Group Memberships from CSV</h2>
    <p class="text-sm text-gray-500 mb-4">
      Add users to (or remove them from) any number of groups. Preview first — nothing changes until you apply.
    </p>

    <div class="mb-4 p-3 rounded-lg bg-gray-50 border border-gray-200 text-sm text-gray-700">
      <p class="mb-1">
        Columns: <code>user</code> (uid or DN), <code>groups</code> (cn or DN; separate several with
        <code>;</code>), and optional <code>op</code> (<code>ADD</code> by default, or <code>REMOVE</code>).
        Quote any DN, since DNs contain commas.
      </p>
      <pre class="text-xs font-mono bg-white border border-gray-200 rounded p-2 overflow-x-auto">{{ TEMPLATE_CSV }}</pre>
      <button type="button" class="mt-2 text-xs font-medium text-blue-600 hover:underline" @click="downloadTemplate">
        Download template CSV
      </button>
    </div>

    <div class="space-y-3">
      <div class="max-w-md">
        <label class="block text-sm font-medium text-gray-700 mb-1">CSV File <span class="text-red-500">*</span></label>
        <label class="csv-file-picker input flex items-center gap-2 w-full cursor-pointer !py-0 !pr-1 hover:border-gray-400 transition-colors bg-white">
          <span class="flex-1 truncate text-sm" :class="file ? 'text-gray-900 font-medium' : 'text-gray-500'">
            {{ file?.name || 'No file chosen' }}
          </span>
          <span class="px-3 py-1 rounded-md bg-blue-50 text-blue-700 text-xs font-medium hover:bg-blue-100 whitespace-nowrap">
            Choose File
          </span>
          <input type="file" accept=".csv,text/csv" @change="onFileChange" aria-label="CSV File" class="sr-only" />
        </label>
      </div>

      <div>
        <button @click="doPreview" :disabled="!file || previewing" class="btn-primary">
          {{ previewing ? 'Previewing…' : 'Preview' }}
        </button>
      </div>

      <!-- Preview -->
      <div v-if="preview" class="mt-2 p-4 rounded-lg bg-gray-50 border border-gray-200 text-sm" data-testid="membership-preview">
        <div class="flex flex-wrap items-center gap-2 mb-3">
          <span class="font-medium text-gray-800">{{ preview.totalPairs }} user-group pairs</span>
          <span v-if="countOf('WILL_ADD')" class="badge-green">{{ countOf('WILL_ADD') }} to add</span>
          <span v-if="countOf('WILL_REMOVE')" class="badge-blue">{{ countOf('WILL_REMOVE') }} to remove</span>
          <span v-if="noOpCount" class="badge-gray">{{ noOpCount }} no change</span>
          <span v-if="problemCount" class="badge-amber">{{ problemCount }} need attention</span>
          <button type="button" class="ml-auto text-xs font-medium text-blue-600 hover:underline" @click="downloadPreview">
            Download preview CSV
          </button>
        </div>

        <ExpandablePreview title="Membership preview" body-class="rounded border border-gray-200 bg-white">
          <table class="w-full text-xs">
            <thead class="bg-gray-100 sticky top-0">
              <tr>
                <th class="px-2 py-1 text-left font-medium text-gray-600">Row</th>
                <th class="px-2 py-1 text-left font-medium text-gray-600">User</th>
                <th class="px-2 py-1 text-left font-medium text-gray-600">Group</th>
                <th class="px-2 py-1 text-left font-medium text-gray-600">Op</th>
                <th class="px-2 py-1 text-left font-medium text-gray-600">Outcome</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-gray-100">
              <tr v-for="(row, idx) in preview.rows" :key="idx" :class="idx % 2 ? 'bg-gray-50' : 'bg-white'">
                <td class="px-2 py-1 text-gray-500">{{ row.rowNumber }}</td>
                <td class="px-2 py-1 text-gray-800" :title="row.userDn || ''">{{ row.user || '—' }}</td>
                <td class="px-2 py-1 text-gray-800" :title="row.groupDn || ''">{{ row.group || '—' }}</td>
                <td class="px-2 py-1 text-gray-600">{{ row.op || '—' }}</td>
                <td class="px-2 py-1">
                  <span :class="dispositionBadge(row.disposition)">{{ DISPOSITION_LABELS[row.disposition] }}</span>
                  <span v-if="row.note" class="text-gray-500 ml-1">{{ row.note }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </ExpandablePreview>

        <div v-if="preview.actionable > 0" class="mt-3 flex flex-wrap items-center gap-2">
          <button @click="doApply" :disabled="applying" class="btn-primary">
            {{ applying ? 'Applying…' : `Apply ${preview.actionable} change${preview.actionable === 1 ? '' : 's'}` }}
          </button>
          <button @click="resetResults" class="btn-neutral">Cancel</button>
          <span class="text-xs text-gray-500">Adds to groups that require approval are queued rather than applied.</span>
        </div>
        <p v-else class="mt-3 text-sm text-gray-500">Nothing to change — no pair needs an add or a remove.</p>
      </div>

      <!-- Result -->
      <div v-if="result" class="mt-2 p-4 rounded-lg bg-gray-50 border border-gray-200 text-sm" data-testid="membership-result">
        <div class="grid grid-cols-4 gap-2 mb-3">
          <div class="text-center"><p class="text-2xl font-bold text-green-600">{{ result.applied }}</p><p class="text-xs text-gray-500">Applied</p></div>
          <div class="text-center"><p class="text-2xl font-bold text-blue-600">{{ result.queued }}</p><p class="text-xs text-gray-500">Queued for approval</p></div>
          <div class="text-center"><p class="text-2xl font-bold text-gray-600">{{ result.skipped }}</p><p class="text-xs text-gray-500">Skipped</p></div>
          <div class="text-center"><p class="text-2xl font-bold text-red-600">{{ result.failed }}</p><p class="text-xs text-gray-500">Failed</p></div>
        </div>
        <ul v-if="failedRows.length" class="space-y-1 mb-2">
          <li v-for="(r, i) in failedRows" :key="i" class="text-red-600 text-xs">
            Row {{ r.rowNumber }} ({{ r.user || '—' }} → {{ r.group || '—' }}): {{ r.message || STATUS_LABELS[r.status] }}
          </li>
        </ul>
        <button type="button" class="text-xs font-medium text-blue-600 hover:underline" @click="downloadResult">
          Download results CSV
        </button>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useNotificationStore } from '@/stores/notifications'
import { downloadBlob } from '@/composables/useApi'
import ExpandablePreview from '@/components/ExpandablePreview.vue'
import {
  previewBulkMemberships, applyBulkMemberships,
  type MembershipDisposition, type MembershipPreviewResult, type MembershipApplyResult,
  type MembershipRowStatus,
} from '@/api/bulkMemberships'

const props = defineProps<{ dirId: string }>()
const notif = useNotificationStore()

const TEMPLATE_CSV = [
  'user,groups,op',
  'jsmith,Developers;VPN Users,ADD',
  'akumar,Finance,ADD',
  '"uid=bwong,ou=people,dc=example,dc=com",Contractors,REMOVE',
].join('\n')

const DISPOSITION_LABELS: Record<MembershipDisposition, string> = {
  WILL_ADD: 'Will add',
  WILL_REMOVE: 'Will remove',
  ALREADY_MEMBER: 'Already a member',
  NOT_A_MEMBER: 'Not a member',
  DUPLICATE: 'Duplicate',
  USER_NOT_FOUND: 'User not found',
  GROUP_NOT_FOUND: 'Group not found',
  AMBIGUOUS: 'Ambiguous',
  OUT_OF_SCOPE: 'Out of scope',
  INVALID: 'Invalid',
}

const STATUS_LABELS: Record<MembershipRowStatus, string> = {
  APPLIED: 'Applied',
  QUEUED_FOR_APPROVAL: 'Queued for approval',
  SKIPPED: 'Skipped',
  REFUSED: 'Refused',
  BLOCKED: 'Blocked by a separation-of-duties policy',
  ERROR: 'Error',
}

const NO_OP: MembershipDisposition[] = ['ALREADY_MEMBER', 'NOT_A_MEMBER', 'DUPLICATE']

const file       = ref<File | null>(null)
const previewing = ref(false)
const applying   = ref(false)
const preview    = ref<MembershipPreviewResult | null>(null)
const result     = ref<MembershipApplyResult | null>(null)

function errMsg(e: unknown): string {
  const err = e as { response?: { data?: { detail?: string } }, message?: string }
  return err.response?.data?.detail || err.message || 'Request failed'
}

function countOf(d: MembershipDisposition): number {
  return (preview.value?.rows ?? []).filter(r => r.disposition === d).length
}

const noOpCount = computed(() =>
  (preview.value?.rows ?? []).filter(r => NO_OP.includes(r.disposition)).length)

const problemCount = computed(() =>
  (preview.value?.rows ?? []).filter(r =>
    r.disposition !== 'WILL_ADD' && r.disposition !== 'WILL_REMOVE' && !NO_OP.includes(r.disposition)).length)

const failedRows = computed(() =>
  (result.value?.rows ?? []).filter(r => r.status === 'REFUSED' || r.status === 'BLOCKED' || r.status === 'ERROR'))

function dispositionBadge(d: MembershipDisposition): string {
  if (d === 'WILL_ADD') return 'badge-green'
  if (d === 'WILL_REMOVE') return 'badge-blue'
  if (NO_OP.includes(d)) return 'badge-gray'
  if (d === 'OUT_OF_SCOPE') return 'badge-red'
  return 'badge-amber'
}

function onFileChange(e: Event) {
  file.value = (e.target as HTMLInputElement).files?.[0] ?? null
  resetResults()
}

function resetResults() {
  preview.value = null
  result.value = null
}

async function doPreview() {
  if (!file.value) return
  previewing.value = true
  result.value = null
  try {
    const { data } = await previewBulkMemberships(props.dirId, file.value)
    preview.value = data
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    previewing.value = false
  }
}

async function doApply() {
  if (!file.value || !preview.value?.actionable) return
  applying.value = true
  try {
    const { data } = await applyBulkMemberships(props.dirId, file.value)
    result.value = data
    preview.value = null
    const msg = `Memberships: ${data.applied} applied, ${data.queued} queued, ${data.failed} failed`
    if (data.failed) notif.error(msg)
    else notif.success(msg)
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    applying.value = false
  }
}

// ── CSV downloads (client-side) ────────────────────────────────────────────

function csvCell(v: unknown): string {
  const s = v == null ? '' : String(v)
  return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
}

function toCsv(header: string[], rows: unknown[][]): Blob {
  const lines = [header, ...rows].map(r => r.map(csvCell).join(','))
  return new Blob([lines.join('\n') + '\n'], { type: 'text/csv;charset=utf-8' })
}

function downloadTemplate() {
  downloadBlob(new Blob([TEMPLATE_CSV + '\n'], { type: 'text/csv;charset=utf-8' }), 'group-memberships-template.csv')
}

function downloadPreview() {
  if (!preview.value) return
  downloadBlob(toCsv(
    ['row', 'user', 'userDn', 'group', 'groupDn', 'op', 'outcome', 'note'],
    preview.value.rows.map(r => [r.rowNumber, r.user, r.userDn, r.group, r.groupDn, r.op,
      DISPOSITION_LABELS[r.disposition], r.note])),
  'group-memberships-preview.csv')
}

function downloadResult() {
  if (!result.value) return
  downloadBlob(toCsv(
    ['row', 'user', 'userDn', 'group', 'groupDn', 'op', 'status', 'message', 'approvalId'],
    result.value.rows.map(r => [r.rowNumber, r.user, r.userDn, r.group, r.groupDn, r.op,
      STATUS_LABELS[r.status], r.message, r.approvalId])),
  'group-memberships-results.csv')
}
</script>

<style scoped>
@reference "tailwindcss";

/* Match the file picker height to neighbouring .input controls, including
   the compact density override (see BulkView.vue for the rationale). */
.csv-file-picker {
  height: 38px;
}
[data-density="compact"] .csv-file-picker {
  height: 30px;
}
</style>
