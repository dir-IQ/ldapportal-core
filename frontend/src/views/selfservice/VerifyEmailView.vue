<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div class="min-h-screen bg-gray-100 flex items-center justify-center p-4">
    <div class="bg-white rounded-2xl shadow-xl w-full max-w-sm p-8 text-center">
      <!-- Verifying -->
      <div v-if="status === 'verifying'" class="space-y-4">
        <div class="w-12 h-12 border-4 border-blue-600 border-t-transparent rounded-full animate-spin mx-auto"></div>
        <p class="text-sm text-gray-600">Verifying your email address...</p>
      </div>

      <!-- Success -->
      <div v-if="status === 'success'" class="space-y-4">
        <div class="w-16 h-16 bg-green-100 text-green-600 rounded-full flex items-center justify-center mx-auto">
          <svg class="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7"/></svg>
        </div>
        <h1 class="text-xl font-bold text-gray-900">Email Verified</h1>
        <p class="text-sm text-gray-600">
          {{ resultMessage }}
        </p>
        <div class="bg-blue-50 rounded-lg p-3 text-sm text-blue-700">
          Status: <strong>{{ resultStatus }}</strong>
        </div>
        <RouterLink to="/self-service/login" class="inline-block mt-2 text-sm text-blue-600 hover:text-blue-700 font-medium">
          Back to Login
        </RouterLink>
      </div>

      <!-- Error / expired -->
      <div v-if="status === 'error'" class="space-y-4">
        <div class="w-16 h-16 bg-red-100 text-red-600 rounded-full flex items-center justify-center mx-auto">
          <svg class="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"/></svg>
        </div>
        <h1 class="text-xl font-bold text-gray-900">Verification Failed</h1>
        <p class="text-sm text-gray-600">
          {{ errorMessage }}
        </p>
        <RouterLink to="/register" class="inline-block mt-2 text-sm text-blue-600 hover:text-blue-700 font-medium">
          Register Again
        </RouterLink>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, RouterLink } from 'vue-router'
import { verifyEmail } from '@/api/selfservice'

const route = useRoute()
const status = ref('verifying')
const resultStatus = ref('')
const resultMessage = ref('')
const errorMessage = ref('')

onMounted(async () => {
  try {
    const { data } = await verifyEmail(route.params.token)
    status.value = 'success'
    resultStatus.value = data.status

    if (data.status === 'PENDING_APPROVAL') {
      resultMessage.value = 'Your email has been verified. Your account request has been submitted for approval. You\'ll receive an email once it\'s been reviewed.'
    } else if (data.status === 'APPROVED') {
      resultMessage.value = 'Your email has been verified and your account has been created! You can now log in.'
    } else if (data.status === 'ALREADY_VERIFIED') {
      resultMessage.value = 'This email has already been verified.'
    } else {
      resultMessage.value = 'Your email has been verified successfully.'
    }
  } catch (e) {
    status.value = 'error'
    errorMessage.value = e.response?.data?.detail
      || 'This verification link is invalid or has expired. Please submit a new registration request.'
  }
})
</script>
