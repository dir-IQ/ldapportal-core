<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div class="p-6">
    <div class="flex items-center justify-between mb-6">
      <div>
        <h1 class="text-2xl font-bold text-gray-900">Operational Reports</h1>
        <p class="text-sm text-gray-500 mt-1">Run and schedule directory reports</p>
      </div>
      <button @click="openSchedules" class="bg-blue-50 border border-blue-200 text-blue-600 rounded-full px-4 py-1.5 text-sm font-medium hover:bg-blue-100 transition-colors flex items-center gap-1.5">
        <svg class="w-4 h-4" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="10" cy="10" r="7"/><path d="M10 6v4l2.5 2.5"/></svg>
        Scheduled Jobs
      </button>
    </div>

    <!-- Directory picker (superadmin only) -->
    <div v-if="!routeDirId" class="mb-4">
      <label for="rj-directory" class="block text-sm font-medium text-gray-700 mb-1">Directory</label>
      <select id="rj-directory" v-model="selectedDir" class="input w-64">
        <option value="" disabled>{{ loadingDirs ? 'Loading…' : '— Select directory —' }}</option>
        <option v-for="d in directories" :key="d.id" :value="d.id">{{ d.displayName }}</option>
      </select>
    </div>

    <!-- Report runner -->
    <section class="bg-white border border-gray-200 rounded-xl p-5 mb-6">
      <div class="grid grid-cols-4 gap-3 mb-3">
        <div>
          <label for="rj-report-type" class="block text-sm font-medium text-gray-700 mb-1">Report Type</label>
          <select id="rj-report-type" v-model="runForm.reportType" class="input w-full">
            <option v-for="t in reportTypes" :key="t.value" :value="t.value">{{ t.label }}</option>
          </select>
        </div>
        <div v-if="needsParam">
          <label for="rj-param-value" class="block text-sm font-medium text-gray-700 mb-1">{{ paramLabel }}</label>
          <DnPicker v-if="currentRunType?.param === 'groupDn'" v-model="runForm.paramValue" :directory-id="dirId" scope="group" />
          <DnPicker v-else-if="currentRunType?.param === 'branchDn'" v-model="runForm.paramValue" :directory-id="dirId" />
          <input v-else id="rj-param-value" v-model="runForm.paramValue" type="text" :placeholder="paramPlaceholder" class="input w-full" />
        </div>
        <div v-if="needsLookback">
          <label for="rj-lookback-days" class="block text-sm font-medium text-gray-700 mb-1">Lookback Days</label>
          <input id="rj-lookback-days" v-model.number="runForm.lookbackDays" type="number" min="1" class="input w-full" placeholder="30" />
        </div>
        <div v-if="needsObjectTypeFilter">
          <label for="rj-object-type" class="block text-sm font-medium text-gray-700 mb-1">Object Type</label>
          <select id="rj-object-type" v-model="runForm.objectType" class="input w-full">
            <option value="">All</option>
            <option value="USER">Users</option>
            <option value="GROUP">Groups</option>
          </select>
        </div>
        <!-- Integrity check options -->
        <div v-if="isIntegrityCheck">
          <label class="block text-sm font-medium text-gray-700 mb-1">Checks to Run</label>
          <div class="flex flex-row gap-4 mt-1">
            <label v-for="c in integrityChecks" :key="c.value" class="flex items-center gap-2 text-sm text-gray-700">
              <input type="checkbox" v-model="runForm.integrityChecks" :value="c.value" class="rounded border-gray-300" />
              {{ c.label }}
            </label>
          </div>
        </div>
      </div>
      <button @click="doRun" :disabled="running || !dirId" class="btn-primary">
        {{ running ? 'Running…' : 'Run Report' }}
      </button>
    </section>

    <!-- Results -->
    <section v-if="hasResults">
      <ResultsTable
        :table-key="`report-jobs:${runForm.reportType}`"
        :columns="tableColumns"
        :rows="resultRows"
        empty-text="No entries found for this report."
      >
        <template #toolbar>
          <button @click="doExport('CSV')" :disabled="exporting" class="btn-secondary text-xs">Export CSV</button>
          <!-- PDF rendering ships with the commercial governance
               module; hide the button on community where the
               GOVERNANCE entitlement isn't granted, so operators
               don't see an export they can't actually run. -->
          <button v-if="auth.isComplianceEnabled" @click="doExport('PDF')"
                  :disabled="exporting || isIntegrityCheck"
                  class="btn-secondary text-xs">Export PDF</button>
        </template>

        <!--
          Single fallback slot dispatches per column key. ReportJobsView has
          fewer formatting cases than AuditReportsView, but uses the same
          pattern for consistency.
        -->
        <template #cell="{ col, value }">
          <!-- Integrity check type badge -->
          <span v-if="col.key === 'type' && isIntegrityCheck" :class="integrityTypeBadgeClass(value as string)" class="inline-block px-2 py-0.5 rounded-full text-xs font-medium">
            {{ friendlyIntegrityType(value as string) }}
          </span>
          <!-- Source badge (Recently Deleted) -->
          <span v-else-if="col.key === 'Source'" :class="sourceBadgeClass(value as string)" class="inline-block px-2 py-0.5 rounded-full text-xs font-medium">
            {{ value }}
          </span>
          <!-- Date columns -->
          <span v-else-if="isDateColumn(col.key, value)" :title="formatFullDate(value as string)" class="font-mono whitespace-nowrap">
            {{ formatRelativeDate(value as string) }}
          </span>
          <!-- DN columns (truncate) -->
          <span v-else-if="isDnColumn(col.key, value)" :title="value as string" class="font-mono truncate block max-w-xs">
            {{ truncateDn(value as string) }}
          </span>
          <!-- Default -->
          <span v-else class="font-mono truncate block max-w-xs">{{ value }}</span>
        </template>
      </ResultsTable>
    </section>

    <!-- Scheduled Jobs Modal -->
    <AppModal v-model="showSchedules" title="Scheduled Jobs" size="xl">
      <div class="space-y-4">
        <!-- Jobs list -->
        <div v-if="loadingJobs" class="text-gray-500 text-sm text-center py-4">Loading…</div>
        <div v-else-if="jobs.length === 0" class="text-gray-500 text-sm text-center py-4">No scheduled jobs.</div>
        <table v-else class="w-full text-sm">
          <thead class="bg-gray-50 border-b border-gray-100">
            <tr>
              <th class="px-3 py-2 text-left font-medium text-gray-500">Name</th>
              <th class="px-3 py-2 text-left font-medium text-gray-500">Type</th>
              <th class="px-3 py-2 text-left font-medium text-gray-500">Schedule</th>
              <th class="px-3 py-2 text-left font-medium text-gray-500">Format</th>
              <th class="px-3 py-2 text-left font-medium text-gray-500">Delivery</th>
              <th class="px-3 py-2 text-left font-medium text-gray-500">Last Run</th>
              <th class="px-3 py-2 text-left font-medium text-gray-500">On</th>
              <th class="px-3 py-2"></th>
            </tr>
          </thead>
          <tbody class="divide-y divide-gray-50">
            <tr v-for="job in jobs" :key="job.id" class="hover:bg-gray-50">
              <td class="px-3 py-2 font-medium text-gray-900">{{ job.name }}</td>
              <td class="px-3 py-2 text-gray-600">{{ labelFor(job.reportType) }}</td>
              <td class="px-3 py-2 text-gray-600">{{ job.cronExpression }}</td>
              <td class="px-3 py-2 text-gray-600">{{ job.outputFormat || 'CSV' }}</td>
              <td class="px-3 py-2 text-gray-600">{{ job.deliveryMethod }}</td>
              <td class="px-3 py-2 text-gray-500 text-xs">
                <span v-if="job.lastRunAt">{{ fmtDate(job.lastRunAt) }}</span>
                <span v-else class="text-gray-300">—</span>
                <span v-if="job.lastRunStatus" :class="job.lastRunStatus === 'SUCCESS' ? 'text-green-600' : 'text-red-500'" class="ml-1 text-xs">({{ job.lastRunStatus }})</span>
              </td>
              <td class="px-3 py-2">
                <button @click="toggleEnabled(job)" :class="job.enabled ? 'bg-green-500' : 'bg-gray-300'" class="relative inline-flex h-4 w-7 rounded-full transition-colors">
                  <span :class="job.enabled ? 'translate-x-3' : 'translate-x-0'" class="inline-block h-4 w-4 transform rounded-full bg-white shadow transition-transform scale-75"></span>
                </button>
              </td>
              <td class="px-3 py-2 text-right whitespace-nowrap">
                <div class="flex items-center justify-end gap-2">
                  <button @click="openEditJob(job)" class="btn-secondary btn-compact">Edit</button>
                  <button @click="confirmDelete(job)" class="btn-danger-soft btn-compact">Delete</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>

        <!-- Add/Edit form -->
        <details :open="showJobForm" class="border border-gray-200 rounded-lg">
          <summary @click.prevent="showJobForm = !showJobForm" class="px-4 py-2 text-sm font-medium text-gray-700 cursor-pointer">
            {{ editJob ? 'Edit Job' : '+ Add Scheduled Job' }}
          </summary>
          <form v-if="showJobForm" @submit.prevent="saveJob" class="px-4 pb-4 pt-2 space-y-3">
            <div class="grid grid-cols-2 gap-3">
              <FormField label="Name" v-model="jobForm.name" required />
              <div>
                <label for="rj-job-report-type" class="block text-sm font-medium text-gray-700 mb-1">Report Type</label>
                <select id="rj-job-report-type" v-model="jobForm.reportType" class="input w-full" required>
                  <option v-for="t in schedulableTypes" :key="t.value" :value="t.value">{{ t.label }}</option>
                </select>
              </div>
            </div>
            <div class="grid grid-cols-3 gap-3">
              <FormField label="Cron Expression" v-model="jobForm.cronExpression" placeholder="0 8 * * 1" required />
              <div>
                <label for="rj-job-output-format" class="block text-sm font-medium text-gray-700 mb-1">Output Format</label>
                <select id="rj-job-output-format" v-model="jobForm.outputFormat" class="input w-full">
                  <option value="CSV">CSV</option>
                  <option value="PDF">PDF</option>
                </select>
              </div>
              <div>
                <label for="rj-job-delivery" class="block text-sm font-medium text-gray-700 mb-1">Delivery</label>
                <select id="rj-job-delivery" v-model="jobForm.deliveryMethod" class="input w-full">
                  <option value="EMAIL">Email</option>
                  <option value="S3">S3</option>
                </select>
              </div>
            </div>
            <div v-if="jobForm.deliveryMethod === 'EMAIL'" class="grid grid-cols-2 gap-3">
              <FormField label="Recipient Email" v-model="jobForm.recipientEmail" placeholder="user@example.com" />
              <FormField label="Email Subject" v-model="jobForm.emailSubject" placeholder="Scheduled report" />
            </div>
            <div v-if="jobForm.deliveryMethod === 'S3'">
              <FormField label="S3 Key Prefix" v-model="jobForm.s3KeyPrefix" placeholder="reports/" />
            </div>
            <div v-if="jobFormNeedsParam">
              <label class="block text-sm font-medium text-gray-700 mb-1">{{ jobFormParamLabel }}</label>
              <DnPicker v-if="currentJobFormType?.param === 'groupDn'" v-model="jobForm.paramValue" :directory-id="dirId" scope="group" />
              <DnPicker v-else-if="currentJobFormType?.param === 'branchDn'" v-model="jobForm.paramValue" :directory-id="dirId" />
              <FormField v-else :label="jobFormParamLabel" v-model="jobForm.paramValue" />
            </div>
            <div v-if="jobFormNeedsLookback">
              <label for="rj-job-lookback-days" class="block text-sm font-medium text-gray-700 mb-1">Lookback Days</label>
              <input id="rj-job-lookback-days" v-model.number="jobForm.lookbackDays" type="number" min="1" placeholder="30"
                class="block w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500" />
            </div>
            <div class="flex items-center gap-2">
              <input type="checkbox" id="jobEnabled" v-model="jobForm.enabled" class="rounded" />
              <label for="jobEnabled" class="text-sm text-gray-700">Enabled</label>
            </div>
            <div class="flex gap-2 justify-end">
              <button type="button" @click="cancelJobForm" class="btn-neutral text-sm">Cancel</button>
              <button type="submit" :disabled="savingJob" class="btn-primary text-sm">{{ savingJob ? 'Saving…' : 'Save' }}</button>
            </div>
          </form>
        </details>
      </div>
    </AppModal>

    <!-- Delete confirm -->
    <ConfirmDialog v-if="deleteTarget" :message="`Delete job '${deleteTarget.name}'?`" @confirm="doDelete" @cancel="deleteTarget = null" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useNotificationStore } from '@/stores/notifications'
