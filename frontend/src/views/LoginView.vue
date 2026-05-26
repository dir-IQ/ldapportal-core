<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div class="min-h-screen bg-gradient-to-br from-gray-50 to-gray-200 flex items-center justify-center p-4">
    <div class="bg-white rounded-2xl shadow-xl w-full max-w-sm p-8 animate-fade-in">
      <div class="text-center mb-5">
        <svg class="w-12 h-12 mx-auto mb-3" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" fill="none">
          <rect width="32" height="32" rx="6" :fill="settings.primaryColour"/>
          <path d="M8 8h4v12H8z" fill="#fff"/>
          <path d="M8 20h10v3H8z" fill="#fff"/>
          <path d="M20 8h4v15h-4z" fill="#fff" opacity="0.55"/>
          <circle cx="22" cy="12" r="2.5" fill="#fff" opacity="0.55"/>
        </svg>
        <h1 class="text-2xl font-bold text-gray-900">{{ settings.appName }}</h1>
        <p class="text-sm text-gray-500 mt-1">Sign in to continue</p>
      </div>

      <!-- WebSEAL silent pre-auth probe spinner — brief, usually invisible -->
      <div v-if="probingWebseal" class="flex flex-col items-center gap-2 py-6 text-sm text-gray-500">
        <svg class="animate-spin h-5 w-5" fill="none" viewBox="0 0 24 24">
          <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" />
          <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
        </svg>
        Checking session…
      </div>

      <!-- Password login form (LOCAL or LDAP) -->
      <form v-else-if="showPasswordLogin" @submit.prevent="handleLogin" class="space-y-2">
        <FormField label="Username" v-model="form.username" autocomplete="username" placeholder="username" required />
        <FormField label="Password" type="password" autocomplete="current-password" v-model="form.password" placeholder="••••••••" required />

        <p v-if="errorMsg" class="text-sm text-red-600 bg-red-50 rounded-lg px-3 py-2">
          {{ errorMsg }}
        </p>

        <button
          type="submit"
          :disabled="loading"
          class="w-full text-white font-semibold py-2.5 rounded-lg transition-all disabled:opacity-50 hover:brightness-90 active:scale-[0.98] flex items-center justify-center gap-2"
          :style="{ backgroundColor: settings.primaryColour }"
        >
          <svg v-if="loading" class="animate-spin h-4 w-4" fill="none" viewBox="0 0 24 24">
            <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" />
            <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
          </svg>
          {{ loading ? 'Signing in...' : 'Sign in' }}
        </button>
      </form>

      <!-- Divider between password and SSO -->
      <div v-if="!probingWebseal && showPasswordLogin && showOidc" class="flex items-center gap-3 my-5">
        <hr class="flex-1 border-gray-200" />
        <span class="text-xs text-gray-500 uppercase">or</span>
        <hr class="flex-1 border-gray-200" />
      </div>

      <!-- SSO button (OIDC) -->
      <div v-if="!probingWebseal && showOidc">
        <p v-if="errorMsg && !showPasswordLogin" class="text-sm text-red-600 bg-red-50 rounded-lg px-3 py-2 mb-4">
          {{ errorMsg }}
        </p>
        <button
          @click="handleOidc"
          :disabled="oidcLoading"
          class="w-full flex items-center justify-center gap-2 border border-gray-300 text-gray-700 font-semibold py-2.5 rounded-lg hover:bg-gray-50 transition-colors disabled:opacity-50"
        >
          <svg class="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4"/>
            <polyline points="10 17 15 12 10 7"/>
            <line x1="15" y1="12" x2="3" y2="12"/>
          </svg>
          {{ oidcLoading ? 'Redirecting…' : 'Sign in with SSO' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useSettingsStore } from '@/stores/settings'
import { oidcAuthorize, websealAuthorize } from '@/api/auth'
import FormField from '@/components/FormField.vue'

const router   = useRouter()
const route    = useRoute()
const auth     = useAuthStore()
const settings = useSettingsStore()

// While the WebSEAL probe is in flight we hide the form to avoid flashing
// the LOCAL/LDAP login UI before the auto-redirect kicks in.
const probingWebseal = ref(true)

onMounted(async () => {
  await settings.init()
  await tryWebsealPreAuth()
  probingWebseal.value = false
})

/**
 * Probe GET /auth/webseal/authorize. Returns 200 (with JWT cookie set) when
 * the request landed here through a trusted WebSEAL junction carrying a
 * valid iv-user header. Any failure responds 401 with an empty body — we
 * treat that as "no WebSEAL session, fall back to the login form".
 */
async function tryWebsealPreAuth() {
  if (!settings.enabledAuthTypes.includes('WEBSEAL')) return
  try {
    await websealAuthorize()
  } catch {
    return  // 401 or network error — stay on the login form.
  }
  // Cookie is set. Re-hydrate principal from /auth/me, then redirect.
  try {
    await auth.reinit()
  } catch { /* non-fatal */ }
  navigateAfterLogin()
}

const form        = ref({ username: '', password: '' })
const loading     = ref(false)
const oidcLoading = ref(false)
const errorMsg    = ref('')

const showPasswordLogin = computed(() => {
  const types = settings.enabledAuthTypes
  return types.includes('LOCAL') || types.includes('LDAP')
})

const showOidc = computed(() => settings.enabledAuthTypes.includes('OIDC'))

async function handleLogin() {
  errorMsg.value = ''
  loading.value  = true
  try {
    await auth.login(form.value.username, form.value.password)
    navigateAfterLogin()
  } catch (err) {
    errorMsg.value = err.response?.data?.detail
      || err.response?.data?.message
      || 'Invalid username or password'
  } finally {
    loading.value = false
  }
}

async function handleOidc() {
  errorMsg.value    = ''
  oidcLoading.value = true
  try {
    const { data } = await oidcAuthorize()
    // Store state for callback validation
    sessionStorage.setItem('oidc_state', data.state)
    sessionStorage.setItem('oidc_redirect', route.query.redirect || '/')
    // Redirect to IdP
    window.location.href = data.authorizationUrl
  } catch (err) {
    errorMsg.value = err.response?.data?.detail
      || err.response?.data?.message
      || 'Failed to initiate SSO login'
    oidcLoading.value = false
  }
}

function navigateAfterLogin() {
  const rawRedirect = route.query.redirect
  const redirect = (typeof rawRedirect === 'string' && rawRedirect.startsWith('/') && rawRedirect !== '/login')
    ? rawRedirect
    : '/'
  router.push(redirect)
}
</script>
