// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

// Mock the API modules the auth store depends on. vi.mock is hoisted so
// these take effect before the store imports them.
vi.mock('@/api/auth', () => ({
  login: vi.fn(),
  logout: vi.fn(),
  me: vi.fn(),
}))
vi.mock('@/api/selfservice', () => ({
  selfServiceLogin: vi.fn(),
}))
vi.mock('@/api/setup', () => ({
  getSetupStatus: vi.fn(),
}))

import { useAuthStore } from './auth'
import { login as apiLogin, me } from '@/api/auth'
import { getSetupStatus } from '@/api/setup'

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
  })
})
