<!-- SPDX-License-Identifier: Apache-2.0 -->
<template>
  <div class="min-h-screen bg-gray-50 flex flex-col items-center py-10 px-4">
    <div class="w-full max-w-2xl">
      <!-- Header -->
      <div class="relative text-center mb-8">
        <h1 class="text-2xl font-bold text-gray-900">{{ settings.appName }}</h1>
        <p class="text-sm text-gray-500 mt-1">First-Run Setup Wizard</p>
        <button
          v-if="!confirmingSkip"
          type="button"
          @click="confirmingSkip = true"
          class="absolute top-0 right-0 text-xs text-gray-500 hover:text-gray-700 underline"
        >Skip wizard</button>
      </div>

      <!-- Skip confirmation -->
      <div
        v-if="confirmingSkip"
        class="bg-amber-50 border border-amber-200 rounded-lg px-4 py-3 mb-6 text-sm"
      >
        <p class="text-amber-900 font-medium mb-1">Skip the setup wizard?</p>
        <p class="text-amber-800 mb-3">
          You can configure directories, provisioning profiles, and access reviews
          later from the admin UI. Anything you've already entered on this page
          won't be saved.
        </p>
        <div class="flex gap-2 justify-end">
          <button
            type="button"
            @click="confirmingSkip = false"
            :disabled="saving"
            class="text-sm text-amber-900 hover:text-amber-700 px-3 py-1.5"
          >Stay in wizard</button>
          <button
            type="button"
            @click="skipWizard"
            :disabled="saving"
            class="text-sm bg-amber-600 hover:bg-amber-700 text-white px-3 py-1.5 rounded"
          >{{ saving ? 'Skipping...' : 'Skip and go to dashboard' }}</button>
        </div>
      </div>

      <!-- Step indicator. visibleSteps drops step 5 (access review) on
           community deployments since access-review campaigns are an
           ee/governance feature; the canonical `step` value still tracks
           1..6 but the indicator renders position (i+1), so the user
           never sees a missing slot. -->
      <div class="flex items-center justify-center gap-2 mb-8">
        <template v-for="(s, i) in visibleSteps" :key="s">
          <div
            class="w-8 h-8 rounded-full flex items-center justify-center text-sm font-medium transition-colors"
            :class="s === step
              ? 'bg-blue-600 text-white'
              : visibleSteps.indexOf(s) < visibleSteps.indexOf(step)
                ? 'bg-green-500 text-white'
                : 'bg-gray-200 text-gray-600'"
          >{{ i + 1 }}</div>
          <div v-if="i < visibleSteps.length - 1" class="w-8 h-0.5"
               :class="visibleSteps.indexOf(s) < visibleSteps.indexOf(step) ? 'bg-green-400' : 'bg-gray-200'" />
        </template>
      </div>

      <!-- Step content -->
      <div class="bg-white rounded-xl shadow-sm border border-gray-200 p-6">

        <!-- ── Step 1: Welcome ────────────────────────────────────────── -->
        <template v-if="step === 1">
          <h2 class="text-xl font-semibold text-gray-900 mb-3">Welcome</h2>
          <p class="text-sm text-gray-600 mb-4">
            Let's get your LDAP directory connected in a few minutes. This wizard will walk you through:
          </p>
          <ol class="text-sm text-gray-600 list-decimal list-inside space-y-1 mb-6">
            <li>Connecting to your LDAP directory</li>
            <li>Verifying the connection works</li>
            <li>Creating a provisioning profile</li>
            <li v-if="auth.isComplianceEnabled">Optionally starting your first access review</li>
          </ol>
          <div class="flex justify-end">
            <button @click="step = 2" class="btn-primary">Get Started</button>
          </div>
        </template>

        <!-- ── Step 2: Connect LDAP ───────────────────────────────────── -->
        <template v-if="step === 2">
          <h2 class="text-xl font-semibold text-gray-900 mb-4">Connect LDAP Directory</h2>
          <div class="space-y-4">
            <div>
              <label for="setup-display-name" class="label">Display Name *</label>
              <input id="setup-display-name" v-model="dir.displayName" type="text" class="input w-full" placeholder="e.g. Corporate Directory" />
            </div>
            <div class="grid grid-cols-2 gap-4">
              <div>
                <label for="setup-host" class="label">Host *</label>
                <input id="setup-host" v-model="dir.host" type="text" class="input w-full" placeholder="ldap.example.com" />
              </div>
              <div>
                <label for="setup-port" class="label">Port</label>
                <input id="setup-port" v-model.number="dir.port" type="number" class="input w-full" />
              </div>
            </div>
            <div>
              <label for="setup-ssl-mode" class="label">SSL Mode</label>
              <select id="setup-ssl-mode" v-model="dir.sslMode" class="input w-full">
                <option value="NONE">None</option>
                <option value="LDAPS">LDAPS</option>
                <option value="STARTTLS">STARTTLS</option>
              </select>
            </div>
            <div>
              <label for="setup-bind-dn" class="label">Bind DN *</label>
              <input id="setup-bind-dn" v-model="dir.bindDn" type="text" class="input w-full" placeholder="cn=admin,dc=example,dc=com" />
            </div>
            <div>
              <label for="setup-bind-password" class="label">Bind Password *</label>
              <input id="setup-bind-password" v-model="dir.bindPassword" type="password" autocomplete="new-password" class="input w-full" />
            </div>
            <div>
              <label for="setup-base-dn" class="label">Base DN *</label>
              <input id="setup-base-dn" v-model="dir.baseDn" type="text" class="input w-full" placeholder="dc=example,dc=com" />
            </div>
            <div class="flex items-center gap-2">
              <input v-model="dir.trustAllCerts" type="checkbox" id="trustCerts" class="rounded" />
              <label for="trustCerts" class="text-sm text-gray-600">Trust all certificates (dev/lab only)</label>
            </div>

            <!-- Test result -->
            <div v-if="testResult" class="rounded-lg px-4 py-3 text-sm" :class="testResult.ok ? 'bg-green-50 text-green-700 border border-green-200' : 'bg-red-50 text-red-700 border border-red-200'">
              {{ testResult.message }}
            </div>

            <div v-if="error" class="bg-red-50 border border-red-200 text-red-700 rounded-lg px-4 py-3 text-sm">{{ error }}</div>
          </div>

          <div class="flex justify-between mt-6">
            <button @click="step = 1" class="btn-neutral">Back</button>
            <div class="flex gap-3">
              <button @click="testConnection" :disabled="!canTest || testing" class="btn-secondary">
                {{ testing ? 'Testing...' : 'Test Connection' }}
              </button>
              <button @click="saveDirectory" :disabled="!canTest || saving" class="btn-primary">
                {{ saving ? 'Saving...' : 'Save & Continue' }}
              </button>
            </div>
          </div>
        </template>

        <!-- ── Step 3: Verify Connection ──────────────────────────────── -->
        <template v-if="step === 3">
          <h2 class="text-xl font-semibold text-gray-900 mb-4">Verify Connection</h2>
          <div v-if="verifying" class="text-sm text-gray-500 py-8 text-center">Querying directory...</div>
          <div v-else class="space-y-4">
            <div class="bg-gray-50 rounded-lg p-4">
              <p class="text-sm font-medium text-gray-700">{{ dir.displayName }}</p>
              <p class="text-xs text-gray-500">{{ dir.host }}:{{ dir.port }} &middot; Base DN: {{ dir.baseDn }}</p>
            </div>
            <div class="grid grid-cols-2 gap-4">
              <div class="bg-blue-50 rounded-lg p-4 text-center">
                <p class="text-2xl font-bold text-blue-700">{{ verifyData.userCount }}</p>
                <p class="text-xs text-gray-600">Users found</p>
              </div>
              <div class="bg-blue-50 rounded-lg p-4 text-center">
                <p class="text-2xl font-bold text-blue-700">{{ verifyData.groupCount }}</p>
                <p class="text-xs text-gray-600">Groups found</p>
              </div>
            </div>
            <div v-if="verifyError"
                 class="bg-red-50 border border-red-200 text-red-700 rounded-lg px-4 py-3 text-sm">
              Failed to query directory: {{ verifyError }}
            </div>
            <div v-else-if="verifyData.userCount === 0 && verifyData.groupCount === 0"
                 class="bg-yellow-50 border border-yellow-200 text-yellow-700 rounded-lg px-4 py-3 text-sm">
              No entries found. Check your base DN and try again.
            </div>
            <div v-if="verifyData.sampleUsers.length" class="text-sm">
              <p class="font-medium text-gray-700 mb-1">Sample users:</p>
              <ul class="text-xs text-gray-500 space-y-0.5">
                <li v-for="u in verifyData.sampleUsers" :key="u">{{ u }}</li>
              </ul>
            </div>
          </div>
          <div class="flex justify-between mt-6">
            <button @click="step = 2" class="btn-neutral">Back</button>
            <button @click="step = 4" :disabled="verifying" class="btn-primary">Continue</button>
          </div>
        </template>

        <!-- ── Step 4: Create Profile ─────────────────────────────────── -->
        <template v-if="step === 4">
          <h2 class="text-xl font-semibold text-gray-900 mb-4">Create Provisioning Profile</h2>
          <div class="space-y-4">
            <div>
              <label for="setup-profile-name" class="label">Profile Name *</label>
              <input id="setup-profile-name" v-model="profile.name" type="text" class="input w-full" />
            </div>
            <div>
              <label class="label">Target OU *</label>
              <DnPicker v-model="profile.targetOuDn" :directory-id="directoryId" />
            </div>
            <div>
              <label class="label">Object Classes *</label>
              <div class="flex flex-wrap gap-1 mb-2">
                <span v-for="oc in profile.objectClasses" :key="oc"
                      class="inline-flex items-center gap-1 bg-blue-100 text-blue-700 text-xs px-2 py-1 rounded-full">
                  {{ oc }}
                  <button @click="profile.objectClasses = profile.objectClasses.filter(x => x !== oc)" class="hover:text-blue-900">&times;</button>
                </span>
              </div>
              <select @change="addObjectClass($event)" aria-label="Add object class" class="input w-full text-sm">
                <option value="">+ Add object class</option>
                <option v-for="oc in availableObjectClasses" :key="oc" :value="oc">{{ oc }}</option>
              </select>
            </div>
            <div>
              <label for="setup-rdn-attribute" class="label">RDN Attribute</label>
              <input id="setup-rdn-attribute" v-model="profile.rdnAttribute" type="text" class="input w-full" />
            </div>
            <div v-if="error" class="bg-red-50 border border-red-200 text-red-700 rounded-lg px-4 py-3 text-sm">{{ error }}</div>

            <!-- Discovery Wizard callout -->
            <div class="bg-indigo-50 border border-indigo-200 rounded-lg px-4 py-3">
              <p class="text-sm text-indigo-800">
                <span class="font-medium">Migrating an existing directory with multiple OUs?</span>
                Use the Discovery Wizard to auto-generate profiles from your directory structure.
              </p>
              <p class="text-xs text-indigo-600 mt-1">Available from Directories management after setup is complete.</p>
            </div>
          </div>
          <div class="flex justify-between mt-6">
            <button @click="step = 3" class="btn-neutral">Back</button>
            <div class="flex gap-3">
              <button @click="step = stepAfterProfile" class="btn-secondary">Skip</button>
              <button @click="saveProfile" :disabled="!canSaveProfile || saving" class="btn-primary">
                {{ saving ? 'Saving...' : 'Save & Continue' }}
              </button>
            </div>
          </div>
        </template>

        <!-- ── Step 5: Access Review (Optional) ──────────────────────── -->
        <template v-if="step === 5">
          <h2 class="text-xl font-semibold text-gray-900 mb-2">First Access Review</h2>
          <p class="text-sm text-gray-500 mb-4">Optional — create an access review campaign to audit group memberships.</p>
          <div class="space-y-4">
            <div>
              <label for="setup-campaign-name" class="label">Campaign Name</label>
              <input id="setup-campaign-name" v-model="campaign.name" type="text" class="input w-full" />
            </div>
            <div>
              <label for="setup-deadline-days" class="label">Deadline (days)</label>
              <input id="setup-deadline-days" v-model.number="campaign.deadlineDays" type="number" min="1" class="input w-full" />
            </div>
            <div>
              <label class="label">Group to Review</label>
              <DnPicker v-model="campaign.groupDn" :directory-id="directoryId" scope="group" />
            </div>
            <div v-if="error" class="bg-red-50 border border-red-200 text-red-700 rounded-lg px-4 py-3 text-sm">{{ error }}</div>
          </div>
          <div class="flex justify-between mt-6">
            <button @click="step = 4" class="btn-neutral">Back</button>
            <div class="flex gap-3">
              <button @click="step = 6" class="btn-secondary">Skip</button>
              <button @click="createReview" :disabled="!campaign.groupDn || campaign.deadlineDays < 1 || saving" class="btn-primary">
                {{ saving ? 'Creating...' : 'Create Campaign & Continue' }}
              </button>
            </div>
          </div>
        </template>

        <!-- ── Step 6: Done ───────────────────────────────────────────── -->
        <template v-if="step === 6">
          <h2 class="text-xl font-semibold text-gray-900 mb-4">Setup Complete</h2>
          <div class="space-y-3 mb-6">
            <div class="flex items-center gap-2 text-sm">
              <span class="text-green-500 font-bold">&#10003;</span>
              <span class="text-gray-700">Directory "<strong>{{ dir.displayName }}</strong>" connected at {{ dir.host }}:{{ dir.port }}</span>
            </div>
            <div class="flex items-center gap-2 text-sm">
              <span class="text-green-500 font-bold">&#10003;</span>
              <span class="text-gray-700">Profile "<strong>{{ profile.name }}</strong>" targeting {{ profile.targetOuDn }}</span>
            </div>
            <div class="flex items-center gap-2 text-sm">
              <span v-if="campaignId" class="text-green-500 font-bold">&#10003;</span>
              <span v-else class="text-gray-500 font-bold">&mdash;</span>
              <span class="text-gray-700">
                <template v-if="campaignId">Access review "<strong>{{ campaign.name }}</strong>" created</template>
                <template v-else>Access review skipped</template>
              </span>
            </div>
          </div>

          <div class="bg-indigo-50 border border-indigo-200 rounded-lg px-4 py-3 mb-6">
            <p class="text-sm text-indigo-800">
              <span class="font-medium">Want more profiles?</span>
              Use the Discovery Wizard from the Directories management page to auto-generate them from your directory structure.
            </p>
          </div>

          <div v-if="error" class="bg-red-50 border border-red-200 text-red-700 rounded-lg px-4 py-3 text-sm mb-4">{{ error }}</div>

          <div class="flex justify-end">
            <button @click="completeSetup" :disabled="saving" class="btn-primary">
              {{ saving ? 'Completing...' : 'Complete Setup & Go to Dashboard' }}
            </button>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useSettingsStore } from '@/stores/settings'
