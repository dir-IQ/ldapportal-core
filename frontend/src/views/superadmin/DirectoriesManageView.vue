<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <PageContainer>
    <div class="flex items-center justify-between mb-6">
      <div>
        <h1 class="text-2xl font-bold text-gray-900">Directory Connections</h1>
        <p class="text-sm text-gray-500 mt-1">Manage LDAP directory connections</p>
      </div>
      <button @click="openCreate" class="btn-primary">+ New Directory</button>
    </div>

    <div class="bg-white border border-gray-200 rounded-xl overflow-hidden">
      <div v-if="loading" class="p-8 text-center text-gray-500 text-sm">Loading…</div>
      <EmptyState v-else-if="dirs.length === 0" icon="folder" title="No directories configured." />
      <table v-else class="w-full text-sm">
        <thead class="bg-gray-50 border-b border-gray-100">
          <tr>
            <th class="px-4 py-3 text-left font-medium text-gray-500">Name</th>
            <th class="px-4 py-3 text-left font-medium text-gray-500">Host</th>
            <th class="px-4 py-3 text-left font-medium text-gray-500">Port</th>
            <th class="px-4 py-3 text-left font-medium text-gray-500">SSL</th>
            <th class="px-4 py-3 text-left font-medium text-gray-500">Base DN</th>
            <th class="px-4 py-3 text-left font-medium text-gray-500">Enabled</th>
            <th class="px-4 py-3 text-left font-medium text-gray-500">Status</th>
            <th class="px-4 py-3"></th>
          </tr>
        </thead>
        <tbody class="divide-y divide-gray-50">
          <tr v-for="d in dirs" :key="d.id" class="hover:bg-gray-50">
            <td class="px-4 py-3 font-medium text-gray-900">
              <div>{{ d.displayName }}</div>
              <!-- Vendor / version badge from the root-DSE probe.
                   Hidden when the probe hasn't run or returned no
                   vendor field (e.g. OpenDJ omits vendorName). Tooltip
                   lists the supported control OIDs so an operator can
                   spot-check what the server actually advertises. -->
              <div
                v-if="vendorBadge(d)"
                class="mt-0.5 inline-flex items-center text-[10px] font-normal text-gray-500 bg-gray-100 px-1.5 py-0.5 rounded"
                :title="capabilitiesTooltip(d)"
              >{{ vendorBadge(d) }}</div>
            </td>
            <td class="px-4 py-3 text-gray-600">{{ d.host }}</td>
            <td class="px-4 py-3 text-gray-600">{{ d.port }}</td>
            <td class="px-4 py-3 text-gray-600">{{ d.sslMode }}</td>
            <td class="px-4 py-3 text-gray-600">{{ d.baseDn }}</td>
            <td class="px-4 py-3">
              <span :class="d.enabled ? 'text-green-600' : 'text-gray-500'" class="text-xs font-medium">
                {{ d.enabled ? 'Yes' : 'No' }}
              </span>
            </td>
            <!-- Live reachability probe (not the `enabled` config flag): an
                 enabled directory whose LDAP host is down reads red here, so
                 this column never claims health the connection doesn't have.
                 Mirrors the dashboard Directories panel dot. -->
            <td class="px-4 py-3">
              <span class="inline-flex items-center gap-1.5 text-xs font-medium" :title="statusOf(d).message">
                <span class="w-2 h-2 rounded-full shrink-0" :class="STATUS_META[statusOf(d).state].dot" aria-hidden="true"></span>
                <span :class="STATUS_META[statusOf(d).state].text">{{ STATUS_META[statusOf(d).state].label }}</span>
              </span>
            </td>
            <td class="px-4 py-3 text-right whitespace-nowrap">
              <ActionMenu :items="[
                { label: 'Discover',   onClick: () => $router.push(`/superadmin/directories/${d.id}/discover`),
                  hidden: !d.enabled || d.directoryType === 'ENTRA_ID' },
                { label: 'Browse',     onClick: () => $router.push(`/superadmin/entra/${d.id}`),
                  hidden: d.directoryType !== 'ENTRA_ID' },
                { label: `${IVIA_ABBR} integration`,
                  onClick: () => $router.push(`/superadmin/directories/${d.id}/isva-config`),
                  // Hide when the addon's entitlement isn't granted
                  // (community + commercial-without-addon) AND when the
                  // directory is Entra (ISVA doesn't run on Entra anyway).
                  hidden: !auth.isIsvaIntegrationEnabled || d.directoryType === 'ENTRA_ID' },
                { label: 'Evict pool', onClick: () => doEvictPool(d),
                  hidden: d.directoryType === 'ENTRA_ID' },
                { label: 'Delete',     onClick: () => confirmDelete(d), danger: true },
              ]">
                <template #primary>
                  <button @click="openEdit(d)" class="btn-secondary btn-compact">Edit</button>
                </template>
              </ActionMenu>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Create/Edit modal -->
    <AppModal v-model="showModal" :title="editing ? 'Edit Directory' : 'New Directory'" size="lg">
      <form @submit.prevent="save" class="space-y-2">
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label for="dm-dir-type" class="block text-sm font-medium text-gray-700 mb-1">Directory Type</label>
            <select id="dm-dir-type" v-model="form.directoryType" @change="applyPreset" class="input w-full">
              <option value="GENERIC">Generic LDAP</option>
              <option value="ACTIVE_DIRECTORY">Active Directory</option>
              <option value="OPENLDAP">OpenLDAP</option>
              <option value="IBM_DIRECTORY_SERVER">IBM Directory Server (Tivoli / Security / Verify)</option>
              <option value="ORACLE_UNIFIED_DIRECTORY">Oracle Unified Directory</option>
              <option value="ENTRA_ID">Microsoft Entra ID</option>
            </select>
          </div>
          <FormField label="Display Name" v-model="form.displayName" required />

          <!-- Entra ID fields -->
          <template v-if="form.directoryType === 'ENTRA_ID'">
            <FormField label="Tenant ID" v-model="form.tenantId" required placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" />
            <FormField label="Client ID" v-model="form.entraClientId" required placeholder="App registration client ID" autocomplete="one-time-code" />
            <FormField label="Client Secret" v-model="form.entraClientSecret" type="password" :placeholder="editing ? 'Leave blank to keep' : ''" autocomplete="new-password" />
            <FormField label="Graph Endpoint" v-model="form.graphEndpoint" placeholder="https://graph.microsoft.com" />
          </template>

          <!-- LDAP fields -->
          <template v-else>
            <FormField label="Host" v-model="form.host" required placeholder="ldap.example.com" />
            <FormField label="Port" v-model.number="form.port" type="number" placeholder="389" />
            <div>
              <label for="dm-ssl-mode" class="block text-sm font-medium text-gray-700 mb-1">SSL Mode</label>
              <select id="dm-ssl-mode" v-model="form.sslMode" class="input w-full">
                <option value="NONE">None</option>
                <option value="LDAPS">LDAPS</option>
                <option value="STARTTLS">STARTTLS</option>
              </select>
            </div>
            <FormField label="Bind DN" v-model="form.bindDn" required placeholder="cn=admin,dc=example,dc=com" />
            <FormField label="Bind Password" v-model="form.bindPassword" type="password" :placeholder="editing ? 'Leave blank to keep' : ''" />
            <div class="col-span-2">
              <FormField label="Base DN" v-model="form.baseDn" required placeholder="dc=example,dc=com" />
            </div>
          </template>
        </div>
        <div class="flex items-center gap-3">
          <label v-if="form.directoryType !== 'ENTRA_ID'" class="flex items-center gap-2 text-sm text-gray-700">
            <input type="checkbox" v-model="form.trustAllCerts" class="rounded" />
            Trust all certificates
          </label>
          <label class="flex items-center gap-2 text-sm text-gray-700">
            <input type="checkbox" v-model="form.enabled" class="rounded" />
            Enabled
          </label>
          <label v-if="auth.isDirectorySyncEnabled && form.directoryType !== 'ENTRA_ID'"
                 class="flex items-center gap-2 text-sm text-gray-700"
                 title="Capture app-initiated writes to this directory and replicate them to its linked targets.">
            <input type="checkbox" v-model="form.replicationEnabled" class="rounded" />
            Replication enabled
          </label>
        </div>

        <!-- Self-service settings (LDAP only) -->
        <details v-if="form.directoryType !== 'ENTRA_ID'" class="border border-gray-200 rounded-lg">
          <summary class="px-4 py-2 text-sm font-medium text-gray-700 cursor-pointer">Self-service portal</summary>
          <div class="px-4 pb-4 pt-2 space-y-3">
            <label class="flex items-center gap-2 text-sm text-gray-700">
              <input type="checkbox" v-model="form.selfServiceEnabled" class="rounded" />
              Enable self-service portal for this directory
            </label>
            <div v-if="form.selfServiceEnabled">
              <FormField label="Login Attribute" v-model="form.selfServiceLoginAttribute"
                placeholder="uid (or sAMAccountName for AD)" />
              <p class="text-xs text-gray-500 mt-1">The LDAP attribute used to identify users during self-service login (e.g. uid, sAMAccountName, mail)</p>
            </div>
          </div>
        </details>

        <!-- Connection pool settings (LDAP only) -->
        <details v-if="form.directoryType !== 'ENTRA_ID'" class="border border-gray-200 rounded-lg">
          <summary class="px-4 py-2 text-sm font-medium text-gray-700 cursor-pointer">Advanced settings</summary>
          <div class="px-4 pb-4 pt-2 grid grid-cols-2 gap-3">
            <FormField label="Paging Size" v-model.number="form.pagingSize" type="number" />
            <FormField label="Pool Min Size" v-model.number="form.poolMinSize" type="number" />
            <FormField label="Pool Max Size" v-model.number="form.poolMaxSize" type="number" />
            <FormField label="Connect Timeout (s)" v-model.number="form.poolConnectTimeoutSeconds" type="number" />
            <FormField label="Response Timeout (s)" v-model.number="form.poolResponseTimeoutSeconds" type="number" />
            <FormField label="Secondary Host" v-model="form.secondaryHost" placeholder="Failover DC (optional)" />
            <FormField label="Secondary Port" v-model.number="form.secondaryPort" type="number" placeholder="Same as primary" />
            <FormField label="Global Catalog Port" v-model.number="form.globalCatalogPort" type="number" placeholder="3268 (AD only)" />
            <div></div>
            <FormField label="Enable/Disable Attribute" v-model="form.enableDisableAttribute" placeholder="e.g. nsAccountLock" />
            <div>
              <label for="dm-endis-type" class="block text-sm font-medium text-gray-700 mb-1">Enable/Disable Value Type</label>
              <select id="dm-endis-type" v-model="form.enableDisableValueType" class="input w-full">
                <option value="BOOLEAN">BOOLEAN</option>
                <option value="TIMESTAMP">TIMESTAMP</option>
              </select>
            </div>
            <FormField label="Enable Value" v-model="form.enableValue" placeholder="e.g. false" />
            <FormField label="Disable Value" v-model="form.disableValue" placeholder="e.g. true" />
          </div>
        </details>

        <!-- Test connection result -->
        <div v-if="testResult" :class="testResult.success ? 'bg-green-50 border-green-200 text-green-800' : 'bg-red-50 border-red-200 text-red-700'" class="border rounded-lg px-3 py-2 text-sm">
          {{ testResult.message }}
        </div>
        <div class="flex justify-between items-center pt-2">
          <button type="button" @click="doTest" :disabled="testLoading" class="btn-secondary text-sm">
            {{ testLoading ? 'Testing…' : 'Test Connection' }}
          </button>
          <div class="flex gap-2">
            <button type="button" @click="showModal = false" class="btn-neutral">Cancel</button>
            <button type="submit" :disabled="saving" class="btn-primary">{{ saving ? 'Saving…' : 'Save' }}</button>
          </div>
        </div>
      </form>
    </AppModal>

    <!-- Delete confirm -->
    <ConfirmDialog
      v-if="deleteTarget"
      :message="`Delete directory '${deleteTarget.displayName}'? All associated profiles and configuration will be removed.`"
      @confirm="doDelete"
      @cancel="deleteTarget = null"
    />
  </PageContainer>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useNotificationStore } from '@/stores/notifications'
