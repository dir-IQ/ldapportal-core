// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const page = vi.hoisted(() => (over: Record<string, unknown> = {}) => ({
  verifiedAt: '2026-11-05T08:00:00Z', incompleteAt: null, detailsRecorded: true,
  counts: { MISSING: 1, ORPHAN: 1, MISMATCH: 1 }, matched: 3, page: 0, size: 25,
  entries: [
    { seq: 0, category: 'MISSING', targetDn: 'uid=pn,ou=people,dc=hr', sourceDn: 'uid=pn,ou=staff,dc=corp',
      identity: 'pn', attributes: [] },
    { seq: 1, category: 'ORPHAN', targetDn: 'uid=old,ou=people,dc=hr', sourceDn: null, identity: null, attributes: [] },
    { seq: 2, category: 'MISMATCH', targetDn: 'uid=js,ou=people,dc=hr', sourceDn: 'uid=js,ou=staff,dc=corp',
      identity: 'js', attributes: [
        { attribute: 'title', expected: ['Senior Engineer'], actual: ['Engineer'], hidden: false, truncated: false },
        { attribute: 'mail', expected: ['js@corp.com'], actual: [], hidden: false, truncated: true },
        { attribute: 'userPassword', expected: [], actual: [], hidden: true, truncated: false },
      ] },
  ],
  ...over,
}))

vi.mock('@/api/sync', () => ({
  getDrift: vi.fn(() => Promise.resolve({ data: page() })),
  downloadDriftCsv: vi.fn(() => Promise.resolve({ data: new Blob(['x']) })),
}))
vi.mock('@/composables/useApi', () => ({ downloadBlob: vi.fn() }))

import SyncDriftPanel from './SyncDriftPanel.vue'
import * as syncApi from '@/api/sync'
import { downloadBlob } from '@/composables/useApi'

const set = { id: 'set-1', name: 'People → HR', sourceAnchorAttribute: 'employeeNumber', verify: null }

function mountPanel(props: Record<string, unknown> = {}) {
  return mount(SyncDriftPanel, {
    props: { set, live: null, verifying: false, refreshKey: 0, canDiff: true,
      sourceDirectoryId: 'dir-src', targetDirectoryId: 'dir-hr', ...props } as never,
    global: { stubs: {
      RouterLink: { props: ['to'], template: '<a class="rl" :data-to="JSON.stringify(to)"><slot /></a>' },
      RelativeTime: { props: ['value'], template: '<span>{{ value }}</span>' },
    } },
  })
}
const btn = (w: ReturnType<typeof mountPanel>, text: string) =>
  w.findAll('button').find(b => b.text().includes(text))!