import { testDirectory, createDirectory, updateDirectory } from '@/api/directories'
import { createProfile } from '@/api/profiles'
import { listObjectClasses } from '@/api/schema'
import { createCampaign } from '@/ee'
import { completeSetup as apiCompleteSetup } from '@/api/settings'
import DnPicker from '@/components/DnPicker.vue'

const router = useRouter()
const auth = useAuthStore()
const settings = useSettingsStore()

type SslMode = 'NONE' | 'LDAPS' | 'STARTTLS'
interface TestResult { ok: boolean; message: string }

const step = ref(1)
// Step 5 is the "First Access Review" prompt — an ee/governance
// feature that the backend rejects at the service layer on community
// deployments. Hide it from the wizard rather than letting the user
// fill it out and hit a confusing 403.
const visibleSteps = computed<number[]>(() =>
  auth.isComplianceEnabled ? [1, 2, 3, 4, 5, 6] : [1, 2, 3, 4, 6]
)
// Step that follows "Create Profile" depending on entitlement: jumps
// straight to "Done" on community.
const stepAfterProfile = computed(() => auth.isComplianceEnabled ? 5 : 6)
const saving = ref(false)
const testing = ref(false)
const error = ref('')
const testResult = ref<TestResult | null>(null)
const confirmingSkip = ref(false)

