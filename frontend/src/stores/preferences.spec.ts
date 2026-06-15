// SPDX-License-Identifier: Apache-2.0
/**
 * Unit tests for the preferences store — the single client-side home for UI
 * customizations. Exercises read/write semantics, the coalesced flush, the
 * one-time migration off localStorage, and reset-on-logout.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

const getMock = vi.fn()
const patchMock = vi.fn()
vi.mock('@/api/preferences', () => ({
  getPreferences: () => getMock(),
  patchPreferences: (p: unknown) => patchMock(p),
}))

import { usePreferencesStore } from './preferences'

beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.clear()
  getMock.mockReset().mockResolvedValue({ data: {} })
  patchMock.mockReset().mockResolvedValue({ data: {} })
})

describe('read / write', () => {
  it('write updates the in-memory document immediately', () => {
    const store = usePreferencesStore()
    store.write('appearance', 'theme', 'dark')
    expect(store.read('appearance', 'theme', 'fallback')).toBe('dark')
  })

  it('read returns the fallback for an absent key', () => {
    const store = usePreferencesStore()
    expect(store.read('appearance', 'theme', 'light')).toBe('light')
  })

  it('read returns a clone — mutating it does not affect the store', () => {
    const store = usePreferencesStore()
    store.write('tables', 'audit', { hidden: ['a'] })
    const got = store.read<{ hidden: string[] }>('tables', 'audit', { hidden: [] })
    got.hidden.push('b')
    expect(store.read<{ hidden: string[] }>('tables', 'audit', { hidden: [] }).hidden).toEqual(['a'])
  })

  it('flush sends a coalesced merge-patch of all queued writes', async () => {
    const store = usePreferencesStore()
    store.write('appearance', 'theme', 'dark')
    store.write('appearance', 'density', 'compact')
    store.write('sidebar', 'collapsed', true)
    await store.flush()
    expect(patchMock).toHaveBeenCalledTimes(1)
    expect(patchMock).toHaveBeenCalledWith({
      appearance: { theme: 'dark', density: 'compact' },
      sidebar: { collapsed: true },
    })
  })

  it('remove queues a null so the server drops the key', async () => {
    const store = usePreferencesStore()
    store.remove('sidebar', 'collapsed')
    await store.flush()
    expect(patchMock).toHaveBeenCalledWith({ sidebar: { collapsed: null } })
  })

  it('flush with nothing pending makes no request', async () => {
    const store = usePreferencesStore()
    await store.flush()
    expect(patchMock).not.toHaveBeenCalled()
  })

  it('re-queues the patch when the server write fails', async () => {
    const store = usePreferencesStore()
    patchMock.mockRejectedValueOnce(new Error('offline'))
    store.write('appearance', 'theme', 'dark')
    await store.flush()                 // fails, re-queues
    patchMock.mockResolvedValueOnce({ data: {} })
    await store.flush()                 // retries
    expect(patchMock).toHaveBeenCalledTimes(2)
    expect(patchMock).toHaveBeenLastCalledWith({ appearance: { theme: 'dark' } })
  })
})

describe('hydrate', () => {
  it('loads the document from the server', async () => {
    getMock.mockResolvedValueOnce({ data: { appearance: { theme: 'dark' } } })
    const store = usePreferencesStore()
    await store.hydrate()
    expect(store.ready).toBe(true)
    expect(store.read('appearance', 'theme', 'light')).toBe('dark')
  })

  it('is resilient to a failed load (e.g. 403 for self-service)', async () => {
    getMock.mockRejectedValueOnce(new Error('forbidden'))
    const store = usePreferencesStore()
    await store.hydrate()
    expect(store.ready).toBe(true)
    expect(store.read('appearance', 'theme', 'light')).toBe('light')
  })
})

describe('one-time migration off localStorage', () => {
  it('folds legacy keys into the document and clears them', async () => {
    localStorage.setItem('ldapportal-theme', 'dark')
    localStorage.setItem('ldapportal-density', 'compact')
    localStorage.setItem('ldapportal.table-prefs.v1:audit', JSON.stringify({ pageSize: 25 }))
    localStorage.setItem('saved-filters:reports', JSON.stringify([{ name: 'mine' }]))
    localStorage.setItem('ldap-search-history', JSON.stringify([{ filter: '(cn=*)' }]))

    const store = usePreferencesStore()
    await store.hydrate()

    expect(patchMock).toHaveBeenCalledTimes(1)
    const patch = patchMock.mock.calls[0][0]
    expect(patch.appearance).toEqual({ theme: 'dark', density: 'compact' })
    expect(patch.tables.audit).toEqual({ pageSize: 25 })
    expect(patch.filters.reports).toEqual([{ name: 'mine' }])
    expect(patch.search.directory.history).toEqual([{ filter: '(cn=*)' }])

    // localStorage cleared once the upload succeeded.
    expect(localStorage.getItem('ldapportal-theme')).toBeNull()
    expect(localStorage.getItem('ldapportal.table-prefs.v1:audit')).toBeNull()
    expect(localStorage.getItem('saved-filters:reports')).toBeNull()
  })

  it('does not overwrite values the server already has', async () => {
    getMock.mockResolvedValueOnce({ data: { appearance: { theme: 'light' } } })
    localStorage.setItem('ldapportal-theme', 'dark')

    const store = usePreferencesStore()
    await store.hydrate()

    // Server already had appearance.theme — the stale local value is dropped,
    // not uploaded, and the localStorage key is still cleared.
    expect(patchMock).not.toHaveBeenCalled()
    expect(localStorage.getItem('ldapportal-theme')).toBeNull()
    expect(store.read('appearance', 'theme', 'x')).toBe('light')
  })

  it('makes no request when there is nothing to migrate', async () => {
    const store = usePreferencesStore()
    await store.hydrate()
    expect(patchMock).not.toHaveBeenCalled()
  })
})

describe('clear', () => {
  it('resets the document and ready flag', async () => {
    const store = usePreferencesStore()
    store.write('appearance', 'theme', 'dark')
    store.clear()
    expect(store.ready).toBe(false)
    expect(store.read('appearance', 'theme', 'light')).toBe('light')
  })
})
