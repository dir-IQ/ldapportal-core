// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ReconcileRunHistory from './ReconcileRunHistory.vue'
import type { SyncReconcileRun } from '@/api/sync'

vi.mock('@/api/sync', () => ({
  listReconcileRuns: vi.fn(),
  getReconcileRun: vi.fn(),
}))

import * as syncApi from '@/api/sync'

function run(over: Partial<SyncReconcileRun> = {}): SyncReconcileRun {
  return {
    id: 'run-1', trigger: 'MANUAL', requestedBy: 'alice', status: 'COMPLETE',
    startedAt: '2026-10-20T10:00:00Z', finishedAt: '2026-10-20T10:01:05Z',
    completeScan: true, sourceCount: 4741, managedBefore: 876, managedAfter: 4741, processed: 4741,
    added: 3, modified: 1, moved: 0, deleted: 0, heldForReview: 0, noChange: 3865,
    forgotten: 0, unchanged: 872, failed: 0, faulted: 0, skipped: 0,
    guardTripped: false, guardReason: null, error: null,
    instanceName: 'app-2', plannedAt: '2026-10-20T10:00:40Z',
    verifiedAt: null, verifyMissing: null, verifyOrphan: null, verifyMismatch: null, allDnsRecorded: false,
    ...over,
  }
}

const listRuns = syncApi.listReconcileRuns as unknown as ReturnType<typeof vi.fn>
/** Reconciles for the summary and history; incremental passes for their own list. */
const resolveRuns = (runs: SyncReconcileRun[], incremental: SyncReconcileRun[] = []) =>
  listRuns.mockImplementation((_set: string, _limit: number, kind?: string) =>
    Promise.resolve({ data: kind === 'INCREMENTAL' ? incremental : runs }))