// ── Step 2: Directory ──────────────────────────────────────────────────
const dir = ref({
  displayName: '',
  host: '',
  port: 389,
  sslMode: 'NONE' as SslMode,
  bindDn: '',
  bindPassword: '',
  baseDn: '',
  trustAllCerts: false,
})

const directoryId = ref<string | undefined>(undefined)

const canTest = computed(() =>
  dir.value.displayName && dir.value.host && dir.value.bindDn && dir.value.bindPassword && dir.value.baseDn
    && dir.value.port > 0 && dir.value.port <= 65535
)

async function testConnection() {
  testing.value = true
  testResult.value = null
  error.value = ''
  try {
    const { data } = await testDirectory({
      host: dir.value.host,
      port: dir.value.port,
      sslMode: dir.value.sslMode,
      trustAllCerts: dir.value.trustAllCerts,
      bindDn: dir.value.bindDn,
      bindPassword: dir.value.bindPassword,
    })
    // Backend DTO is TestConnectionResult{success, message, elapsedMs}.
    // Earlier code read `data.error` and `data.responseTimeMs` — both wrong
    // field names — which made the failure path render an empty red box
    // because `undefined` interpolates as an empty string.
    testResult.value = {
      ok: data.success === true,
      message: data.success
        ? `Connected successfully (${data.elapsedMs ?? '?'} ms)`
        : (data.message || 'Connection failed (no detail returned by server)'),
    }
  } catch (e) {
    testResult.value = { ok: false, message: extractErrorMessage(e, 'Test connection failed') }
  } finally {
    testing.value = false
  }
}

