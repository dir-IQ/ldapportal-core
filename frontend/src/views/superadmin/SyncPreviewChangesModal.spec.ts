// SPDX-License-Identifier: Apache-2.0
/**
 * The per-entry preview dialog: loads page 0 when opened, renders each entry's
 * action and attribute changes, pages, and re-queries when the filter changes.
 * The sync API is mocked; AppModal/DataTable are stubbed so slots render.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import type { AxiosResponse } from 'axios'
import type { SyncPreviewPage } from '@/api/sync'

const api = vi.hoisted(() => ({ previewEntries: vi.fn() }))
vi.mock('@/api/sync', () => ({ previewEntries: api.previewEntries }))

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
        previousTargetDn: null, action: 'REVIEW', reason: 'scope-exit held for review (deletePolicy=REVIEW)',
        changes: [],
      },
    ],
    page: 0, size: 50, totalEntries: 2, totalPages: 1, changesOnly: true,
    sourceCount: 4, managedCount: 3, unchangedCount: 2,
    guardTripped: false, guardReason: null, completeScan: true, note: null,
    ...overrides,
  }
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
  })

  it('loads the first page of changes when opened', async () => {
    api.previewEntries.mockResolvedValue(ok(page()))
    const w = mountModal()
    await flushPromises()

    expect(api.previewEntries).toHaveBeenCalledWith('set-1', 0, 50, true)
    const text = w.text()
    expect(text).toContain('Modify')
    expect(text).toContain('uid=alice,ou=Users,dc=dst')
    expect(text).toContain('1 attribute changed')
    expect(text).toContain('scope-exit held for review (deletePolicy=REVIEW)')
    expect(text).toContain('Unchanged (hidden): 2')
  })

  it('does not query while closed', async () => {
    mountModal(false)
    await flushPromises()
    expect(api.previewEntries).not.toHaveBeenCalled()
  })

  it('expands a row to show values removed and added, with the capped remainder', async () => {
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

  it('pages forward and back', async () => {
    api.previewEntries.mockImplementation((_id: string, p: number) =>
      Promise.resolve(ok(page({ page: p, totalPages: 3, totalEntries: 120 }))))
    const w = mountModal()
    await flushPromises()
    expect(w.text()).toContain('Page 1 of 3')

    const next = w.findAll('button').find((b) => b.text() === 'Next')!
    await next.trigger('click')
    await flushPromises()

    expect(api.previewEntries).toHaveBeenLastCalledWith('set-1', 1, 50, true)
    expect(w.text()).toContain('Page 2 of 3')
  })

  it('re-queries from page 0 when "Changes only" is turned off', async () => {
    api.previewEntries.mockResolvedValue(ok(page()))
    const w = mountModal()
    await flushPromises()

    await w.find('input[type="checkbox"]').setValue(false)
    await flushPromises()

    expect(api.previewEntries).toHaveBeenLastCalledWith('set-1', 0, 50, false)
  })

  it('surfaces the delete guard and an incomplete scan', async () => {
    api.previewEntries.mockResolvedValue(ok(page({
      guardTripped: true, guardReason: 'planned deletions (60) exceed the absolute cap of 50',
      note: 'Source enumeration failed (timeout); no deletes would happen.',
    })))
    const w = mountModal()
    await flushPromises()

    expect(w.text()).toContain('Delete guard would trip: planned deletions (60) exceed the absolute cap of 50')
    expect(w.text()).toContain('Source enumeration failed (timeout)')
  })

  it('reports a failed load through the notification store', async () => {
    api.previewEntries.mockRejectedValue({ response: { data: { detail: 'size must be between 1 and 200' } } })
    mountModal()
    const notif = useNotificationStore()
    const spy = vi.spyOn(notif, 'error')
    await flushPromises()

    expect(spy).toHaveBeenCalledWith('size must be between 1 and 200')
  })
})