import { useAuthStore } from '@/stores/auth'
import { listDirectories, createDirectory, updateDirectory, deleteDirectory, testDirectory, evictPool, getDirectoryStatus } from '@/api/directories'
import { testEntraConnection } from '@/api/entra'
import FormField from '@/components/FormField.vue'
import AppModal from '@/components/AppModal.vue'
import ActionMenu from '@/components/ActionMenu.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import { IVIA_ABBR } from '@/constants/productNames'
import PageContainer from '@/components/PageContainer.vue'
import type { components } from '@/api/openapi'

// The generated schema lags the backend (no IBM/Oracle directory types,
// replication, or capabilities), so the form is a superset of the typed
// request. Cast at the API boundary rather than narrowing the form.
type DirectoryRequest = components['schemas']['DirectoryConnectionRequest']

interface DirectoryForm {
  directoryType: string
  displayName: string
  host: string
  port: number | undefined
  sslMode: string
  trustAllCerts: boolean
  bindDn: string
  bindPassword: string
  baseDn: string
  pagingSize: number | undefined
  poolMinSize: number | undefined
  poolMaxSize: number | undefined
  poolConnectTimeoutSeconds: number | undefined
  poolResponseTimeoutSeconds: number | undefined
  enableDisableAttribute: string
  enableDisableValueType: string
  enableValue: string
  disableValue: string
  enabled: boolean
  replicationEnabled: boolean
  selfServiceEnabled: boolean
  selfServiceLoginAttribute: string
  secondaryHost: string
  secondaryPort: number | undefined
  globalCatalogPort: number | undefined
  tenantId: string
  entraClientId: string
  entraClientSecret: string
  graphEndpoint: string
}

