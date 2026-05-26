<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup lang="ts">
import { ref, reactive, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave } from 'vue-router'
import { useNotificationStore } from '@/stores/notifications'
import { useSettingsStore } from '@/stores/settings'
import { useConfirm } from '@/composables/useConfirm'
import { getSettings, updateSettings, testSiem } from '@/api/settings'
import SettingsSidebar from './SettingsSidebar.vue'
import { findSection, DEFAULT_SECTION_ID } from './sectionsRegistry'

// Shape of the raw settings response from the server (used for *Configured hints).
interface SettingsData {
  appName?: string
  logoUrl?: string | null
  primaryColour?: string | null
  secondaryColour?: string | null
  directorySearchInlineEditEnabled?: boolean
  sessionTimeoutMinutes?: number
  smtpHost?: string | null
  smtpPort?: number | null
  smtpSenderAddress?: string | null
  smtpUsername?: string | null
  smtpUseTls?: boolean
  s3EndpointUrl?: string | null
  s3BucketName?: string | null
  s3AccessKey?: string | null
  s3Region?: string | null
  s3PresignedUrlTtlHours?: number
  enabledAuthTypes?: string[]
  ldapAuthHost?: string | null
  ldapAuthPort?: number | null
  ldapAuthSslMode?: string | null
  ldapAuthTrustAllCerts?: boolean
  ldapAuthTrustedCertPem?: string | null
  ldapAuthBindDn?: string | null
  ldapAuthUserSearchBase?: string | null
  ldapAuthBindDnPattern?: string | null
  oidcIssuerUrl?: string | null
  oidcClientId?: string | null
  oidcScopes?: string | null
  oidcUsernameClaim?: string | null
  oidcRedirectUri?: string | null
  websealTrustedProxies?: string | null
  websealUserHeader?: string | null
  websealGroupsHeader?: string | null
  websealLogoutUrl?: string | null
  siemEnabled?: boolean
  siemProtocol?: string | null
  siemHost?: string | null
  siemPort?: number | null
  siemFormat?: string | null
  webhookUrl?: string | null
}

// The reactive form mirrors the response shape but secret fields are always
// written as null (never echoed back by the server) and string fields that
// arrive as null are coerced to '' for input binding.
interface SettingsForm {
  appName: string
  logoUrl: string
  primaryColour: string
  secondaryColour: string
  directorySearchInlineEditEnabled: boolean
  sessionTimeoutMinutes: number
  smtpHost: string
  smtpPort: number
  smtpSenderAddress: string
  smtpUsername: string
  smtpPassword: string | null
  smtpUseTls: boolean
  s3EndpointUrl: string
  s3BucketName: string
  s3AccessKey: string
  s3SecretKey: string | null
  s3Region: string
  s3PresignedUrlTtlHours: number
  enabledAuthTypes: string[]
  ldapAuthHost: string
  ldapAuthPort: number | null
  ldapAuthSslMode: string
  ldapAuthTrustAllCerts: boolean
  ldapAuthTrustedCertPem: string
  ldapAuthBindDn: string
  ldapAuthBindPassword: string | null
  ldapAuthUserSearchBase: string
  ldapAuthBindDnPattern: string
  oidcIssuerUrl: string
  oidcClientId: string
  oidcClientSecret: string | null
  oidcScopes: string
  oidcUsernameClaim: string
  oidcRedirectUri: string
  websealTrustedProxies: string
  websealUserHeader: string
  websealGroupsHeader: string
  websealLogoutUrl: string
  siemEnabled: boolean
  siemProtocol: string
  siemHost: string
  siemPort: number | null
  siemFormat: string
  siemAuthToken: string | null
  webhookUrl: string
  webhookAuthHeader: string | null
}

interface SiemTestResult {
  ok: boolean
  message: string
}

const route  = useRoute()
const router = useRouter()
const notif  = useNotificationStore()
const brandingStore = useSettingsStore()
const confirm = useConfirm()

const loading         = ref<boolean>(false)
const saving          = ref<boolean>(false)
const settings        = ref<SettingsData | null>(null)  // raw server response (for *Configured hints)
const savedSnapshot   = ref<string | null>(null)        // JSON-stringified baseline for dirty tracking
const testingSiem     = ref<boolean>(false)
const siemTestResult  = ref<SiemTestResult | null>(null)

const form = reactive<SettingsForm>(defaultForm())
const sidebarRef = ref<InstanceType<typeof SettingsSidebar> | null>(null)