/**
 * Extracts a user-presentable error string from an axios error.
 * Falls back through: RFC 7807 detail → message field → friendly
 * status-code translation → the raw exception message → a hardcoded
 * fallback. The fallback ensures we never render a visually-empty
 * error box when something goes wrong.
 */
function extractErrorMessage(e: unknown, fallback = 'Request failed (no detail returned)') {
  // Treat axios-like errors as a loose shape — we only care about the
  // standard fields and accept that anything else can be undefined.
  const err = e as { response?: { data?: { detail?: string; message?: string }; status?: number }; message?: string; code?: string }
  const detail = err?.response?.data?.detail
  const message = err?.response?.data?.message
  const status = err?.response?.status
  const exMsg = err?.message
  return detail
    || message
    || (status ? friendlyStatusMessage(status) : null)
    // Network error (no response) — axios sets err.code = 'ERR_NETWORK'.
    || (err?.code === 'ERR_NETWORK' ? 'Could not reach the server. Check your network connection and try again.' : null)
    || exMsg
    || fallback
}

/**
 * Maps a bare HTTP status into a sentence a non-engineer can act on.
 * "502" alone is opaque — the most common cause in this app is a
 * sleeping upstream (LDAP machine suspended, backend cold-starting,
 * proxy timeout) and the user just needs to be told to retry.
 */
