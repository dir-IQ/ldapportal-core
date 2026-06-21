// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'

// In-memory stand-in for the server-backed preferences document so the
// component's read/write round-trips without touching the API. Hoisted so the
// vi.mock factory (itself hoisted above imports) can close over it safely.
const store = vi.hoisted(() => ({ data: {} as Record<string, unknown> }))
vi.mock('@/stores/preferences', () => ({
  usePreferencesStore: () => ({
    read: (_ns: string, key: string, fb: unknown) => (key in store.data ? store.data[key] : fb),
    write: (_ns: string, key: string, val: unknown) => { store.data[key] = val },
  }),
}))

import RecentSearches from './RecentSearches.vue'

function mountIt(props: { directoryId?: string, storageKey?: string } = {}) {
  return mount(RecentSearches, {
    props: { directoryId: 'dir-1', storageKey: 'users', ...props },
  })
}

describe('RecentSearches', () => {
  beforeEach(() => { store.data = {} })

  it('starts collapsed and reveals an empty-state panel with no history', async () => {
    const wrapper = mountIt()
    expect(wrapper.text()).toContain('Recent searches')
    expect(wrapper.text()).not.toContain('No recent searches yet')
    await wrapper.find('button[aria-expanded]').trigger('click')
    expect(wrapper.text()).toContain('No recent searches yet')
  })

  it('records a filter, persists it, and renders it as a chip', async () => {
    const wrapper = mountIt()
    ;(wrapper.vm as unknown as { record: (f: string) => void }).record('(cn=jane*)')
    await wrapper.vm.$nextTick()
    expect(store.data['users:dir-1']).toEqual({ recent: ['(cn=jane*)'] })
    await wrapper.find('button[aria-expanded]').trigger('click')
    const chip = wrapper.findAll('button').find(b => b.text() === '(cn=jane*)')
    expect(chip).toBeTruthy()
  })

  it('ignores blank filters and de-duplicates, moving repeats to the front', async () => {
    const wrapper = mountIt()
    const vm = wrapper.vm as unknown as { record: (f: string) => void }
    vm.record('   ')          // blank — ignored
    vm.record('(uid=a)')
    vm.record('(uid=b)')
    vm.record('(uid=a)')      // repeat — moves to front, no duplicate
    await wrapper.vm.$nextTick()
    expect(store.data['users:dir-1']).toEqual({ recent: ['(uid=a)', '(uid=b)'] })
  })

  it('caps history at 10 entries', async () => {
    const wrapper = mountIt()
    const vm = wrapper.vm as unknown as { record: (f: string) => void }
    for (let i = 0; i < 15; i++) vm.record(`(n=${i})`)
    await wrapper.vm.$nextTick()
    const saved = store.data['users:dir-1'] as { recent: string[] }
    expect(saved.recent).toHaveLength(10)
    expect(saved.recent[0]).toBe('(n=14)') // newest first
  })

  it('emits select and collapses when a chip is picked', async () => {
    store.data['users:dir-1'] = { recent: ['(cn=x)'] }
    const wrapper = mountIt()
    await wrapper.find('button[aria-expanded]').trigger('click')
    const chip = wrapper.findAll('button').find(b => b.text() === '(cn=x)')
    await chip!.trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual(['(cn=x)'])
    // Panel collapses after a pick.
    expect(wrapper.text()).not.toContain('Click a search to run it again')
  })

  it('keeps User and Group histories separate via storageKey', async () => {
    const users = mountIt({ storageKey: 'users' })
    ;(users.vm as unknown as { record: (f: string) => void }).record('(objectClass=person)')
    const groups = mountIt({ storageKey: 'groups' })
    ;(groups.vm as unknown as { record: (f: string) => void }).record('(objectClass=groupOfNames)')
    expect(store.data['users:dir-1']).toEqual({ recent: ['(objectClass=person)'] })
    expect(store.data['groups:dir-1']).toEqual({ recent: ['(objectClass=groupOfNames)'] })
  })

  it('reloads history when the directory changes', async () => {
    store.data['users:dir-1'] = { recent: ['(cn=one)'] }
    store.data['users:dir-2'] = { recent: ['(cn=two)'] }
    const wrapper = mountIt({ directoryId: 'dir-1' })
    await wrapper.find('button[aria-expanded]').trigger('click')
    expect(wrapper.text()).toContain('(cn=one)')
    await wrapper.setProps({ directoryId: 'dir-2' })
    expect(wrapper.text()).toContain('(cn=two)')
    expect(wrapper.text()).not.toContain('(cn=one)')
  })
})