// Parsed copy of the last-saved snapshot so the sidebar can diff per-section
// without re-parsing on every render.
const savedForm = computed<SettingsForm | null>(() => {
  return savedSnapshot.value ? JSON.parse(savedSnapshot.value) : null
})

// ── Active section (driven by route param, defaults to 'branding') ─────────
const activeId = computed<string>(() => {
  const param = route.params.section
  return (Array.isArray(param) ? param[0] : param) || DEFAULT_SECTION_ID
})
const activeSection = computed(() => findSection(activeId.value))

function selectSection(id: string): void {
  // Same-route param change — doesn't trigger beforeRouteLeave, so users can
  // roam the sidebar without unsaved-changes warnings while still editing.
  router.push({ name: 'settings', params: { section: id } })
}

// ── Dirty tracking ─────────────────────────────────────────────────────────
const isDirty = computed<boolean>(() => {
  if (!savedSnapshot.value) return false
  return JSON.stringify(form) !== savedSnapshot.value
})

function snapshot(): void {
  savedSnapshot.value = JSON.stringify(form)
}

// ── Defaults ───────────────────────────────────────────────────────────────
function defaultForm(): SettingsForm {
  return {
    appName: 'LDAP Portal',
    logoUrl: '',
    primaryColour: '#3b82f6',
    secondaryColour: '#64748b',
    directorySearchInlineEditEnabled: true,
    sessionTimeoutMinutes: 60,
    smtpHost: '',
    smtpPort: 587,
    smtpSenderAddress: '',
    smtpUsername: '',
    smtpPassword: null,
    smtpUseTls: true,
    s3EndpointUrl: '',
    s3BucketName: '',
    s3AccessKey: '',
    s3SecretKey: null,
    s3Region: '',
    s3PresignedUrlTtlHours: 24,
    enabledAuthTypes: ['LOCAL'],
    ldapAuthHost: '',
    ldapAuthPort: null,
    ldapAuthSslMode: '',
    ldapAuthTrustAllCerts: false,
    ldapAuthTrustedCertPem: '',
    ldapAuthBindDn: '',
    ldapAuthBindPassword: null,
    ldapAuthUserSearchBase: '',
    ldapAuthBindDnPattern: '',
    oidcIssuerUrl: '',
    oidcClientId: '',
    oidcClientSecret: null,
    oidcScopes: 'openid profile email',
    oidcUsernameClaim: 'preferred_username',
    oidcRedirectUri: '',
    websealTrustedProxies: '',
    websealUserHeader: 'iv-user',
    websealGroupsHeader: 'iv-groups',
    websealLogoutUrl: '/pkmslogout',
    siemEnabled: false,
    siemProtocol: '',
    siemHost: '',
    siemPort: null,
    siemFormat: '',
    siemAuthToken: null,
    webhookUrl: '',
    webhookAuthHeader: null,
  }
}