function friendlyStatusMessage(status: number): string {
  switch (status) {
    case 401: return 'Not authenticated. Please sign in again.'
    case 403: return "You don't have permission to perform this action."
    case 404: return 'The requested resource was not found.'
    case 408:
    case 504: return 'The request timed out before the server responded. The target service may be starting up — try again in a few seconds.'
    case 502: return "The backend isn't responding. The upstream service may be starting up or unreachable — try again in a few seconds."
    case 503: return 'Service temporarily unavailable. Try again shortly.'
    default:
      if (status >= 500) return `Server error (HTTP ${status}). Try again or check the server logs.`
      if (status >= 400) return `Request rejected (HTTP ${status}).`
      return `Unexpected response (HTTP ${status}).`
  }
}

async function saveDirectory() {
  saving.value = true
  error.value = ''
  try {
    // Optional schema fields are explicitly omitted (was sending `null` for
    // each, which is wider than the schema's `string | undefined`). Backend
    // treats omitted fields the same as null for these.
    const payload = {
      displayName: dir.value.displayName,
      host: dir.value.host,
      port: dir.value.port,
      sslMode: dir.value.sslMode,
      trustAllCerts: dir.value.trustAllCerts,
      bindDn: dir.value.bindDn,
      bindPassword: dir.value.bindPassword,
      baseDn: dir.value.baseDn,
      pagingSize: 500,
      poolMinSize: 2,
      poolMaxSize: 10,
      poolConnectTimeoutSeconds: 10,
      poolResponseTimeoutSeconds: 30,
      enabled: true,
    }
    if (directoryId.value) {
      // Update existing directory if user went back and changed settings
      await updateDirectory(directoryId.value, payload)
    } else {
      const { data } = await createDirectory(payload)
      directoryId.value = data.id
    }
    // Reset verification data so Step 3 re-queries with updated config
    verifyData.value = { userCount: 0, groupCount: 0, sampleUsers: [], capped: false }
    step.value = 3
  } catch (e) {
    error.value = extractErrorMessage(e)
  } finally {
    saving.value = false
  }
}