// Root-DSE probe result the directory list renders as a vendor chip.
interface DirectoryCapabilities {
  vendorName?: string | null
  vendorVersion?: string | null
  probedAt?: string | null
  supportedControls?: string[] | null
}

// Row shape from the (untyped) directories API; only the fields this
// view reads are modelled.
interface DirectoryRow extends Partial<DirectoryForm> {
  id: string
  displayName: string
  enabled: boolean
  capabilities?: DirectoryCapabilities | null
}

interface TestResult {
  success?: boolean
  message?: string
}

// Per-row reachability state. 'checking' until the probe returns;
// 'disabled' for rows we don't probe (the directory is intentionally off).
type DirStatusState = 'checking' | 'reachable' | 'unreachable' | 'disabled'
interface DirStatus { state: DirStatusState; message?: string }

// Dot colour + label + text colour per state. Raw colour utilities (not the
// .input/.btn project classes) are correct here — this is a status indicator,
// not a form control, matching the dashboard Directories panel.
const STATUS_META: Record<DirStatusState, { dot: string; text: string; label: string }> = {
  checking:    { dot: 'bg-gray-300 animate-pulse',  text: 'text-gray-500', label: 'Checking…' },
  reachable:   { dot: 'bg-green-500',               text: 'text-green-600', label: 'Online' },
  unreachable: { dot: 'bg-red-500',                 text: 'text-red-600',   label: 'Unreachable' },
  disabled:    { dot: 'border border-gray-400',     text: 'text-gray-500', label: 'Disabled' },
}