// ── Load / Save ───────────────────────────────────────────────────────────
async function loadSettings(): Promise<void> {
  loading.value = true
  try {
    const { data } = await getSettings()
    settings.value = data as SettingsData
    Object.assign(form, {
      appName:                data.appName ?? 'LDAP Portal',
      logoUrl:                data.logoUrl ?? '',
      primaryColour:          data.primaryColour ?? '#3b82f6',
      secondaryColour:        data.secondaryColour ?? '#64748b',
      directorySearchInlineEditEnabled: data.directorySearchInlineEditEnabled ?? true,
      sessionTimeoutMinutes:  data.sessionTimeoutMinutes ?? 60,
      smtpHost:               data.smtpHost ?? '',
      smtpPort:               data.smtpPort ?? 587,
      smtpSenderAddress:      data.smtpSenderAddress ?? '',
      smtpUsername:           data.smtpUsername ?? '',
      smtpPassword:           null,
      smtpUseTls:             data.smtpUseTls ?? true,
      s3EndpointUrl:          data.s3EndpointUrl ?? '',
      s3BucketName:           data.s3BucketName ?? '',
      s3AccessKey:            data.s3AccessKey ?? '',
      s3SecretKey:            null,
      s3Region:               data.s3Region ?? '',
      s3PresignedUrlTtlHours: data.s3PresignedUrlTtlHours ?? 24,
      enabledAuthTypes:       data.enabledAuthTypes ? [...data.enabledAuthTypes] : ['LOCAL'],
      ldapAuthHost:           data.ldapAuthHost ?? '',
      ldapAuthPort:           data.ldapAuthPort ?? null,
      ldapAuthSslMode:        data.ldapAuthSslMode ?? '',
      ldapAuthTrustAllCerts:  data.ldapAuthTrustAllCerts ?? false,
      ldapAuthTrustedCertPem: data.ldapAuthTrustedCertPem ?? '',
      ldapAuthBindDn:         data.ldapAuthBindDn ?? '',
      ldapAuthBindPassword:   null,
      ldapAuthUserSearchBase: data.ldapAuthUserSearchBase ?? '',
      ldapAuthBindDnPattern:  data.ldapAuthBindDnPattern ?? '',
      oidcIssuerUrl:          data.oidcIssuerUrl ?? '',
      oidcClientId:           data.oidcClientId ?? '',
      oidcClientSecret:       null,
      oidcScopes:             data.oidcScopes ?? 'openid profile email',
      oidcUsernameClaim:      data.oidcUsernameClaim ?? 'preferred_username',
      oidcRedirectUri:        data.oidcRedirectUri ?? '',
      websealTrustedProxies:  data.websealTrustedProxies ?? '',
      websealUserHeader:      data.websealUserHeader ?? 'iv-user',
      websealGroupsHeader:    data.websealGroupsHeader ?? 'iv-groups',
      websealLogoutUrl:       data.websealLogoutUrl ?? '/pkmslogout',
      siemEnabled:            data.siemEnabled ?? false,
      siemProtocol:           data.siemProtocol ?? '',
      siemHost:               data.siemHost ?? '',
      siemPort:               data.siemPort ?? null,
      siemFormat:             data.siemFormat ?? '',
      siemAuthToken:          null,
      webhookUrl:             data.webhookUrl ?? '',
      webhookAuthHeader:      null,
    })
    snapshot()
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    notif.error(err.response?.data?.detail || err.message || '')
  } finally {
    loading.value = false
  }
}

async function doSave(): Promise<void> {
  if (!form.enabledAuthTypes || form.enabledAuthTypes.length === 0) {
    notif.error('At least one authentication method must be enabled.')
    return
  }
  saving.value = true
  try {
    await updateSettings({
      appName:               form.appName,
      logoUrl:               form.logoUrl        || null,
      primaryColour:         form.primaryColour  || null,
      secondaryColour:       form.secondaryColour || null,
      directorySearchInlineEditEnabled: form.directorySearchInlineEditEnabled,
      sessionTimeoutMinutes: form.sessionTimeoutMinutes,
      smtpHost:              form.smtpHost       || null,
      smtpPort:              form.smtpPort       || null,
      smtpSenderAddress:     form.smtpSenderAddress || null,
      smtpUsername:          form.smtpUsername   || null,
      smtpPassword:          form.smtpPassword,
      smtpUseTls:            form.smtpUseTls,
      s3EndpointUrl:         form.s3EndpointUrl  || null,
      s3BucketName:          form.s3BucketName   || null,
      s3AccessKey:           form.s3AccessKey    || null,
      s3SecretKey:           form.s3SecretKey,
      s3Region:              form.s3Region       || null,
      s3PresignedUrlTtlHours: form.s3PresignedUrlTtlHours ?? 24,
      enabledAuthTypes:      form.enabledAuthTypes,
      ldapAuthHost:          form.ldapAuthHost       || null,
      ldapAuthPort:          form.ldapAuthPort       || null,
      ldapAuthSslMode:       form.ldapAuthSslMode    || null,
      ldapAuthTrustAllCerts: form.ldapAuthTrustAllCerts,
      ldapAuthTrustedCertPem: form.ldapAuthTrustedCertPem || null,
      ldapAuthBindDn:        form.ldapAuthBindDn     || null,
      ldapAuthBindPassword:  form.ldapAuthBindPassword,
      ldapAuthUserSearchBase: form.ldapAuthUserSearchBase || null,
      ldapAuthBindDnPattern: form.ldapAuthBindDnPattern || null,
      oidcIssuerUrl:         form.oidcIssuerUrl      || null,
      oidcClientId:          form.oidcClientId       || null,
      oidcClientSecret:      form.oidcClientSecret,
      oidcScopes:            form.oidcScopes         || null,
      oidcUsernameClaim:     form.oidcUsernameClaim  || null,
      oidcRedirectUri:       form.oidcRedirectUri    || null,
      websealTrustedProxies: form.websealTrustedProxies || null,
      websealUserHeader:     form.websealUserHeader  || null,
      websealGroupsHeader:   form.websealGroupsHeader || null,
      websealLogoutUrl:      form.websealLogoutUrl   || null,
      siemEnabled:           form.siemEnabled,
      siemProtocol:          form.siemProtocol       || null,
      siemHost:              form.siemHost           || null,
      siemPort:              form.siemPort           || null,
      siemFormat:            form.siemFormat         || null,
      siemAuthToken:         form.siemAuthToken,
      webhookUrl:            form.webhookUrl         || null,
      webhookAuthHeader:     form.webhookAuthHeader,
    })
    notif.success('Settings saved')
    // Sync branding store so sidebar + page title update immediately.
    brandingStore.apply(form)
    await loadSettings()
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    notif.error(err.response?.data?.detail || err.message || '')
  } finally {
    saving.value = false
  }
}

