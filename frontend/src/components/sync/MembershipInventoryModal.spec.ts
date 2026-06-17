// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import MembershipInventoryModal from './MembershipInventoryModal.vue'

const set = {
  id: 'set-1', linkId: 'link-1', name: 'people', objectScopeBaseDn: null, objectScope: 'SUB' as const,
  identityKey: null, targetBaseDn: null, applicabilityFilter: null, referenceAttributes: null,
  sourceAnchorAttribute: null, deletePolicy: 'DELETE' as const, transformRules: null,
  excludedAttributes: null, reconcileCadenceSeconds: null,
  reconcileLastRunAt: null, enabled: true, createdAt: '', updatedAt: '', version: 0,
  stateCounts: { APPLIED: 5, FAILED: 2, REVIEW: 1 },
}
const membership = {
  syncSetId: 'set-1', identity: '1111', sourceDn: 'uid=a,ou=people,dc=src',
  targetDn: 'uid=a,ou=Users,dc=dst', state: 'REVIEW', failReason: 'ambiguous',
  lastSrcCursor: null, lastScanEpoch: null,
}
const page = (content: unknown[]) => ({
  data: { content, totalElements: content.length, totalPages: 1, number: 0, size: 50 },
})

vi.mock('@/api/sync', () => ({
  listMemberships: vi.fn(() => Promise.resolve(page([membership]))),
  reconcileSet: vi.fn(() => Promise.resolve({ data: { enumerated: 3 } })),
  recomputeKey: vi.fn(() => Promise.resolve({ data: undefined })),
  dismissMembership: vi.fn(() => Promise.resolve({ data: undefined })),
  verifyContents: vi.fn(() => Promise.resolve({
    data: {
      sourceMembers: 5, targetEntries: 4, inSync: 3,
      missingOnTarget: 1, orphanOnTarget: 0, contentMismatches: 1,
      sampleMissing: ['uid=gone,ou=Users,dc=dst'], sampleOrphans: [],
      sampleMismatches: ['uid=drift,ou=Users,dc=dst'],
      sourceComplete: true, targetComplete: true, note: null,
    },
  })),
}))

import * as syncApi from '@/api/sync'

function open() {
  return mount(MembershipInventoryModal, {
    attachTo: document.body,
    props: { show: true, set, sourceName: 'Source', targetName: 'Target' },
  })
}
const byText = (t: string) =>
  Array.from(document.querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent?.includes(t))

describe('MembershipInventoryModal', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    document.body.innerHTML = ''
  })

  it('loads memberships on open and renders state chips with counts', async () => {
    const wrapper = open()
    await flushPromises()
    expect(syncApi.listMemberships).toHaveBeenCalledWith('set-1', expect.objectContaining({ page: 0, size: 50 }))
    const txt = document.body.textContent ?? ''
    expect(txt).toContain('All') // All chip
    expect(txt).toContain('Applied')
    expect(txt).toContain('Failed')
    expect(txt).toContain('Membership inventory — people')
    wrapper.unmount()
  })

  it('clicking a state chip filters by that state', async () => {
    const wrapper = open()
    await flushPromises()
    byText('Failed')!.click()
    await flushPromises()
    expect(syncApi.listMemberships).toHaveBeenLastCalledWith('set-1', expect.objectContaining({ state: 'FAILED' }))
    wrapper.unmount()
  })

  it('row Recompute calls the API with the identity', async () => {
    const wrapper = open()
    await flushPromises()
    byText('Recompute')!.click()
    await flushPromises()
    expect(syncApi.recomputeKey).toHaveBeenCalledWith('set-1', '1111')
    wrapper.unmount()
  })

  it('Verify contents calls the API and renders the mismatch summary', async () => {
    const wrapper = open()
    await flushPromises()
    byText('Verify contents')!.click()
    await flushPromises()
    expect(syncApi.verifyContents).toHaveBeenCalledWith('set-1')
    const txt = document.body.textContent ?? ''
    expect(txt).toContain('Content verification')
    expect(txt).toContain('uid=gone,ou=Users,dc=dst') // sample missing DN
    expect(txt).toContain('uid=drift,ou=Users,dc=dst') // sample drift DN
    wrapper.unmount()
  })

  it('offers to recompute the search term when nothing matches', async () => {
    ;(syncApi.listMemberships as unknown as { mockResolvedValue: (v: unknown) => void })
      .mockResolvedValue(page([]))
    const wrapper = open()
    await flushPromises()
    const input = document.querySelector('input[aria-label="Filter memberships"]') as HTMLInputElement
    input.value = 'uid=ghost,dc=x'
    input.dispatchEvent(new Event('input'))
    await new Promise((r) => setTimeout(r, 300)) // debounce
    await flushPromises()
    const btn = byText('Recompute "uid=ghost,dc=x"')!
    expect(btn).toBeTruthy()
    btn.click()
    await flushPromises()
    expect(syncApi.recomputeKey).toHaveBeenCalledWith('set-1', 'uid=ghost,dc=x')
    wrapper.unmount()
  })
})
