<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div class="min-h-screen bg-gray-100 flex items-center justify-center p-4">
    <div class="bg-white rounded-2xl shadow-xl w-full max-w-sm p-8 text-center">
      <div v-if="!errorMsg" class="space-y-4">
        <div class="animate-spin w-8 h-8 border-3 border-blue-600 border-t-transparent rounded-full mx-auto"></div>
        <p class="text-sm text-gray-500">Completing sign in…</p>
      </div>
      <div v-else class="space-y-4">
        <p class="text-sm text-red-600 bg-red-50 rounded-lg px-3 py-2">{{ errorMsg }}</p>
        <a href="/login" class="text-sm text-blue-600 hover:text-blue-800 font-medium">Back to login</a>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { oidcCallback } from '@/api/auth'

const router   = useRouter()
const auth     = useAuthStore()
const errorMsg = ref('')

onMounted(async () => {
  const params = new URLSearchParams(window.location.search)
  const code   = params.get('code')
  const state  = params.get('state')

  if (!code || !state) {
    errorMsg.value = params.get('error_description') || params.get('error') || 'Missing authorization code'
    return
  }

  // Validate state matches what we stored
  const storedState = sessionStorage.getItem('oidc_state')
  if (state !== storedState) {
    errorMsg.value = 'Invalid state parameter — possible CSRF attack'
    return
  }

  try {
    const { data } = await oidcCallback(code, state)
    // Populate auth store with principal from response
    auth.principal = {
      id:          data.id,
      username:    data.username,
      accountType: data.accountType,
    }
    auth.initialized = true

    const redirect = sessionStorage.getItem('oidc_redirect') || '/'
    sessionStorage.removeItem('oidc_state')
    sessionStorage.removeItem('oidc_redirect')
    router.push(redirect)
  } catch (err) {
    errorMsg.value = err.response?.data?.detail
      || err.response?.data?.message
      || 'SSO authentication failed'
  }
})
</script>