describe('SyncDriftPanel', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('lists every stored item with its kind, and counts per kind', async () => {
    const w = mountPanel()
    await flushPromises()

    expect(syncApi.getDrift).toHaveBeenCalledWith('set-1', { category: undefined, q: undefined, page: 0, size: 25 })
    expect(w.text()).toContain('Verified 2026-11-05T08:00:00Z · complete scan')
    const cards = w.findAll('[aria-pressed]').map(b => b.findAll('span').map(x => x.text()))
    expect(cards).toEqual([['All', '3'], ['Missing on target', '1'], ['Orphaned on target', '1'], ['Content drift', '1']])
    expect(w.find('[data-testid="drift-row-0"]').text()).toContain('Missing')
    expect(w.find('[data-testid="drift-row-2"]').text()).toContain('title, mail, userPassword')
    expect(w.text()).toContain('1–3 of 3')
  })

  it('links target entries (not missing ones) and source entries to the Directory Browser', async () => {
    const w = mountPanel()
    await flushPromises()

    const orphanLink = w.find('[data-testid="drift-row-1"] .rl')
    expect(JSON.parse(orphanLink.attributes('data-to')!)).toEqual(
      { path: '/superadmin/directory-browser', query: { dir: 'dir-hr', dn: 'uid=old,ou=people,dc=hr' } })
    expect(w.find('[data-testid="drift-row-0"] .rl').exists()).toBe(false)
  })

  it('a card filters the table by kind', async () => {
    const w = mountPanel()
    await flushPromises()
    await btn(w, 'Content drift').trigger('click')
    await flushPromises()

    expect(syncApi.getDrift).toHaveBeenLastCalledWith('set-1', expect.objectContaining({ category: 'MISMATCH', page: 0 }))
    expect(btn(w, 'Content drift').attributes('aria-pressed')).toBe('true')
  })

  it('searches after typing stops', async () => {
    vi.useFakeTimers()
    try {
      const w = mountPanel()
      await flushPromises()
      await w.find('input[aria-label="Filter drift"]').setValue('senior')
      expect(syncApi.getDrift).toHaveBeenCalledTimes(1)
      await vi.advanceTimersByTimeAsync(300)
      expect(syncApi.getDrift).toHaveBeenLastCalledWith('set-1', expect.objectContaining({ q: 'senior', page: 0 }))
    } finally {
      vi.useRealTimers()
    }
  })

  it('expands a content drift to its attributes: expected against actual, hiding secrets', async () => {
    const w = mountPanel()
    await flushPromises()
    await w.find('[data-testid="drift-row-2"] button[aria-expanded]').trigger('click')

    const detailEl = w.find('[data-testid="drift-detail-2"]')
    const rows = detailEl.findAll('tbody tr').map(r => r.findAll('td').map(td => td.text()))
    expect(rows).toEqual([
      ['title', 'Senior Engineer', 'Engineer'],
      ['mail', 'js@corp.com', '(none)'],
      ['userPassword', '(hidden)', '(hidden)'],
    ])
    const detail = detailEl.text().replace(/\s+/g, ' ')
    expect(detail).toContain('Long value lists show their first 50 values')
    expect(detail).toContain('Source: uid=js,ou=staff,dc=corp · identity js')
    // An orphan has nothing to expand.
    expect(w.find('[data-testid="drift-row-1"] button[aria-expanded]').exists()).toBe(false)
  })

  it('pages through the items', async () => {
    vi.mocked(syncApi.getDrift).mockResolvedValue({ data: page({ matched: 60 }) } as never)
    const w = mountPanel()
    await flushPromises()
    expect(w.text()).toContain('Page 1 of 3')
    await btn(w, 'Next').trigger('click')
    await flushPromises()
    expect(syncApi.getDrift).toHaveBeenLastCalledWith('set-1', expect.objectContaining({ page: 1 }))
  })

  it('exports the stored items as CSV', async () => {
    const w = mountPanel()
    await flushPromises()
    await btn(w, 'Export CSV').trigger('click')
    await flushPromises()
    expect(syncApi.downloadDriftCsv).toHaveBeenCalledWith('set-1')
    expect(vi.mocked(downloadBlob).mock.calls[0][1]).toBe('sync-drift-People-HR.csv')
  })

  it('asks for a check when the counts predate stored details', async () => {
    vi.mocked(syncApi.getDrift).mockResolvedValue({ data: page({ detailsRecorded: false, entries: [], matched: 0 }) } as never)
    const w = mountPanel()
    await flushPromises()
    expect(w.find('[data-testid="drift-no-details"]').exists()).toBe(true)
  })

  it('says when source and target agree, and when a later check was incomplete', async () => {
    vi.mocked(syncApi.getDrift).mockResolvedValue({ data: page({
      counts: { MISSING: 0, ORPHAN: 0, MISMATCH: 0 }, matched: 0, entries: [],
      incompleteAt: '2026-11-05T09:00:00Z',
    }) } as never)
    const w = mountPanel()
    await flushPromises()
    expect(w.find('[data-testid="drift-none"]').exists()).toBe(true)
    expect(w.find('[data-testid="drift-incomplete"]').exists()).toBe(true)
    expect(w.find('table').exists()).toBe(false)
  })

  it('Verify now asks the dialog to check; a new refresh key reloads', async () => {
    const w = mountPanel()
    await flushPromises()
    await btn(w, 'Verify now').trigger('click')
    expect(w.emitted('verify')).toHaveLength(1)

    await w.setProps({ refreshKey: 1 })
    await flushPromises()
    expect(syncApi.getDrift).toHaveBeenCalledTimes(2)
  })

  it('hides the Directory Diff link without that permission', async () => {
    const w = mountPanel({ canDiff: false })
    await flushPromises()
    expect(w.text()).not.toContain('Compare in Directory Diff')
  })
})