// ── Step 3: Verify ─────────────────────────────────────────────────────
const verifying = ref(false)
const verifyError = ref('')
interface VerifyData {
  userCount: number | string  // string for "200+" sentinel when fetchLimit is hit
  groupCount: number | string
  sampleUsers: string[]
  capped: boolean
}
const verifyData = ref<VerifyData>({ userCount: 0, groupCount: 0, sampleUsers: [], capped: false })

watch(step, async (s) => {
  if (s !== 3) return
  // Skip re-verification if already successfully loaded.
  // userCount/groupCount can be either a number or the "200+" sentinel string;
  // either truthy state means we already have data.
  const hasData =
    (typeof verifyData.value.userCount === 'string' || verifyData.value.userCount > 0) ||
    (typeof verifyData.value.groupCount === 'string' || verifyData.value.groupCount > 0)
  if (hasData) return
  verifying.value = true
  verifyError.value = ''
  try {
    const fetchLimit = 201 // fetch 201 to detect "more than 200"
    const [usersRes, groupsRes] = await Promise.all([
      import('@/api/users').then(m => m.searchUsers(directoryId.value, { limit: fetchLimit })),
      import('@/api/groups').then(m => m.searchGroups(directoryId.value, { limit: fetchLimit })),
    ])
    const users = Array.isArray(usersRes.data) ? usersRes.data : []
    const groups = Array.isArray(groupsRes.data) ? groupsRes.data : []
    verifyData.value = {
      userCount: users.length >= fetchLimit ? '200+' : users.length,
      groupCount: groups.length >= fetchLimit ? '200+' : groups.length,
      sampleUsers: users.slice(0, 5).map((u: { dn?: string; attributes?: { dn?: string } }) =>
        u.dn || u.attributes?.dn || 'unknown'),
      capped: users.length >= fetchLimit || groups.length >= fetchLimit,
    }
  } catch (e) {
    verifyError.value = extractErrorMessage(e, 'Verification query failed')
  } finally {
    verifying.value = false
  }
})

// ── Step 4: Profile ────────────────────────────────────────────────────
const profile = ref({
  name: '',
  targetOuDn: '',
  objectClasses: ['inetOrgPerson', 'organizationalPerson', 'person', 'top'],
  rdnAttribute: 'uid',
})
const schemaObjectClasses = ref([])

const availableObjectClasses = computed(() =>
  schemaObjectClasses.value.filter(oc => !profile.value.objectClasses.includes(oc))
)

const canSaveProfile = computed(() =>
  profile.value.name && profile.value.targetOuDn && profile.value.objectClasses.length > 0 && profile.value.rdnAttribute
)

