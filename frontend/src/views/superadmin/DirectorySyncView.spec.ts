// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import DirectorySyncView from './DirectorySyncView.vue'

// Drives the dashboard deep-link tests; reset to {} for the non-deep-link cases.
const routerState = vi.hoisted(() => ({ query: {} as Record<string, string> }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: routerState.query }),
  useRouter: () => ({ push: vi.fn() }),
}))

// The delete confirmation; each test decides the answer.
const confirmApi = vi.hoisted(() => ({ confirm: vi.fn() }))
vi.mock('@/composables/useConfirm', () => ({ useConfirm: () => confirmApi.confirm }))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ hasSuperadminPermission: () => true }),
}))
vi.mock('@/api/directories', () => ({
  listDirectories: vi.fn(() =>
    Promise.resolve({ data: [
      { id: 'dir-src', displayName: 'Source LDAP' },
      { id: 'dir-dst', displayName: 'Target LDAP' },
    ] }),
  ),
}))

const link = {
  id: 'link-1', displayName: 'src->dst', sourceDirId: 'dir-src', targetDirId: 'dir-dst',
  enabled: true, captureMode: 'APP_INTERCEPT', createdAt: '', updatedAt: '', version: 0,
}
const set = {
  id: 'set-1', linkId: 'link-1', name: 'people', objectScopeBaseDn: 'ou=people,dc=src',
  objectScope: 'SUB', identityKey: null, targetBaseDn: 'ou=Users,dc=dst', applicabilityFilter: null,
  referenceAttributes: null, sourceAnchorAttribute: null, deletePolicy: 'DELETE', transformRules: null,
  reconcileCadenceSeconds: null, reconcileLastRunAt: null, enabled: true, loggingLevel: 'SUMMARY',
  createdAt: '', updatedAt: '', version: 0,
  stateCounts: { APPLIED: 5, FAILED: 2, REVIEW: 1 },
}
const membership = {
  syncSetId: 'set-1', identity: '1111', sourceDn: 'uid=a,ou=people,dc=src',
  targetDn: 'uid=a,ou=Users,dc=dst', state: 'REVIEW', failReason: 'ambiguous',
  lastSrcCursor: null, lastScanEpoch: null,
}

vi.mock('@/api/sync', () => ({
  listSyncLinks: vi.fn(() => Promise.resolve({ data: [link] })),
  createSyncLink: vi.fn(() => Promise.resolve({ data: link })),
  updateSyncLink: vi.fn(() => Promise.resolve({ data: link })),
  deleteSyncLink: vi.fn(() => Promise.resolve({ data: undefined })),
  listSyncSets: vi.fn(() => Promise.resolve({ data: [set] })),
  createSyncSet: vi.fn(() => Promise.resolve({ data: set })),
  updateSyncSet: vi.fn(() => Promise.resolve({ data: set })),
  deleteSyncSet: vi.fn(() => Promise.resolve({ data: undefined })),
  getSyncSetDeleteImpact: vi.fn(() => Promise.resolve({ data: {
    name: 'people', managed: 876, pendingReview: 3, reconcileRuns: 12, otherSets: ['groups'],
  } })),
  getSyncSetDefaults: vi.fn(() => Promise.resolve({ data: {
    identityKeyByDirectoryType: { OPENLDAP: 'entryUUID', GENERIC: 'entryUUID', ACTIVE_DIRECTORY: 'objectGUID' },
    referenceAttributes: ['member', 'uniqueMember', 'manager', 'owner', 'secDN'],
    reconcileIntervalSeconds: 3600,
    excludedAttributes: ['userPassword', 'entryUUID'],
  } })),
  getPreviewRun: vi.fn(() => Promise.resolve({ data: { runId: null, status: null, actionCounts: {} } })),
  previewEntries: vi.fn(() => Promise.resolve({ data: {
    entries: [], page: 0, size: 50, totalEntries: 0, totalPages: 0, changesOnly: true, runId: 'run-1',
  } })),
  startPreviewRun: vi.fn(() => Promise.resolve({ data: {
    runId: 'run-1', status: 'COMPLETE', requestedBy: 'ann', startedAt: '2026-10-23T03:00:00Z', finishedAt: '2026-10-23T03:00:05Z', processed: 5,
    total: 5, outdated: false, error: null, sourceCount: 5, managedCount: 3, actionableCount: 2,
    unchangedCount: 3, guardTripped: false, guardReason: null, completeScan: true, note: null,
    actionCounts: { ADD: 1, DELETE: 1, NO_CHANGE: 2, UNCHANGED: 1 },
  } })),
  listMemberships: vi.fn(() => Promise.resolve({
    data: { content: [membership], totalElements: 8, totalPages: 1, number: 0, size: 50 },
  })),
  reconcileSet: vi.fn(() => Promise.resolve({ data: { enumerated: 3 } })),
  recomputeKey: vi.fn(() => Promise.resolve({ data: undefined })),
  dismissMembership: vi.fn(() => Promise.resolve({ data: undefined })),
}))

