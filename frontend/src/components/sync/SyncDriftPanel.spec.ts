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
  getHeldReferences: vi.fn(() => Promise.resolve({ data: {
    verifiedAt: '2026-11-05T08:00:00Z', detailsRecorded: true, counts: { DANGLING: 1, UNSYNCED: 30, UNCHECKED: 0 },
    matched: 1, page: 0, size: 25, entries: [
      { seq: 4, entryDn: 'cn=eng,ou=groups,dc=hr', sourceDn: 'cn=eng,ou=groups,dc=corp', identity: 'eng',
        attribute: 'member', reference: 'uid=ghost,ou=staff,dc=corp', status: 'DANGLING' },
    ],
  } })),
  downloadHeldCsv: vi.fn(() => Promise.resolve({ data: new Blob(['h']) })),
  previewOrphanDelete: vi.fn(() => Promise.resolve({ data: { items: [
    { seq: 1, action: 'DELETE', targetDn: 'uid=old,ou=people,dc=hr', reason: null },
    { seq: 7, action: 'SKIP', targetDn: 'uid=kept,ou=people,dc=hr', reason: 'This set now manages it.' },
  ] } })),
  deleteOrphans: vi.fn(() => Promise.resolve({ data: { deleted: 1, skipped: 0, failed: 0, items: [] } })),
  previewDriftRepair: vi.fn(() => Promise.resolve({ data: { items: [
    { seq: 0, action: 'CREATE', targetDn: 'uid=pn,ou=people,dc=hr', sourceDn: 'uid=pn,ou=staff,dc=corp', attributes: [], reason: null },
    { seq: 2, action: 'REAPPLY', targetDn: 'uid=js,ou=people,dc=hr', sourceDn: 'uid=js,ou=staff,dc=corp',
      attributes: [{ attribute: 'title', expected: ['Senior Engineer'], actual: ['Engineer'], hidden: false, truncated: false }], reason: null },
    { seq: 5, action: 'SKIP', targetDn: 'uid=ok,ou=people,dc=hr', sourceDn: 'uid=ok,ou=staff,dc=corp', attributes: [], reason: 'It no longer drifts.' },
  ] } })),
  repairDrift: vi.fn(() => Promise.resolve({ data: {
    created: 1, reapplied: 1, alreadyInSync: 0, skipped: 0, held: 0, failed: 0, items: [],
  } })),
}))
vi.mock('@/composables/useApi', () => ({ downloadBlob: vi.fn() }))

import SyncDriftPanel from './SyncDriftPanel.vue'
import * as syncApi from '@/api/sync'
import { downloadBlob } from '@/composables/useApi'
import { useNotificationStore } from '@/stores/notifications'

const set = { id: 'set-1', name: 'People → HR', sourceAnchorAttribute: 'employeeNumber', identityKey: null, verify: null }
/** The stored verify's time, sent back with every repair and delete. */
const AT = '2026-11-05T08:00:00Z'

function mountPanel(props: Record<string, unknown> = {}) {
  return mount(SyncDriftPanel, {
    props: { set, live: null, verifying: false, refreshKey: 0, canDiff: true,
      sourceDirectoryId: 'dir-src', targetDirectoryId: 'dir-hr', ...props } as never,
    global: { stubs: {
      AppModal: { props: ['modelValue', 'title'], template: '<div v-if="modelValue" class="modal"><slot /><slot name="footer" /></div>' },
      RouterLink: { props: ['to'], template: '<a class="rl" :data-to="JSON.stringify(to)"><slot /></a>' },
      RelativeTime: { props: ['value'], template: '<span>{{ value }}</span>' },
    } },
  })
}
const btn = (w: ReturnType<typeof mountPanel>, text: string) =>
  w.findAll('button').find(b => b.text().includes(text))!
/** Tick a row's checkbox. */
async function selectRow(w: ReturnType<typeof mountPanel>, seq: number) {
  await w.find(`[data-testid="drift-row-${seq}"] input[type="checkbox"]`).setValue(true)
}

