// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'

const settings = vi.hoisted(() => ({
  appName: 'LDAP Portal',
  enabledAuthTypes: ['WEBSEAL'] as string[],
  init: vi.fn(async () => {}),
}))
vi.mock('@/stores/settings', () => ({ useSettingsStore: () => settings }))
const auth = vi.hoisted(() => ({
  isLoggedIn: false, isSelfService: false,
  reinit: vi.fn(async () => {}), login: vi.fn(async () => {}),
}))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => auth }))
const api = vi.hoisted(() => ({ websealAuthorize: vi.fn(), oidcAuthorize: vi.fn() }))
vi.mock('@/api/auth', () => api)
const router = vi.hoisted(() => ({
  push: vi.fn(),
  resolve: vi.fn((to: string) => ({ href: `/app${to}` })),
}))
const route = vi.hoisted(() => ({ query: { redirect: '/users' } as Record<string, string> }))
vi.mock('vue-router', () => ({ useRouter: () => router, useRoute: () => route }))

import LoginView from './LoginView.vue'
import { isSignedOut, markSignedOut, clearSignedOut } from '@/utils/signedOut'

let wrapper: VueWrapper | null = null
const assign = vi.fn()

beforeEach(() => {
  vi.clearAllMocks()
  clearSignedOut()
  settings.enabledAuthTypes = ['WEBSEAL']
  api.websealAuthorize.mockResolvedValue({ data: {} })
  vi.stubGlobal('location', { ...window.location, assign, protocol: 'https:' })
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  vi.unstubAllGlobals()
})

async function mountLogin() {
  wrapper = mount(LoginView, { global: { stubs: { FormField: true } } })
  await flushPromises()
  return wrapper
}

describe('LoginView WebSEAL sign-in', () => {
  it('signs straight in from the WebSEAL header normally', async () => {
    await mountLogin()
    expect(api.websealAuthorize).toHaveBeenCalledTimes(1)
    expect(auth.reinit).toHaveBeenCalled()
    expect(router.push).toHaveBeenCalledWith('/users')
  })

  it('after a logout, says so instead of signing straight back in', async () => {
    markSignedOut()
    const w = await mountLogin()
    expect(api.websealAuthorize).not.toHaveBeenCalled()
    expect(router.push).not.toHaveBeenCalled()
    expect(w.find('[data-testid="signed-out"]').text()).toContain("You've signed out")
    expect(w.text()).toContain('close the browser')
  })

  it('Sign in again forgets the logout and reloads the requested page', async () => {
    markSignedOut()
    const w = await mountLogin()
    await w.findAll('button').find(b => b.text() === 'Sign in again')!.trigger('click')
    expect(isSignedOut()).toBe(false)
    expect(assign).toHaveBeenCalledWith('/app/users')
  })

  it('still offers the password form beside the notice', async () => {
    settings.enabledAuthTypes = ['LOCAL', 'WEBSEAL']
    markSignedOut()
    const w = await mountLogin()
    expect(w.find('[data-testid="signed-out"]').exists()).toBe(true)
    expect(w.find('form').exists()).toBe(true)
  })

  it('shows no notice where WebSEAL isn’t a sign-in method', async () => {
    settings.enabledAuthTypes = ['LOCAL']
    markSignedOut()
    const w = await mountLogin()
    expect(w.find('[data-testid="signed-out"]').exists()).toBe(false)
    expect(w.find('form').exists()).toBe(true)
  })

  it('forgets the logout when the user starts an SSO sign-in', async () => {
    settings.enabledAuthTypes = ['OIDC', 'WEBSEAL']
    markSignedOut()
    api.oidcAuthorize.mockResolvedValue({ data: { state: 's', authorizationUrl: 'https://idp/auth' } })
    const w = await mountLogin()
    await w.findAll('button').find(b => b.text().includes('Sign in with SSO'))!.trigger('click')
    expect(isSignedOut()).toBe(false)
  })
})
