<!-- SPDX-License-Identifier: Apache-2.0 -->
<script setup>
import { ref, computed } from 'vue'
import FormField from '@/components/FormField.vue'
import AppModal from '@/components/AppModal.vue'
import OidcConfigHelpDialog from '@/components/OidcConfigHelpDialog.vue'
import WebSealConfigHelpDialog from '@/components/WebSealConfigHelpDialog.vue'

const props = defineProps({
  form:     { type: Object, required: true },
  settings: { type: Object, default: null },  // original server response (for *Configured placeholder hints)
})

const showOidcHelp = ref(false)
const showWebsealHelp = ref(false)
const showLocalBreakGlassModal = ref(false)

const hasTrustedProxies = computed(() => {
  const raw = props.form.websealTrustedProxies
  if (!raw || typeof raw !== 'string') return false
  return raw.split(/\r?\n/).some(l => l.trim() && !l.trim().startsWith('#'))
})

/**
 * Light CIDR-shape validation for the Trusted Proxies textarea. Matches
 * dotted-quad IPv4 and bracket-less IPv6, optionally followed by a /prefix.
 * Used only to surface warnings in the UI — the authoritative parser is
 * CidrChecker on the server and is tolerant of malformed lines.
 */
const cidrLineRegex = /^\s*(?:(?:\d{1,3}\.){3}\d{1,3}|[0-9a-fA-F:]+)(?:\/\d{1,3})?\s*$/
const malformedCidrLines = computed(() => {
  const raw = props.form.websealTrustedProxies
  if (!raw || typeof raw !== 'string') return []
  const bad = []
  for (const line of raw.split(/\r?\n/)) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('#')) continue
    if (!cidrLineRegex.test(trimmed)) bad.push(trimmed)
  }
  return bad
})

/**
 * Handle checkbox toggles for the Enabled login methods list. Guards against
 * two lockout footguns:
 *   - untick LOCAL while LOCAL is the only enabled method (hard-reject)
 *   - untick LOCAL when only SSO methods remain (confirm-before-disable modal)
 */
function toggleAuthType(type, enabled) {
  const current = [...(props.form.enabledAuthTypes || [])]
  if (enabled) {
    if (!current.includes(type)) current.push(type)
    props.form.enabledAuthTypes = current
    return
  }

  const next = current.filter(t => t !== type)
  if (next.length === 0) {
    // Hard reject — the backend enforces "at least one", but surface it at
    // the UI too so the user knows why nothing happened.
    return
  }
  if (type === 'LOCAL') {
    // Show the break-glass confirmation modal. The checkbox stays ticked
    // until the user confirms. We re-enter this function with enabled=false
    // from confirmDisableLocal below to actually apply the change.
    showLocalBreakGlassModal.value = true
    return
  }
  props.form.enabledAuthTypes = next
}

function confirmDisableLocal() {
  props.form.enabledAuthTypes = (props.form.enabledAuthTypes || []).filter(t => t !== 'LOCAL')
  showLocalBreakGlassModal.value = false
}
</script>

