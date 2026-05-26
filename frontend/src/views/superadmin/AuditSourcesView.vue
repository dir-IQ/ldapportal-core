<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <PageContainer>
    <div class="flex items-center justify-between mb-6">
      <div>
        <h1 class="text-2xl font-bold text-gray-900">Audit Data Sources</h1>
        <p class="text-sm text-gray-500 mt-1">LDAP changelog reader connections for audit log polling</p>
      </div>
      <button @click="openCreate" class="btn-primary">+ New Source</button>
    </div>

    <div class="bg-white border border-gray-200 rounded-xl overflow-hidden">
      <div v-if="loading" class="p-8 text-center text-gray-500 text-sm">Loading…</div>
      <div v-else-if="sources.length === 0" class="p-8 text-center text-gray-500 text-sm">No audit data sources configured.</div>
      <table v-else class="w-full text-sm">
        <thead class="bg-gray-50 border-b border-gray-100">
          <tr>
            <th class="px-4 py-3 text-left font-medium text-gray-500">Name</th>
            <th class="px-4 py-3 text-left font-medium text-gray-500">Host</th>
            <th class="px-4 py-3 text-left font-medium text-gray-500">Port</th>
            <th class="px-4 py-3 text-left font-medium text-gray-500">SSL</th>
            <th class="px-4 py-3 text-left font-medium text-gray-500">Format</th>
            <th class="px-4 py-3 text-left font-medium text-gray-500">Changelog DN</th>
            <th class="px-4 py-3 text-left font-medium text-gray-500">Enabled</th>
            <th class="px-4 py-3"></th>
          </tr>
        </thead>
        <tbody class="divide-y divide-gray-50">
          <tr v-for="s in sources" :key="s.id" class="hover:bg-gray-50">
            <td class="px-4 py-3 font-medium text-gray-900">{{ s.displayName }}</td>
            <td class="px-4 py-3 text-gray-600">{{ s.host }}</td>
            <td class="px-4 py-3 text-gray-600">{{ s.port }}</td>
            <td class="px-4 py-3 text-gray-600">{{ s.sslMode }}</td>
            <td class="px-4 py-3 text-gray-600">{{ formatLabels[s.changelogFormat] || s.changelogFormat }}</td>
            <td class="px-4 py-3 text-gray-600">{{ s.changelogBaseDn }}</td>
            <td class="px-4 py-3">
              <span :class="s.enabled ? 'text-green-600' : 'text-gray-500'" class="text-xs font-medium">
                {{ s.enabled ? 'Yes' : 'No' }}
              </span>
            </td>
            <td class="px-4 py-3 text-right whitespace-nowrap">
              <button @click="openEdit(s)" class="text-blue-600 hover:text-blue-800 text-xs font-medium mr-3">Edit</button>
              <button @click="confirmDelete(s)" class="text-red-500 hover:text-red-700 text-xs font-medium">Delete</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Create/Edit modal -->
    <AppModal v-model="showModal" :title="editing ? 'Edit Audit Source' : 'New Audit Source'" size="lg">
      <form @submit.prevent="save" class="space-y-2">
        <div class="grid grid-cols-2 gap-3">
          <FormField label="Display Name" v-model="form.displayName" required />
          <FormField label="Host" v-model="form.host" required />
          <FormField label="Port" v-model.number="form.port" type="number" placeholder="389" />
          <div>
            <label for="as-ssl-mode" class="block text-sm font-medium text-gray-700 mb-1">SSL Mode</label>
            <select id="as-ssl-mode" v-model="form.sslMode" class="input w-full">
              <option value="NONE">None</option>
              <option value="LDAPS">LDAPS</option>
              <option value="STARTTLS">STARTTLS</option>
            </select>
          </div>
          <FormField label="Bind DN" v-model="form.bindDn" required autocomplete="off" />
          <FormField label="Bind Password" v-model="form.bindPassword" type="password" :placeholder="editing ? 'Leave blank to keep' : ''" autocomplete="new-password" />
          <div>
            <label for="as-changelog-format" class="block text-sm font-medium text-gray-700 mb-1">Changelog Format</label>
            <select id="as-changelog-format" v-model="form.changelogFormat" @change="onFormatChange" class="input w-full">
              <option value="DSEE_CHANGELOG">Oracle DSEE / cn=changelog</option>
              <option value="OPENLDAP_ACCESSLOG">OpenLDAP accesslog</option>
            </select>
          </div>
          <FormField label="Changelog Base DN" v-model="form.changelogBaseDn" :placeholder="form.changelogFormat === 'OPENLDAP_ACCESSLOG' ? 'cn=accesslog' : 'cn=changelog'" required />
          <FormField label="Branch Filter DN" v-model="form.branchFilterDn" placeholder="optional" />
        </div>
        <div class="flex items-center gap-3">
          <label class="flex items-center gap-2 text-sm text-gray-700">
            <input type="checkbox" v-model="form.trustAllCerts" class="rounded" />
            Trust all certificates
          </label>
          <label class="flex items-center gap-2 text-sm text-gray-700">
            <input type="checkbox" v-model="form.enabled" class="rounded" />
            Enabled
          </label>
        </div>
        <!-- Test connection result -->
        <div v-if="testResult" :class="testResult.success ? 'bg-green-50 border-green-200 text-green-800' : 'bg-red-50 border-red-200 text-red-700'" class="border rounded-lg px-3 py-2 text-sm">
          {{ testResult.message }} <span v-if="testResult.elapsedMs != null" class="text-xs opacity-70">({{ testResult.elapsedMs }}ms)</span>
        </div>
        <div class="flex justify-between items-center pt-2">
          <button type="button" @click="doTest" :disabled="testLoading || !form.host || !form.bindDn || (!editing && !form.bindPassword)" class="btn-secondary text-sm">
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
      :message="`Delete audit source '${deleteTarget.displayName}'?`"
      @confirm="doDelete"
      @cancel="deleteTarget = null"
    />
  </PageContainer>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useNotificationStore } from '@/stores/notifications'
