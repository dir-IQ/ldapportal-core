// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import MembershipInventoryModal from './MembershipInventoryModal.vue'
import { useNotificationStore } from '@/stores/notifications'

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

vi.mock('@/api/sync', () => ({
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

  it('explains how Verify contents, Reconcile now and Re-check target differ', async () => {
    const wrapper = open()
    await flushPromises()
    expect(byText('Verify contents')!.title).toMatch(/never writes/)
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

  it('Verify contents shows which phase the check is in and how far it has got', async () => {
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

    byText('Verify contents')!.click()
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