// Repo-standard axios/native error narrowing (see docs/frontend-conventions.md).
function errMsg(e: unknown, fallback = 'Something went wrong'): string {
  const err = e as { response?: { data?: { detail?: string } }; message?: string }
  return err.response?.data?.detail || err.message || fallback
}

const notif = useNotificationStore()
const auth = useAuthStore()

const loading      = ref(false)
const saving       = ref(false)
const dirs         = ref<DirectoryRow[]>([])
const showModal    = ref(false)
const editing      = ref<string | null>(null)
const deleteTarget = ref<DirectoryRow | null>(null)
const testLoading  = ref(false)
const testResult   = ref<TestResult | null>(null)
const statusById   = ref<Record<string, DirStatus>>({})

function statusOf(d: DirectoryRow): DirStatus {
  return statusById.value[d.id] ?? { state: d.enabled ? 'checking' : 'disabled' }
}

/**
 * Probe one stored directory's live reachability. Disabled directories are
 * skipped (they're off by choice, not by failure). Runs independently per
 * row so the table renders immediately and dots resolve as probes return —
 * an unreachable host blocks only its own cell up to the LDAP timeout.
 */
async function probeStatus(d: DirectoryRow) {
  if (!d.enabled) {
    statusById.value[d.id] = { state: 'disabled' }
    return
  }
  statusById.value[d.id] = { state: 'checking' }
  try {
    const { data } = await getDirectoryStatus(d.id)
    statusById.value[d.id] = data.success
      ? { state: 'reachable', message: data.message || 'Reachable' }
      : { state: 'unreachable', message: data.message || 'Unreachable' }
  } catch (e) {
    statusById.value[d.id] = { state: 'unreachable', message: errMsg(e) }
  }
}

function probeAll() {
  statusById.value = {}
  dirs.value.forEach(probeStatus)
}

const form = ref<DirectoryForm>(emptyForm())

