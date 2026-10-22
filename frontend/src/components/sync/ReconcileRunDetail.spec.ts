// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ReconcileRunDetail from './ReconcileRunDetail.vue'
import type { SyncReconcileRun, SyncReconcileRunDetail } from '@/api/sync'

vi.mock('@/api/sync', () => ({ getReconcileRun: vi.fn(), downloadReconcileRunEntries: vi.fn() }))
vi.mock('@/composables/useApi', () => ({ downloadBlob: vi.fn() }))

import * as syncApi from '@/api/sync'
import { downloadBlob } from '@/composables/useApi'

const getRun = syncApi.getReconcileRun as unknown as ReturnType<typeof vi.fn>

const run: SyncReconcileRun = {
  id: 'run-1', trigger: 'SCHEDULED', requestedBy: null, status: 'COMPLETE',
  startedAt: '2026-10-20T10:00:00Z', finishedAt: '2026-10-20T10:02:36Z', completeScan: true,
  sourceCount: 4741, managedBefore: 876, managedAfter: 4741, processed: 4743,
  added: 30, modified: 4, moved: 0, deleted: 1, heldForReview: 0, noChange: 3865, forgotten: 0,
  unchanged: 840, failed: 2, faulted: 1, skipped: 0, guardTripped: false, guardReason: null, error: null,
  instanceName: 'ldapportal-7f9c', plannedAt: '2026-10-20T10:01:10Z',
  verifiedAt: '2026-10-20T10:03:00Z', verifyMissing: 2, verifyOrphan: 0, verifyMismatch: 0,
  allDnsRecorded: false,
}

function detail(over: Partial<SyncReconcileRunDetail> = {}): SyncReconcileRunDetail {
  return {
    run,
    settings: {
      deletePolicy: 'REVIEW', objectScopeBaseDn: 'ou=people,dc=corp', objectScope: 'SUB',
      applicabilityFilter: '(objectClass=inetOrgPerson)', targetBaseDn: 'ou=Users,dc=dmz', identityKey: null,
      sourceAnchorAttribute: null, reconcileCadenceSeconds: null,
      guardMaxDeletes: 50, guardMaxDeletePercent: 20, guardMinPopulation: 20,
    },
    failureReasons: [
      { reason: 'apply failed: 32 (no such object)', count: 2, example: "apply failed: 32 (no such object) — parent 'ou=x' missing" },
      { reason: 'recompute error: IllegalStateException', count: 1, example: 'recompute error: IllegalStateException — contended' },
    ],
    samples: { ADD: ['uid=a,ou=Users,dc=dmz', 'uid=b,ou=Users,dc=dmz'], ERROR: ['uid=x,ou=Users,dc=dmz'] },
    sampleLimit: 25,
    ...over,
  }
}

describe('ReconcileRunDetail', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
  })

  it('shows where and how long it ran, every count, and the drift after it', async () => {
    getRun.mockResolvedValue({ data: detail() })
    const wrapper = mount(ReconcileRunDetail, { props: { setId: 'set-1', runId: 'run-1' } })
    await flushPromises()
    expect(getRun).toHaveBeenCalledWith('set-1', 'run-1')
    const txt = wrapper.text()
    expect(txt).toContain('ldapportal-7f9c')
    expect(txt).toMatch(/Source scan\s*1m 10s/)
    expect(txt).toMatch(/Applying\s*1m 26s/)
    expect(txt).toMatch(/Errored \(retried\)\s*1/)
    expect(txt).toContain('2 missing on target')
    expect(txt).toContain('1 entry hit an error and will be retried')
  })

  it('lists failures by reason and sample DNs, saying when a sample is partial', async () => {
    getRun.mockResolvedValue({ data: detail() })
    const wrapper = mount(ReconcileRunDetail, { props: { setId: 'set-1', runId: 'run-1' } })
    await flushPromises()
    const txt = wrapper.text()
    expect(txt).toContain('2 × apply failed: 32 (no such object)')
    expect(txt).toContain("e.g. apply failed: 32 (no such object) — parent 'ou=x' missing")
    expect(txt).toContain('Added — first 2 of 30')
    expect(txt).toContain('Failed — first 1 of 2')
    expect(txt).toContain('uid=a,ou=Users,dc=dmz')
  })

  it('shows the settings at run time, or says they were not recorded', async () => {
    getRun.mockResolvedValue({ data: detail() })
    const wrapper = mount(ReconcileRunDetail, { props: { setId: 'set-1', runId: 'run-1' } })
    await flushPromises()
    expect(wrapper.text()).toContain('(objectClass=inetOrgPerson)')
    expect(wrapper.text()).toContain('more than 50 deletes, or more than 20% of a managed set of at least 20')

    getRun.mockResolvedValue({ data: detail({ settings: null, failureReasons: [], samples: {} }) })
    await wrapper.setProps({ runId: 'run-2' })
    await flushPromises()
    expect(wrapper.text()).toContain('Not recorded for this run.')
    expect(wrapper.text()).not.toContain('Failures by reason')
  })

  it('shows a load error inline and emits close', async () => {
    getRun.mockRejectedValue({ response: { data: { detail: 'Reconcile run not found' } } })
    const wrapper = mount(ReconcileRunDetail, { props: { setId: 'set-1', runId: 'run-1' } })
    await flushPromises()
    expect(wrapper.find('[role="alert"]').text()).toBe('Reconcile run not found')
    await wrapper.find('[aria-label="Close run details"]').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('offers the full entry list as CSV when the run recorded every DN', async () => {
    getRun.mockResolvedValue({ data: detail({ run: { ...run, allDnsRecorded: true } }) })
    const blob = new Blob(['outcome,dn,reason'])
    ;(syncApi.downloadReconcileRunEntries as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ data: blob })
    const wrapper = mount(ReconcileRunDetail, { props: { setId: 'set-1', runId: 'run-1' } })
    await flushPromises()

    await wrapper.findAll('button').find((b) => b.text() === 'Download all entries (CSV)')!.trigger('click')
    await flushPromises()

    expect(syncApi.downloadReconcileRunEntries).toHaveBeenCalledWith('set-1', 'run-1')
    expect(downloadBlob).toHaveBeenCalledWith(blob, 'sync-run-run-1.csv')
    expect(wrapper.text()).not.toContain('Record every DN')
  })

  it('points to "Record every DN" when the samples were cut short', async () => {
    getRun.mockResolvedValue({ data: detail() }) // 30 added, 2 sampled
    const wrapper = mount(ReconcileRunDetail, { props: { setId: 'set-1', runId: 'run-1' } })
    await flushPromises()
    expect(wrapper.findAll('button').some((b) => b.text().includes('Download all entries'))).toBe(false)
    expect(wrapper.text()).toContain('Up to 25 DNs per outcome are kept')
    expect(wrapper.text()).toContain('Record every DN')
  })

  it('leaves out the scan, verification and settings for an incremental pass', async () => {
    getRun.mockResolvedValue({ data: detail({
      run: { ...run, trigger: 'INCREMENTAL', completeScan: false, plannedAt: run.startedAt, verifiedAt: null },
      settings: null,
    }) })
    const wrapper = mount(ReconcileRunDetail, { props: { setId: 'set-1', runId: 'run-1' } })
    await flushPromises()
    const txt = wrapper.text()
    expect(txt).toContain('Incremental')
    expect(txt).not.toContain('Source scan')
    expect(txt).not.toContain('Managed before')
    expect(txt).not.toContain('content verification')
    expect(txt).not.toContain('Settings at run time')
    expect(txt).not.toContain('source scan did not complete')
  })
})