<template>
  <section class="bg-white border border-gray-200 rounded-xl p-6">
    <h2 class="text-base font-semibold text-gray-900 mb-3">Authentication</h2>

    <!-- Enabled auth methods -->
    <div class="mb-4">
      <label class="block text-sm font-medium text-gray-700 mb-2">Enabled login methods</label>
      <div class="flex flex-wrap gap-x-6 gap-y-2">
        <label v-for="t in ['LOCAL', 'LDAP']" :key="t" class="flex items-center gap-2">
          <input type="checkbox" :value="t" :checked="form.enabledAuthTypes.includes(t)"
                 @change="toggleAuthType(t, $event.target.checked)"
                 class="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500" />
          <span class="text-sm text-gray-700">{{ t }}</span>
        </label>
        <!-- OIDC / WebSEAL toggles are gated on .env-driven visibility flags
             (app.auth.*.ui-visible), surfaced read-only via the settings DTO.
             Enablement itself is still persisted in enabledAuthTypes. -->
        <label v-if="settings?.oidcToggleVisible" class="flex items-center gap-2">
          <input type="checkbox" :value="'OIDC'" :checked="form.enabledAuthTypes.includes('OIDC')"
                 @change="toggleAuthType('OIDC', $event.target.checked)"
                 class="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500" />
          <span class="text-sm text-gray-700">OIDC</span>
        </label>
        <label v-if="settings?.websealToggleVisible" class="flex items-center gap-2">
          <input type="checkbox" :value="'WEBSEAL'" :checked="form.enabledAuthTypes.includes('WEBSEAL')"
                 @change="toggleAuthType('WEBSEAL', $event.target.checked)"
                 class="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500" />
          <span class="text-sm text-gray-700">WEBSEAL</span>
        </label>
      </div>
      <p class="text-xs text-gray-500 mt-1">
        At least one method must remain enabled.
        <span class="ml-1">LOCAL is strongly recommended as a break-glass path even when SSO is in use.</span>
      </p>
    </div>

    <!-- LDAP Auth Provider -->
    <div v-if="form.enabledAuthTypes.includes('LDAP')" class="border-t border-gray-100 pt-4 mb-3">
      <h3 class="text-sm font-semibold text-gray-700 mb-3">LDAP Auth Provider</h3>
      <div class="grid grid-cols-2 gap-3">
        <FormField label="Host" v-model="form.ldapAuthHost" placeholder="ldap.example.com" />
        <FormField label="Port" v-model.number="form.ldapAuthPort" type="number" placeholder="389" />
        <FormField label="SSL Mode" v-model="form.ldapAuthSslMode" type="select"
                   :options="[{ value: '', label: 'None' }, { value: 'STARTTLS', label: 'STARTTLS' }, { value: 'LDAPS', label: 'LDAPS' }]" />
        <div class="flex items-center gap-2 pt-6">
          <input type="checkbox" id="ldapTrustAll" v-model="form.ldapAuthTrustAllCerts" class="rounded" />
          <label for="ldapTrustAll" class="text-sm text-gray-700">Trust all certificates</label>
        </div>
        <div class="col-span-2">
          <label for="set-ldap-trusted-cert-pem" class="block text-sm font-medium text-gray-700 mb-1">Trusted Certificate (PEM)</label>
          <textarea id="set-ldap-trusted-cert-pem" v-model="form.ldapAuthTrustedCertPem" rows="3" placeholder="-----BEGIN CERTIFICATE-----"
                    class="input w-full font-mono text-xs"></textarea>
        </div>
        <FormField label="Service Account Bind DN" v-model="form.ldapAuthBindDn"
                   placeholder="cn=admin,dc=example,dc=com" />
        <div>
          <FormField label="Bind Password" v-model="form.ldapAuthBindPassword" type="password" autocomplete="new-password"
                     :placeholder="settings?.ldapAuthBindPasswordConfigured ? '●●●●●●●● (leave blank to keep)' : 'Set password'" />
          <p class="text-xs text-gray-500 mt-1">Leave blank to keep existing. Enter a space to clear.</p>
        </div>
        <FormField label="User Search Base" v-model="form.ldapAuthUserSearchBase"
                   placeholder="ou=people,dc=example,dc=com" />
        <FormField label="Bind DN Pattern" v-model="form.ldapAuthBindDnPattern"
                   placeholder="uid={username},ou=people,dc=example,dc=com"
                   hint="{username} is replaced with the login username." />
      </div>
    </div>

    <!-- OIDC Provider — hidden unless the OIDC toggle is visible (app.auth.oidc.ui-visible) -->
    <div v-if="settings?.oidcToggleVisible && form.enabledAuthTypes.includes('OIDC')" class="border-t border-gray-100 pt-4">
      <div class="flex items-center gap-2 mb-3">
        <h3 class="text-sm font-semibold text-gray-700">OIDC Provider</h3>
        <button type="button" @click="showOidcHelp = true"
                class="inline-flex items-center justify-center w-5 h-5 rounded-full border border-gray-300 text-gray-500 hover:text-gray-900 hover:border-gray-400 hover:bg-gray-50 transition-colors"
                title="Open the OIDC configuration guide">
          <svg class="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor"
               stroke-width="2.25" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <circle cx="12" cy="12" r="10" />
            <path d="M9.5 9a2.5 2.5 0 0 1 5 0c0 1.5-2.5 2-2.5 3.5" />
            <circle cx="12" cy="17" r="0.5" fill="currentColor" />
          </svg>
          <span class="sr-only">OIDC configuration help</span>
        </button>
      </div>
      <div class="grid grid-cols-2 gap-3">
        <div class="col-span-2">
          <FormField label="Issuer URL" v-model="form.oidcIssuerUrl"
                     placeholder="https://accounts.google.com"
                     hint="Must support OpenID Connect Discovery (/.well-known/openid-configuration)." />
        </div>
        <FormField label="Client ID" v-model="form.oidcClientId" placeholder="your-client-id" />
        <div>
          <FormField label="Client Secret" v-model="form.oidcClientSecret" type="password" autocomplete="new-password"
                     :placeholder="settings?.oidcClientSecretConfigured ? '●●●●●●●● (leave blank to keep)' : 'Set secret'" />
          <p class="text-xs text-gray-500 mt-1">Leave blank to keep existing. Enter a space to clear.</p>
        </div>
        <FormField label="Scopes" v-model="form.oidcScopes" placeholder="openid profile email" />
        <FormField label="Username Claim" v-model="form.oidcUsernameClaim" placeholder="preferred_username"
                   hint="ID token claim matched against Account.username." />
        <div class="col-span-2">
          <FormField label="Redirect URI" v-model="form.oidcRedirectUri"
                     placeholder="https://ldap.example.com/oidc/callback"
                     hint="Full callback URL registered with the IdP. Required in production — when blank the server falls back to deriving the URL from the Host header, which is spoofable." />
        </div>
      </div>
    </div>

    <!-- WebSEAL (IBM Verify Identity Access) header-based SSO — hidden unless
         the WebSEAL toggle is visible (app.auth.webseal.ui-visible) -->
    <div v-if="settings?.websealToggleVisible && form.enabledAuthTypes.includes('WEBSEAL')" class="border-t border-gray-100 pt-4">
      <div class="flex items-center gap-2 mb-3">
        <h3 class="text-sm font-semibold text-gray-700">WebSEAL (Header-Based SSO)</h3>
        <button type="button" @click="showWebsealHelp = true"
                class="inline-flex items-center justify-center w-5 h-5 rounded-full border border-gray-300 text-gray-500 hover:text-gray-900 hover:border-gray-400 hover:bg-gray-50 transition-colors"
                title="Open the WebSEAL configuration guide">
          <svg class="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor"
               stroke-width="2.25" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <circle cx="12" cy="12" r="10" />
            <path d="M9.5 9a2.5 2.5 0 0 1 5 0c0 1.5-2.5 2-2.5 3.5" />
            <circle cx="12" cy="17" r="0.5" fill="currentColor" />
          </svg>
          <span class="sr-only">WebSEAL configuration help</span>
        </button>
      </div>
      <!-- Trust-boundary warning when the feature is on but no CIDRs configured -->
      <div v-if="!hasTrustedProxies"
           class="bg-red-50 border border-red-200 rounded p-3 mb-3 text-red-900 text-xs">
        <p class="font-semibold uppercase tracking-wider mb-1">Trust boundary not configured</p>
        <p>
          WebSEAL is enabled but the Trusted Proxies list is empty. The feature stays disabled
          at runtime until you list the CIDR(s) that WebSEAL reaches this app from — otherwise
          any client could send <code>iv-user</code> and walk in.
        </p>
      </div>
      <div class="grid grid-cols-2 gap-3">
        <div class="col-span-2">
          <label for="set-webseal-trusted-proxies" class="block text-sm font-medium text-gray-700 mb-1">Trusted Proxies</label>
          <textarea id="set-webseal-trusted-proxies" v-model="form.websealTrustedProxies" rows="3"
                    placeholder="10.0.0.0/8&#10;192.168.1.5/32"
                    class="input w-full font-mono text-xs"></textarea>
          <p class="text-xs text-gray-500 mt-1">
            Newline-separated CIDR list. Only requests whose peer IP is in this list will be
            trusted. Uses the immediate TCP peer (<code>getRemoteAddr</code>) — not
            <code>X-Forwarded-For</code>. Empty list = feature disabled at runtime.
          </p>
          <p v-if="malformedCidrLines.length" class="text-xs text-amber-700 mt-1">
            Skipping {{ malformedCidrLines.length }} line(s) that don't look like CIDRs:
            <code>{{ malformedCidrLines.slice(0, 3).join(', ') }}{{ malformedCidrLines.length > 3 ? '…' : '' }}</code>
          </p>
        </div>
        <FormField label="User Header" v-model="form.websealUserHeader" placeholder="iv-user"
                   hint="HTTP header name carrying the authenticated username." />
        <FormField label="Groups Header" v-model="form.websealGroupsHeader" placeholder="iv-groups"
                   hint="Audit-only — groups never drive role assignment in this model." />
        <div class="col-span-2">
          <FormField label="Logout URL" v-model="form.websealLogoutUrl" placeholder="/pkmslogout"
                     hint="Absolute URL or path to redirect the browser to on logout so WebSEAL clears its session cookie. Default is WebSEAL's standard /pkmslogout endpoint." />
        </div>
      </div>
    </div>

    <OidcConfigHelpDialog v-model="showOidcHelp" />
    <WebSealConfigHelpDialog v-model="showWebsealHelp" />

    <!-- LOCAL break-glass override modal -->
    <AppModal v-model="showLocalBreakGlassModal"
              title="Disable LOCAL login?"
              size="md">
      <div class="text-sm text-gray-700 space-y-2">
        <p>
          LOCAL password login is the standard break-glass path if SSO breaks (IdP outage,
          misconfigured WebSEAL junction, etc.).
        </p>
        <p>
          With LOCAL disabled, a broken SSO path will lock every admin out of this app —
          including superadmins. Recovery usually means editing the database directly.
        </p>
        <p class="font-medium">Disable LOCAL anyway?</p>
      </div>
      <template #footer>
        <button type="button" @click="showLocalBreakGlassModal = false"
                class="btn-neutral btn-compact">Keep LOCAL enabled</button>
        <button type="button" @click="confirmDisableLocal"
                class="btn-primary btn-compact">Disable LOCAL</button>
      </template>
    </AppModal>
  </section>
</template>