import { useAuthStore } from '@/stores/auth'
import { useProfilePickerStore } from '@/stores/profilePicker'
import {
  listReportJobs, createReportJob, updateReportJob,
  deleteReportJob, setReportJobEnabled,
  runOperationalReport, runOperationalReportData, runOperationalReportPdf,
} from '@/api/reports'
import { listDirectories } from '@/api/directories'
import { checkIntegrity } from '@/api/browse'
import { downloadBlob } from '@/composables/useApi'
import {
  friendlyColumnName, formatRelativeDate, formatFullDate,
  truncateDn, looksLikeTimestamp, looksLikeDn,
  integrityTypeBadgeClass, friendlyIntegrityType, sourceBadgeClass,
  DATE_COLUMNS, HIDDEN_COLUMNS,
} from '@/composables/useReportFormatting'
import FormField from '@/components/FormField.vue'
import AppModal from '@/components/AppModal.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import DnPicker from '@/components/DnPicker.vue'
import ResultsTable, { type ColumnDef } from '@/components/ResultsTable.vue'

interface ReportTypeDef {
  value: string
  label: string
  param: string | null
  paramLabel?: string
  paramPlaceholder?: string
  lookback: boolean
}

interface DirectoryOption {
  id: string
  displayName: string
  directoryType?: string
}