function emptyForm(): DirectoryForm {
  return {
    directoryType: 'GENERIC',
    displayName: '', host: '', port: 389, sslMode: 'NONE',
    trustAllCerts: false, bindDn: '', bindPassword: '', baseDn: '',
    pagingSize: 500, poolMinSize: 2, poolMaxSize: 10,
    poolConnectTimeoutSeconds: 10, poolResponseTimeoutSeconds: 30,
    enableDisableAttribute: '', enableDisableValueType: 'BOOLEAN',
    enableValue: '', disableValue: '', enabled: true,
    replicationEnabled: false,
    selfServiceEnabled: false, selfServiceLoginAttribute: 'uid',
    secondaryHost: '', secondaryPort: undefined, globalCatalogPort: undefined,
    tenantId: '', entraClientId: '', entraClientSecret: '', graphEndpoint: '',
  }
}

function applyPreset() {
  const t = form.value.directoryType
  if (t === 'ACTIVE_DIRECTORY') {
    if (!form.value.port || form.value.port === 389) form.value.port = 636
    if (form.value.sslMode === 'NONE') form.value.sslMode = 'LDAPS'
    if (!form.value.selfServiceLoginAttribute || form.value.selfServiceLoginAttribute === 'uid')
      form.value.selfServiceLoginAttribute = 'sAMAccountName'
    if (!form.value.enableDisableAttribute) {
      form.value.enableDisableAttribute = 'userAccountControl'
      form.value.enableDisableValueType = 'BOOLEAN'
      form.value.enableValue = '512'
      form.value.disableValue = '514'
    }
  } else if (t === 'OPENLDAP' || t === 'IBM_DIRECTORY_SERVER' || t === 'ORACLE_UNIFIED_DIRECTORY') {
    // OpenLDAP, ITDS and OUD all default to inetOrgPerson / uid on port 389
    // — same preset works for all three. Vendor-specific defaults (e.g.
    // ITDS ibm-pwdPolicy enable/disable, OUD ds-pwp-account-disabled) are
    // intentionally left blank in this phase; operators configure server-side
    // and the disable affordances stay generic until P2 of each respective
    // support plan adds a capability probe.
    if (!form.value.port || form.value.port === 636) form.value.port = 389
    if (!form.value.selfServiceLoginAttribute || form.value.selfServiceLoginAttribute === 'sAMAccountName')
      form.value.selfServiceLoginAttribute = 'uid'
  }
}

// Vendor / version badge formatters. The server may publish vendorName
// and vendorVersion independently; show whichever combination is
// available, prefer "vendor version" when both, fall back to just the
// version when only that is set (common on OpenDJ). When the probe ran
// but the server advertised neither (OpenLDAP doesn't populate vendor*
// in its root DSE by default), fall back to a friendly directory-type
// label so the chip still conveys "we know what kind of server this
// is, we just didn't get a self-reported version." Returns '' only
// when the probe didn't run or the server is generic enough that the
// fallback wouldn't tell the operator anything new (GENERIC, ENTRA_ID).
const TYPE_FALLBACK_LABEL: Record<string, string> = {
  OPENLDAP:                 'OpenLDAP',
  ACTIVE_DIRECTORY:         'Active Directory',
  IBM_DIRECTORY_SERVER:     'IBM Directory Server',
  ORACLE_UNIFIED_DIRECTORY: 'Oracle Unified Directory',
}

function vendorBadge(d: DirectoryRow) {
  const caps = d?.capabilities
  if (!caps) return ''
  const v = (caps.vendorName || '').trim()
  const ver = (caps.vendorVersion || '').trim()
  if (v && ver) return `${v} ${ver}`
  if (v)        return v
  if (ver)      return ver
  // Probe ran (caps is non-null) but server didn't advertise vendor info.
  // Use the directory-type label as the chip text for named types; skip
  // for GENERIC / ENTRA_ID where it would be redundant or wrong.
  return TYPE_FALLBACK_LABEL[d?.directoryType ?? ''] || ''
}

function capabilitiesTooltip(d: DirectoryRow) {
  const caps = d?.capabilities
  if (!caps) return ''
  const lines: string[] = []
  if (caps.probedAt) lines.push(`Probed ${new Date(caps.probedAt).toLocaleString()}`)
  const ctrls = caps.supportedControls || []
  if (ctrls.length) lines.push(`Supported controls (${ctrls.length}):\n${ctrls.slice(0, 12).join('\n')}${ctrls.length > 12 ? '\n…' : ''}`)
  return lines.join('\n\n')
}

