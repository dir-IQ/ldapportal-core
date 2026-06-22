// SPDX-License-Identifier: Apache-2.0
/**
 * ApiTokensView wires the superadmin API-token CRUD surface. These tests pin
 * the behaviours that matter: the list loads on mount, create posts the chosen
 * expiry and reveals the one-time secret, rotate/revoke route to the right
 * endpoints, and the "Show revoked" toggle re-queries with includeRevoked.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

const tokenApi = vi.hoisted(() => ({
  listApiTokens: vi.fn(),
  createApiToken: vi.fn(),
  rotateApiToken: vi.fn(),
  revokeApiToken: vi.fn(),
}))

vi.mock('@/api/apiTokens', () => tokenApi)
vi.mock('@/stores/notifications', () => ({
  useNotificationStore: () => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() }),
}))

import ApiTokensView from './ApiTokensView.vue'

const stubs = {
  AppModal: { props: ['modelValue'], template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>' },
  ActionMenu: {
    props: ['items'],
    template: `<div><button v-for="it in items.filter(i => !i.hidden)" :key="it.label"
                 :data-action="it.label" @click="it.onClick && it.onClick()">{{ it.label }}</button></div>`,
  },
  ConfirmDialog: {
    props: ['modelValue'],
    emits: ['confirm', 'update:modelValue'],
    template: `<button class="confirm" @click="$emit('confirm')">confirm</button>`,
  },
  FormField: { props: ['modelValue'], template: '<div />' },
  FormValidationSummary: { template: '<div />' },
  CopyButton: { props: ['text'], template: '<button class="copy" />' },
}

function rows() {
  return [
    {
      id: 't1', name: 'ci-terraform', description: 'CI', tokenPrefix: 'ldap_pat_aB3x',
      createdByUsername: 'alice', createdAt: '2026-01-01T00:00:00Z',
      expiresAt: '2027-01-01T00:00:00Z', lastUsedAt: '2026-06-01T00:00:00Z', status: 'ACTIVE',
    },
    {
      id: 't2', name: 'old-jenkins', description: '', tokenPrefix: 'ldap_pat_Zz00',
      createdByUsername: 'bob', createdAt: '2025-01-01T00:00:00Z',
      expiresAt: '2026-01-01T00:00:00Z', lastUsedAt: null, status: 'REVOKED',
    },
  ]
}

beforeEach(() => {
  vi.clearAllMocks()
  tokenApi.listApiTokens.mockResolvedValue({ data: rows() })
  tokenApi.createApiToken.mockResolvedValue({ data: { token: rows()[0], plaintext: 'ldap_pat_secret123' } })
  tokenApi.rotateApiToken.mockResolvedValue({ data: { token: rows()[0], plaintext: 'ldap_pat_rotated456' } })
  tokenApi.revokeApiToken.mockResolvedValue({ data: undefined })
})

describe('ApiTokensView', () => {
  it('loads tokens on mount (active-only by default)', async () => {
    const wrapper = mount(ApiTokensView, { global: { stubs } })
    await flushPromises()
    expect(tokenApi.listApiTokens).toHaveBeenCalledWith(false)
    expect(wrapper.text()).toContain('ci-terraform')
  })

  it('re-queries with includeRevoked when the toggle is checked', async () => {
    const wrapper = mount(ApiTokensView, { global: { stubs } })
    await flushPromises()

    await wrapper.find('input[type="checkbox"]').setValue(true)
    await flushPromises()

    expect(tokenApi.listApiTokens).toHaveBeenLastCalledWith(true)
  })

  it('creates a token with the selected preset expiry and reveals the secret once', async () => {
    const wrapper = mount(ApiTokensView, { global: { stubs } })
    await flushPromises()

    await wrapper.find('button.btn-primary').trigger('click') // + New token
    // Name is required; the FormField is stubbed so set the model directly via vm.
    ;(wrapper.vm as unknown as { form: { name: string } }).form.name = 'svc-token'
    // Pick the "90 days" preset chip.
    const chip = wrapper.findAll('button').find(b => b.text() === '90 days')!
    await chip.trigger('click')

    const createBtn = wrapper.findAll('button').find(b => b.text() === 'Create token')!
    await createBtn.trigger('click')
    await flushPromises()

    expect(tokenApi.createApiToken).toHaveBeenCalledTimes(1)
    const arg = tokenApi.createApiToken.mock.calls[0][0]
    expect(arg.name).toBe('svc-token')
    expect(typeof arg.expiresAt).toBe('string')
    // ~90 days out (allow a day of slack).
    const days = (new Date(arg.expiresAt).getTime() - Date.now()) / 86_400_000
    expect(days).toBeGreaterThan(89)
    expect(days).toBeLessThan(91)
    // One-time secret is surfaced.
    expect(wrapper.text()).toContain('ldap_pat_secret123')
  })

  it('does not submit when no expiry is chosen', async () => {
    const wrapper = mount(ApiTokensView, { global: { stubs } })
    await flushPromises()

    await wrapper.find('button.btn-primary').trigger('click')
    ;(wrapper.vm as unknown as { form: { name: string } }).form.name = 'svc-token'
    await flushPromises() // let the Create button's disabled binding update
    await wrapper.findAll('button').find(b => b.text() === 'Create token')!.trigger('click')
    await flushPromises()

    expect(tokenApi.createApiToken).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Pick an expiry')
  })

  it('rotates an active token and reveals the new secret', async () => {
    const wrapper = mount(ApiTokensView, { global: { stubs } })
    await flushPromises()

    await wrapper.findAll('[data-action="Rotate"]')[0].trigger('click')
    await flushPromises()

    expect(tokenApi.rotateApiToken).toHaveBeenCalledWith('t1')
    expect(wrapper.text()).toContain('ldap_pat_rotated456')
  })

  it('revokes a token after confirmation', async () => {
    const wrapper = mount(ApiTokensView, { global: { stubs } })
    await flushPromises()

    await wrapper.findAll('[data-action="Revoke"]')[0].trigger('click')
    await wrapper.find('.confirm').trigger('click')
    await flushPromises()

    expect(tokenApi.revokeApiToken).toHaveBeenCalledWith('t1')
  })

  it('offers no Rotate/Revoke actions on a revoked row', async () => {
    const wrapper = mount(ApiTokensView, { global: { stubs } })
    await flushPromises()

    // Row t1 (ACTIVE) exposes both actions; the REVOKED row t2 exposes neither,
    // so there is exactly one of each across the table.
    expect(wrapper.findAll('[data-action="Rotate"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-action="Revoke"]')).toHaveLength(1)
  })
})
