// SPDX-License-Identifier: Apache-2.0
/**
 * The per-entry preview dialog: reads the set's latest background run when
 * opened (starting one if there is none), polls while it runs, then pages the
 * stored result. The sync API is mocked; AppModal/DataTable are stubbed so
 * slots render.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import type { AxiosResponse } from 'axios'
import type { SyncPreviewPage, SyncPreviewStatus } from '@/api/sync'

const api = vi.hoisted(() => ({
  previewEntries: vi.fn(),
  getPreviewRun: vi.fn(),
  startPreviewRun: vi.fn(),
}))
vi.mock('@/api/sync', () => ({
  previewEntries: api.previewEntries,
  getPreviewRun: api.getPreviewRun,
  startPreviewRun: api.startPreviewRun,
}))

import SyncPreviewChangesModal from './SyncPreviewChangesModal.vue'
import { useNotificationStore } from '@/stores/notifications'

const stubs = {
  AppModal: { props: ['modelValue'], template: '<div v-if="modelValue"><slot /></div>' },
  DataTable: {
    props: ['columns', 'rows', 'rowKey', 'loading', 'expandedKey', 'highlightKey', 'emptyText'],
    emits: ['row-click'],
    template: `<div class="dt"><template v-for="r in rows" :key="r.identity">
      <div class="dt-row" @click="$emit('row-click', r)">
        <slot name="cell-action" :row="r" /><slot name="cell-targetDn" :row="r" /><slot name="cell-detail" :row="r" />
      </div>
      <div v-if="expandedKey !== undefined && r.identity === expandedKey" class="dt-detail">
        <slot name="row-detail" :row="r" />
      </div>
    </template><p v-if="!rows.length" class="dt-empty">{{ emptyText }}</p></div>`,
  },
}

function ok<T>(data: T): AxiosResponse<T> {
  return { data, status: 200, statusText: 'OK', headers: {}, config: {} } as unknown as AxiosResponse<T>
}

function run(overrides: Partial<SyncPreviewStatus> = {}): SyncPreviewStatus {
  return {
    runId: 'run-1', status: 'COMPLETE', requestedBy: 'ann',
    startedAt: '2026-10-20T10:00:00Z', finishedAt: '2026-10-20T10:00:05Z',
    processed: 4, total: 4, outdated: false, error: null,
    sourceCount: 4, managedCount: 3, actionableCount: 2, unchangedCount: 2,
    guardTripped: false, guardReason: null, completeScan: true, note: null,
    ...overrides,
  }
}

function page(overrides: Partial<SyncPreviewPage> = {}): SyncPreviewPage {
  return {
    entries: [
      {
        identity: 'id-alice', sourceDn: 'uid=alice,ou=people,dc=src', targetDn: 'uid=alice,ou=Users,dc=dst',
        previousTargetDn: null, action: 'MODIFY', reason: null,
        changes: [{ attribute: 'sn', removed: ['alice'], added: ['Smith'], omittedRemoved: 0, omittedAdded: 3 }],
      },
      {
        identity: 'id-bob', sourceDn: null, targetDn: 'uid=bob,ou=Users,dc=dst',
        previousTargetDn: null, action: 'ERROR', reason: 'Couldn\'t predict this entry: target busy',
        changes: [],
      },
    ],
    page: 0, size: 50, totalEntries: 2, totalPages: 1, changesOnly: true, runId: 'run-1',
    ...overrides,
  }
}

function deferred<T>() {
  let resolve!: (v: T) => void
  const promise = new Promise<T>((r) => { resolve = r })
  return { promise, resolve }
}

function mountModal(open = true) {
  return mount(SyncPreviewChangesModal, {
    props: { modelValue: open, setId: 'set-1', setName: 'people' },
    global: { stubs },
  })
}

describe('SyncPreviewChangesModal', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    api.previewEntries.mockReset()
    api.getPreviewRun.mockReset()
    api.startPreviewRun.mockReset()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('shows the latest completed run and its first page when opened', async () => {
    api.getPreviewRun.mockResolvedValue(ok(run()))
    api.previewEntries.mockResolvedValue(ok(page()))
    const w = mountModal()
    await flushPromises()

    expect(api.startPreviewRun).not.toHaveBeenCalled()
    expect(api.previewEntries).toHaveBeenCalledWith('set-1', 0, 50, true)
    const text = w.text()
    expect(text).toContain('Modify')
    expect(text).toContain('uid=alice,ou=Users,dc=dst')
    expect(text).toContain('1 attribute changed')
    expect(text).toContain('Error')
    expect(text).toContain('target busy')
    expect(text).toContain('Would change: 2')
    expect(text).toContain('by ann')
  })

  it('does not query while closed', async () => {
    mountModal(false)
    await flushPromises()
    expect(api.getPreviewRun).not.toHaveBeenCalled()
  })

  it('starts a run when the set has none, shows progress, and loads the result when it finishes', async () => {
    vi.useFakeTimers()
    api.getPreviewRun.mockResolvedValueOnce(ok(run({ runId: null, status: null })))
    api.startPreviewRun.mockResolvedValue(ok(run({ status: 'RUNNING', processed: 0, total: 0, finishedAt: null })))
    api.getPreviewRun.mockResolvedValueOnce(ok(run({ status: 'RUNNING', processed: 40, total: 100, finishedAt: null })))
    api.getPreviewRun.mockResolvedValueOnce(ok(run()))
    api.previewEntries.mockResolvedValue(ok(page()))
    const w = mountModal()
    await flushPromises()

    expect(api.startPreviewRun).toHaveBeenCalledWith('set-1')
    expect(w.text()).toContain('Scanning the source')
    expect(api.previewEntries).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(1500)
    expect(w.text()).toContain('Checking entries: 40 of 100')

    await vi.advanceTimersByTimeAsync(1500)
    await flushPromises()
    expect(api.previewEntries).toHaveBeenCalledWith('set-1', 0, 50, true)
    expect(w.text()).toContain('uid=alice,ou=Users,dc=dst')
  })

  it('stops polling when closed', async () => {
    vi.useFakeTimers()
    api.getPreviewRun.mockResolvedValue(ok(run({ status: 'RUNNING', finishedAt: null })))
    const w = mountModal()
    await flushPromises()
    expect(api.getPreviewRun).toHaveBeenCalledTimes(1)

    await w.setProps({ modelValue: false })
    await vi.advanceTimersByTimeAsync(5000)

    expect(api.getPreviewRun).toHaveBeenCalledTimes(1)
  })

  it('"Run again" starts a new run', async () => {
    api.getPreviewRun.mockResolvedValue(ok(run()))
    api.previewEntries.mockResolvedValue(ok(page()))
    api.startPreviewRun.mockResolvedValue(ok(run({ runId: 'run-2', status: 'RUNNING', finishedAt: null })))
    const w = mountModal()
    await flushPromises()

    await w.findAll('button').find((b) => b.text() === 'Run again')!.trigger('click')
    await flushPromises()

    expect(api.startPreviewRun).toHaveBeenCalledWith('set-1')
    expect(w.text()).toContain('Running…')
  })

  it('expands a row to show values removed and added, with the capped remainder', async () => {
    api.getPreviewRun.mockResolvedValue(ok(run()))
    api.previewEntries.mockResolvedValue(ok(page()))
    const w = mountModal()
    await flushPromises()

    await w.findAll('.dt-row')[0].trigger('click')

    const detail = w.find('.dt-detail').text()
    expect(detail).toContain('sn')
    expect(detail).toContain('− alice')
    expect(detail).toContain('+ Smith')
    expect(detail).toContain('… and 3 more')
  })

  it('pages forward, and follows the server to the last page when the listing shrank', async () => {
    api.getPreviewRun.mockResolvedValue(ok(run()))
    api.previewEntries.mockImplementation((_id: string, p: number) =>
      Promise.resolve(ok(page({ page: Math.min(p, 2), totalPages: 3, totalEntries: 120 }))))
    const w = mountModal()
    await flushPromises()
    expect(w.text()).toContain('Page 1 of 3')

    await w.findAll('button').find((b) => b.text() === 'Next')!.trigger('click')
    await flushPromises()
    expect(api.previewEntries).toHaveBeenLastCalledWith('set-1', 1, 50, true)
    expect(w.text()).toContain('Page 2 of 3')
  })

  it('re-queries from page 0 when "Changes only" is turned off', async () => {
    api.getPreviewRun.mockResolvedValue(ok(run()))
    api.previewEntries.mockResolvedValue(ok(page()))
    const w = mountModal()
    await flushPromises()

    await w.find('input[type="checkbox"]').setValue(false)
    await flushPromises()

    expect(api.previewEntries).toHaveBeenLastCalledWith('set-1', 0, 50, false)
  })

  it('disables the filters while a page loads, and ignores a stale response', async () => {
    api.getPreviewRun.mockResolvedValue(ok(run()))
    const first = deferred<AxiosResponse<SyncPreviewPage>>()
    const second = deferred<AxiosResponse<SyncPreviewPage>>()
    api.previewEntries.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const w = mountModal()
    await flushPromises()

    expect(w.find('input[type="checkbox"]').attributes('disabled')).toBeDefined()
    expect(w.find('select').attributes('disabled')).toBeDefined()

    // Force a second request while the first is in flight (as a quick double change could).
    const vm = w.vm as unknown as { $: { setupState: { load: (p: number) => Promise<void> } } }
    void vm.$.setupState.load(0)
    second.resolve(ok(page({
      entries: [{ identity: 'id-new', sourceDn: null, targetDn: 'uid=new,ou=Users,dc=dst', previousTargetDn: null,
        action: 'ADD', reason: null, changes: [] }],
    })))
    await flushPromises()
    first.resolve(ok(page()))
    await flushPromises()

    expect(w.text()).toContain('uid=new,ou=Users,dc=dst')
    expect(w.text()).not.toContain('uid=alice,ou=Users,dc=dst')
    expect(w.find('input[type="checkbox"]').attributes('disabled')).toBeUndefined()
  })

  it('surfaces the delete guard, an incomplete scan, and an outdated result', async () => {
    api.getPreviewRun.mockResolvedValue(ok(run({
      guardTripped: true, guardReason: 'planned deletions (60) exceed the absolute cap of 50',
      note: 'Source enumeration failed (timeout); a reconcile would make no deletes.',
      outdated: true,
    })))
    api.previewEntries.mockResolvedValue(ok(page()))
    const w = mountModal()
    await flushPromises()

    expect(w.text()).toContain('Delete guard would trip: planned deletions (60) exceed the absolute cap of 50')
    expect(w.text()).toContain('Source enumeration failed (timeout)')
    expect(w.text()).toContain('edited or reconciled since this preview ran')
  })

  it('shows why a run failed', async () => {
    api.getPreviewRun.mockResolvedValue(ok(run({
      status: 'FAILED', error: 'Interrupted: the server running this preview stopped before it finished.',
    })))
    const w = mountModal()
    await flushPromises()

    expect(w.text()).toContain('Interrupted')
    expect(api.previewEntries).not.toHaveBeenCalled()
  })

  it('reports a failed page load through the notification store', async () => {
    api.getPreviewRun.mockResolvedValue(ok(run()))
    api.previewEntries.mockRejectedValue({ response: { status: 400, data: { detail: 'size must be between 1 and 200' } } })
    mountModal()
    const notif = useNotificationStore()
    const spy = vi.spyOn(notif, 'error')
    await flushPromises()

    expect(spy).toHaveBeenCalledWith('size must be between 1 and 200')
  })
})