async function load() {
  loading.value = true
  try {
    const { data } = await listDirectories()
    // Runtime rows are richer than the stale generated schema (capabilities,
    // replication, extra directory types); treat them as DirectoryRow.
    dirs.value = data as DirectoryRow[]
    // Kick off reachability probes (fire-and-forget; each cell fills in as
    // its probe resolves). Not awaited so the table paints immediately.
    probeAll()
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    loading.value = false
  }
}

onMounted(load)

function openCreate() {
  editing.value = null
  form.value = emptyForm()
  testResult.value = null
  showModal.value = true
}

function openEdit(d: DirectoryRow) {
  editing.value = d.id
  form.value = {
    directoryType: d.directoryType || 'GENERIC',
    displayName: d.displayName, host: d.host ?? '', port: d.port, sslMode: d.sslMode ?? 'NONE',
    trustAllCerts: d.trustAllCerts ?? false, bindDn: d.bindDn ?? '', bindPassword: '', baseDn: d.baseDn ?? '',
    pagingSize: d.pagingSize, poolMinSize: d.poolMinSize, poolMaxSize: d.poolMaxSize,
    poolConnectTimeoutSeconds: d.poolConnectTimeoutSeconds,
    poolResponseTimeoutSeconds: d.poolResponseTimeoutSeconds,
    enableDisableAttribute: d.enableDisableAttribute || '',
    enableDisableValueType: d.enableDisableValueType || 'BOOLEAN',
    enableValue: d.enableValue || '', disableValue: d.disableValue || '',
    enabled: d.enabled,
    replicationEnabled: d.replicationEnabled || false,
    selfServiceEnabled: d.selfServiceEnabled || false,
    selfServiceLoginAttribute: d.selfServiceLoginAttribute || 'uid',
    secondaryHost: d.secondaryHost || '',
    secondaryPort: d.secondaryPort || undefined,
    globalCatalogPort: d.globalCatalogPort || undefined,
    tenantId: d.tenantId || '',
    entraClientId: d.entraClientId || '',
    entraClientSecret: '',
    graphEndpoint: d.graphEndpoint || '',
  }
  testResult.value = null
  showModal.value = true
}

async function save() {
  saving.value = true
  try {
    const payload: Partial<DirectoryForm> = { ...form.value }
    if (editing.value && !payload.bindPassword) delete payload.bindPassword
    if (editing.value && !payload.entraClientSecret) delete payload.entraClientSecret
    // Form is a superset of the (stale) generated request schema.
    const requestBody = payload as unknown as DirectoryRequest
    if (editing.value) {
      await updateDirectory(editing.value, requestBody)
      notif.success('Directory updated')
    } else {
      await createDirectory(requestBody)
      notif.success('Directory created')
    }
    showModal.value = false
    await load()
  } catch (e) {
    notif.error(errMsg(e))
  } finally {
    saving.value = false
  }
}

function confirmDelete(d: DirectoryRow) { deleteTarget.value = d }

async function doDelete() {
  if (!deleteTarget.value) return
  try {
    await deleteDirectory(deleteTarget.value.id)
    notif.success('Directory deleted')
    deleteTarget.value = null
    await load()
  } catch (e) {
    notif.error(errMsg(e))
    deleteTarget.value = null
  }
}

async function doTest() {
  testLoading.value = true
  testResult.value = null
  try {
    let data: TestResult
    if (form.value.directoryType === 'ENTRA_ID') {
      const dirId = editing.value || '00000000-0000-0000-0000-000000000000'
      const res = await testEntraConnection(dirId, {
        tenantId: form.value.tenantId,
        entraClientId: form.value.entraClientId,
        entraClientSecret: form.value.entraClientSecret,
        graphEndpoint: form.value.graphEndpoint,
      })
      data = res.data
    } else {
      const res = await testDirectory(form.value as unknown as components['schemas']['TestConnectionRequest'])
      data = res.data
    }
    testResult.value = data
  } catch (e) {
    testResult.value = { success: false, message: errMsg(e) }
  } finally {
    testLoading.value = false
  }
}

async function doEvictPool(d: DirectoryRow) {
  try {
    await evictPool(d.id)
    notif.success('Connection pool evicted')
  } catch (e) {
    notif.error(errMsg(e))
  }
}
</script>

<style scoped>
@reference "tailwindcss";
</style>
