<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div class="min-h-screen bg-gray-100 flex items-center justify-center p-4">
    <div class="bg-white rounded-2xl shadow-xl w-full max-w-lg p-8">
      <!-- Header -->
      <div class="text-center mb-6">
        <svg class="w-12 h-12 mx-auto mb-3" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" fill="none">
          <rect width="32" height="32" rx="6" fill="#2563eb"/>
          <path d="M8 8h4v12H8z" fill="#fff"/>
          <path d="M8 20h10v3H8z" fill="#fff"/>
          <path d="M20 8h4v15h-4z" fill="#fff" opacity="0.55"/>
          <circle cx="22" cy="12" r="2.5" fill="#fff" opacity="0.55"/>
        </svg>
        <h1 class="text-2xl font-bold text-gray-900">Request an Account</h1>
        <p class="text-sm text-gray-500 mt-1">Fill out the form below to request access</p>
      </div>

      <!-- Success state -->
      <div v-if="submitted" class="text-center space-y-4">
        <div class="w-16 h-16 bg-green-100 text-green-600 rounded-full flex items-center justify-center mx-auto">
          <svg class="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z"/></svg>
        </div>
        <h2 class="text-lg font-semibold text-gray-900">Check your email</h2>
        <p class="text-sm text-gray-600">
          We've sent a verification link to <strong>{{ formData.email }}</strong>.
          Click the link to verify your email address and submit your request for approval.
        </p>
        <div class="bg-gray-50 rounded-lg p-4 text-sm text-gray-500">
          <p>Your request ID: <code class="bg-gray-200 px-1.5 py-0.5 rounded text-xs">{{ submitResult.requestId }}</code></p>
          <p class="mt-1">You can check the status of your request at any time.</p>
        </div>
        <div class="flex gap-3 justify-center pt-2">
          <RouterLink :to="`/register/status/${submitResult.requestId}`" class="btn-secondary">Check Status</RouterLink>
          <RouterLink to="/self-service/login" class="btn-primary">Back to Login</RouterLink>
        </div>
      </div>

      <!-- Registration form -->
      <form v-else @submit.prevent="handleSubmit" class="space-y-4">
        <!-- Step 1: Directory & Profile selection -->
        <div class="space-y-3">
          <h2 class="text-sm font-semibold text-gray-700 uppercase tracking-wider">Directory</h2>

          <div>
            <label for="register-directory" class="block text-sm font-medium text-gray-700 mb-1">Directory *</label>
            <select id="register-directory" v-model="formData.directoryId" required @change="onDirectoryChange"
              class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500">
              <option value="" disabled>— Select directory —</option>
              <option v-for="dir in directories" :key="dir.id" :value="dir.id">{{ dir.displayName }}</option>
            </select>
          </div>

          <div v-if="formData.directoryId">
            <label for="ss-register-profile" class="block text-sm font-medium text-gray-700 mb-1">Profile *</label>
            <select id="ss-register-profile" v-model="formData.profileId" required @change="onProfileChange"
              class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500">
              <option value="">-- select profile --</option>
              <option v-for="p in profiles" :key="p.id" :value="p.id">{{ p.name }}</option>
            </select>
            <p v-if="selectedProfileDesc" class="text-xs text-gray-500 mt-1">{{ selectedProfileDesc }}</p>
          </div>
        </div>

        <!-- Loading form schema -->
        <div v-if="loadingForm" class="text-center text-gray-500 text-sm py-4">Loading form fields...</div>

        <!-- Step 2: Dynamic fields from ProfileAttributeConfig -->
        <template v-if="formFields.length > 0">
          <hr class="border-gray-200" />

          <template v-for="(sectionFields, sectionName) in groupedFormFields" :key="sectionName">
            <h2 class="text-sm font-semibold text-gray-700 uppercase tracking-wider">
              {{ sectionName === '_default' ? 'Account Details' : sectionName }}
            </h2>

            <div :class="sectionFields.some(f => f.columnSpan < 6) ? 'grid grid-cols-6 gap-3' : 'space-y-3'">
              <div v-for="field in sectionFields" :key="field.attributeName"
                :style="{ gridColumn: `span ${field.columnSpan || 6}` }">
                <label v-if="field.inputType !== 'BOOLEAN'" :for="`ss-register-field-${field.attributeName}`" class="block text-sm font-medium text-gray-700 mb-1">
                  {{ field.label }} {{ field.required ? '*' : '' }}
                </label>
                <label v-else class="block text-sm font-medium text-gray-700 mb-1">
                  {{ field.label }} {{ field.required ? '*' : '' }}
                </label>

                <textarea v-if="field.inputType === 'TEXTAREA'"
                  :id="`ss-register-field-${field.attributeName}`"
                  v-model="attributeValues[field.attributeName]" rows="2"
                  :required="field.required"
                  class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500" />

                <select v-else-if="field.inputType === 'SELECT' && field.allowedValues"
                  :id="`ss-register-field-${field.attributeName}`"
                  v-model="attributeValues[field.attributeName]"
                  :required="field.required"
                  class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500">
                  <option value="">-- select --</option>
                  <option v-for="opt in parseAllowedValues(field.allowedValues)" :key="opt" :value="opt">{{ opt }}</option>
                </select>

                <label v-else-if="field.inputType === 'BOOLEAN'" class="flex items-center gap-2 py-1">
                  <input type="checkbox" v-model="attributeValues[field.attributeName]" class="rounded" />
                  <span class="text-sm text-gray-700">{{ field.label }}</span>
                </label>

                <input v-else-if="field.inputType === 'DATE'"
                  :id="`ss-register-field-${field.attributeName}`"
                  v-model="attributeValues[field.attributeName]"
                  type="date" :required="field.required"
                  class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500" />

                <div v-else-if="field.inputType === 'PASSWORD'" class="relative">
                  <input
                    :id="`ss-register-field-${field.attributeName}`"
                    v-model="attributeValues[field.attributeName]"
                    :type="regPasswordVisible[field.attributeName] ? 'text' : 'password'"
                    :required="field.required"
                    class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 pr-9" />
                  <button type="button"
                    class="absolute right-2.5 top-1/2 -translate-y-1/2 text-gray-500 hover:text-gray-600"
                    @mousedown.prevent="regPasswordVisible[field.attributeName] = true"
                    @mouseup.prevent="regPasswordVisible[field.attributeName] = false"
                    @mouseleave="regPasswordVisible[field.attributeName] = false"
                    @touchstart.prevent="regPasswordVisible[field.attributeName] = true"
                    @touchend.prevent="regPasswordVisible[field.attributeName] = false"
                    title="Hold to show password">
                    <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path v-if="!regPasswordVisible[field.attributeName]" stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
                      <path v-else stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M3 3l18 18" />
                    </svg>
                  </button>
                </div>

                <input v-else
                  :id="`ss-register-field-${field.attributeName}`"
                  v-model="attributeValues[field.attributeName]"
                  :type="field.inputType === 'DATETIME' ? 'datetime-local' : 'text'"
                  :required="field.required"
                  :placeholder="field.label"
                  class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500" />

                <p v-if="fieldErrors[field.attributeName]" class="text-xs text-red-500 mt-1">
                  {{ fieldErrors[field.attributeName] }}
                </p>
              </div>
            </div>
          </template>

          <!-- Email (always required) -->
          <div>
            <label for="ss-register-email" class="block text-sm font-medium text-gray-700 mb-1">Email Address *</label>
            <input id="ss-register-email" v-model="formData.email" type="email" required placeholder="john.doe@example.com"
              class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500" />
            <p class="text-xs text-gray-500 mt-1">A verification link will be sent to this address</p>
          </div>

          <!-- Justification -->
          <div>
            <label for="ss-register-justification" class="block text-sm font-medium text-gray-700 mb-1">Justification *</label>
            <textarea id="ss-register-justification" v-model="formData.justification" rows="3" required
              placeholder="Please explain why you need access..."
              class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500"></textarea>
          </div>

          <p v-if="errorMsg" class="text-sm text-red-600 bg-red-50 rounded-lg px-3 py-2">{{ errorMsg }}</p>

          <button type="submit" :disabled="loading"
            class="w-full bg-blue-600 text-white font-semibold py-2.5 rounded-lg hover:bg-blue-700 transition-colors disabled:opacity-50">
            {{ loading ? 'Submitting...' : 'Submit Request' }}
          </button>
        </template>
      </form>

      <!-- Login link -->
      <div v-if="!submitted" class="mt-6 text-center">
        <p class="text-sm text-gray-500">
          Already have an account?
          <RouterLink to="/self-service/login" class="text-blue-600 hover:text-blue-700 font-medium">Sign in</RouterLink>
        </p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import {
  listRegistrationDirectories,
  listRegistrationProfiles,
  getRegistrationForm,
  submitRegistration,
} from '@/api/selfservice'

