<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div>
    <div class="mb-6">
      <h1 class="text-2xl font-bold text-gray-900">Change Password</h1>
      <p class="text-sm text-gray-500 mt-1">Update your LDAP account password</p>
    </div>

    <div class="bg-white rounded-lg border shadow-sm max-w-lg">
      <!-- Success message -->
      <div v-if="success" class="m-6 bg-green-50 border border-green-200 text-green-700 rounded-lg px-4 py-3 text-sm flex items-center gap-2">
        <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7"/></svg>
        Password changed successfully.
      </div>

      <form v-if="!success" @submit.prevent="handleSubmit" class="p-6 space-y-4">
        <div>
          <label for="ss-pwd-current" class="block text-sm font-medium text-gray-700 mb-1">Current Password *</label>
          <div class="relative">
            <input id="ss-pwd-current" v-model="form.currentPassword" :type="showCurrent ? 'text' : 'password'" required placeholder="Enter current password"
              class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 pr-9" />
            <button type="button" class="absolute right-2.5 top-1/2 -translate-y-1/2 text-gray-500 hover:text-gray-600"
              @mousedown.prevent="showCurrent = true" @mouseup.prevent="showCurrent = false"
              @mouseleave="showCurrent = false" @touchstart.prevent="showCurrent = true" @touchend.prevent="showCurrent = false"
              title="Hold to show password">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path v-if="!showCurrent" stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
                <path v-else stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M3 3l18 18" />
              </svg>
            </button>
          </div>
        </div>

        <hr class="border-gray-100" />

        <div>
          <label for="ss-pwd-new" class="block text-sm font-medium text-gray-700 mb-1">New Password *</label>
          <div class="relative">
            <input id="ss-pwd-new" v-model="form.newPassword" :type="showNew ? 'text' : 'password'" required placeholder="Enter new password"
              class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 pr-9" />
            <button type="button" class="absolute right-2.5 top-1/2 -translate-y-1/2 text-gray-500 hover:text-gray-600"
              @mousedown.prevent="showNew = true" @mouseup.prevent="showNew = false"
              @mouseleave="showNew = false" @touchstart.prevent="showNew = true" @touchend.prevent="showNew = false"
              title="Hold to show password">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path v-if="!showNew" stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
                <path v-else stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M3 3l18 18" />
              </svg>
            </button>
          </div>
        </div>

        <div>
          <label for="ss-pwd-confirm" class="block text-sm font-medium text-gray-700 mb-1">Confirm New Password *</label>
          <div class="relative">
            <input id="ss-pwd-confirm" v-model="form.confirmPassword" :type="showConfirm ? 'text' : 'password'" required placeholder="Re-enter new password"
              class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 pr-9" />
            <button type="button" class="absolute right-2.5 top-1/2 -translate-y-1/2 text-gray-500 hover:text-gray-600"
              @mousedown.prevent="showConfirm = true" @mouseup.prevent="showConfirm = false"
              @mouseleave="showConfirm = false" @touchstart.prevent="showConfirm = true" @touchend.prevent="showConfirm = false"
              title="Hold to show password">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path v-if="!showConfirm" stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
                <path v-else stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M3 3l18 18" />
              </svg>
            </button>
          </div>
        </div>

        <!-- Password requirements -->
        <div class="bg-gray-50 rounded-lg p-3">
          <p class="text-xs font-medium text-gray-600 mb-2">Password requirements:</p>
          <ul class="text-xs text-gray-500 space-y-1">
            <li :class="checks.length ? 'text-green-600' : ''">
              {{ checks.length ? '✓' : '✗' }} At least 8 characters
            </li>
            <li :class="checks.uppercase ? 'text-green-600' : ''">
              {{ checks.uppercase ? '✓' : '✗' }} At least one uppercase letter
            </li>
            <li :class="checks.lowercase ? 'text-green-600' : ''">
              {{ checks.lowercase ? '✓' : '✗' }} At least one lowercase letter
            </li>
            <li :class="checks.number ? 'text-green-600' : ''">
              {{ checks.number ? '✓' : '✗' }} At least one number
            </li>
            <li :class="checks.match ? 'text-green-600' : ''">
              {{ checks.match ? '✓' : '✗' }} Passwords match
            </li>
          </ul>
        </div>

        <p v-if="errorMsg" class="text-sm text-red-600 bg-red-50 rounded-lg px-3 py-2">{{ errorMsg }}</p>

        <button type="submit" :disabled="loading || !allValid"
          class="w-full bg-blue-600 text-white font-semibold py-2.5 rounded-lg hover:bg-blue-700 transition-colors disabled:opacity-50">
          {{ loading ? 'Changing...' : 'Change Password' }}
        </button>
      </form>

      <!-- Reset after success -->
      <div v-if="success" class="px-6 pb-6">
        <button @click="reset" class="text-sm text-blue-600 hover:text-blue-700">Change password again</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { changePassword } from '@/api/selfservice'

const loading = ref(false)
const errorMsg = ref('')
const success = ref(false)
const showCurrent = ref(false)
const showNew = ref(false)
const showConfirm = ref(false)

const form = reactive({
  currentPassword: '',
  newPassword: '',
  confirmPassword: '',
})

const checks = computed(() => ({
  length: form.newPassword.length >= 8,
  uppercase: /[A-Z]/.test(form.newPassword),
  lowercase: /[a-z]/.test(form.newPassword),
  number: /[0-9]/.test(form.newPassword),
  match: form.newPassword.length > 0 && form.newPassword === form.confirmPassword,
}))

const allValid = computed(() => Object.values(checks.value).every(Boolean))

async function handleSubmit() {
  errorMsg.value = ''
  loading.value = true
  try {
    await changePassword(form.currentPassword, form.newPassword)
    success.value = true
  } catch (e) {
    errorMsg.value = e.response?.data?.detail || 'Current password is incorrect'
  } finally {
    loading.value = false
  }
}

function reset() {
  success.value = false
  form.currentPassword = ''
  form.newPassword = ''
  form.confirmPassword = ''
}
</script>
