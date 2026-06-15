<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div class="min-h-screen bg-gray-100 flex items-center justify-center p-4">
    <div class="bg-white rounded-2xl shadow-xl w-full max-w-md p-8">
      <div class="text-center mb-6">
        <h1 class="text-xl font-bold text-gray-900">Registration Status</h1>
        <p class="text-sm text-gray-500 mt-1">Track the progress of your account request</p>
      </div>

      <!-- Email input for auth -->
      <div v-if="!statusData && !loadError" class="space-y-4">
        <form @submit.prevent="loadStatus" class="space-y-3">
          <div>
            <label for="ss-status-email" class="block text-sm font-medium text-gray-700 mb-1">Email Address</label>
            <input id="ss-status-email" v-model="email" type="email" required placeholder="Enter the email you registered with"
              class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500" />
          </div>
          <button type="submit" :disabled="loading"
            class="w-full bg-blue-600 text-white font-semibold py-2.5 rounded-lg hover:bg-blue-700 transition-colors disabled:opacity-50">
            {{ loading ? 'Loading...' : 'Check Status' }}
          </button>
        </form>
      </div>

      <!-- Error -->
      <div v-if="loadError" class="text-center text-red-600 text-sm bg-red-50 rounded-lg p-4">
        {{ loadError }}
        <button @click="loadError = ''; statusData = null" class="block mx-auto mt-2 text-blue-600 text-sm">Try again</button>
      </div>

      <!-- Status card -->
      <div v-if="statusData" class="bg-gray-50 rounded-lg p-5 space-y-4">
        <div class="flex justify-between text-sm">
          <span class="text-gray-500">Request ID</span>
          <code class="bg-gray-200 px-1.5 py-0.5 rounded text-xs">{{ statusData.requestId }}</code>
        </div>
        <div class="flex justify-between text-sm">
          <span class="text-gray-500">Submitted</span>
          <span class="text-gray-900">{{ formatDate(statusData.createdAt) }}</span>
        </div>
        <div class="flex justify-between text-sm">
          <span class="text-gray-500">Email</span>
          <span class="text-gray-900">{{ statusData.email }}</span>
        </div>

        <hr class="border-gray-200" />

        <!-- Status timeline -->
        <div class="space-y-3">
          <TimelineStep :done="true" label="Request Submitted" :date="formatDate(statusData.createdAt)" />
          <div class="ml-3 w-px h-4 bg-gray-300"></div>

          <TimelineStep
            :done="statusData.emailVerified"
            :active="!statusData.emailVerified && statusData.status === 'PENDING_VERIFICATION'"
            label="Email Verified"
            :date="statusData.emailVerified ? 'Verified' : 'Pending'" />
          <div class="ml-3 w-px h-4 bg-gray-300"></div>

          <TimelineStep
            :done="statusData.status === 'APPROVED' || statusData.status === 'REJECTED'"
            :active="statusData.status === 'PENDING_APPROVAL'"
            :label="statusData.status === 'REJECTED' ? 'Request Rejected' : 'Approval'"
            :date="statusData.status === 'PENDING_APPROVAL' ? 'Waiting for administrator review' : (statusData.status === 'APPROVED' ? 'Approved' : (statusData.status === 'REJECTED' ? 'Rejected' : 'Pending'))"
            :error="statusData.status === 'REJECTED'" />
          <div class="ml-3 w-px h-4 bg-gray-200"></div>

          <TimelineStep
            :done="statusData.status === 'APPROVED'"
            label="Account Created"
            :date="statusData.status === 'APPROVED' ? 'Complete' : 'Pending'" />
        </div>
      </div>

      <div class="mt-6 text-center">
        <RouterLink to="/self-service/login" class="text-sm text-blue-600 hover:text-blue-700 font-medium">
          Back to Login
        </RouterLink>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute, RouterLink } from 'vue-router'
import { getRegistrationStatus } from '@/api/selfservice'

const route = useRoute()
const email = ref('')
const loading = ref(false)
const loadError = ref('')
const statusData = ref(null)

async function loadStatus() {
  loading.value = true
  loadError.value = ''
  try {
    const { data } = await getRegistrationStatus(route.params.requestId, email.value)
    statusData.value = data
  } catch (e) {
    loadError.value = e.response?.data?.detail || 'Request not found or email does not match.'
  } finally {
    loading.value = false
  }
}

function formatDate(dateStr) {
  if (!dateStr) return ''
  return new Date(dateStr).toLocaleString()
}

// Timeline step component
const TimelineStep = {
  props: ['done', 'active', 'label', 'date', 'error'],
  template: `
    <div class="flex items-start gap-3">
      <div class="w-6 h-6 rounded-full flex items-center justify-center shrink-0 mt-0.5"
        :class="done ? (error ? 'bg-red-500 text-white' : 'bg-green-500 text-white')
                      : (active ? 'bg-blue-500 text-white animate-pulse' : 'bg-gray-200 text-gray-600')">
        <svg v-if="done && !error" class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="3" d="M5 13l4 4L19 7"/>
        </svg>
        <svg v-else-if="error" class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"/>
        </svg>
        <svg v-else-if="active" class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"/>
        </svg>
        <svg v-else class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7"/>
        </svg>
      </div>
      <div>
        <p class="text-sm font-medium" :class="active ? 'text-blue-700' : (error ? 'text-red-700' : (done ? 'text-gray-900' : 'text-gray-500'))">{{ label }}</p>
        <p class="text-xs" :class="done || active ? 'text-gray-500' : 'text-gray-500'">{{ date }}</p>
      </div>
    </div>
  `
}
</script>