const loading = ref(false)
const loadingForm = ref(false)
const regPasswordVisible = reactive({})
const errorMsg = ref('')
const submitted = ref(false)
const submitResult = ref(null)

const formData = reactive({
  directoryId: '',
  profileId: '',
  email: '',
  justification: '',
})

const directories = ref([])
const profiles = ref([])
const formFields = ref([])
const attributeValues = reactive({})
const fieldErrors = reactive({})

const selectedProfileDesc = computed(() => {
  const p = profiles.value.find(p => p.id === formData.profileId)
  return p?.description || ''
})

const groupedFormFields = computed(() => {
  const groups = {}
  for (const field of formFields.value) {
    const section = field.sectionName || '_default'
    if (!groups[section]) groups[section] = []
    groups[section].push(field)
  }
  return groups
})

onMounted(async () => {
  try {
    const { data } = await listRegistrationDirectories()
    directories.value = data
  } catch { /* no directories */ }
})

async function onDirectoryChange() {
  formData.profileId = ''
  profiles.value = []
  formFields.value = []
  errorMsg.value = ''
  if (!formData.directoryId) return
  try {
    const { data } = await listRegistrationProfiles(formData.directoryId)
    profiles.value = data
  } catch { /* no profiles */ }
}

async function onProfileChange() {
  formFields.value = []
  errorMsg.value = ''
  Object.keys(attributeValues).forEach(k => delete attributeValues[k])
  if (!formData.profileId) return

  loadingForm.value = true
  try {
    const { data } = await getRegistrationForm(formData.profileId)
    formFields.value = data
    // Initialize attribute values
    for (const field of data) {
      if (field.inputType === 'BOOLEAN') {
        attributeValues[field.attributeName] = false
      } else {
        attributeValues[field.attributeName] = ''
      }
    }
  } catch (e) {
    errorMsg.value = e.response?.data?.detail || 'Failed to load form'
  } finally {
    loadingForm.value = false
  }
}

function parseAllowedValues(json) {
  try { return JSON.parse(json) } catch { return [] }
}

function validateFields() {
  Object.keys(fieldErrors).forEach(k => delete fieldErrors[k])
  let valid = true
  for (const field of formFields.value) {
    const val = attributeValues[field.attributeName]
    const strVal = typeof val === 'boolean' ? (val ? 'TRUE' : 'FALSE') : (val || '')

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

async function handleSubmit() {
  if (!validateFields()) return

  errorMsg.value = ''
  loading.value = true
  try {
    // Build attributes map: { attrName: [value] }
    const attributes = {}
    for (const field of formFields.value) {
      const val = attributeValues[field.attributeName]
      if (field.inputType === 'BOOLEAN') {
        attributes[field.attributeName] = [val ? 'TRUE' : 'FALSE']
      } else if (val) {
        attributes[field.attributeName] = [val]
      }
    }

    const { data } = await submitRegistration(
      formData.profileId, formData.email, formData.justification, attributes)
    submitResult.value = data
    submitted.value = true
  } catch (e) {
    errorMsg.value = e.response?.data?.detail || 'Registration failed. Please try again.'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
@reference "tailwindcss";
</style>
