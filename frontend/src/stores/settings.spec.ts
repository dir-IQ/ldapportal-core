// SPDX-License-Identifier: Apache-2.0
/**
 * Settings store: focused on apply() refreshing enabledAuthTypes.
 *
 * Regression guard — init() fetches the public branding (incl. enabledAuthTypes)
 * exactly once, so after an operator saves Settings → Authentication the store
 * must pick up the new auth-method list from apply(). Without that, a freshly
 * enabled method (e.g. WEBSEAL) is missing from the login page and the
 * admin-user "Auth type" dropdown until a full page reload.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

vi.mock('@/api/settings', () => ({
  getBranding: vi.fn().mockResolvedValue({ data: {} }),
}))

import { useSettingsStore } from './settings'

beforeEach(() => setActivePinia(createPinia()))

describe('settings store apply()', () => {
  it('refreshes enabledAuthTypes so a saved auth-method change applies without a reload', () => {
    const store = useSettingsStore()
    expect(store.enabledAuthTypes).toEqual(['LOCAL']) // boot default

    // Mirrors SettingsView calling brandingStore.apply(form) after a save that
    // turned on WEBSEAL.
    store.apply({ appName: 'X', enabledAuthTypes: ['LOCAL', 'WEBSEAL'] })

    expect(store.enabledAuthTypes).toEqual(['LOCAL', 'WEBSEAL'])
  })

  it('leaves enabledAuthTypes untouched on a branding-only apply()', () => {
    const store = useSettingsStore()
    store.apply({ enabledAuthTypes: ['LOCAL', 'OIDC'] })
    store.apply({ appName: 'Y' }) // no enabledAuthTypes key
    expect(store.enabledAuthTypes).toEqual(['LOCAL', 'OIDC'])
  })
})
