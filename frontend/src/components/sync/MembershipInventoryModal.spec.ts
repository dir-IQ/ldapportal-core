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
  lastSrcCursor: null, lastScanEpoch: null, reviewKind: null as string | null,
}
const page = (content: unknown[]) => ({
  data: { content, totalElements: content.length, totalPages: 1, number: 0, size: 50 },
})

vi.mock('@/api/sync', () => ({
  listMemberships: vi.fn(() => Promise.resolve(page([membership]))),
  reconcileSet: vi.fn(() => Promise.resolve({ data: { enumerated: 3 } })),
  recomputeKey: vi.fn(() => Promise.resolve({ data: undefined })),
  dismissMembership: vi.fn(() => Promise.resolve({ data: undefined })),
  approveDelete: vi.fn(() => Promise.resolve({
    data: { outcome: 'DELETE', targetDirectoryId: 'dir-2', targetDn: 'uid=a,ou=Users,dc=dst', message: null },
  })),
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

const confirmMock = vi.hoisted(() => vi.fn(() => Promise.resolve(true)))
vi.mock('@/composables/useConfirm', () => ({ useConfirm: () => confirmMock }))

import * as syncApi from '@/api/sync'

/** List exactly one membership row, overriding the default fixture's fields. */
function withRow(over: Partial<typeof membership>) {
  ;(syncApi.listMemberships as unknown as { mockResolvedValue: (v: unknown) => void })
    .mockResolvedValue(page([{ ...membership, ...over }]))
}

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
    withRow({}) // tests below override the listed row; start each from the fixture
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

  it('opens pre-filtered when given an initialState (dashboard deep link)', async () => {
    const wrapper = mount(MembershipInventoryModal, {
      attachTo: document.body,
      props: { show: true, set, sourceName: 'Source', targetName: 'Target', initialState: 'FAILED' as const },
    })
    await flushPromises()
    // The very first load is already scoped to the deep-linked state.
    expect(syncApi.listMemberships).toHaveBeenCalledWith('set-1', expect.objectContaining({ state: 'FAILED' }))
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

  it('labels a held delete "Delete target" and approves it after a danger confirm', async () => {
    withRow({ reviewKind: 'PENDING_DELETE', failReason: 'scope-exit held for review (deletePolicy=REVIEW)' })
    const wrapper = open()
    await flushPromises()
    expect(byText('Keep entry, stop syncing')).toBeTruthy()
    expect(byText('Re-check')).toBeTruthy()
    byText('Delete target')!.click()
    await flushPromises()
    expect(confirmMock).toHaveBeenCalledWith(expect.objectContaining({ danger: true, confirmLabel: 'Delete entry' }))
    expect(syncApi.approveDelete).toHaveBeenCalledWith('set-1', '1111')
    wrapper.unmount()
  })

  it('does not delete when the confirm is cancelled', async () => {
    withRow({ reviewKind: 'PENDING_DELETE' })
    confirmMock.mockResolvedValueOnce(false)
    const wrapper = open()
    await flushPromises()
    byText('Delete target')!.click()
    await flushPromises()
    expect(syncApi.approveDelete).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('"Keep entry, stop syncing" dismisses the row after confirming', async () => {
    withRow({ reviewKind: 'PENDING_DELETE' })
    const wrapper = open()
    await flushPromises()
    byText('Keep entry, stop syncing')!.click()
    await flushPromises()
    expect(confirmMock).toHaveBeenCalledWith(expect.objectContaining({ confirmLabel: 'Keep and stop syncing' }))
    expect(syncApi.dismissMembership).toHaveBeenCalledWith('set-1', '1111')
    wrapper.unmount()
  })

  it('names the recompute for the row: Retry match, Retry, Re-sync', async () => {
    for (const [over, label] of [
      [{ state: 'REVIEW', reviewKind: 'AMBIGUOUS_MATCH' }, 'Retry match'],
      [{ state: 'FAILED', reviewKind: null }, 'Retry'],
      [{ state: 'APPLIED', reviewKind: null, failReason: null }, 'Re-sync'],
    ] as const) {
      vi.clearAllMocks()
      document.body.innerHTML = ''
      withRow(over as Partial<typeof membership>)
      const wrapper = open()
      await flushPromises()
      const btn = Array.from(document.querySelectorAll<HTMLButtonElement>('button'))
        .find((b) => b.textContent?.trim() === label)
      expect(btn, label).toBeTruthy()
      btn!.click()
      await flushPromises()
      expect(syncApi.recomputeKey).toHaveBeenCalledWith('set-1', '1111')
      expect(byText('Delete target')).toBeUndefined()
      wrapper.unmount()
    }
  })

  it('"Stop tracking" asks first and dismisses only when confirmed', async () => {
    withRow({ state: 'FAILED', reviewKind: null })
    confirmMock.mockResolvedValueOnce(false)
    const wrapper = open()
    await flushPromises()
    byText('Stop tracking')!.click()
    await flushPromises()
    expect(syncApi.dismissMembership).not.toHaveBeenCalled()
    byText('Stop tracking')!.click()
    await flushPromises()
    expect(syncApi.dismissMembership).toHaveBeenCalledWith('set-1', '1111')
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