interface ReportRow {
  [col: string]: unknown
}

interface Job {
  id: string
  name: string
  reportType: string
  cronExpression: string
  outputFormat?: string
  deliveryMethod?: string
  recipientEmail?: string
  emailSubject?: string
  s3KeyPrefix?: string
  reportParams?: Record<string, unknown>
  enabled: boolean
  lastRunAt?: string
  lastRunStatus?: string
}

const route = useRoute()
const notif = useNotificationStore()
const auth = useAuthStore()
const profilePicker = useProfilePickerStore()
const routeDirId = route.params.dirId as string | undefined

// Directory picker for superadmin (when no dirId from route)
const directories = ref<DirectoryOption[]>([])
const loadingDirs = ref(false)
const selectedDir = ref('')

const dirId = computed(() => routeDirId || selectedDir.value)

const reportTypes: ReportTypeDef[] = [
  { value: 'USERS_IN_GROUP',       label: 'Users in Group',         param: 'groupDn',  paramLabel: 'Group DN',  paramPlaceholder: 'cn=admins,dc=example,dc=com', lookback: false },
  { value: 'USERS_IN_BRANCH',      label: 'Users in Branch',        param: 'branchDn', paramLabel: 'Branch DN', paramPlaceholder: 'ou=people,dc=example,dc=com', lookback: false },
  { value: 'USERS_WITH_NO_GROUP',  label: 'Users with No Group',    param: null, lookback: false },
  { value: 'RECENTLY_ADDED',       label: 'Recently Added',         param: null, lookback: true },
  { value: 'RECENTLY_MODIFIED',    label: 'Recently Modified',      param: null, lookback: true },
  { value: 'RECENTLY_DELETED',     label: 'Recently Deleted',       param: null, lookback: true },
  { value: 'DISABLED_ACCOUNTS',    label: 'Disabled Accounts',      param: null, lookback: false },
  { value: 'MISSING_PROFILE_GROUPS', label: 'Missing Profile Groups', param: null, lookback: false },
  { value: 'INTEGRITY_CHECK',      label: 'Integrity Check',        param: null, lookback: false },
]