import * as syncApi from '@/api/sync'

describe('DirectorySyncView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    routerState.query = {}
  })

  it('loads and renders sync links', async () => {
    const wrapper = mount(DirectorySyncView)
    await flushPromises()
    expect(syncApi.listSyncLinks).toHaveBeenCalled()
    expect(wrapper.text()).toContain('src->dst')
    expect(wrapper.text()).toContain('Source LDAP')
  })

  it('selecting a link loads its sync sets', async () => {
    const wrapper = mount(DirectorySyncView)
    await flushPromises()
    await wrapper.find('tbody tr').trigger('click')
    await flushPromises()
    expect(syncApi.listSyncSets).toHaveBeenCalledWith('link-1')
    expect(wrapper.text()).toContain('people')
  })

  it('selecting a set opens the membership inventory modal', async () => {
    const wrapper = mount(DirectorySyncView, { attachTo: document.body })
    await flushPromises()
    await wrapper.find('tbody tr').trigger('click') // expand link
    await flushPromises()
    const setRow = wrapper.findAll('table')[1].findAll('tbody tr')[0]
    await setRow.trigger('click') // select set → opens the (teleported) modal
    await flushPromises()
    // The modal teleports to <body>; it loads the set's memberships and titles itself.
    expect(syncApi.listMemberships).toHaveBeenCalledWith('set-1', expect.objectContaining({ page: 0, size: 50 }))
    expect(document.body.textContent).toContain('Membership inventory — people')
    wrapper.unmount()
  })

  it('rolls up membership health onto the link row (worst state wins)', async () => {
    const wrapper = mount(DirectorySyncView)
    await flushPromises()
    // FAILED outranks REVIEW/APPLIED, so the link shows the failure summary.
    expect(wrapper.find('tbody tr').text()).toContain('2 failed')
  })

  it('authoring a transform rule includes it in the create payload', async () => {
    const wrapper = mount(DirectorySyncView, { attachTo: document.body })
    await flushPromises()
    await wrapper.find('tbody tr').trigger('click') // select link
    await flushPromises()
    // The set modal teleports to <body>; drive it through document.
    const byText = (t: string) =>
      Array.from(document.querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent?.includes(t))!
    byText('New Set').click()
    await flushPromises()

    const nameLabel = Array.from(document.querySelectorAll('label')).find((l) => l.textContent?.trim().startsWith('Name'))!
    const nameInput = document.getElementById((nameLabel as HTMLLabelElement).htmlFor) as HTMLInputElement
    nameInput.value = 'groups'
    nameInput.dispatchEvent(new Event('input'))

    byText('Add mapping').click()
    await flushPromises()
    const src = document.getElementById('tr-src-0') as HTMLInputElement
    src.value = 'uid'
    src.dispatchEvent(new Event('input'))
    const tgt = document.getElementById('tr-tgt-0') as HTMLInputElement
    tgt.value = 'sAMAccountName'
    tgt.dispatchEvent(new Event('input'))
    await flushPromises()

    document.querySelector('form')!.dispatchEvent(new Event('submit'))
    await flushPromises()

    expect(syncApi.createSyncSet).toHaveBeenCalledTimes(1)
    const payload = (syncApi.createSyncSet as unknown as { mock: { calls: unknown[][] } }).mock.calls[0][0] as {
      transformRules: unknown
    }
    expect(payload.transformRules).toEqual([
      { sourceAttr: 'uid', targetAttr: 'sAMAccountName', valueTemplate: null },
    ])
    wrapper.unmount()
  })

  it('deep link ?set=<id> expands the link and opens that set inventory', async () => {
    routerState.query = { set: 'set-1' }
    const wrapper = mount(DirectorySyncView, { attachTo: document.body })
    await flushPromises()
    await flushPromises()
    expect(syncApi.listSyncSets).toHaveBeenCalledWith('link-1') // parent link expanded
    expect(syncApi.listMemberships).toHaveBeenCalledWith('set-1', expect.objectContaining({ page: 0, size: 50 }))
    expect(document.body.textContent).toContain('Membership inventory — people')
    wrapper.unmount()
  })

  it('deep link ?state=FAILED opens the worst set pre-filtered to that state', async () => {
    routerState.query = { state: 'FAILED' }
    const wrapper = mount(DirectorySyncView, { attachTo: document.body })
    await flushPromises()
    await flushPromises()
    // set-1 has the FAILED memberships, so the inventory opens already filtered.
    expect(syncApi.listMemberships).toHaveBeenCalledWith('set-1', expect.objectContaining({ state: 'FAILED' }))
    wrapper.unmount()
  })

  it('deep link ?link=<id> expands that link without opening a set modal', async () => {
    routerState.query = { link: 'link-1' }
    const wrapper = mount(DirectorySyncView)
    await flushPromises()
    expect(syncApi.listSyncSets).toHaveBeenCalledWith('link-1')
    expect(wrapper.text()).toContain('people') // nested sets shown
    expect(syncApi.listMemberships).not.toHaveBeenCalled() // no inventory modal
    wrapper.unmount()
  })

  describe('the set editor', () => {
    const buttons = () => Array.from(document.querySelectorAll<HTMLButtonElement>('button'))
    const button = (text: string) => buttons().find((b) => b.textContent?.trim() === text)
    const field = (label: string) => {
      const l = Array.from(document.querySelectorAll('label')).find((x) => x.textContent?.includes(label))!
      return document.getElementById((l as HTMLLabelElement).htmlFor) as HTMLInputElement
    }
    const type = async (label: string, value: string) => {
      const input = field(label)
      input.value = value
      input.dispatchEvent(new Event('input'))
      await flushPromises()
    }
    const submit = async () => {
      document.getElementById('sync-set-form')!.dispatchEvent(new Event('submit'))
      await flushPromises()
    }

    async function openEditor(action: 'Edit' | '+ New Set') {
      const wrapper = mount(DirectorySyncView, { attachTo: document.body })
      await flushPromises()
      await wrapper.find('tbody tr').trigger('click') // select link
      await flushPromises()
      const matches = buttons().filter((b) => b.textContent?.trim() === action)
      matches[matches.length - 1].click() // the set row's Edit, not the link's
      await flushPromises()
      return wrapper
    }

    it('holds the preview buttons until edits are saved', async () => {
      const wrapper = await openEditor('Edit')
      expect(button('Preview impact')!.disabled).toBe(false)
      expect(button('Save')!.disabled).toBe(true) // nothing to save yet

      await type('Reference attributes', 'member,uniqueMember,manager,owner')
      expect(button('Preview impact')!.disabled).toBe(true)
      expect(button('Show changes')).toBeUndefined() // one button: Preview impact opens the list
      expect(document.body.textContent).toContain('Save your changes to preview them')
      wrapper.unmount()
    })

    it('saves without closing, then enables the preview', async () => {
      const saved = { ...set, referenceAttributes: 'member,uniqueMember,manager,owner' }
      vi.mocked(syncApi.updateSyncSet).mockResolvedValueOnce({ data: saved } as never)
      const wrapper = await openEditor('Edit')

      await type('Reference attributes', 'member,uniqueMember,manager,owner')
      await submit()

      expect(syncApi.updateSyncSet).toHaveBeenCalledWith('set-1',
        expect.objectContaining({ referenceAttributes: 'member,uniqueMember,manager,owner' }))
      expect(document.body.textContent).toContain('Edit sync set') // still open
      expect(field('Reference attributes').value).toBe('member,uniqueMember,manager,owner')
      expect(button('Preview impact')!.disabled).toBe(false)
      expect(button('Save')!.disabled).toBe(true)

      // Preview impact opens the per-entry list, which runs a fresh preview (the set has none).
      const complete = (await syncApi.startPreviewRun('set-1')).data
      vi.mocked(syncApi.startPreviewRun).mockClear()
      vi.mocked(syncApi.getPreviewRun)
        .mockResolvedValueOnce({ data: { runId: null, status: null, actionCounts: {} } } as never)
        .mockResolvedValueOnce({ data: complete } as never)
      button('Preview impact')!.click()
      await flushPromises()
      expect(document.body.textContent).toContain('Planned changes — people')
      expect(syncApi.startPreviewRun).toHaveBeenCalledWith('set-1')

      // Closing it leaves that run's counts in the editor: the same run the list showed.
      const closes = Array.from(document.querySelectorAll<HTMLButtonElement>('button[aria-label="Close"]'))
      closes[closes.length - 1].click()
      await flushPromises()
      expect(document.body.textContent).not.toContain('Planned changes — people')
      expect(document.querySelector('[data-testid="impact-counts"]')!.textContent!.replace(/\s+/g, ' '))
        .toContain('Would add: 1 · Would modify: 0 · Would move: 0 · Would delete: 1 · Held for review: 0 · '
          + 'Errors: 0 · No change: 3')
      expect(document.body.textContent).toContain('Last preview:')
      expect(document.body.textContent).toContain('by ann')
      wrapper.unmount()
    })

    it('turns a created set into an editable one with the preview available', async () => {
      const wrapper = await openEditor('+ New Set')
      expect(document.body.textContent).toContain('New sync set')
      expect(button('Preview impact')).toBeUndefined()

      await type('Name', 'groups')
      await type('Source base DN', 'ou=groups,dc=src')
      await submit()

      expect(syncApi.createSyncSet).toHaveBeenCalledTimes(1)
      expect(document.body.textContent).toContain('Edit sync set')
      expect(button('Preview impact')!.disabled).toBe(false)
      wrapper.unmount()
    })

    it('asks before Close discards unsaved edits', async () => {
      const wrapper = await openEditor('Edit')
      await type('Reference attributes', 'member')

      button('Close')!.click()
      await flushPromises()
      expect(document.body.textContent).toContain('Discard unsaved changes?')
      button('Keep editing')!.click()
      await flushPromises()
      expect(field('Reference attributes').value).toBe('member')

      button('Close')!.click()
      await flushPromises()
      button('Discard')!.click()
      await flushPromises()
      expect(document.body.textContent).not.toContain('Edit sync set')
      expect(syncApi.updateSyncSet).not.toHaveBeenCalled()
      wrapper.unmount()
    })

    it('sends the logging level, and the filter as typed over several lines', async () => {
      const wrapper = await openEditor('Edit')
      const level = document.getElementById('sync-set-logging-level') as HTMLSelectElement
      expect(level.value).toBe('SUMMARY')
      level.value = 'DETAILED'
      level.dispatchEvent(new Event('change'))
      const filter = field('Applicability filter')
      expect(filter.tagName).toBe('TEXTAREA')
      filter.value = '(&(objectClass=secGroup)\n  (!(cn=iv-admin)))'
      filter.dispatchEvent(new Event('input'))
      await flushPromises()
      await submit()

      expect(syncApi.updateSyncSet).toHaveBeenCalledWith('set-1', expect.objectContaining({
        loggingLevel: 'DETAILED',
        applicabilityFilter: '(&(objectClass=secGroup)\n  (!(cn=iv-admin)))', // the server joins the lines
      }))
      wrapper.unmount()
    })

    it('marks the source base DN required and points blank fields at their defaults', async () => {
      const wrapper = await openEditor('+ New Set')
      const labels = Array.from(document.querySelectorAll('label')).map((l) => l.textContent?.trim() ?? '')
      expect(labels).toContain('Source base DN *')
      expect(labels).toContain('Reconcile interval (seconds)')
      expect(labels.some((l) => l.includes('cadence'))).toBe(false)
      expect(field('Source base DN').placeholder).toBe('')
      expect(field('Reference attributes').placeholder).toBe('Leave blank for the default (see ⓘ)')
      expect(field('Target base DN').placeholder).toBe('Leave blank for the default (see ⓘ)')
      expect(document.body.textContent).not.toContain('Record every DN')
      wrapper.unmount()
    })

    it('shows the latest preview run when it opens, with the same counts Show changes lists', async () => {
      vi.mocked(syncApi.getPreviewRun).mockResolvedValueOnce({ data: {
        runId: 'run-0', status: 'COMPLETE', processed: 4, total: 4, outdated: true, error: null,
        sourceCount: 4, managedCount: 4, actionableCount: 1, unchangedCount: 3, guardTripped: false,
        guardReason: null, completeScan: true, note: null, actionCounts: { MODIFY: 1, UNCHANGED: 3 },
      } } as never)
      const wrapper = await openEditor('Edit')

      expect(document.querySelector('[data-testid="impact-counts"]')!.textContent).toContain('Would modify: 1')
      expect(document.body.textContent).toContain('Preview again for current counts')
      wrapper.unmount()
    })

    it('asks before enabling a set when its preview run would delete entries', async () => {
      vi.mocked(syncApi.listSyncSets).mockResolvedValue({ data: [{ ...set, enabled: false }] } as never)
      const wrapper = await openEditor('Edit')
      const enabled = Array.from(document.querySelectorAll<HTMLInputElement>('input[type="checkbox"]'))
        .find((c) => c.parentElement?.textContent?.includes('Enabled'))!
      enabled.checked = true
      enabled.dispatchEvent(new Event('change'))
      await flushPromises()
      await submit()

      expect(syncApi.startPreviewRun).toHaveBeenCalledWith('set-1')
      expect(syncApi.updateSyncSet).not.toHaveBeenCalled()
      expect(document.body.textContent).toContain('1 target entry(ies) would be deleted.')
      button('Enable anyway')!.click()
      await flushPromises()
      expect(syncApi.updateSyncSet).toHaveBeenCalledWith('set-1', expect.objectContaining({ enabled: true }))
      vi.mocked(syncApi.listSyncSets).mockResolvedValue({ data: [set] } as never)
      wrapper.unmount()
    })

    it('closes straight away when nothing changed', async () => {
      const wrapper = await openEditor('Edit')
      button('Close')!.click()
      await flushPromises()
      expect(document.body.textContent).not.toContain('Discard unsaved changes?')
      expect(document.body.textContent).not.toContain('Edit sync set')
      wrapper.unmount()
    })
  })

  describe('deleting a set', () => {
    // Render the row menu's items as plain buttons so the test can click "Delete".
    const stubs = {
      ActionMenu: {
        props: ['items'],
        template: '<div><slot name="primary" /><button v-for="i in items" :key="i.label" type="button" '
          + 'class="menu-item" @click.stop="i.onClick()">{{ i.label }}</button></div>',
      },
    }

    async function clickDeleteOnTheSet() {
      const wrapper = mount(DirectorySyncView, { global: { stubs } })
      await flushPromises()
      await wrapper.find('tbody tr').trigger('click') // expand the link
      await flushPromises()
      const setTable = wrapper.findAll('table')[1]
      await setTable.findAll('button.menu-item').find((b) => b.text() === 'Delete')!.trigger('click')
      await flushPromises()
      return wrapper
    }

    it('asks first, spelling out what is discarded and what stays on the target', async () => {
      confirmApi.confirm.mockResolvedValue(true)

      await clickDeleteOnTheSet()

      expect(syncApi.getSyncSetDeleteImpact).toHaveBeenCalledWith('set-1')
      const opts = confirmApi.confirm.mock.calls[0][0]
      expect(opts).toMatchObject({ title: 'Delete sync set', confirmLabel: 'Delete set', danger: true })
      expect(opts.message).toContain('Delete sync set "people"?')
      expect(opts.message).toContain('876 entries it manages stay on the target, no longer managed')
      expect(opts.message).toContain('3 entries held for review are discarded')
      expect(opts.message).toContain('12 recorded reconcile runs are deleted')
      expect(opts.message).toContain('Other sync sets (groups)')
      expect(syncApi.deleteSyncSet).toHaveBeenCalledWith('set-1')
    })

    it('does nothing when the confirmation is cancelled', async () => {
      confirmApi.confirm.mockResolvedValue(false)

      await clickDeleteOnTheSet()

      expect(confirmApi.confirm).toHaveBeenCalled()
      expect(syncApi.deleteSyncSet).not.toHaveBeenCalled()
    })

    it('still confirms, without counts, when the impact cannot be loaded', async () => {
      vi.mocked(syncApi.getSyncSetDeleteImpact).mockRejectedValueOnce(new Error('boom'))
      confirmApi.confirm.mockResolvedValue(true)

      await clickDeleteOnTheSet()

      const opts = confirmApi.confirm.mock.calls[0][0]
      expect(opts.message).toContain('Delete sync set "people"?')
      expect(opts.message).toContain('stay on the target, no longer managed')
      expect(syncApi.deleteSyncSet).toHaveBeenCalledWith('set-1')
    })
  })

  describe('deleting a link', () => {
    const stubs = {
      ActionMenu: {
        props: ['items'],
        template: '<div><slot name="primary" /><button v-for="i in items" :key="i.label" type="button" '
          + 'class="menu-item" @click.stop="i.onClick()">{{ i.label }}</button></div>',
      },
    }

    // The link's own sets (asked for by link id) vs. every set (the health rollup on load).
    function linkHasNoSets() {
      vi.mocked(syncApi.listSyncSets).mockImplementation(((linkId?: string) =>
        Promise.resolve({ data: linkId ? [] : [set] })) as never)
    }
    afterEach(() => {
      vi.mocked(syncApi.listSyncSets).mockImplementation((() => Promise.resolve({ data: [set] })) as never)
    })

    async function clickDeleteOnTheLink() {
      const wrapper = mount(DirectorySyncView, { global: { stubs } })
      await flushPromises()
      const linkRow = wrapper.findAll('table')[0].findAll('tbody tr')[0]
      await linkRow.findAll('button.menu-item').find((b) => b.text() === 'Delete')!.trigger('click')
      await flushPromises()
      return wrapper
    }

    it('refuses up front while the link still has sync sets', async () => {
      await clickDeleteOnTheLink()

      expect(syncApi.listSyncSets).toHaveBeenCalledWith('link-1')
      expect(confirmApi.confirm).not.toHaveBeenCalled()
      expect(syncApi.deleteSyncLink).not.toHaveBeenCalled()
    })

    it('asks first once the link is empty, and deletes on confirm', async () => {
      linkHasNoSets()
      confirmApi.confirm.mockResolvedValue(true)

      await clickDeleteOnTheLink()

      const opts = confirmApi.confirm.mock.calls[0][0]
      expect(opts).toMatchObject({ title: 'Delete sync link', confirmLabel: 'Delete link', danger: true })
      expect(opts.message).toContain('Delete sync link "src->dst" (Source LDAP → Target LDAP)?')
      expect(opts.message).toContain('Neither directory is changed.')
      // APP_INTERCEPT: no changelog position to lose.
      expect(opts.message).not.toContain('changelog')
      expect(syncApi.deleteSyncLink).toHaveBeenCalledWith('link-1')
    })

    it('warns that a changelog link loses its position in the changelog', async () => {
      vi.mocked(syncApi.listSyncLinks).mockResolvedValueOnce({ data: [{ ...link, captureMode: 'CHANGELOG' }] } as never)
      linkHasNoSets()
      confirmApi.confirm.mockResolvedValue(true)

      await clickDeleteOnTheLink()

      expect(confirmApi.confirm.mock.calls[0][0].message).toContain('position in the source changelog is discarded')
    })

    it('does nothing when the confirmation is cancelled', async () => {
      linkHasNoSets()
      confirmApi.confirm.mockResolvedValue(false)

      await clickDeleteOnTheLink()

      expect(confirmApi.confirm).toHaveBeenCalled()
      expect(syncApi.deleteSyncLink).not.toHaveBeenCalled()
    })
  })
})
