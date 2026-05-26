<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div>
    <div class="flex items-center justify-between mb-6">
      <div>
        <h1 class="text-2xl font-bold text-gray-900">My Profile</h1>
        <p class="text-sm text-gray-500 mt-1">View and update your account information</p>
      </div>
      <button v-if="!editing && hasEditableFields" @click="startEdit" class="btn-primary">Edit Profile</button>
      <div v-else-if="editing" class="flex gap-2">
        <button @click="handleSave" :disabled="saving" class="btn-primary">
          {{ saving ? 'Saving...' : 'Save Changes' }}
        </button>
        <button @click="cancelEdit" class="btn-neutral">Cancel</button>
      </div>
    </div>

    <!-- Loading -->
    <div v-if="loadingTemplate || loadingProfile" class="text-center text-gray-500 text-sm py-8">Loading profile...</div>

    <!-- Error -->
    <div v-else-if="errorMsg" class="bg-red-50 border border-red-200 text-red-700 rounded-lg px-4 py-3 mb-6 text-sm">
      {{ errorMsg }}
    </div>

    <template v-else-if="profileData">
      <!-- Success message -->
      <div v-if="saved" class="bg-green-50 border border-green-200 text-green-700 rounded-lg px-4 py-3 mb-6 text-sm flex items-center gap-2">
        <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7"/></svg>
        Profile updated successfully.
      </div>

      <!-- Save error -->
      <div v-if="saveError" class="bg-red-50 border border-red-200 text-red-700 rounded-lg px-4 py-3 mb-6 text-sm">
        {{ saveError }}
      </div>

      <!-- Profile card -->
      <div class="bg-white rounded-lg border shadow-sm">
        <!-- Avatar / header section -->
        <div class="p-6 border-b border-gray-100 flex items-center gap-4">
          <div class="w-16 h-16 bg-blue-100 text-blue-600 rounded-full flex items-center justify-center text-2xl font-bold">
            {{ initials }}
          </div>
          <div>
            <h2 class="text-lg font-semibold text-gray-900">{{ profileData.displayName }}</h2>
            <p class="text-sm text-gray-500">{{ profileData.dn }}</p>
          </div>
        </div>

        <!-- Dynamic fields grouped by section -->
        <div class="p-6">
          <template v-for="(sectionFields, sectionName) in groupedFields" :key="sectionName">
            <h3 v-if="sectionName !== '_default'" class="text-sm font-semibold text-gray-700 uppercase tracking-wider mb-3 mt-6 first:mt-0">
              {{ sectionName }}
            </h3>
            <div class="space-y-1">
              <div v-for="field in sectionFields" :key="field.attributeName"
                class="grid grid-cols-3 gap-4 items-start py-2 border-b border-gray-50 last:border-0">
                <label :for="editing && field.editable && field.inputType !== 'BOOLEAN' ? `ss-profile-field-${field.attributeName}` : undefined" class="text-sm font-medium text-gray-600 pt-2">
                  {{ field.label }}
                  <span v-if="field.editable && editing" class="text-xs text-blue-500 block">editable</span>
                  <span v-if="!field.editable && editing" class="text-xs text-gray-500 block">read-only</span>
                </label>
                <div class="col-span-2">
                  <!-- Editable field -->
                  <template v-if="editing && field.editable">
                    <textarea v-if="field.inputType === 'TEXTAREA'"
                      :id="`ss-profile-field-${field.attributeName}`"
                      v-model="editForm[field.attributeName]" rows="3"
                      class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500" />
                    <select v-else-if="field.inputType === 'SELECT' && field.allowedValues"
                      :id="`ss-profile-field-${field.attributeName}`"
                      v-model="editForm[field.attributeName]"
                      class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500">
                      <option value="">-- select --</option>
                      <option v-for="opt in parseAllowedValues(field.allowedValues)" :key="opt" :value="opt">{{ opt }}</option>
                    </select>
                    <label v-else-if="field.inputType === 'BOOLEAN'" class="flex items-center gap-2 py-2">
                      <input type="checkbox" v-model="editForm[field.attributeName]" class="rounded" />
                      <span class="text-sm text-gray-700">{{ editForm[field.attributeName] ? 'Yes' : 'No' }}</span>
                    </label>
                    <div v-else-if="field.inputType === 'PASSWORD'" class="relative">
                      <input
                        :id="`ss-profile-field-${field.attributeName}`"
                        v-model="editForm[field.attributeName]"
                        :type="profilePwdVisible[field.attributeName] ? 'text' : 'password'"
                        class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 pr-9" />
                      <button type="button"
                        class="absolute right-2.5 top-1/2 -translate-y-1/2 text-gray-500 hover:text-gray-600"
                        @mousedown.prevent="profilePwdVisible[field.attributeName] = true"
                        @mouseup.prevent="profilePwdVisible[field.attributeName] = false"
                        @mouseleave="profilePwdVisible[field.attributeName] = false"
                        @touchstart.prevent="profilePwdVisible[field.attributeName] = true"
                        @touchend.prevent="profilePwdVisible[field.attributeName] = false"
                        title="Hold to show password">
                        <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path v-if="!profilePwdVisible[field.attributeName]" stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
                          <path v-else stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M3 3l18 18" />
                        </svg>
                      </button>
                    </div>
                    <input v-else
                      :id="`ss-profile-field-${field.attributeName}`"
                      v-model="editForm[field.attributeName]"
                      type="text"
                      class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500" />
                    <!-- Validation hint -->
                    <p v-if="fieldErrors[field.attributeName]" class="text-xs text-red-500 mt-1">
                      {{ fieldErrors[field.attributeName] }}
                    </p>
                  </template>
                  <!-- Read-only field -->
                  <p v-else class="text-sm text-gray-900 py-2">
                    {{ getDisplayValue(field) }}
                  </p>
                </div>
              </div>
            </div>
          </template>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { getTemplate, getProfile, updateProfile } from '@/api/selfservice'