function doReset(): void {
  loadSettings()
}

async function doTestSiem(): Promise<void> {
  testingSiem.value = true
  siemTestResult.value = null
  try {
    const { data } = await testSiem()
    const delivery = (data as { delivery?: string }).delivery
    siemTestResult.value = {
      ok: !delivery?.includes('failed') && !delivery?.includes('not enabled'),
      message: delivery ?? '',
    }
  } catch (e) {
    const err = e as { response?: { data?: { detail?: string } }, message?: string }
    siemTestResult.value = { ok: false, message: err.response?.data?.detail || err.message || '' }
  } finally {
    testingSiem.value = false
  }
}

// ── Unsaved-changes guards ─────────────────────────────────────────────────
// vue-router awaits a Promise<boolean | false> returned from the
// guard, so the await here is well-supported without additional
// hooks. Returning false aborts the navigation.
onBeforeRouteLeave(async () => {
  if (!isDirty.value) return
  const proceed = await confirm({
    title: 'Unsaved changes',
    message: 'You have unsaved changes. Leave anyway?',
    confirmLabel: 'Leave',
    danger: true,
  })
  if (!proceed) return false
})

function onBeforeUnload(e: BeforeUnloadEvent): void {
  if (isDirty.value) {
    e.preventDefault()
    e.returnValue = ''
  }
}

// Cmd/Ctrl+K focuses the sidebar's search input.
function onKeydown(e: KeyboardEvent): void {
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    sidebarRef.value?.focusSearch()
  }
}

onMounted(() => {
  loadSettings()
  window.addEventListener('beforeunload', onBeforeUnload)
  window.addEventListener('keydown', onKeydown)
})
onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', onBeforeUnload)
  window.removeEventListener('keydown', onKeydown)
})

// Ensure the URL matches the active section on first load (e.g. if someone
// lands on /settings with no param, normalise to /settings/branding).
watch(activeId, (id: string) => {
  if (!route.params.section) {
    router.replace({ name: 'settings', params: { section: id } })
  }
}, { immediate: true })
</script>

<template>
  <div class="flex h-[calc(100vh-0px)]">
    <SettingsSidebar ref="sidebarRef" :active-id="activeId" :form="form" :saved-form="savedForm ?? undefined"
                     @select="selectSection" />

    <div class="flex-1 flex flex-col overflow-hidden">
      <!-- Sticky header with title + global Save / Reset -->
      <header class="shrink-0 bg-white border-b border-gray-200 px-6 py-4 flex items-center justify-between">
        <div>
          <h1 class="text-xl font-bold text-gray-900">Application Settings</h1>
          <p class="text-xs text-gray-500 mt-0.5">{{ activeSection?.label }}</p>
        </div>
        <div class="flex items-center gap-2">
          <span v-if="isDirty" class="text-xs text-amber-600">Unsaved changes</span>
          <button type="button" @click="doReset" :disabled="!isDirty || saving"
                  class="btn-neutral btn-compact">
            Reset
          </button>
          <button type="button" @click="doSave" :disabled="!isDirty || saving"
                  class="btn-primary btn-compact">
            {{ saving ? 'Saving…' : 'Save' }}
          </button>
        </div>
      </header>

      <!-- Scrollable section content -->
      <div class="flex-1 overflow-y-auto p-6">
        <div v-if="loading" class="text-sm text-gray-500">Loading…</div>
        <form v-else @submit.prevent="doSave" class="max-w-3xl">
          <component
            :is="activeSection?.component"
            :form="form"
            :settings="settings"
            :testing="testingSiem"
            :test-result="siemTestResult"
            @test="doTestSiem"
          />
        </form>
      </div>
    </div>
  </div>
</template>

<style scoped>
@reference "tailwindcss";
</style>
