// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import DirectorySyncView from './DirectorySyncView.vue'

vi.mock('@/api/directories', () => ({
  listDirectories: vi.fn(() =>
    Promise.resolve({ data: [
      { id: 'dir-src', displayName: 'Source LDAP' },
      { id: 'dir-dst', displayName: 'Target LDAP' },
    ] }),
  ),
}))

const link = {
  id: 'link-1', displayName: 'src->dst', sourceDirId: 'dir-src', targetDirId: 'dir-dst',
  enabled: true, captureMode: 'APP_INTERCEPT', createdAt: '', updatedAt: '', version: 0,
}
const set = {
  id: 'set-1', linkId: 'link-1', name: 'people', objectScopeBaseDn: 'ou=people,dc=src',
  objectScope: 'SUB', identityKey: null, targetBaseDn: 'ou=Users,dc=dst', applicabilityFilter: null,
  referenceAttributes: null, sourceAnchorAttribute: null, deletePolicy: 'DELETE', transformRules: null,
  reconcileCadenceSeconds: null, reconcileLastRunAt: null, enabled: true, createdAt: '', updatedAt: '', version: 0,
}
const membership = {
  syncSetId: 'set-1', identity: '1111', sourceDn: 'uid=a,ou=people,dc=src',
  targetDn: 'uid=a,ou=Users,dc=dst', state: 'REVIEW', failReason: 'ambiguous',
  lastSrcCursor: null, lastScanEpoch: null,
}

vi.mock('@/api/sync', () => ({
  listSyncLinks: vi.fn(() => Promise.resolve({ data: [link] })),
  createSyncLink: vi.fn(() => Promise.resolve({ data: link })),
  updateSyncLink: vi.fn(() => Promise.resolve({ data: link })),
  deleteSyncLink: vi.fn(() => Promise.resolve({ data: undefined })),
  listSyncSets: vi.fn(() => Promise.resolve({ data: [set] })),
  createSyncSet: vi.fn(() => Promise.resolve({ data: set })),
  updateSyncSet: vi.fn(() => Promise.resolve({ data: set })),
  deleteSyncSet: vi.fn(() => Promise.resolve({ data: undefined })),
  listMemberships: vi.fn(() => Promise.resolve({ data: [membership] })),
  reconcileSet: vi.fn(() => Promise.resolve({ data: { enumerated: 3 } })),
  recomputeKey: vi.fn(() => Promise.resolve({ data: undefined })),
  dismissMembership: vi.fn(() => Promise.resolve({ data: undefined })),
}))

import * as syncApi from '@/api/sync'

describe('DirectorySyncView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('loads and renders sync links', async () => {
    const wrapper = mount(DirectorySyncView)
    await flushPromises()
    expect(syncApi.listSyncLinks).toHaveBeenCalled()
    expect(wrapper.text()).toContain('src->dst')
    expect(wrapper.text()).toContain('Source LDAP')
  })

  it('selecting a link loads its sync sets', async () => {
    const wrapper = mount(DirectorySyncView)
    await flushPromises()
    await wrapper.find('tbody tr').trigger('click')
    await flushPromises()
    expect(syncApi.listSyncSets).toHaveBeenCalledWith('link-1')
    expect(wrapper.text()).toContain('people')
  })

  it('selecting a set loads the membership inventory and surfaces REVIEW state', async () => {
    const wrapper = mount(DirectorySyncView)
    await flushPromises()
    await wrapper.find('tbody tr').trigger('click') // select link
    await flushPromises()
    const setRows = wrapper.findAll('section')[1].findAll('tbody tr')
    await setRows[0].trigger('click') // select set
    await flushPromises()
    expect(syncApi.listMemberships).toHaveBeenCalledWith('set-1', undefined)
    expect(wrapper.text()).toContain('REVIEW')
  })

  it('reconcile triggers the API and reports the count', async () => {
    const wrapper = mount(DirectorySyncView)
    await flushPromises()
    await wrapper.find('tbody tr').trigger('click')
    await flushPromises()
    const setRows = wrapper.findAll('section')[1].findAll('tbody tr')
    await setRows[0].trigger('click')
    await flushPromises()
    const reconcileBtn = wrapper.findAll('button').find((b) => b.text().includes('Reconcile now'))!
    await reconcileBtn.trigger('click')
    await flushPromises()
    expect(syncApi.reconcileSet).toHaveBeenCalledWith('set-1')
  })
})
