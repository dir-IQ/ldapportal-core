// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useUnsavedChangesStore } from './unsavedChanges'

describe('unsavedChanges store', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('reports null when nothing is registered', () => {
    expect(useUnsavedChangesStore().dirtyPage()).toBeNull()
  })

  it('names the page whose predicate is dirty, evaluating lazily', () => {
    const store = useUnsavedChangesStore()
    let open = false
    store.register('Users', () => open)
    expect(store.dirtyPage()).toBeNull()
    open = true
    expect(store.dirtyPage()).toBe('Users')
  })

  it('returns the first dirty page in registration order', () => {
    const store = useUnsavedChangesStore()
    store.register('Dashboard', () => false)
    store.register('Groups', () => true)
    store.register('Users', () => true)
    expect(store.dirtyPage()).toBe('Groups')
  })

  it('unregister removes only that guard', () => {
    const store = useUnsavedChangesStore()
    const offUsers = store.register('Users', () => true)
    store.register('Groups', () => true)
    offUsers()
    expect(store.dirtyPage()).toBe('Groups')
    expect(store.guards).toHaveLength(1)
  })

  it('treats a throwing predicate as clean', () => {
    const store = useUnsavedChangesStore()
    store.register('Broken', () => { throw new Error('boom') })
    expect(store.dirtyPage()).toBeNull()
  })

  it('clear drops every registration', () => {
    const store = useUnsavedChangesStore()
    store.register('Users', () => true)
    store.clear()
    expect(store.dirtyPage()).toBeNull()
  })
})