function addObjectClass(event: Event) {
  const target = event.target as HTMLSelectElement | HTMLInputElement
  const val = target.value
  if (val && !profile.value.objectClasses.includes(val)) {
    profile.value.objectClasses.push(val)
  }
  target.value = ''
}

// Load schema when entering step 4
watch(step, async (s) => {
  if (s !== 4) return
  if (!profile.value.name) profile.value.name = dir.value.displayName
  if (!profile.value.targetOuDn) profile.value.targetOuDn = dir.value.baseDn
  if (schemaObjectClasses.value.length === 0 && directoryId.value) {
    try {
      const { data } = await listObjectClasses(directoryId.value)
      schemaObjectClasses.value = data.map((oc: { name: string }) => oc.name).sort()
    } catch (e) { console.warn('Schema load failed:', e) }
  }
})

const profileId = ref(null)

async function saveProfile() {
  if (profileId.value) {
    // Profile already created — skip to next step
    step.value = stepAfterProfile.value
    return
  }
  saving.value = true
  error.value = ''
  try {
    const { data } = await createProfile(directoryId.value, {
      name: profile.value.name,
      targetOuDn: profile.value.targetOuDn,
      objectClassNames: profile.value.objectClasses,
      rdnAttribute: profile.value.rdnAttribute,
      showDnField: false,
      enabled: true,
      selfRegistrationAllowed: false,
      autoIncludeGroups: false,
      excludeAutoIncludes: false,
      attributeConfigs: [],
      groupAssignments: [],
    })
    profileId.value = data.id
    step.value = stepAfterProfile.value
  } catch (e) {
    error.value = extractErrorMessage(e)
  } finally {
    saving.value = false
  }
}

// ── Step 5: Access Review ──────────────────────────────────────────────
const campaign = ref({
  name: 'Initial Access Review',
  deadlineDays: 30,
  groupDn: '',
})
const campaignId = ref<string | undefined>(undefined)

async function createReview() {
  // Capture in a local because TypeScript's narrowing of auth.principal
  // doesn't survive the await — the getter could in principle return null
  // by the time we read it again. The cast is needed because the auth
  // store is still .js — its principal ref infers as Ref<null>. When
  // auth.js gets converted to TS, this cast can be removed.
  const principal = auth.principal as { id: string } | null
  if (!principal) {
    error.value = 'Not authenticated; please sign in again.'
    return
  }
  saving.value = true
  error.value = ''
  try {
    const { data } = await createCampaign(directoryId.value, {
      name: campaign.value.name,
      deadlineDays: campaign.value.deadlineDays,
      autoRevoke: false,
      autoRevokeOnExpiry: false,
      groups: [{
        groupDn: campaign.value.groupDn,
        memberAttribute: 'member',
        reviewerAccountId: principal.id,
      }],
    })
    campaignId.value = data.id
    step.value = 6
  } catch (e) {
    error.value = extractErrorMessage(e)
  } finally {
    saving.value = false
  }
}

// ── Step 6: Complete ───────────────────────────────────────────────────
async function completeSetup() {
  saving.value = true
  error.value = ''
  try {
    await apiCompleteSetup()
    auth.markSetupComplete()
    router.push('/superadmin/dashboard')
  } catch (e) {
    error.value = extractErrorMessage(e)
  } finally {
    saving.value = false
  }
}

// ── Skip ───────────────────────────────────────────────────────────────
// Marks setup complete without walking the steps. Identical backend
// effect to completeSetup(); the wizard's only product is the
// setupCompleted flag flipping to true. Anything entered in earlier
// steps is discarded — directories, profiles, etc. would have been
// persisted on their own step's submit handler, not at the end.
async function skipWizard() {
  saving.value = true
  error.value = ''
  try {
    await apiCompleteSetup()
    auth.markSetupComplete()
    router.push('/superadmin/dashboard')
  } catch (e) {
    error.value = extractErrorMessage(e)
    confirmingSkip.value = false
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
@reference "tailwindcss";
.label { @apply block text-xs font-medium text-gray-600 mb-1; }
</style>
