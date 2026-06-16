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
  stateCounts: { APPLIED: 5, FAILED: 2, REVIEW: 1 },
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
  getExcludedAttributeDefaults: vi.fn(() => Promise.resolve({ data: ['userPassword', 'entryUUID'] })),
  previewReconcile: vi.fn(() => Promise.resolve({ data: {
    sourceCount: 3, managedCount: 3, plannedAdds: 0, plannedDeletes: 0,
    sampleDeleteDns: [], guardTripped: false, guardReason: null, completeScan: true,
  } })),
  listMemberships: vi.fn(() => Promise.resolve({
    data: { content: [membership], totalElements: 8, totalPages: 1, number: 0, size: 50 },
  })),
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

  it('selecting a set opens the membership inventory modal', async () => {
    const wrapper = mount(DirectorySyncView, { attachTo: document.body })
    await flushPromises()
    await wrapper.find('tbody tr').trigger('click') // expand link
    await flushPromises()
    const setRow = wrapper.findAll('table')[1].findAll('tbody tr')[0]
    await setRow.trigger('click') // select set → opens the (teleported) modal
    await flushPromises()
    // The modal teleports to <body>; it loads the set's memberships and titles itself.
    expect(syncApi.listMemberships).toHaveBeenCalledWith('set-1', expect.objectContaining({ page: 0, size: 50 }))
    expect(document.body.textContent).toContain('Membership inventory — people')
    wrapper.unmount()
  })

  it('rolls up membership health onto the link row (worst state wins)', async () => {
    const wrapper = mount(DirectorySyncView)
    await flushPromises()
    // FAILED outranks REVIEW/APPLIED, so the link shows the failure summary.
    expect(wrapper.find('tbody tr').text()).toContain('2 failed')
  })

  it('authoring a transform rule includes it in the create payload', async () => {
    const wrapper = mount(DirectorySyncView, { attachTo: document.body })
    await flushPromises()
    await wrapper.find('tbody tr').trigger('click') // select link
    await flushPromises()
    // The set modal teleports to <body>; drive it through document.
    const byText = (t: string) =>
      Array.from(document.querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent?.includes(t))!
    byText('New Set').click()
    await flushPromises()

    const nameLabel = Array.from(document.querySelectorAll('label')).find((l) => l.textContent?.trim().startsWith('Name'))!
    const nameInput = document.getElementById((nameLabel as HTMLLabelElement).htmlFor) as HTMLInputElement
    nameInput.value = 'groups'
    nameInput.dispatchEvent(new Event('input'))

    byText('Add mapping').click()
    await flushPromises()
    const src = document.getElementById('tr-src-0') as HTMLInputElement
    src.value = 'uid'
    src.dispatchEvent(new Event('input'))
    const tgt = document.getElementById('tr-tgt-0') as HTMLInputElement
    tgt.value = 'sAMAccountName'
    tgt.dispatchEvent(new Event('input'))
    await flushPromises()

    document.querySelector('form')!.dispatchEvent(new Event('submit'))
    await flushPromises()

    expect(syncApi.createSyncSet).toHaveBeenCalledTimes(1)
    const payload = (syncApi.createSyncSet as unknown as { mock: { calls: unknown[][] } }).mock.calls[0][0] as {
      transformRules: unknown
    }
    expect(payload.transformRules).toEqual([
      { sourceAttr: 'uid', targetAttr: 'sAMAccountName', valueTemplate: null },
    ])
    wrapper.unmount()
  })
})