// Scheduled jobs can't use INTEGRITY_CHECK (it's not a backend report type)
const schedulableTypes = computed(() => reportTypes.filter(t => t.value !== 'INTEGRITY_CHECK'))

const integrityChecks = [
  { value: 'BROKEN_MEMBER',  label: 'Broken Member References' },
  { value: 'ORPHANED_ENTRY', label: 'Orphaned Entries' },
  { value: 'EMPTY_GROUP',    label: 'Empty Groups' },
]

function labelFor(type: string): string { return reportTypes.find(t => t.value === type)?.label ?? type }
function fmtDate(iso: string): string { return new Date(iso).toLocaleString() }

// ── Report runner ─────────────────────────────────────────────────────────────

const runForm = ref({
  reportType: 'RECENTLY_ADDED', paramValue: '', lookbackDays: 30, objectType: '',
  integrityChecks: ['BROKEN_MEMBER', 'ORPHANED_ENTRY', 'EMPTY_GROUP'] as string[],
})
const running = ref(false)
const exporting = ref(false)
const hasResults = ref(false)
const resultColumns = ref<string[]>([])
const resultRows = ref<ReportRow[]>([])

const currentRunType   = computed(() => reportTypes.find(t => t.value === runForm.value.reportType))
const needsParam       = computed(() => !!currentRunType.value?.param)
const paramLabel       = computed(() => currentRunType.value?.paramLabel ?? '')
const paramPlaceholder = computed(() => currentRunType.value?.paramPlaceholder ?? '')
const needsLookback    = computed(() => !!currentRunType.value?.lookback)
const RECENTLY_TYPES = new Set(['RECENTLY_ADDED', 'RECENTLY_MODIFIED', 'RECENTLY_DELETED'])
const needsObjectTypeFilter = computed(() => RECENTLY_TYPES.has(runForm.value.reportType))
const isIntegrityCheck = computed(() => runForm.value.reportType === 'INTEGRITY_CHECK')

