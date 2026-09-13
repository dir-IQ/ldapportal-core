// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

// Mock the API modules the auth store depends on. vi.mock is hoisted so
// these take effect before the store imports them.
vi.mock('@/api/auth', () => ({
  login: vi.fn(),
  logout: vi.fn(),
  me: vi.fn(),
  websealAuthorize: vi.fn(),
}))
// init() consults the public branding payload (enabled sign-in methods) to
// decide whether a WebSEAL pre-auth probe is worth a request.
vi.mock('@/api/settings', () => ({
  getBranding: vi.fn().mockResolvedValue({ data: { enabledAuthTypes: ['LOCAL'] } }),
}))
vi.mock('@/api/selfservice', () => ({
  selfServiceLogin: vi.fn(),
}))
vi.mock('@/api/setup', () => ({
  getSetupStatus: vi.fn(),
}))
// init()/login() hydrate the preferences store; mock its API so the auth-store
// tests don't reach for a backend.
vi.mock('@/api/preferences', () => ({
  getPreferences: vi.fn().mockResolvedValue({ data: {} }),
  patchPreferences: vi.fn().mockResolvedValue({ data: {} }),
}))

import { useAuthStore } from './auth'
import { login as apiLogin, me, websealAuthorize } from '@/api/auth'
import { getSetupStatus } from '@/api/setup'
import { getBranding } from '@/api/settings'