import { listAuditSources, createAuditSource, updateAuditSource, deleteAuditSource, testAuditSource } from '@/api/auditDataSources'
import FormField from '@/components/FormField.vue'
import AppModal from '@/components/AppModal.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import PageContainer from '@/components/PageContainer.vue'

const notif = useNotificationStore()

const loading      = ref(false)
const saving       = ref(false)
const sources      = ref([])
const showModal    = ref(false)
const editing      = ref(null)
const deleteTarget = ref(null)
const testLoading  = ref(false)
const testResult   = ref(null)

const form = ref(emptyForm())

const formatLabels = {
  DSEE_CHANGELOG: 'DSEE',
  OPENLDAP_ACCESSLOG: 'accesslog',
}

function emptyForm() {
  return {
    displayName: '', host: '', port: 389, sslMode: 'NONE',
    trustAllCerts: false, bindDn: '', bindPassword: '',
    changelogBaseDn: 'cn=changelog', branchFilterDn: '',
    changelogFormat: 'DSEE_CHANGELOG', enabled: true,
  }
}

function onFormatChange() {
  // Auto-switch the default base DN when toggling format
  if (form.value.changelogFormat === 'OPENLDAP_ACCESSLOG' && form.value.changelogBaseDn === 'cn=changelog') {
    form.value.changelogBaseDn = 'cn=accesslog'
  } else if (form.value.changelogFormat === 'DSEE_CHANGELOG' && form.value.changelogBaseDn === 'cn=accesslog') {
    form.value.changelogBaseDn = 'cn=changelog'
  }
}

async function load() {
  loading.value = true
  try {
    const { data } = await listAuditSources()
    sources.value = data
  } catch (e) {
    notif.error(e.response?.data?.detail || e.message)
  } finally {
    loading.value = false
  }
}

onMounted(load)

function openCreate() {
  editing.value = null
  testResult.value = null
  form.value = emptyForm()
  showModal.value = true
}

function openEdit(s) {
  editing.value = s.id
  testResult.value = null
  form.value = {
    displayName: s.displayName, host: s.host, port: s.port, sslMode: s.sslMode,
    trustAllCerts: s.trustAllCerts, bindDn: s.bindDn, bindPassword: '',
    changelogBaseDn: s.changelogBaseDn, branchFilterDn: s.branchFilterDn || '',
    changelogFormat: s.changelogFormat || 'DSEE_CHANGELOG', enabled: s.enabled,
  }
  showModal.value = true
}

async function doTest() {
  testLoading.value = true
  testResult.value = null
  try {
    const { data } = await testAuditSource(form.value)
    testResult.value = data
  } catch (e) {
    testResult.value = { success: false, message: e.response?.data?.detail || e.message }
  } finally {
    testLoading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    const payload = { ...form.value }
    if (editing.value && !payload.bindPassword) delete payload.bindPassword
    if (editing.value) {
      await updateAuditSource(editing.value, payload)
      notif.success('Audit source updated')
    } else {
      await createAuditSource(payload)
      notif.success('Audit source created')
    }
    showModal.value = false
    await load()
  } catch (e) {
    notif.error(e.response?.data?.detail || e.message)
  } finally {
    saving.value = false
  }
}

function confirmDelete(s) { deleteTarget.value = s }

async function doDelete() {
  try {
    await deleteAuditSource(deleteTarget.value.id)
    notif.success('Audit source deleted')
    deleteTarget.value = null
    await load()
  } catch (e) {
    notif.error(e.response?.data?.detail || e.message)
    deleteTarget.value = null
  }
}
</script>

<style scoped>
@reference "tailwindcss";
</style>