// Show max 10 columns, hide internal columns like 'id'. Use the friendly
// column name for display (e.g. "cn" → "Name") so callers see consistent
// labels regardless of report shape.
const tableColumns = computed<ColumnDef[]>(() =>
  resultColumns.value
    .filter(c => !HIDDEN_COLUMNS.has(c))
    .slice(0, 10)
    .map(c => ({ key: c, label: friendlyColumnName(c) })),
)

// Smart column type detection
function isDateColumn(col: string, val: unknown): boolean {
  return DATE_COLUMNS.has(col) || (col.toLowerCase().includes('timestamp') && looksLikeTimestamp(val as string))
}
function isDnColumn(col: string, val: unknown): boolean {
  if (!val) return false
  const cl = col.toLowerCase()
  return (cl === 'dn' || cl.endsWith('dn') || cl === 'user' || cl === 'target' || cl === 'missing group') && looksLikeDn(val as string)
}

function buildReportParams(): Record<string, unknown> {
  const params: Record<string, unknown> = { lookbackDays: runForm.value.lookbackDays || 30 }
  if (currentRunType.value?.param) params[currentRunType.value.param] = runForm.value.paramValue
  if (runForm.value.objectType) params.objectType = runForm.value.objectType
  // Admin-view scoping: when an admin runs a report, scope unbounded
  // LDAP queries (recently-added, disabled-accounts, …) to the picked
  // profile's target OU so they only see entries inside the OUs they
  // can act on. Superadmins don't pass this — they run against the
  // whole directory by default. Report types that already take an
  // explicit DN parameter (USERS_IN_BRANCH, USERS_IN_GROUP) override
  // this in the type-specific branches above.
  if (!auth.isSuperadmin && profilePicker.selectedProfile?.targetOuDn) {
    params.scopeBaseDn = profilePicker.selectedProfile.targetOuDn
  }
  return params
}

async function doRun(): Promise<void> {
  if (!dirId.value) { notif.error('Please select a directory.'); return }
  running.value = true
  hasResults.value = false
  try {
    if (isIntegrityCheck.value) {
      await runIntegrityCheck()
    } else {
      const { data } = await runOperationalReportData(dirId.value, {
        reportType: runForm.value.reportType,
        reportParams: buildReportParams(),
      })
      resultColumns.value = data.columns || []
      resultRows.value = data.rows || []
    }
    hasResults.value = true
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    notif.error(err.response?.data?.detail || err.message || 'Report failed')
  } finally {
    running.value = false
  }
}