describe('useAuthStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  describe('login() — first-run-wizard regression', () => {
    /**
     * Regression for: "after login I'm taken to the dashboard, but on
     * reload I'm taken to the wizard." The login() path was not calling
     * /auth/setup-status, so setupPending stayed at its initial false
     * value and the route guard didn't redirect superadmins to /setup.
     * Reload went through init() (which DID call setup-status) and saw
     * the real value, redirecting correctly. The two paths must agree.
     */
    it('calls /auth/setup-status for superadmin and sets setupPending', async () => {
      apiLogin.mockResolvedValue({ data: { id: 'a1', username: 'admin', accountType: 'SUPERADMIN' } })
      me.mockResolvedValue({ data: { id: 'a1', username: 'admin', accountType: 'SUPERADMIN' } })
      getSetupStatus.mockResolvedValue({ data: { setupCompleted: false } })

      const store = useAuthStore()
      await store.login('admin', 'pw')

      expect(getSetupStatus).toHaveBeenCalledTimes(1)
      expect(store.setupPending).toBe(true)
    })

    it('does NOT fetch setup-status for non-superadmin accounts', async () => {
      apiLogin.mockResolvedValue({ data: { id: 'a2', username: 'user', accountType: 'ADMIN' } })
      me.mockResolvedValue({ data: { id: 'a2', username: 'user', accountType: 'ADMIN' } })

      const store = useAuthStore()
      await store.login('user', 'pw')

      expect(getSetupStatus).not.toHaveBeenCalled()
      expect(store.setupPending).toBe(false)
    })

    it('treats setup-status fetch failure as completed (no trap)', async () => {
      apiLogin.mockResolvedValue({ data: { id: 'a3', username: 'admin', accountType: 'SUPERADMIN' } })
      me.mockResolvedValue({ data: { id: 'a3', username: 'admin', accountType: 'SUPERADMIN' } })
      getSetupStatus.mockRejectedValue(new Error('500 Internal Server Error'))

      const store = useAuthStore()
      await store.login('admin', 'pw')

      expect(store.setupPending).toBe(false)
    })

    it('sets setupPending=false when backend reports completed', async () => {
      apiLogin.mockResolvedValue({ data: { id: 'a4', username: 'admin', accountType: 'SUPERADMIN' } })
      me.mockResolvedValue({ data: { id: 'a4', username: 'admin', accountType: 'SUPERADMIN' } })
      getSetupStatus.mockResolvedValue({ data: { setupCompleted: true } })

      const store = useAuthStore()
      await store.login('admin', 'pw')

      expect(store.setupPending).toBe(false)
    })
  })

  describe('init()', () => {
    it('still fetches setup-status (regression guard for the existing path)', async () => {
      me.mockResolvedValue({ data: { id: 'a5', username: 'admin', accountType: 'SUPERADMIN' } })
      getSetupStatus.mockResolvedValue({ data: { setupCompleted: false } })

      const store = useAuthStore()
      await store.init()

      expect(getSetupStatus).toHaveBeenCalledTimes(1)
      expect(store.setupPending).toBe(true)
    })

    it('asks /auth/me not to hard-redirect on 401 (the router guard owns that redirect)', async () => {
      me.mockResolvedValue({ data: { id: 'a5', username: 'admin', accountType: 'ADMIN' } })

      await useAuthStore().init()

      expect(me).toHaveBeenCalledWith({ skipAuthRedirect: true })
    })
  })

  /**
   * A WebSEAL sign-in used to reach the app with no JWT, get bounced to
   * /login by the guard, and only then run the pre-auth probe — a visible
   * flash of the login page on every SSO arrival. The probe now runs inside
   * the boot-time session restore, so the guard sees a logged-in user and
   * routes straight to the requested page.
   */
  describe('init() — WebSEAL silent session restore', () => {
    const unauthorized = () => Object.assign(new Error('401'), { response: { status: 401 } })

    it('completes the pre-auth probe and re-fetches /auth/me when WEBSEAL is enabled', async () => {
      getBranding.mockResolvedValue({ data: { enabledAuthTypes: ['LOCAL', 'WEBSEAL'] } })
      me.mockRejectedValueOnce(unauthorized())
        .mockResolvedValueOnce({ data: { id: 'w1', username: 'alice', accountType: 'ADMIN', authType: 'WEBSEAL' } })
      websealAuthorize.mockResolvedValue({ data: { id: 'w1', username: 'alice', accountType: 'ADMIN' } })

      const store = useAuthStore()
      await store.init()

      expect(websealAuthorize).toHaveBeenCalledTimes(1)
      expect(me).toHaveBeenCalledTimes(2)
      expect(store.isLoggedIn).toBe(true)
      expect(store.username).toBe('alice')
    })

    it('skips the probe entirely when WEBSEAL is not an enabled sign-in method', async () => {
      getBranding.mockResolvedValue({ data: { enabledAuthTypes: ['LOCAL'] } })
      me.mockRejectedValue(unauthorized())

      const store = useAuthStore()
      await store.init()

      expect(websealAuthorize).not.toHaveBeenCalled()
      expect(me).toHaveBeenCalledTimes(1)
      expect(store.isLoggedIn).toBe(false)
    })

    it('falls back to logged-out when the probe is rejected (no junction in front)', async () => {
      getBranding.mockResolvedValue({ data: { enabledAuthTypes: ['LOCAL', 'WEBSEAL'] } })
      me.mockRejectedValue(unauthorized())
      websealAuthorize.mockRejectedValue(unauthorized())

      const store = useAuthStore()
      await store.init()

      expect(websealAuthorize).toHaveBeenCalledTimes(1)
      expect(me).toHaveBeenCalledTimes(1)
      expect(store.isLoggedIn).toBe(false)
    })

    it('does not probe on a non-401 failure (backend down is not "no session")', async () => {
      getBranding.mockResolvedValue({ data: { enabledAuthTypes: ['LOCAL', 'WEBSEAL'] } })
      me.mockRejectedValue(Object.assign(new Error('503'), { response: { status: 503 } }))

      const store = useAuthStore()
      await store.init()

      expect(websealAuthorize).not.toHaveBeenCalled()
      expect(store.isLoggedIn).toBe(false)
    })
  })

  describe('superadmin permissions', () => {
    it('exposes hasSuperadminPermission and isSuperadminOwner from /me', async () => {
      me.mockResolvedValue({ data: {
        id: 'a6', username: 'owner', accountType: 'SUPERADMIN',
        superadminPermissions: [
          'superadmin.manage_superadmins',
          'superadmin.manage_application_accounts',
        ],
      } })
      getSetupStatus.mockResolvedValue({ data: { setupCompleted: true } })

      const store = useAuthStore()
      await store.init()

      expect(store.hasSuperadminPermission('superadmin.manage_application_accounts')).toBe(true)
      expect(store.hasSuperadminPermission('superadmin.manage_directories')).toBe(false)
      expect(store.isSuperadminOwner).toBe(true)
    })

    it('reports no superadmin permissions for a scoped account', async () => {
      me.mockResolvedValue({ data: {
        id: 'a7', username: 'scoped', accountType: 'SUPERADMIN',
        superadminPermissions: ['superadmin.manage_application_settings'],
      } })
      getSetupStatus.mockResolvedValue({ data: { setupCompleted: true } })

      const store = useAuthStore()
      await store.init()

      expect(store.hasSuperadminPermission('superadmin.manage_application_settings')).toBe(true)
      expect(store.isSuperadminOwner).toBe(false)
    })
  })
})