describe('ReconcileRunHistory', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
  })

  it('summarises the latest run: trigger, duration, counts, managed before → after', async () => {
    resolveRuns([run()])
    const wrapper = mount(ReconcileRunHistory, { props: { setId: 'set-1' } })
    await flushPromises()
    expect(syncApi.listReconcileRuns).toHaveBeenCalledWith('set-1', 10, 'RECONCILE')
    const txt = wrapper.text()
    expect(txt).toContain('Completed')
    expect(txt).toContain('Manual (alice)')
    expect(txt).toContain('took 1m 5s')
    expect(txt).toContain('scan 40s, apply 25s')
    expect(txt).toContain('on app-2')
    expect(txt).toMatch(/Added:\s*3/)
    expect(txt).toMatch(/Already matched:\s*3865/)
    expect(txt).toMatch(/Managed:\s*876\s*→\s*4741/)
    expect(txt).not.toContain('Recent runs') // only one run
  })

  it('warns about a mid-run disable, an incomplete scan, a tripped guard and faults', async () => {
    resolveRuns([run({
      skipped: 3865, completeScan: false, guardTripped: true, guardReason: 'too many deletes', faulted: 2,
    })])
    const wrapper = mount(ReconcileRunHistory, { props: { setId: 'set-1' } })
    await flushPromises()
    const txt = wrapper.text()
    expect(txt).toContain('3865 entries were skipped')
    expect(txt).toContain('source scan did not complete')
    expect(txt).toContain('Delete guard tripped: too many deletes')
    expect(txt).toContain('2 entries hit an error')
    expect(txt).toMatch(/Failed:\s*2/)
  })

  it('shows a failed run with its error', async () => {
    resolveRuns([run({ status: 'FAILED', error: 'The run stopped without finishing.', managedAfter: null })])
    const wrapper = mount(ReconcileRunHistory, { props: { setId: 'set-1' } })
    await flushPromises()
    expect(wrapper.find('.badge-red').text()).toBe('Failed')
    expect(wrapper.text()).toContain('The run stopped without finishing.')
  })

  it('says so when no run has been recorded', async () => {
    resolveRuns([])
    const wrapper = mount(ReconcileRunHistory, { props: { setId: 'set-1' } })
    await flushPromises()
    expect(wrapper.text()).toContain('No reconcile has been recorded')
  })

  it('toggles the recent-runs table when there is more than one run', async () => {
    resolveRuns([run(), run({ id: 'run-2', trigger: 'SCHEDULED', requestedBy: null })])
    const wrapper = mount(ReconcileRunHistory, { props: { setId: 'set-1' } })
    await flushPromises()
    const toggle = wrapper.findAll('button').find((b) => b.text().includes('Recent runs (2)'))!
    expect(toggle).toBeTruthy()
    await toggle.trigger('click')
    expect(wrapper.text()).toContain('Scheduled')
    expect(wrapper.text()).toContain('Hide recent runs')
  })

  it('reloads when refreshKey changes', async () => {
    resolveRuns([run()])
    const wrapper = mount(ReconcileRunHistory, { props: { setId: 'set-1', refreshKey: 0 } })
    await flushPromises()
    await wrapper.setProps({ refreshKey: 1 })
    await flushPromises()
    expect(syncApi.listReconcileRuns).toHaveBeenCalledTimes(4) // reconciles + incremental, twice
  })

  it('shows the load error inline', async () => {
    listRuns.mockRejectedValue({ response: { data: { detail: 'Sync set not found' } } })
    const wrapper = mount(ReconcileRunHistory, { props: { setId: 'set-1' } })
    await flushPromises()
    expect(wrapper.find('[role="alert"]').text()).toBe('Sync set not found')
  })

  it('shows the drift the verification after the run found', async () => {
    resolveRuns([run({ verifiedAt: '2026-10-20T10:02:00Z', verifyMissing: 0, verifyOrphan: 3, verifyMismatch: 1 })])
    const wrapper = mount(ReconcileRunHistory, { props: { setId: 'set-1' } })
    await flushPromises()
    expect(wrapper.text()).toContain('0 missing on target · 3 orphaned on target · 1 with content drift')
  })

  it('opens the run details from "Details" and from a history row', async () => {
    resolveRuns([run(), run({ id: 'run-2', trigger: 'SCHEDULED', requestedBy: null })])
    ;(syncApi.getReconcileRun as unknown as ReturnType<typeof vi.fn>).mockImplementation(
      (_set: string, id: string) => Promise.resolve({
        data: { run: run({ id }), settings: null, failureReasons: [], samples: {}, sampleLimit: 25 },
      }))
    const wrapper = mount(ReconcileRunHistory, { props: { setId: 'set-1' } })
    await flushPromises()
    await wrapper.findAll('button').find((b) => b.text() === 'Details')!.trigger('click')
    await flushPromises()
    expect(syncApi.getReconcileRun).toHaveBeenLastCalledWith('set-1', 'run-1')
    expect(wrapper.find('[aria-label="Reconcile run details"]').exists()).toBe(true)

    await wrapper.findAll('button').find((b) => b.text().includes('Recent runs'))!.trigger('click')
    await wrapper.findAll('tbody tr')[1].trigger('click')
    await flushPromises()
    expect(syncApi.getReconcileRun).toHaveBeenLastCalledWith('set-1', 'run-2')

    await wrapper.find('[aria-label="Close run details"]').trigger('click')
    expect(wrapper.find('[aria-label="Reconcile run details"]').exists()).toBe(false)
  })

  it('lists incremental passes separately, keeping the summary on the last reconcile', async () => {
    const pass = run({
      id: 'inc-1', trigger: 'INCREMENTAL', requestedBy: null, completeScan: false,
      added: 0, modified: 2, noChange: 0, unchanged: 0, managedBefore: 4741, managedAfter: 4741,
    })
    resolveRuns([run()], [pass])
    const wrapper = mount(ReconcileRunHistory, { props: { setId: 'set-1' } })
    await flushPromises()
    expect(syncApi.listReconcileRuns).toHaveBeenCalledWith('set-1', 20, 'INCREMENTAL')
    expect(wrapper.text()).toContain('Manual (alice)') // the summary is the reconcile

    await wrapper.findAll('button').find((b) => b.text().includes('Incremental changes (1)'))!.trigger('click')
    expect(wrapper.text()).toContain('Changes applied between reconciles')
    expect(wrapper.text()).toContain('~2')
  })

  it('shows incremental passes even before the first reconcile', async () => {
    resolveRuns([], [run({ id: 'inc-1', trigger: 'INCREMENTAL', requestedBy: null, completeScan: false })])
    const wrapper = mount(ReconcileRunHistory, { props: { setId: 'set-1' } })
    await flushPromises()
    expect(wrapper.text()).toContain('No reconcile has been recorded')
    expect(wrapper.findAll('button').some((b) => b.text().includes('Incremental changes (1)'))).toBe(true)
  })
})