async function runIntegrityCheck(): Promise<void> {
  const checks = runForm.value.integrityChecks
  if (!checks.length) { notif.error('Select at least one check.'); return }
  const { data } = await checkIntegrity(dirId.value, '', checks)
  const issues = data.issues || []
  resultColumns.value = ['type', 'dn', 'description']
  resultRows.value = issues.map((i: { type: string, dn: string, description: string }) => ({
    type: i.type, dn: i.dn, description: i.description,
  }))
}

async function doExport(format: 'CSV' | 'PDF'): Promise<void> {
  if (!dirId.value) return
  if (isIntegrityCheck.value) {
    // Export integrity results as CSV from current data
    const header = resultColumns.value.join(',')
    const rows = resultRows.value.map(r => resultColumns.value.map(c => {
      const v = r[c]
      const s = v == null ? '' : String(v)
      return `"${s.replace(/"/g, '""')}"`
    }).join(','))
    const csv = [header, ...rows].join('\n')
    const blob = new Blob([csv], { type: 'text/csv' })
    downloadBlob(blob, 'integrity_check.csv')
    return
  }
  exporting.value = true
  try {
    if (format === 'PDF') {
      // PDF rendering ships with the commercial governance module;
      // route the PDF request to the ee endpoint, which is gated on
      // GOVERNANCE. UI already hides this button on community.
      const { data } = await runOperationalReportPdf(dirId.value, {
        reportType: runForm.value.reportType,
        reportParams: buildReportParams(),
      })
      downloadBlob(data, `${runForm.value.reportType.toLowerCase()}.pdf`)
      return
    }
    const { data } = await runOperationalReport(dirId.value, {
      reportType: runForm.value.reportType,
      reportParams: buildReportParams(),
    }, format)
    downloadBlob(data, `${runForm.value.reportType.toLowerCase()}.csv`)
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    notif.error(err.response?.data?.detail || err.message || 'Export failed')
  } finally {
    exporting.value = false
  }
}

// ── Scheduled jobs ────────────────────────────────────────────────────────────

const showSchedules = ref(false)
const loadingJobs = ref(false)
const savingJob = ref(false)
const jobs = ref<Job[]>([])
const editJob = ref<Job | null>(null)
const deleteTarget = ref<Job | null>(null)
const showJobForm = ref(false)

interface JobForm {
  name: string
  reportType: string
  cronExpression: string
  outputFormat: string
  deliveryMethod: string
  recipientEmail: string
  emailSubject: string
  s3KeyPrefix: string
  paramValue: string
  lookbackDays: number
  enabled: boolean
}

function blankJobForm(): JobForm {
  return {
    name: '', reportType: 'RECENTLY_ADDED', cronExpression: '0 8 * * 1',
    outputFormat: 'CSV', deliveryMethod: 'EMAIL', recipientEmail: '',
    emailSubject: '', s3KeyPrefix: '', paramValue: '', lookbackDays: 30, enabled: true,
  }
}

const jobForm = ref<JobForm>(blankJobForm())

const currentJobFormType   = computed(() => reportTypes.find(t => t.value === jobForm.value.reportType))
const jobFormNeedsParam    = computed(() => !!currentJobFormType.value?.param)
const jobFormParamLabel    = computed(() => currentJobFormType.value?.paramLabel ?? '')
const jobFormNeedsLookback = computed(() => !!currentJobFormType.value?.lookback)

async function openSchedules(): Promise<void> {
  if (!dirId.value) { notif.error('Please select a directory first.'); return }
  showSchedules.value = true
  loadingJobs.value = true
  try {
    const { data } = await listReportJobs(dirId.value, { size: 50 })
    jobs.value = (data.content ?? data) as Job[]
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    notif.error(err.response?.data?.detail || err.message || 'Failed to load jobs')
  } finally {
    loadingJobs.value = false
  }
}

