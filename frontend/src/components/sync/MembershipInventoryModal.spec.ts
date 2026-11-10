// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import MembershipInventoryModal from './MembershipInventoryModal.vue'
import { useNotificationStore } from '@/stores/notifications'
import { useAuthStore } from '@/stores/auth'

const set = {
  id: 'set-1', linkId: 'link-1', name: 'people', objectScopeBaseDn: null, objectScope: 'SUB' as const,
  identityKey: null, targetBaseDn: null, applicabilityFilter: null, referenceAttributes: null,
  sourceAnchorAttribute: null, deletePolicy: 'DELETE' as const, transformRules: null,
  excludedAttributes: null, reconcileCadenceSeconds: null,
  reconcileLastRunAt: null, enabled: true, loggingLevel: 'SUMMARY' as const, createdAt: '', updatedAt: '', version: 0,
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

const notInSetPage = vi.hoisted(() => (over: Record<string, unknown> = {}) => ({
  runId: 'run-1', finishedAt: '2026-10-29T10:00:00Z', allDnsRecorded: false, total: 412,
  reasons: [
    { reason: 'FILTER', label: "Doesn't match the applicability filter", count: 400, samples: [] },
    { reason: 'PLACEMENT', label: "Can't be placed under the target base", count: 12, samples: [] },
  ],
  entries: [{ sourceDn: 'ou=people,dc=src', reason: 'FILTER', label: "Doesn't match the applicability filter" }],
  matched: 37, page: 0, size: 50, sampleLimit: 25,
  ...over,
}))

const driftPage = vi.hoisted(() => (over: Record<string, unknown> = {}) => ({
  verifiedAt: '2026-11-05T08:00:00Z', incompleteAt: null, detailsRecorded: true,
  counts: { MISSING: 1, ORPHAN: 0, MISMATCH: 1 }, matched: 2, page: 0, size: 25,
  entries: [
    { seq: 0, category: 'MISSING', targetDn: 'uid=gone,ou=Users,dc=dst', sourceDn: 'uid=gone,ou=people,dc=src',
      identity: 'gone', attributes: [] },
    { seq: 1, category: 'MISMATCH', targetDn: 'uid=drift,ou=Users,dc=dst', sourceDn: 'uid=drift,ou=people,dc=src',
      identity: 'drift', attributes: [{ attribute: 'title', expected: ['Lead'], actual: ['Engineer'], hidden: false, truncated: false }] },
  ],
  ...over,
}))

vi.mock('@/api/sync', () => ({
  getDrift: vi.fn(() => Promise.resolve({ data: driftPage() })),
  getHeldReferences: vi.fn(() => Promise.resolve({ data: {
    verifiedAt: '2026-11-05T08:00:00Z', detailsRecorded: true, counts: { DANGLING: 1, UNSYNCED: 1, UNCHECKED: 0 },
    matched: 1, page: 0, size: 25, entries: [{ seq: 0, entryDn: 'cn=a,dc=dst', sourceDn: 'cn=a,dc=src', identity: 'a',
      attribute: 'secDN', reference: 'uid=ghost,dc=src', status: 'DANGLING' }],
  } })),
  downloadDriftCsv: vi.fn(() => Promise.resolve({ data: new Blob() })),
  listMemberships: vi.fn(() => Promise.resolve(page([membership]))),
  startReconcile: vi.fn(() => Promise.resolve({ data: { started: true } })),
  recheckTarget: vi.fn(() => Promise.resolve({ data: { entries: 5, reconcileQueued: true } })),
  listReconcileRuns: vi.fn(() => Promise.resolve({ data: [] })),
  getSyncSetActivity: vi.fn(() => Promise.resolve({ data: { reconcile: null, verify: null, asOf: new Date().toISOString() } })),
  listNotInSet: vi.fn(() => Promise.resolve({ data: notInSetPage() })),
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
const byExactText = (t: string) =>
  Array.from(document.querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent?.trim() === t)
const tabButton = (label: string) =>
  Array.from(document.querySelectorAll<HTMLButtonElement>('[role="tab"]')).find((b) => b.textContent?.includes(label))!
async function openDriftTab() {
  tabButton('Drift').click()
  await flushPromises()
}

describe('MembershipInventoryModal', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    withRow({}) // tests below override the listed row; start each from the fixture
    document.body.innerHTML = ''
  })

  it('links to Directory Diff in a new tab, filled in from this set, for superadmins who may run diffs', async () => {
    const auth = useAuthStore()
    const diffLink = () => Array.from(document.querySelectorAll<HTMLAnchorElement>('a'))
      .find(a => a.textContent?.includes('Compare in Directory Diff'))
    let w = open()
    await flushPromises()
    await openDriftTab()
    expect(diffLink()).toBeUndefined()
    w.unmount()

    auth.principal = { superadminPermissions: ['superadmin.manage_directory_diff'] } as never
    w = open()
    await flushPromises()
    await openDriftTab()
    const link = diffLink()!
    // A new tab: following it never closes the inventory.
    expect(link.getAttribute('target')).toBe('_blank')
    expect(link.getAttribute('href')).toBe(`/superadmin/directory-diff?verifySet=${set.id}`)
    w.unmount()
  })

  it('counts held deletes once in the All chip (they are part of Review)', async () => {
    const w = mount(MembershipInventoryModal, {
      attachTo: document.body,
      props: { show: true, set: { ...set, stateCounts: { APPLIED: 40, REVIEW: 5, REVIEW_DELETE: 3 } },
        sourceName: 'Source', targetName: 'Target' },
    })
    await flushPromises()
    const all = byExactText('All 45') ?? Array.from(document.querySelectorAll('button'))
      .find((b) => b.textContent?.replace(/\s+/g, ' ').trim() === 'All 45')
    expect(all).toBeTruthy()
    w.unmount()
  })

  it('loads memberships on open and renders state chips with counts', async () => {
    const wrapper = open()
    await flushPromises()
    expect(syncApi.listMemberships).toHaveBeenCalledWith('set-1', expect.objectContaining({ page: 0, size: 50 }))
    const txt = document.body.textContent ?? ''
    expect(txt).toContain('All') // All chip
    expect(txt).toContain('Synced')
    expect(txt).not.toContain('Applied')
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

  // ── Bulk actions on selected rows ──────────────────────────────────────────

  /** List these membership rows: the fixture with each override, identities 1111, 2222, … unless given. */
  function withRows(...overs: Record<string, unknown>[]) {
    vi.mocked(syncApi.listMemberships).mockResolvedValue(page(overs.map((o, i) => ({
      ...membership, identity: String(i + 1).repeat(4), ...o,
    }))) as never)
  }
  async function selectRow(identity: string) {
    document.querySelector<HTMLInputElement>(`input[aria-label="Select row ${identity}"]`)!.click()
    await flushPromises()
  }
  async function selectAll() {
    document.querySelector<HTMLInputElement>('input[aria-label="Select all rows"]')!.click()
    await flushPromises()
  }

  it('has no per-row actions; the bulk actions wait for a selection', async () => {
    const wrapper = open()
    await flushPromises()
    const headers = Array.from(document.querySelectorAll('th')).map((th) => th.textContent?.trim())
    expect(headers).not.toContain('Actions')
    expect(document.body.textContent).toContain('Select rows to act on them')
    expect(byExactText('Recompute')!.disabled).toBe(true)
    expect(byExactText('Stop tracking')!.disabled).toBe(true)

    await selectRow('1111')
    expect(document.body.textContent).toContain('1 selected')
    expect(byExactText('Recompute (1)')!.disabled).toBe(false)
    expect(byExactText('Stop tracking (1)')!.disabled).toBe(false)
    byExactText('Clear selection')!.click()
    await flushPromises()
    expect(byExactText('Recompute')!.disabled).toBe(true)
    wrapper.unmount()
  })

  it('Recompute calls the API with the selected row\'s identity', async () => {
    const wrapper = open()
    await flushPromises()
    await selectRow('1111')
    byExactText('Recompute (1)')!.click()
    await flushPromises()
    expect(syncApi.recomputeKey).toHaveBeenCalledWith('set-1', '1111')
    wrapper.unmount()
  })

  it('recomputes every selected row, then reports what they became', async () => {
    vi.useFakeTimers()
    try {
      withRows({ state: 'FAILED', reviewKind: null }, { state: 'FAILED', reviewKind: null })
      const wrapper = open()
      await flushPromises()
      const success = vi.spyOn(useNotificationStore(), 'success')
      await selectAll()

      byExactText('Retry (2)')!.click()
      await flushPromises()
      expect(syncApi.recomputeKey).toHaveBeenCalledWith('set-1', '1111')
      expect(syncApi.recomputeKey).toHaveBeenCalledWith('set-1', '2222')
      expect(success).toHaveBeenCalledWith('Retry queued for 2 rows: watching them for the result…')

      withRows({ state: 'APPLIED', reviewKind: null, failReason: null },
        { state: 'APPLIED', reviewKind: null, failReason: null })
      await vi.advanceTimersByTimeAsync(2000)
      expect(success).toHaveBeenLastCalledWith('Retry done: 2 now Synced')
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('offers Delete target only for selected held deletes and approves them after a danger confirm', async () => {
    withRows({ state: 'FAILED', reviewKind: null }, { reviewKind: 'PENDING_DELETE' })
    const wrapper = open()
    await flushPromises()
    await selectRow('1111')
    expect(byText('Delete target')).toBeUndefined()

    await selectRow('2222')
    byExactText('Delete target (1)')!.click()
    await flushPromises()
    expect(confirmMock).toHaveBeenCalledWith(expect.objectContaining({
      danger: true, confirmLabel: 'Delete entry',
      message: expect.stringContaining('The other 1 selected row isn\'t a held delete and is left alone.'),
    }))
    expect(syncApi.approveDelete).toHaveBeenCalledTimes(1)
    expect(syncApi.approveDelete).toHaveBeenCalledWith('set-1', '2222')
    wrapper.unmount()
  })

  it('deletes selected held deletes children first, so a parent isn\'t refused for its children', async () => {
    withRows(
      { identity: 'p', reviewKind: 'PENDING_DELETE', targetDn: 'ou=team,ou=Users,dc=dst' },
      { identity: 'c', reviewKind: 'PENDING_DELETE', targetDn: 'uid=a,ou=team,ou=Users,dc=dst' },
    )
    const wrapper = open()
    await flushPromises()
    await selectAll()
    byExactText('Delete target (2)')!.click()
    await flushPromises()
    expect(vi.mocked(syncApi.approveDelete).mock.calls.map(c => c[1])).toEqual(['c', 'p'])
    wrapper.unmount()
  })

  it('deletes several held deletes in one go and sums up the outcomes', async () => {
    withRows({ reviewKind: 'PENDING_DELETE' }, { reviewKind: 'PENDING_DELETE' }, { reviewKind: 'PENDING_DELETE' })
    vi.mocked(syncApi.approveDelete)
      .mockResolvedValueOnce({ data: { outcome: 'DELETE', targetDirectoryId: 'd', targetDn: 'uid=a', message: null } } as never)
      .mockResolvedValueOnce({ data: { outcome: 'FORGET', targetDirectoryId: 'd', targetDn: 'uid=b', message: null } } as never)
      .mockResolvedValueOnce({ data: { outcome: 'ERROR', targetDirectoryId: 'd', targetDn: 'uid=c', message: 'busy' } } as never)
    const wrapper = open()
    await flushPromises()
    const notif = useNotificationStore()
    const success = vi.spyOn(notif, 'success')
    const error = vi.spyOn(notif, 'error')
    await selectAll()

    byExactText('Delete target (3)')!.click()
    await flushPromises()
    expect(confirmMock).toHaveBeenCalledWith(expect.objectContaining({
      title: 'Delete 3 target entries?', confirmLabel: 'Delete 3 entries',
    }))
    expect(syncApi.approveDelete).toHaveBeenCalledTimes(3)
    expect(success).toHaveBeenCalledWith(
      'Deleted 1 target entry; kept 1 that another sync identity owns (only their rows were removed)')
    expect(error).toHaveBeenCalledWith('1 of 3 deletions failed: busy')
    wrapper.unmount()
  })

  it('does not delete when the confirm is cancelled', async () => {
    withRow({ reviewKind: 'PENDING_DELETE' })
    confirmMock.mockResolvedValueOnce(false)
    const wrapper = open()
    await flushPromises()
    await selectRow('1111')
    byText('Delete target')!.click()
    await flushPromises()
    expect(syncApi.approveDelete).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('Stop tracking on a held delete says the target entry is kept, then dismisses it', async () => {
    withRow({ reviewKind: 'PENDING_DELETE' })
    const wrapper = open()
    await flushPromises()
    await selectRow('1111')
    byExactText('Stop tracking (1)')!.click()
    await flushPromises()
    expect(confirmMock).toHaveBeenCalledWith(expect.objectContaining({
      confirmLabel: 'Stop tracking', message: expect.stringContaining('The held delete keeps its target entry'),
    }))
    expect(syncApi.dismissMembership).toHaveBeenCalledWith('set-1', '1111')
    wrapper.unmount()
  })

  it('Re-check watches the row and refreshes the table once the worker has handled it', async () => {
    vi.useFakeTimers()
    try {
      withRow({ reviewKind: 'PENDING_DELETE', failReason: 'scope-exit held for review (deletePolicy=REVIEW)' })
      const wrapper = open()
      await flushPromises()
      const success = vi.spyOn(useNotificationStore(), 'success')
      await selectRow('1111')

      byExactText('Re-check (1)')!.click()
      await flushPromises()
      expect(syncApi.recomputeKey).toHaveBeenCalledWith('set-1', '1111')
      // The worker has now applied it: the entry is back in the set.
      withRow({ state: 'APPLIED', reviewKind: null, failReason: null } as unknown as Partial<typeof membership>)
      await vi.advanceTimersByTimeAsync(2000)

      expect(success).toHaveBeenLastCalledWith('Re-check done: the row is now Synced')
      expect(document.querySelector('td span')?.textContent).not.toContain('Review')
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('looks up a recomputed row that left the filtered page on its own', async () => {
    vi.useFakeTimers()
    try {
      withRow({ state: 'FAILED', reviewKind: null })
      const wrapper = mount(MembershipInventoryModal, {
        attachTo: document.body,
        props: { show: true, set, sourceName: 'Source', targetName: 'Target', initialState: 'FAILED' as const },
      })
      await flushPromises()
      const success = vi.spyOn(useNotificationStore(), 'success')
      await selectRow('1111')
      byExactText('Retry (1)')!.click()
      await flushPromises()

      // The Failed page is now empty; looked up by identity, the row is Synced.
      vi.mocked(syncApi.listMemberships).mockImplementation(((_id: string, p: { q?: string }) =>
        Promise.resolve(p.q === '1111'
          ? page([{ ...membership, state: 'APPLIED', reviewKind: null, failReason: null }])
          : page([]))) as never)
      await vi.advanceTimersByTimeAsync(2000)

      expect(syncApi.listMemberships).toHaveBeenCalledWith('set-1', expect.objectContaining({ q: '1111' }))
      expect(success).toHaveBeenLastCalledWith('Retry done: the row is now Synced')
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('Re-check that changes nothing says why a held deletion stays held', async () => {
    vi.useFakeTimers()
    try {
      withRow({ reviewKind: 'PENDING_DELETE', failReason: 'scope-exit held for review (deletePolicy=REVIEW)' })
      const wrapper = open()
      await flushPromises()
      const info = vi.spyOn(useNotificationStore(), 'info')
      await selectRow('1111')

      byExactText('Re-check (1)')!.click()
      await vi.advanceTimersByTimeAsync(20_000)
      await flushPromises()

      expect(info).toHaveBeenCalledWith(expect.stringContaining("still outside the set's filter or scope"))
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('names the recompute for the selection: Retry match, Retry, Re-sync, or Recompute when mixed', async () => {
    for (const [overs, label] of [
      [[{ state: 'REVIEW', reviewKind: 'AMBIGUOUS_MATCH' }], 'Retry match (1)'],
      [[{ state: 'FAILED', reviewKind: null }], 'Retry (1)'],
      [[{ state: 'APPLIED', reviewKind: null, failReason: null }], 'Re-sync (1)'],
      [[{ state: 'FAILED', reviewKind: null }, { state: 'APPLIED', reviewKind: null }], 'Recompute (2)'],
    ] as const) {
      vi.clearAllMocks()
      document.body.innerHTML = ''
      withRows(...overs)
      const wrapper = open()
      await flushPromises()
      await selectAll()
      const btn = byExactText(label)
      expect(btn, label).toBeTruthy()
      btn!.click()
      await flushPromises()
      expect(syncApi.recomputeKey).toHaveBeenCalledWith('set-1', '1111')
      expect(byText('Delete target')).toBeUndefined()
      wrapper.unmount()
    }
  })

  it('"Stop tracking" asks first and dismisses every selected row only when confirmed', async () => {
    withRows({ state: 'FAILED', reviewKind: null }, { state: 'APPLIED', reviewKind: null })
    confirmMock.mockResolvedValueOnce(false)
    const wrapper = open()
    await flushPromises()
    await selectAll()
    byExactText('Stop tracking (2)')!.click()
    await flushPromises()
    expect(confirmMock).toHaveBeenCalledWith(expect.objectContaining({ title: 'Stop tracking 2 entries?' }))
    expect(syncApi.dismissMembership).not.toHaveBeenCalled()
    byExactText('Stop tracking (2)')!.click()
    await flushPromises()
    expect(syncApi.dismissMembership).toHaveBeenCalledWith('set-1', '1111')
    expect(syncApi.dismissMembership).toHaveBeenCalledWith('set-1', '2222')
    wrapper.unmount()
  })

  it('drops selected rows that a new filter or page no longer shows', async () => {
    const wrapper = open()
    await flushPromises()
    await selectRow('1111')
    expect(document.body.textContent).toContain('1 selected')
    withRows({ identity: '9999', state: 'FAILED' })
    byText('Failed')!.click()
    await flushPromises()
    expect(document.body.textContent).toContain('Select rows to act on them')
    wrapper.unmount()
  })

  it('explains how Verify now, Reconcile now and Re-check target differ', async () => {
    const wrapper = open()
    await flushPromises()
    await openDriftTab()
    expect(byText('Verify now')!.title).toMatch(/never writes/)
    expect(byText('Reconcile now')!.title).toMatch(/Skips entries whose source hasn't changed/)
    expect(byText('Re-check target')!.title).toMatch(/re-reads every target entry/)
    wrapper.unmount()
  })

  it('Re-check target confirms first, then queues the re-check', async () => {
    const wrapper = open()
    await flushPromises()
    byText('Re-check target')!.click()
    await flushPromises()
    expect(confirmMock).toHaveBeenCalledWith(expect.objectContaining({ confirmLabel: 'Re-check target' }))
    expect(syncApi.recheckTarget).toHaveBeenCalledWith('set-1')
    wrapper.unmount()
  })

  it('Re-check target does nothing when the confirm is cancelled', async () => {
    confirmMock.mockResolvedValueOnce(false)
    const wrapper = open()
    await flushPromises()
    byText('Re-check target')!.click()
    await flushPromises()
    expect(syncApi.recheckTarget).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('Verify now runs the check, then reloads the Drift tab and tells the page the counts changed', async () => {
    const wrapper = open()
    await flushPromises()
    await openDriftTab()
    expect(syncApi.getDrift).toHaveBeenCalledTimes(1)
    byText('Verify now')!.click()
    await flushPromises()
    expect(syncApi.verifyContents).toHaveBeenCalledWith('set-1')
    expect(syncApi.getDrift).toHaveBeenCalledTimes(2)
    expect(wrapper.emitted('changed')).toBeTruthy()
    const txt = document.body.textContent ?? ''
    expect(txt).toContain('Just now: source 5 members')
    expect(txt).toContain('uid=gone,ou=Users,dc=dst')
    expect(txt).toContain('uid=drift,ou=Users,dc=dst')
    wrapper.unmount()
  })

  it('opens on the Reconciliation tab, or on Drift when asked, badged with the last check’s drift', async () => {
    const drifting = { ...set, verify: { at: '2026-11-05T08:00:00Z', missing: 2, orphan: 1, mismatch: 4,
      incompleteAt: null, held: null, dangling: null } }
    let w = mount(MembershipInventoryModal, { attachTo: document.body,
      props: { show: true, set: drifting, sourceName: 'S', targetName: 'T' } })
    await flushPromises()
    expect(tabButton('Reconciliation').getAttribute('aria-selected')).toBe('true')
    expect(tabButton('Drift').textContent).toContain('7')
    expect(syncApi.getDrift).not.toHaveBeenCalled() // only loaded when the tab is opened
    w.unmount()
    document.body.innerHTML = ''

    w = mount(MembershipInventoryModal, { attachTo: document.body,
      props: { show: true, set: drifting, sourceName: 'S', targetName: 'T', initialTab: 'drift' } })
    await flushPromises()
    expect(tabButton('Drift').getAttribute('aria-selected')).toBe('true')
    expect(syncApi.getDrift).toHaveBeenCalledWith('set-1', expect.objectContaining({ page: 0, size: 25 }))
    w.unmount()
  })

  it('Verify now stores every held reference; the drift tab counts them and lists the dangling ones', async () => {
    const wrapper = open()
    await flushPromises()
    await openDriftTab()
    // The check stores them; the tab reloads its stored page, now with held counts.
    vi.mocked(syncApi.getDrift).mockResolvedValue({ data: driftPage({ held: { DANGLING: 1, UNSYNCED: 1 } }) } as never)
    byText('Verify now')!.click()
    await flushPromises()
    const held = document.querySelector('[data-testid="held-references"]')!.textContent!.replace(/\s+/g, ' ')
    expect(held).toContain('Held references (not drift): 2 values name an entry the sync hasn’t placed')
    expect(held).toContain('1 names an entry that doesn’t exist on the source')

    Array.from(document.querySelectorAll<HTMLButtonElement>('[data-testid="held-references"] button'))[0].click()
    await flushPromises()
    expect(syncApi.getHeldReferences).toHaveBeenCalledWith('set-1', expect.objectContaining({ status: 'DANGLING' }))
    expect(document.querySelector('[data-testid="held-row-0"]')!.textContent).toContain('uid=ghost,dc=src')
    wrapper.unmount()
  })

  /** The set's activity as getSyncSetActivity returns it: no run, or the given one. */
  function activityWith(reconcile: Record<string, unknown> | null) {
    vi.mocked(syncApi.getSyncSetActivity).mockResolvedValue({ data: {
      reconcile, verify: null, asOf: new Date().toISOString(),
    } } as never)
  }
  const run = (over: Record<string, unknown>) => ({
    id: 'r1', trigger: 'MANUAL', status: 'RUNNING', startedAt: new Date().toISOString(),
    heartbeatAt: new Date().toISOString(), phase: 'APPLYING', processed: 0, sourceCount: 120, currentDn: null,
    added: 0, modified: 0, moved: 0, noChange: 0, unchanged: 0, error: null, ...over,
  })

  it('Reconcile now starts the run, follows it, then reports it and refreshes the counts', async () => {
    vi.useFakeTimers()
    try {
      activityWith(null)
      const wrapper = open()
      await flushPromises()
      const success = vi.spyOn(useNotificationStore(), 'success')
      expect(syncApi.listReconcileRuns).toHaveBeenCalledTimes(2) // reconciles + incremental passes
      vi.mocked(syncApi.listMemberships).mockClear()
      vi.mocked(syncApi.listNotInSet).mockClear()

      byText('Reconcile now')!.click()
      await flushPromises()
      expect(syncApi.startReconcile).toHaveBeenCalledWith('set-1')
      expect(byExactText('Reconciling…')!.disabled).toBe(true)

      activityWith(run({ status: 'RUNNING', processed: 40 }))
      await vi.advanceTimersByTimeAsync(1500)
      expect(syncApi.listMemberships).not.toHaveBeenCalled() // nothing to refresh mid-run

      activityWith(run({ status: 'COMPLETE', phase: null, processed: 120, added: 2, modified: 1, noChange: 2 }))
      await vi.advanceTimersByTimeAsync(1500)
      expect(success).toHaveBeenCalledWith('Reconcile finished: 2 added, 1 updated, 2 already matched')
      expect(syncApi.listMemberships).toHaveBeenCalled()
      expect(syncApi.listNotInSet).toHaveBeenCalled()
      expect(syncApi.listReconcileRuns).toHaveBeenCalledTimes(4)
      expect(wrapper.emitted('changed')).toBeTruthy() // the set's chip counts reload
      expect(byExactText('Reconcile now')!.disabled).toBe(false)
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('Reconcile now shows why it can\'t start, e.g. while another reconcile runs', async () => {
    vi.mocked(syncApi.startReconcile).mockRejectedValueOnce(
      { response: { status: 409, data: { detail: 'A reconcile of this sync set is already running.' } } })
    const wrapper = open()
    await flushPromises()
    const error = vi.spyOn(useNotificationStore(), 'error')

    byText('Reconcile now')!.click()
    await flushPromises()

    expect(error).toHaveBeenCalledWith('A reconcile of this sync set is already running.')
    expect(byExactText('Reconcile now')!.disabled).toBe(false)
    wrapper.unmount()
  })

  it('reloads the Drift tab in place when the check a reconcile ends with completes', async () => {
    vi.useFakeTimers()
    try {
      const verifyDone = (runId: string) => ({ runId, status: 'COMPLETE', phase: 'DONE', processed: 5, total: 5,
        currentDn: null, error: null, startedAt: new Date().toISOString(), heartbeatAt: new Date().toISOString(),
        finishedAt: new Date().toISOString() })
      const activity = (reconcile: unknown, verify: unknown) => vi.mocked(syncApi.getSyncSetActivity)
        .mockResolvedValue({ data: { reconcile, verify, asOf: new Date().toISOString() } } as never)
      activity(run({ trigger: 'SCHEDULED', status: 'RUNNING' }), verifyDone('v-old'))
      const wrapper = open()
      await flushPromises()
      await openDriftTab()
      vi.mocked(syncApi.getDrift).mockClear()

      // The run finishes; its check starts only afterwards, so the dialog keeps looking.
      activity(run({ trigger: 'SCHEDULED', status: 'COMPLETE', phase: null }), verifyDone('v-old'))
      await vi.advanceTimersByTimeAsync(1500)
      expect(syncApi.getDrift).not.toHaveBeenCalled()

      activity(run({ trigger: 'SCHEDULED', status: 'COMPLETE', phase: null }), verifyDone('v-new'))
      await vi.advanceTimersByTimeAsync(1500)
      expect(syncApi.getDrift).toHaveBeenCalledTimes(1)
      // Once per check: a later read of the same one doesn't reload again.
      await vi.advanceTimersByTimeAsync(1500)
      expect(syncApi.getDrift).toHaveBeenCalledTimes(1)
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('refreshes the counts when a reconcile it didn\'t start finishes', async () => {
    vi.useFakeTimers()
    try {
      activityWith(run({ trigger: 'SCHEDULED', status: 'RUNNING' }))
      const wrapper = open()
      await flushPromises()
      expect(byExactText('Reconciling…')!.disabled).toBe(true)
      vi.mocked(syncApi.listMemberships).mockClear()

      activityWith(run({ trigger: 'SCHEDULED', status: 'COMPLETE', phase: null }))
      await vi.advanceTimersByTimeAsync(1500)

      expect(syncApi.listMemberships).toHaveBeenCalled()
      expect(wrapper.emitted('changed')).toBeTruthy()
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
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

  // ── Not in set ─────────────────────────────────────────────────────────────

  it('counts the source entries the set doesn\'t manage, and lists them with the reason', async () => {
    const wrapper = open()
    await flushPromises()
    const chip = byText('Not in set')!
    expect(chip.textContent).toContain('412')

    chip.click()
    await flushPromises()

    expect(syncApi.listNotInSet).toHaveBeenLastCalledWith('set-1', expect.objectContaining({ page: 0, size: 50 }))
    const txt = document.body.textContent ?? ''
    expect(txt).toContain('412 in-scope source entries aren\'t managed by this set')
    expect(txt).toContain("400 doesn't match the applicability filter")
    expect(txt).toContain('ou=people,dc=src')
    expect(txt).toContain('Showing up to 25 examples per reason')
    expect(syncApi.listMemberships).toHaveBeenCalledTimes(1) // only the opening load
    wrapper.unmount()
  })

  // ── Live progress ──────────────────────────────────────────────────────────

  function deferred<T>() {
    let resolve!: (v: T) => void
    const promise = new Promise<T>((r) => { resolve = r })
    return { promise, resolve }
  }

  it('Reconcile now shows the run\'s phase, count and the entry it\'s on while it runs', async () => {
    vi.useFakeTimers()
    const now = new Date().toISOString()
    activityWith(null)
    const wrapper = open()
    await flushPromises()

    byText('Reconcile now')!.click()
    await flushPromises()
    vi.mocked(syncApi.getSyncSetActivity).mockResolvedValue({ data: {
      reconcile: {
        id: 'r1', trigger: 'MANUAL', status: 'RUNNING', startedAt: now, heartbeatAt: now, phase: 'APPLYING',
        processed: 37, sourceCount: 120, currentDn: 'cn=staff,ou=groups,dc=src',
      },
      verify: null, asOf: now,
    } } as never)
    await vi.advanceTimersByTimeAsync(1500)

    const txt = document.body.textContent ?? ''
    expect(txt).toContain('Reconcile: applying entries: 37 of 120')
    expect(txt).toContain('Now checking cn=staff,ou=groups,dc=src')
    expect(txt).toContain('last update')
    expect(document.querySelector('[role="progressbar"]')!.getAttribute('aria-valuenow')).toBe('37')

    activityWith(run({ startedAt: now, status: 'COMPLETE', phase: null, processed: 120 }))
    await vi.advanceTimersByTimeAsync(1500)
    expect(document.body.textContent).not.toContain('applying entries')
    wrapper.unmount()
    vi.useRealTimers()
  })

  it('Reconcile now says when the run is queuing the entries that refer to changed ones', async () => {
    vi.useFakeTimers()
    const now = new Date().toISOString()
    activityWith(null)
    const wrapper = open()
    await flushPromises()

    byText('Reconcile now')!.click()
    await flushPromises()
    vi.mocked(syncApi.getSyncSetActivity).mockResolvedValue({ data: {
      reconcile: {
        id: 'r1', trigger: 'MANUAL', status: 'RUNNING', startedAt: now, heartbeatAt: now, phase: 'REFERENCES',
        processed: 120, sourceCount: 120, currentDn: 'uid=alice,ou=people,dc=src',
      },
      verify: null, asOf: now,
    } } as never)
    await vi.advanceTimersByTimeAsync(1500)

    const txt = document.body.textContent ?? ''
    expect(txt).toContain('Reconcile: queuing entries that refer to changed ones')
    expect(txt).toContain('Finding referrers of uid=alice,ou=people,dc=src')

    wrapper.unmount()
    vi.useRealTimers()
  })

  it('Verify now shows which phase the check is in and how far it has got', async () => {
    vi.useFakeTimers()
    const now = new Date().toISOString()
    const pending = deferred<unknown>()
    vi.mocked(syncApi.verifyContents).mockReturnValueOnce(pending.promise as never)
    vi.mocked(syncApi.getSyncSetActivity).mockResolvedValue({ data: {
      reconcile: null,
      verify: { runId: 'v1', status: 'RUNNING', phase: 'COMPARING', processed: 12, total: 40,
        currentDn: 'uid=bob,ou=Users,dc=dst', error: null, startedAt: now, heartbeatAt: now, finishedAt: null },
      asOf: now,
    } } as never)
    const wrapper = open()
    await flushPromises()
    tabButton('Drift').click()
    await vi.advanceTimersByTimeAsync(0)

    byText('Verify now')!.click()
    await vi.advanceTimersByTimeAsync(1500)

    const txt = document.body.textContent ?? ''
    expect(txt).toContain('Checking contents: comparing entries: 12 of 40')
    expect(txt).toContain('Now comparing uid=bob,ou=Users,dc=dst')
    wrapper.unmount()
    vi.useRealTimers()
  })

  it('Re-check target waits for its reconcile, then says what it did', async () => {
    vi.useFakeTimers()
    const wrapper = open()
    await flushPromises()

    byText('Re-check target')!.click()
    await flushPromises()
    expect(document.body.textContent).toContain('waiting for the next reconcile to start')

    const later = new Date(Date.now() + 30_000).toISOString()
    vi.mocked(syncApi.getSyncSetActivity).mockResolvedValue({ data: {
      reconcile: {
        id: 'r2', trigger: 'SCHEDULED', status: 'COMPLETE', startedAt: later, heartbeatAt: later, phase: null,
        processed: 5, sourceCount: 5, added: 2, modified: 1, moved: 0, noChange: 2, unchanged: 0, error: null,
      },
      verify: null, asOf: later,
    } } as never)
    const notif = useNotificationStore()
    const spy = vi.spyOn(notif, 'success')
    await vi.advanceTimersByTimeAsync(1500)

    expect(spy).toHaveBeenCalledWith('Re-check finished: 2 added, 1 updated, 2 already matched')
    expect(document.body.textContent).not.toContain('waiting for the next reconcile')
    wrapper.unmount()
    vi.useRealTimers()
  })

  it('labels the states for operators: Synced (APPLIED), with what each means', async () => {
    withRow({ state: 'APPLIED' })
    const wrapper = open()
    await flushPromises()
    const chip = byText('Synced')!
    expect(chip.getAttribute('title')).toContain('Verify contents checks that the target still matches')
    const badge = Array.from(document.querySelectorAll('td span')).find((e) => e.textContent?.trim() === 'Synced')
    expect(badge).toBeTruthy()
    expect(document.body.textContent).not.toContain('APPLIED')
    wrapper.unmount()
  })
})
