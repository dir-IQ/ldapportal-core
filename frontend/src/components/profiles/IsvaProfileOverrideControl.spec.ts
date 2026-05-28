// SPDX-License-Identifier: Apache-2.0
/**
 * Component tests for IsvaProfileOverrideControl.
 *
 * Covers the gating (hidden unless the addon is present AND the
 * directory has ISVA enabled), the override round-trip, and the
 * optimistic-toggle revert on save failure. Stores + api are mocked at
 * the module level — pure component tests.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

const hoisted = vi.hoisted(() => ({
  authEnabled: { value: true },
  getIsvaConfig: vi.fn(),
  getIsvaProfileOverride: vi.fn(),
  setIsvaProfileOverride: vi.fn(),
  notifSuccess: vi.fn(),
  notifError: vi.fn(),
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    get isIsvaIntegrationEnabled() {
      return hoisted.authEnabled.value
    },
  }),
}))

vi.mock('@/stores/notifications', () => ({
  useNotificationStore: () => ({
    success: hoisted.notifSuccess,
    error: hoisted.notifError,
    info: vi.fn(),
    warning: vi.fn(),
  }),
}))

vi.mock('@/api/isvaConfig', () => ({
  getIsvaConfig: hoisted.getIsvaConfig,
  getIsvaProfileOverride: hoisted.getIsvaProfileOverride,
  setIsvaProfileOverride: hoisted.setIsvaProfileOverride,
}))

import IsvaProfileOverrideControl from './IsvaProfileOverrideControl.vue'

const DIR = 'dir-1'
const PROFILE = 'profile-1'

function mountControl() {
  return mount(IsvaProfileOverrideControl, {
    props: { directoryId: DIR, profileId: PROFILE },
  })
}

beforeEach(() => {
  hoisted.authEnabled.value = true
  hoisted.getIsvaConfig.mockReset()
  hoisted.getIsvaProfileOverride.mockReset()
  hoisted.setIsvaProfileOverride.mockReset()
  hoisted.notifSuccess.mockReset()
  hoisted.notifError.mockReset()
})

describe('IsvaProfileOverrideControl', () => {
  it('renders nothing when the addon is not present', async () => {
    hoisted.authEnabled.value = false
    const wrapper = mountControl()
    await flushPromises()
    expect(wrapper.find('input[type="checkbox"]').exists()).toBe(false)
    expect(hoisted.getIsvaConfig).not.toHaveBeenCalled()
  })

  it('renders nothing when the directory does not have ISVA enabled', async () => {
    hoisted.getIsvaConfig.mockResolvedValue({ data: { enabled: false } })
    const wrapper = mountControl()
    await flushPromises()
    expect(wrapper.find('input[type="checkbox"]').exists()).toBe(false)
    expect(hoisted.getIsvaProfileOverride).not.toHaveBeenCalled()
  })

  it('renders nothing when there is no ISVA config row (404)', async () => {
    hoisted.getIsvaConfig.mockRejectedValue({ response: { status: 404 } })
    const wrapper = mountControl()
    await flushPromises()
    expect(wrapper.find('input[type="checkbox"]').exists()).toBe(false)
  })

  it('renders a fieldset with an "IVIA Integration" legend', async () => {
    hoisted.getIsvaConfig.mockResolvedValue({ data: { enabled: true } })
    hoisted.getIsvaProfileOverride.mockResolvedValue({ data: { override: 'INHERIT' } })
    const wrapper = mountControl()
    await flushPromises()
    const legend = wrapper.find('fieldset > legend')
    expect(legend.exists()).toBe(true)
    expect(legend.text()).toBe('IVIA Integration')
    // The checkbox label uses the abbreviation per the Policy-tab move:
    // the fieldset legend already spells IVIA out structurally, so the
    // label stays short to match the surrounding Policy panel copy.
    expect(wrapper.text()).toContain('Exempt this profile from IVIA provisioning')
  })

  it('shows the checkbox checked when the profile is FORCE_OFF', async () => {
    hoisted.getIsvaConfig.mockResolvedValue({ data: { enabled: true } })
    hoisted.getIsvaProfileOverride.mockResolvedValue({ data: { override: 'FORCE_OFF' } })
    const wrapper = mountControl()
    await flushPromises()
    const checkbox = wrapper.find('input[type="checkbox"]')
    expect(checkbox.exists()).toBe(true)
    expect((checkbox.element as HTMLInputElement).checked).toBe(true)
  })

  it('saves FORCE_OFF when toggled on and notifies success', async () => {
    hoisted.getIsvaConfig.mockResolvedValue({ data: { enabled: true } })
    hoisted.getIsvaProfileOverride.mockResolvedValue({ data: { override: 'INHERIT' } })
    hoisted.setIsvaProfileOverride.mockResolvedValue({ data: { override: 'FORCE_OFF' } })
    const wrapper = mountControl()
    await flushPromises()

    await wrapper.find('input[type="checkbox"]').setValue(true)
    await flushPromises()

    expect(hoisted.setIsvaProfileOverride).toHaveBeenCalledWith(DIR, PROFILE, 'FORCE_OFF')
    expect(hoisted.notifSuccess).toHaveBeenCalled()
  })

  it('reverts the toggle and notifies an error when the save fails', async () => {
    hoisted.getIsvaConfig.mockResolvedValue({ data: { enabled: true } })
    hoisted.getIsvaProfileOverride.mockResolvedValue({ data: { override: 'INHERIT' } })
    hoisted.setIsvaProfileOverride.mockRejectedValue({ response: { data: { detail: 'nope' } } })
    const wrapper = mountControl()
    await flushPromises()

    const checkbox = wrapper.find('input[type="checkbox"]')
    await checkbox.setValue(true)
    await flushPromises()

    expect(hoisted.notifError).toHaveBeenCalledWith('nope')
    expect((checkbox.element as HTMLInputElement).checked).toBe(false)
  })
})
