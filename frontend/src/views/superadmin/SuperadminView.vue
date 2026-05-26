<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <PageContainer variant="form">
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-2xl font-bold text-gray-900">Superadmins</h1>
      <button @click="openCreate" class="btn-primary">+ Add Superadmin</button>
    </div>

    <div class="bg-white border border-gray-200 rounded-xl overflow-hidden">
      <div v-if="loading" class="p-8 text-center text-gray-500 text-sm">Loading…</div>
      <div v-else-if="admins.length === 0" class="p-8 text-center text-gray-500 text-sm">No superadmins found.</div>
      <table v-else class="w-full text-sm">
        <thead class="bg-gray-50 border-b border-gray-100">
          <tr>
            <th class="px-4 py-3 text-left font-medium text-gray-500">Username</th>
            <th class="px-4 py-3 text-left font-medium text-gray-500">Email</th>
            <th class="px-4 py-3"></th>
          </tr>
        </thead>
        <tbody class="divide-y divide-gray-50">
          <tr v-for="admin in admins" :key="admin.id" class="hover:bg-gray-50">
            <td class="px-4 py-3 font-medium text-gray-900">{{ admin.username }}</td>
            <td class="px-4 py-3 text-gray-600">{{ admin.email ?? '—' }}</td>
            <td class="px-4 py-3 text-right">
              <button
                v-if="admin.id !== auth.principal?.id"
                @click="confirmDelete(admin)"
                class="text-red-500 hover:text-red-700 text-xs font-medium"
              >Delete</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Create modal -->
    <AppModal v-model="showModal" title="Add Superadmin" size="sm">
      <form @submit.prevent="doCreate" class="space-y-4">
        <FormField label="Username" v-model="form.username" required />
        <FormField label="Email" v-model="form.email" placeholder="optional" />
        <FormField label="Password" v-model="form.password" type="password" required />
        <div class="flex justify-end gap-2 pt-2">
          <button type="button" @click="showModal = false" class="btn-neutral">Cancel</button>
          <button type="submit" :disabled="saving" class="btn-primary">{{ saving ? 'Adding…' : 'Add' }}</button>
        </div>
      </form>
    </AppModal>

    <!-- Delete confirm -->
    <ConfirmDialog
      v-if="deleteTarget"
      :message="`Remove superadmin '${deleteTarget.username}'?`"
      @confirm="doDelete"
      @cancel="deleteTarget = null"
    />
  </PageContainer>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'
import { listSuperadmins, createSuperadmin, deleteSuperadmin } from '@/api/superadmin'
import FormField from '@/components/FormField.vue'
import AppModal from '@/components/AppModal.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import PageContainer from '@/components/PageContainer.vue'

const auth  = useAuthStore()
const notif = useNotificationStore()

const loading      = ref(false)
const saving       = ref(false)
const admins       = ref([])
const showModal    = ref(false)
const deleteTarget = ref(null)
const form         = ref({ username: '', email: '', password: '' })

async function loadAdmins() {
  loading.value = true
  try {
    const { data } = await listSuperadmins()
    admins.value = data
  } catch (e) {
    notif.error(e.response?.data?.detail || e.message)
  } finally {
    loading.value = false
  }
}

onMounted(loadAdmins)

function openCreate() {
  form.value = { username: '', email: '', password: '' }
  showModal.value = true
}

async function doCreate() {
  saving.value = true
  try {
    await createSuperadmin(form.value)
    notif.success('Superadmin created')
    showModal.value = false
    await loadAdmins()
  } catch (e) {
    notif.error(e.response?.data?.detail || e.message)
  } finally {
    saving.value = false
  }
}

function confirmDelete(admin) { deleteTarget.value = admin }

async function doDelete() {
  try {
    await deleteSuperadmin(deleteTarget.value.id)
    notif.success('Superadmin removed')
    deleteTarget.value = null
    await loadAdmins()
  } catch (e) {
    notif.error(e.response?.data?.detail || e.message)
    deleteTarget.value = null
  }
}
</script>

<style scoped>
@reference "tailwindcss";
</style>