describe('SyncDriftPanel', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    // Some tests override the page with mockResolvedValue, which outlives clearAllMocks.
    vi.mocked(syncApi.getDrift).mockImplementation(() => Promise.resolve({ data: page() }) as never)
  })

  it('lists every stored item with its kind, and counts per kind', async () => {
    const w = mountPanel()
    await flushPromises()

    expect(syncApi.getDrift).toHaveBeenCalledWith('set-1', { category: undefined, q: undefined, page: 0, size: 25 })
    expect(w.text()).toContain('Verified 2026-11-05T08:00:00Z · complete scan')
    const chips = w.findAll('[aria-pressed]').map(b => b.text().replace(/\s+/g, ' ').trim())
    expect(chips).toEqual(['All 3', 'Missing on target 1', 'Orphaned on target 1', 'Content drift 1'])
    expect(w.find('[data-testid="drift-row-0"]').text()).toContain('Missing')
    expect(w.find('[data-testid="drift-row-2"]').text()).toContain('title, mail, userPassword')
    expect(w.text()).toContain('of 3')
  })

  it('links target entries (not missing ones) and source entries to the Directory Browser', async () => {
    const w = mountPanel()
    await flushPromises()

    const orphanLink = w.find('[data-testid="drift-row-1"] .rl')
    expect(JSON.parse(orphanLink.attributes('data-to')!)).toEqual(
      { path: '/superadmin/directory-browser', query: { dir: 'dir-hr', dn: 'uid=old,ou=people,dc=hr' } })
    expect(w.find('[data-testid="drift-row-0"] .rl').exists()).toBe(false)
  })

  it('a chip filters the table by kind', async () => {
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

  it('makes one request per change, back on the first page', async () => {
    vi.useFakeTimers()
    try {
      vi.mocked(syncApi.getDrift).mockResolvedValue({ data: page({ matched: 60 }) } as never)
      const w = mountPanel()
      await flushPromises()
      await btn(w, 'Next').trigger('click')
      await flushPromises()
      await btn(w, 'Next').trigger('click')
      await flushPromises()
      const calls = () => vi.mocked(syncApi.getDrift).mock.calls.length
      const lastArgs = () => vi.mocked(syncApi.getDrift).mock.lastCall?.[1]

      let before = calls()
      await btn(w, 'Orphaned on target').trigger('click')
      await flushPromises()
      expect(calls() - before).toBe(1)
      expect(lastArgs()).toMatchObject({ category: 'ORPHAN', page: 0 })

      await btn(w, 'Next').trigger('click')
      await flushPromises()
      before = calls()
      await w.find('select[aria-label="Drift rows per page"]').setValue('100')
      await flushPromises()
      expect(calls() - before).toBe(1)
      expect(lastArgs()).toMatchObject({ size: 100, page: 0 })

      // A finished check with a search typed: one reload, nothing stale after it.
      await w.find('input[aria-label="Filter drift"]').setValue('senior')
      await vi.advanceTimersByTimeAsync(300)
      await flushPromises()
      before = calls()
      await w.setProps({ refreshKey: 1 })
      await flushPromises()
      await vi.advanceTimersByTimeAsync(300)
      await flushPromises()
      expect(calls() - before).toBe(1)
      expect(lastArgs()).toMatchObject({ category: undefined, q: undefined, page: 0 })
    } finally {
      vi.useRealTimers()
    }
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

  it('reports entries leaving the set (held deletes) apart from the orphans', async () => {
    const live = { sourceMembers: 8, targetEntries: 11, inSync: 8, missingOnTarget: 0, orphanOnTarget: 1,
      contentMismatches: 0, sampleMissing: [], sampleOrphans: [], sampleMismatches: [],
      sourceComplete: true, targetComplete: true, note: null, leavingSet: 2 }
    const w = mountPanel({ live })
    await flushPromises()
    expect(w.find('[data-testid="leaving-set"]').text()).toContain('2 leaving the set')
    await w.setProps({ live: { ...live, leavingSet: 0 } })
    expect(w.find('[data-testid="leaving-set"]').exists()).toBe(false)
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

  it('a check it didn\'t start reloads the items in place, keeping the filter and re-selecting by DN', async () => {
    const w = mountPanel({ canManage: true })
    await flushPromises()
    await btn(w, 'Content drift').trigger('click')
    await flushPromises()
    await selectRow(w, 2)
    vi.mocked(syncApi.getDrift).mockClear()
    // The newer check renumbered the same entry from 2 to 9.
    vi.mocked(syncApi.getDrift).mockResolvedValueOnce({ data: page({
      verifiedAt: '2026-11-05T09:00:00Z', counts: { MISSING: 0, ORPHAN: 0, MISMATCH: 1 }, matched: 1,
      entries: [{ seq: 9, category: 'MISMATCH', targetDn: 'uid=js,ou=people,dc=hr', sourceDn: 'uid=js,ou=staff,dc=corp',
        identity: 'js', attributes: [] }],
    }) } as never)

    await w.setProps({ reloadKey: 1 })
    await flushPromises()

    expect(syncApi.getDrift).toHaveBeenCalledTimes(1)
    expect(syncApi.getDrift).toHaveBeenCalledWith('set-1', expect.objectContaining({ category: 'MISMATCH', page: 0 }))
    expect(btn(w, 'Content drift').attributes('aria-pressed')).toBe('true')
    expect((w.find('[data-testid="drift-row-9"] input[type="checkbox"]').element as HTMLInputElement).checked).toBe(true)
    expect(w.find('[data-testid="drift-bulk"]').text()).toContain('1 selected')
  })

  describe('held references', () => {
    const withHeld = () => vi.mocked(syncApi.getDrift).mockResolvedValue(
      { data: page({ held: { DANGLING: 1, UNSYNCED: 30, UNCHECKED: 0 } }) } as never)

    it('counts them apart from drift, with a chip per status that has any', async () => {
      withHeld()
      const w = mountPanel()
      await flushPromises()
      const chips = w.findAll('[aria-pressed]').map(b => b.text().replace(/\s+/g, ' ').trim())
      expect(chips).toEqual(['All 3', 'Missing on target 1', 'Orphaned on target 1', 'Content drift 1',
        'Held: doesn’t exist on source 1', 'Held: not synced yet 30'])
      expect(w.find('[data-testid="held-references"]').text().replace(/\s+/g, ' '))
        .toContain('31 values name an entry the sync hasn’t placed')
    })

    it('a held chip lists every held reference of that status, with the source entry to fix', async () => {
      withHeld()
      const w = mountPanel({ canManage: true })
      await flushPromises()
      await btn(w, 'Held: doesn’t exist on source').trigger('click')
      await flushPromises()

      expect(syncApi.getHeldReferences).toHaveBeenCalledWith('set-1',
        { status: 'DANGLING', q: undefined, page: 0, size: 25 })
      const row = w.find('[data-testid="held-row-4"]')
      expect(row.text()).toContain('Doesn’t exist on source')
      expect(row.text()).toContain('member')
      expect(row.text()).toContain('uid=ghost,ou=staff,dc=corp')
      expect(JSON.parse(row.find('.rl').attributes('data-to')!)).toEqual(
        { path: '/superadmin/directory-browser', query: { dir: 'dir-src', dn: 'cn=eng,ou=groups,dc=corp' } })
      // Not drift: nothing to select or repair.
      expect(w.find('[data-testid="drift-bulk"]').exists()).toBe(false)
      expect(w.find('[data-testid="drift-row-0"]').exists()).toBe(false)
      expect(w.text()).toContain('of 1')
    })

    it('the summary links straight to the ones to fix, and Export CSV exports the held list', async () => {
      withHeld()
      const w = mountPanel()
      await flushPromises()
      await w.find('[data-testid="held-references"] button').trigger('click')
      await flushPromises()
      expect(btn(w, 'Held: doesn’t exist on source').attributes('aria-pressed')).toBe('true')

      await btn(w, 'Export CSV').trigger('click')
      await flushPromises()
      expect(syncApi.downloadHeldCsv).toHaveBeenCalledWith('set-1')
      expect(downloadBlob).toHaveBeenCalledWith(expect.any(Blob), 'sync-held-references-People-HR.csv')
    })

    it('offers the list even when nothing drifts', async () => {
      vi.mocked(syncApi.getDrift).mockResolvedValue({ data: page({
        counts: { MISSING: 0, ORPHAN: 0, MISMATCH: 0 }, matched: 0, entries: [], held: { UNSYNCED: 2 },
      }) } as never)
      const w = mountPanel()
      await flushPromises()
      expect(w.find('[data-testid="drift-none"]').exists()).toBe(true)
      expect(btn(w, 'Held: not synced yet')).toBeTruthy()
    })
  })

  describe('repair', () => {
    it('offers repair only to managers, and only on missing and drifted entries', async () => {
      let w = mountPanel({ canManage: false })
      await flushPromises()
      expect(w.find('[data-testid="drift-bulk"]').exists()).toBe(false)
      expect(w.findAll('tbody input[type="checkbox"]')).toHaveLength(0)

      w = mountPanel({ canManage: true })
      await flushPromises()
      expect(w.find('[data-testid="drift-bulk"]').exists()).toBe(true)
      expect(w.find('[data-testid="drift-row-0"] input[type="checkbox"]').exists()).toBe(true)
      // An orphan of an anchored set can be selected (to delete).
      expect(w.find('[data-testid="drift-row-1"] input[type="checkbox"]').exists()).toBe(true)
      // Actions live in the bulk bar only, not on the rows.
      for (const seq of [0, 1, 2]) {
        expect(w.find(`[data-testid="drift-row-${seq}"]`).findAll('button').map(b => b.text()))
          .not.toContain(expect.stringMatching(/Create|Re-apply|Delete/))
      }
    })

    it('previews a row\'s repair after a live re-check, then repairs what isn\'t skipped', async () => {
      const w = mountPanel({ canManage: true })
      await flushPromises()
      const success = vi.spyOn(useNotificationStore(), 'success')

      await selectRow(w, 0)
      await selectRow(w, 2)
      await btn(w, 'Repair (2)').trigger('click')
      await flushPromises()
      // With each chosen row's target DN, in case a newer check renumbers them meanwhile.
      expect(syncApi.previewDriftRepair).toHaveBeenCalledWith('set-1', [0, 2], AT,
        ['uid=pn,ou=people,dc=hr', 'uid=js,ou=people,dc=hr'])
      const groups = (k: string) => w.find(`[data-testid="repair-group-${k}"]`).text().replace(/\s+/g, ' ')
      expect(groups('create')).toContain('1 to create on the target')
      expect(groups('reapply')).toContain('1 to re-apply from the source')
      expect(groups('reapply')).toContain('— title')
      expect(groups('skip')).toContain('It no longer drifts.')
      expect(syncApi.repairDrift).not.toHaveBeenCalled()

      await btn(w, 'Repair 2').trigger('click')
      await flushPromises()

      expect(syncApi.repairDrift).toHaveBeenCalledWith('set-1', [0, 2], AT,
        ['uid=pn,ou=people,dc=hr', 'uid=js,ou=people,dc=hr'])
      expect(success).toHaveBeenCalledWith('Repair finished: 1 created, 1 re-applied')
      expect(w.emitted('changed')).toHaveLength(1)
      expect(syncApi.getDrift).toHaveBeenCalledTimes(2) // reloaded
      expect(w.find('[data-testid="repair-preview"]').exists()).toBe(false)
    })

    it('acts on the selection from the bulk bar: Repair takes the repairable rows, Delete the orphans', async () => {
      const w = mountPanel({ canManage: true })
      await flushPromises()
      await w.find('thead input[type="checkbox"]').setValue(true)
      expect(w.find('[data-testid="drift-bulk"]').text()).toContain('3 selected')

      await btn(w, 'Repair (2)').trigger('click')
      await flushPromises()
      expect(syncApi.previewDriftRepair).toHaveBeenCalledWith('set-1', [0, 2], AT,
        ['uid=pn,ou=people,dc=hr', 'uid=js,ou=people,dc=hr'])

      await btn(w, 'Delete (1)').trigger('click')
      await flushPromises()
      expect(syncApi.previewOrphanDelete).toHaveBeenCalledWith('set-1', [1], AT, ['uid=old,ou=people,dc=hr'])
    })

    it('warns with the reason when something was held or failed', async () => {
      vi.mocked(syncApi.repairDrift).mockResolvedValueOnce({ data: {
        created: 0, reapplied: 1, alreadyInSync: 0, skipped: 0, held: 1, failed: 0,
        items: [{ seq: 0, outcome: 'HELD', targetDn: 'uid=pn,ou=people,dc=hr', message: 'Placement conflict at uid=pn' }],
      } } as never)
      const w = mountPanel({ canManage: true })
      await flushPromises()
      const warning = vi.spyOn(useNotificationStore(), 'warning')
      await selectRow(w, 0)
      await btn(w, 'Repair (1)').trigger('click')
      await flushPromises()
      await btn(w, 'Repair 2').trigger('click')
      await flushPromises()

      expect(warning).toHaveBeenCalledWith('Repair finished: 1 re-applied, 1 held for review. Placement conflict at uid=pn')
    })

    it('can\'t apply when the re-check leaves nothing to repair', async () => {
      vi.mocked(syncApi.previewDriftRepair).mockResolvedValueOnce({ data: { items: [
        { seq: 0, action: 'SKIP', targetDn: 'uid=pn,ou=people,dc=hr', sourceDn: null, attributes: [], reason: 'It no longer drifts.' },
      ] } } as never)
      const w = mountPanel({ canManage: true })
      await flushPromises()
      await selectRow(w, 0)
      await btn(w, 'Repair (1)').trigger('click')
      await flushPromises()
      expect(btn(w, 'Repair 0').attributes('disabled')).toBeDefined()
    })
  })

  describe('delete orphans', () => {
    it('never offers delete when the set writes no source anchor', async () => {
      const w = mountPanel({ canManage: true, set: { ...set, sourceAnchorAttribute: null } })
      await flushPromises()
      expect(w.find('[data-testid="drift-row-1"] input[type="checkbox"]').exists()).toBe(false)
      expect(w.find('[data-testid="orphan-no-delete"]').text()).toContain('without an anchor nothing proves')
    })

    it('hides the selection when nothing on the page can be acted on, and says why', async () => {
      vi.mocked(syncApi.getDrift).mockResolvedValue({ data: page({
        counts: { MISSING: 0, ORPHAN: 1, MISMATCH: 0 }, matched: 1,
        entries: [{ seq: 1, category: 'ORPHAN', targetDn: 'uid=old,ou=people,dc=hr', sourceDn: null, identity: null,
          attributes: [] }],
      }) } as never)
      const w = mountPanel({ canManage: true, set: { ...set, sourceAnchorAttribute: null } })
      await flushPromises()
      expect(w.find('thead input[type="checkbox"]').exists()).toBe(false)
      expect(w.find('[data-testid="drift-bulk"]').exists()).toBe(false)
      expect(w.find('[data-testid="orphan-no-delete"]').exists()).toBe(true)
    })

    it('never offers delete when the set keys on a custom identity attribute', async () => {
      const w = mountPanel({ canManage: true, set: { ...set, identityKey: 'uid' } })
      await flushPromises()
      expect(w.find('[data-testid="drift-row-1"] input[type="checkbox"]').exists()).toBe(false)
      expect(w.find('[data-testid="orphan-no-delete"]').text()).toContain('custom identity attribute')
    })

    it('closes the dialog and reloads when the server refuses a stale selection', async () => {
      vi.mocked(syncApi.deleteOrphans).mockRejectedValueOnce({ response: { data: {
        detail: 'The drift list changed since you loaded it (a newer content check ran); reload it and choose again.',
      } } })
      const w = mountPanel({ canManage: true })
      await flushPromises()
      const error = vi.spyOn(useNotificationStore(), 'error')
      await selectRow(w, 1)
      await btn(w, 'Delete (1)').trigger('click')
      await flushPromises()
      await w.find('#drift-delete-confirm').setValue('DELETE')
      vi.mocked(syncApi.getDrift).mockClear()
      await btn(w, 'Delete 1').trigger('click')
      await flushPromises()

      expect(error).toHaveBeenCalledWith(expect.stringContaining('The drift list changed'))
      expect(w.find('[data-testid="delete-preview"]').exists()).toBe(false)
      expect(syncApi.getDrift).toHaveBeenCalledTimes(1)
    })

    it('previews, needs DELETE typed, then deletes only what the re-check allows', async () => {
      const w = mountPanel({ canManage: true })
      await flushPromises()
      const success = vi.spyOn(useNotificationStore(), 'success')

      await selectRow(w, 1)
      await btn(w, 'Delete (1)').trigger('click')
      await flushPromises()
      expect(syncApi.previewOrphanDelete).toHaveBeenCalledWith('set-1', [1], AT, ['uid=old,ou=people,dc=hr'])
      expect(w.find('[data-testid="delete-group-delete"]').text()).toContain('1 to delete from the target')
      expect(w.find('[data-testid="delete-group-skip"]').text()).toContain('This set now manages it.')
      const go = () => btn(w, 'Delete 1')
      expect(go().attributes('disabled')).toBeDefined()
      await w.find('#drift-delete-confirm').setValue('delete')
      expect(go().attributes('disabled')).toBeDefined() // exact word only
      await w.find('#drift-delete-confirm').setValue('DELETE')
      expect(go().attributes('disabled')).toBeUndefined()

      await go().trigger('click')
      await flushPromises()

      expect(syncApi.deleteOrphans).toHaveBeenCalledWith('set-1', [1], AT, 'DELETE', ['uid=old,ou=people,dc=hr'])
      expect(success).toHaveBeenCalledWith('Delete finished: 1 deleted')
      expect(w.emitted('changed')).toHaveLength(1)
      expect(w.find('[data-testid="delete-preview"]').exists()).toBe(false)
    })

    it('warns with the reason when an orphan was left alone at delete time', async () => {
      vi.mocked(syncApi.deleteOrphans).mockResolvedValueOnce({ data: { deleted: 0, skipped: 0, failed: 1, items: [
        { seq: 1, outcome: 'FAILED', targetDn: 'uid=old,ou=people,dc=hr', message: 'delete failed: NOT_ALLOWED_ON_NONLEAF' },
      ] } } as never)
      const w = mountPanel({ canManage: true })
      await flushPromises()
      const warning = vi.spyOn(useNotificationStore(), 'warning')
      await selectRow(w, 1)
      await btn(w, 'Delete (1)').trigger('click')
      await flushPromises()
      await w.find('#drift-delete-confirm').setValue('DELETE')
      await btn(w, 'Delete 1').trigger('click')
      await flushPromises()

      expect(warning).toHaveBeenCalledWith('Delete finished: 1 failed. delete failed: NOT_ALLOWED_ON_NONLEAF')
    })
  })
})