function openEditJob(job: Job): void {
  editJob.value = job
  const typeInfo = reportTypes.find(t => t.value === job.reportType)
  jobForm.value = {
    name: job.name, reportType: job.reportType,
    cronExpression: job.cronExpression,
    outputFormat: job.outputFormat || 'CSV',
    deliveryMethod: job.deliveryMethod ?? 'EMAIL',
    recipientEmail: job.recipientEmail ?? '',
    emailSubject: job.emailSubject ?? '',
    s3KeyPrefix: job.s3KeyPrefix ?? '',
    paramValue: typeInfo?.param ? String(job.reportParams?.[typeInfo.param] ?? '') : '',
    lookbackDays: (job.reportParams?.lookbackDays as number) ?? 30,
    enabled: job.enabled,
  }
  showJobForm.value = true
}

function cancelJobForm(): void {
  showJobForm.value = false
  editJob.value = null
  jobForm.value = blankJobForm()
}

function buildJobPayload(): Record<string, unknown> {
  const params: Record<string, unknown> = { lookbackDays: jobForm.value.lookbackDays || 30 }
  if (currentJobFormType.value?.param) params[currentJobFormType.value.param] = jobForm.value.paramValue
  return {
    name: jobForm.value.name,
    reportType: jobForm.value.reportType,
    reportParams: params,
    cronExpression: jobForm.value.cronExpression,
    outputFormat: jobForm.value.outputFormat,
    deliveryMethod: jobForm.value.deliveryMethod,
    recipientEmail: jobForm.value.deliveryMethod === 'EMAIL' ? jobForm.value.recipientEmail : null,
    emailSubject: jobForm.value.deliveryMethod === 'EMAIL' ? jobForm.value.emailSubject : null,
    s3KeyPrefix: jobForm.value.deliveryMethod === 'S3' ? jobForm.value.s3KeyPrefix : null,
    enabled: jobForm.value.enabled,
  }
}

async function saveJob(): Promise<void> {
  savingJob.value = true
  try {
    const payload = buildJobPayload()
    if (editJob.value) {
      await updateReportJob(dirId.value, editJob.value.id, payload)
      notif.success('Job updated')
    } else {
      await createReportJob(dirId.value, payload)
      notif.success('Job created')
    }
    cancelJobForm()
    const { data } = await listReportJobs(dirId.value, { size: 50 })
    jobs.value = (data.content ?? data) as Job[]
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    notif.error(err.response?.data?.detail || err.message || 'Save failed')
  } finally {
    savingJob.value = false
  }
}

async function toggleEnabled(job: Job): Promise<void> {
  try {
    const { data } = await setReportJobEnabled(dirId.value, job.id, !job.enabled)
    job.enabled = (data as { enabled: boolean }).enabled
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    notif.error(err.response?.data?.detail || err.message || 'Toggle failed')
  }
}

function confirmDelete(job: Job): void { deleteTarget.value = job }

async function doDelete(): Promise<void> {
  if (!deleteTarget.value) return
  try {
    await deleteReportJob(dirId.value, deleteTarget.value.id)
    notif.success('Job deleted')
    deleteTarget.value = null
    const { data } = await listReportJobs(dirId.value, { size: 50 })
    jobs.value = (data.content ?? data) as Job[]
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    notif.error(err.response?.data?.detail || err.message || 'Delete failed')
    deleteTarget.value = null
  }
}

// ── Init ──────────────────────────────────────────────────────────────────────

onMounted(async () => {
  if (!routeDirId) {
    // Superadmin mode — load directory list
    loadingDirs.value = true
    try {
      const { data } = await listDirectories()
      directories.value = (data as DirectoryOption[]).filter(d => d.directoryType !== 'ENTRA_ID')
      if (directories.value.length === 1) selectedDir.value = directories.value[0].id
    } catch (e) {
      const err = e as { response?: { data?: { detail?: string } }, message?: string }
      notif.error(err.response?.data?.detail || err.message || 'Failed to load directories')
    } finally {
      loadingDirs.value = false
    }
  }
})
</script>

<style scoped>
@reference "tailwindcss";
</style>