const loadingTemplate = ref(true)
const loadingProfile = ref(true)
const editing = ref(false)
const saving = ref(false)
const saved = ref(false)
const errorMsg = ref('')
const saveError = ref('')

const templateData = ref(null)
const profileData = ref(null)
const editForm = reactive({})
const fieldErrors = reactive({})
const profilePwdVisible = reactive({})

onMounted(async () => {
  try {
    const [tmpl, prof] = await Promise.all([getTemplate(), getProfile()])
    templateData.value = tmpl.data
    profileData.value = prof.data
  } catch (e) {
    errorMsg.value = e.response?.data?.detail || 'Failed to load profile'
  } finally {
    loadingTemplate.value = false
    loadingProfile.value = false
  }
})

const hasEditableFields = computed(() =>
  templateData.value?.fields?.some(f => f.editable) ?? false
)

const initials = computed(() => {
  const name = profileData.value?.displayName || ''
  const parts = name.split(' ')
  return parts.map(p => p[0]).join('').toUpperCase().slice(0, 2) || '?'
})

const groupedFields = computed(() => {
  if (!templateData.value?.fields) return {}
  const groups = {}
  for (const field of templateData.value.fields) {
    const section = field.sectionName || '_default'
    if (!groups[section]) groups[section] = []
    groups[section].push(field)
  }
  return groups
})

function getDisplayValue(field) {
  if (!profileData.value?.attributes) return '-'
  const vals = profileData.value.attributes[field.attributeName]
  if (!vals || vals.length === 0) return '-'
  if (field.inputType === 'BOOLEAN') return vals[0] === 'TRUE' ? 'Yes' : 'No'
  return vals.join(', ')
}

function startEdit() {
  // Initialize edit form with current values
  for (const field of templateData.value.fields) {
    if (field.editable) {
      const vals = profileData.value?.attributes?.[field.attributeName]
      if (field.inputType === 'BOOLEAN') {
        editForm[field.attributeName] = vals?.[0] === 'TRUE'
      } else {
        editForm[field.attributeName] = vals?.[0] || ''
      }
    }
  }
  Object.keys(fieldErrors).forEach(k => delete fieldErrors[k])
  editing.value = true
}

function cancelEdit() {
  editing.value = false
  saveError.value = ''
}

function parseAllowedValues(json) {
  try { return JSON.parse(json) } catch { return [] }
}

function validateFields() {
  Object.keys(fieldErrors).forEach(k => delete fieldErrors[k])
  let valid = true
  for (const field of templateData.value.fields) {
    if (!field.editable) continue
    const value = editForm[field.attributeName]
    const strVal = typeof value === 'boolean' ? (value ? 'TRUE' : 'FALSE') : (value || '')

    if (field.required && !strVal) {
      fieldErrors[field.attributeName] = `${field.label} is required`
      valid = false
      continue
    }
    if (!strVal) continue
    if (field.minLength && strVal.length < field.minLength) {
      fieldErrors[field.attributeName] = `Must be at least ${field.minLength} characters`
      valid = false
    }
    if (field.maxLength && strVal.length > field.maxLength) {
      fieldErrors[field.attributeName] = `Must be at most ${field.maxLength} characters`
      valid = false
    }
    if (field.validationRegex && !new RegExp(field.validationRegex).test(strVal)) {
      fieldErrors[field.attributeName] = field.validationMessage || 'Invalid format'
      valid = false
    }
  }
  return valid
}

async function handleSave() {
  if (!validateFields()) return

  saving.value = true
  saved.value = false
  saveError.value = ''
  try {
    // Build the update payload
    const updates = {}
    for (const field of templateData.value.fields) {
      if (!field.editable) continue
      const val = editForm[field.attributeName]
      if (field.inputType === 'BOOLEAN') {
        updates[field.attributeName] = [val ? 'TRUE' : 'FALSE']
      } else {
        updates[field.attributeName] = val ? [val] : []
      }
    }

    await updateProfile(updates)

    // Refresh profile data
    const { data } = await getProfile()
    profileData.value = data

    editing.value = false
    saved.value = true
    setTimeout(() => { saved.value = false }, 3000)
  } catch (e) {
    saveError.value = e.response?.data?.detail || 'Failed to save changes'
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
@reference "tailwindcss";
</style>
