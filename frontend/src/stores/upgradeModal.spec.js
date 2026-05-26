// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useUpgradeModalStore } from './upgradeModal'

describe('useUpgradeModalStore', () => {
  beforeEach(() => {
    // Each test gets a fresh Pinia instance — no state leakage across tests.
    setActivePinia(createPinia())
  })

  it('starts hidden with null params', () => {
    const store = useUpgradeModalStore()
    expect(store.visible).toBe(false)
    expect(store.params).toBeNull()
  })

  it('show() makes the modal visible and sets params', () => {
    const store = useUpgradeModalStore()
    const payload = { code: 'LIMIT_EXCEEDED', limitType: 'directories', currentCount: 5, maximum: 5, currentEdition: 'COMMUNITY' }
    store.show(payload)
    expect(store.visible).toBe(true)
    expect(store.params).toEqual(payload)
  })

  it('hide() makes the modal invisible but preserves params', () => {
    const store = useUpgradeModalStore()
    const payload = { code: 'ENTITLEMENT_MISSING', entitlement: 'HYBRID', currentEdition: 'COMMUNITY' }
    store.show(payload)
    store.hide()
    expect(store.visible).toBe(false)
    // Params intentionally preserved so closing animation can still read them.
    expect(store.params).toEqual(payload)
  })

  it('show() called twice replaces params', () => {
    const store = useUpgradeModalStore()
    store.show({ code: 'LIMIT_EXCEEDED', limitType: 'a' })
    store.show({ code: 'ENTITLEMENT_MISSING', entitlement: 'b' })
    expect(store.params.code).toBe('ENTITLEMENT_MISSING')
    expect(store.params.entitlement).toBe('b')
    expect(store.visible).toBe(true)
  })

  it('show() then hide() then show() goes back to visible', () => {
    const store = useUpgradeModalStore()
    store.show({ code: 'LIMIT_EXCEEDED' })
    store.hide()
    expect(store.visible).toBe(false)
    store.show({ code: 'ENTITLEMENT_MISSING' })
    expect(store.visible).toBe(true)
  })
})
