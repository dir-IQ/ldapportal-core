// SPDX-License-Identifier: Apache-2.0
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { login as apiLogin, logout as apiLogout, me } from '@/api/auth'
import { selfServiceLogin as apiSelfServiceLogin } from '@/api/selfservice'
import { getSetupStatus } from '@/api/setup'
import { useTheme } from '@/composables/useTheme'
import { useDensity } from '@/composables/useDensity'

export const useAuthStore = defineStore('auth', () => {
  const principal   = ref(null)
  const initialized = ref(false)
  const setupPending = ref(false)

  const isLoggedIn    = computed(() => !!principal.value)
  const isSuperadmin  = computed(() => principal.value?.accountType === 'SUPERADMIN')
  const isSelfService = computed(() => principal.value?.accountType === 'SELF_SERVICE')
  const username      = computed(() => principal.value?.username || '')
  const themePreference = computed(() => principal.value?.themePreference || 'system')
  const authType      = computed(() => principal.value?.authType || null)
  const isHrEnabled   = computed(() => principal.value?.hrIntegrationEnabled !== false)
  const isComplianceEnabled = computed(() => principal.value?.complianceEnabled !== false)
  // Entitlement-gated like the two above. The default-true fallback covers
  // any client talking to a server that predates this field; gating the UI
  // strictly on a missing flag would hide alerts after a backend rollback.
  const isAlertingEnabled = computed(() => principal.value?.alertingEnabled !== false)
  // Addon-granted entitlement. Defaults to false (not true) — an
  // addon's absence is the safer default: don't show ISVA UI on a
  // server where we can't confirm the addon is loaded. Set to true
  // only when /me reports the entitlement granted.
  const isIsvaIntegrationEnabled = computed(() => principal.value?.isvaIntegrationEnabled === true)
  // Distribution probe from /me. True on the community jar (no ee/
  // classes server-side). Default false so any client talking to a
  // server that predates this field assumes commercial — that's the
  // safe direction; the worst case is showing a settings panel whose
  // toggles do nothing, vs. hiding one a paying customer expects.
  const isCommunityDistribution = computed(() => principal.value?.communityDistribution === true)
  // Operator-controlled UX flag from ApplicationSettings (not entitlement-
  // gated). Default true preserves the Phase 1 behaviour for any client
  // talking to a server that hasn't yet shipped this field.
  const isDirectorySearchInlineEditEnabled = computed(
    () => principal.value?.directorySearchInlineEditEnabled !== false,
  )

  /**
   * Called once per page load (from the router guard) to restore the session
   * from the httpOnly JWT cookie by hitting /api/auth/me.
   */
  function principalFromMe(data) {
    return {
      id:                 data.id,
      username:           data.username,
      accountType:        data.accountType,
      dn:                 data.dn || null,
      directoryId:        data.directoryId || null,
      themePreference:    data.themePreference || 'system',
      densityPreference:  data.densityPreference || 'comfortable',
      authType:           data.authType || null,
      email:              data.email || null,
      displayName:        data.displayName || null,
      features:           data.features || [],
      hrIntegrationEnabled: data.hrIntegrationEnabled !== false,
      complianceEnabled: data.complianceEnabled !== false,
      alertingEnabled: data.alertingEnabled !== false,
      isvaIntegrationEnabled: data.isvaIntegrationEnabled === true,
      directorySearchInlineEditEnabled: data.directorySearchInlineEditEnabled !== false,
    }
  }

  /**
   * After /auth/me hydrates, push the persisted theme + density preferences
   * down into their composables. This is what makes the choice cross-browser:
   * the localStorage value seeded at module load is only valid for "this
   * browser"; the server value is the user's account-level intent and wins.
   * No-ops if the server returned null/undefined.
   */
  function syncPreferencesFromAccount(data) {
    useTheme().syncFromAccount(data.themePreference)
    useDensity().syncFromAccount(data.densityPreference)
  }

  async function init() {
    if (initialized.value) return
    initialized.value = true
    try {
      const { data } = await me()
      principal.value = principalFromMe(data)
      syncPreferencesFromAccount(data)
      await refreshSetupStatusIfSuperadmin(data.accountType)
    } catch {
      principal.value = null
    }
  }

  /**
   * Updates `setupPending` from the backend's first-run-status endpoint,
   * but only for superadmins (the only role that can be redirected to the
   * setup wizard). Called from both init() and login() — both paths need
   * to know whether the wizard should fire on the next navigation.
   *
   * Failures are swallowed and treated as "setup completed" — better to
   * let the user into the app than to trap them at /setup if the
   * status endpoint is briefly unavailable.
   */
  async function refreshSetupStatusIfSuperadmin(accountType) {
    if (accountType !== 'SUPERADMIN') return
    try {
      const { data: status } = await getSetupStatus()
      setupPending.value = !status.setupCompleted
    } catch { /* treat as completed */ }
  }

  /**
   * Unconditional re-fetch of /auth/me. The principal is normally
   * hydrated once at session init/login and never refreshed; call
   * reinit() any time something happens during the session that
   * could change what /auth/me returns.
   *
   * Known callers / when to call:
   * - WebSEAL silent pre-auth on the login page (cookie set outside
   *   this store; we re-init to pick up the newly-signed-in principal).
   * - Any flow that mutates the principal's features / *Enabled flags /
   *   role server-side: license install/activation, admin permission
   *   self-modification, etc. The *Enabled flags (complianceEnabled,
   *   hrIntegrationEnabled) are entitlement-derived, so they change when
   *   the active license changes, not from application settings. The cost
   *   is one /auth/me round-trip; the benefit is the sidebar (and any
   *   other auth-gated UI) re-renders without forcing the user to log out.
   */
  async function reinit() {
    initialized.value = false
    await init()
  }

  function markSetupComplete() {
    setupPending.value = false
  }

  function updatePrincipal(fields) {
    if (principal.value) {
      principal.value = { ...principal.value, ...fields }
    }
  }

  async function login(username, password) {
    const { data } = await apiLogin(username, password)
    principal.value = {
      id:          data.id,
      username:    data.username,
      accountType: data.accountType,
    }
    initialized.value = true
    // Re-fetch /auth/me to pick up preferences AND feature flags
    // (complianceEnabled, hrIntegrationEnabled, features). Without this the
    // sidebar would show compliance/HR nav links on first login until reload.
    try {
      const { data: meData } = await me()
      principal.value = principalFromMe(meData)
      syncPreferencesFromAccount(meData)
    } catch { /* ok */ }
    // Refresh first-run-wizard status so the route guard knows whether
    // to redirect superadmins to /setup. Without this call, login took
    // the user to the dashboard while a subsequent reload (which goes
    // through init()) would redirect — surprising and unreliable.
    await refreshSetupStatusIfSuperadmin(data.accountType)
  }

  async function selfServiceLogin(directoryId, username, password) {
    const { data } = await apiSelfServiceLogin(directoryId, username, password)
    principal.value = {
      id:          data.id,
      username:    data.username,
      accountType: data.accountType,
      dn:          data.dn,
      directoryId: data.directoryId,
    }
    initialized.value = true
  }

  /**
   * Log out. If the server responds with a `logoutUrl` (OIDC RP-initiated
   * logout with the IdP's end_session_endpoint), returns it to the caller so
   * the router can hand the browser off before landing on /login — otherwise
   * the IdP session stays open and the user gets silently re-authenticated
   * next visit. Always clears local principal even if the API call fails.
   */
  async function logout() {
    let logoutUrl = null
    try {
      const { data } = await apiLogout()
      logoutUrl = data?.logoutUrl || null
    } finally {
      principal.value = null
    }
    return logoutUrl
  }

  function hasFeature(featureDbValue) {
    return principal.value?.features?.includes(featureDbValue) ?? false
  }

  return {
    principal, isLoggedIn, isSuperadmin, isSelfService, username,
    themePreference, authType, hasFeature, isHrEnabled, isComplianceEnabled,
    isAlertingEnabled, isIsvaIntegrationEnabled, isCommunityDistribution, isDirectorySearchInlineEditEnabled,
    setupPending, init, reinit, login, selfServiceLogin, logout,
    markSetupComplete, updatePrincipal,
  }
})
