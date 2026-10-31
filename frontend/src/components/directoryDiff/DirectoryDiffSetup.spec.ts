// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

const notif = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('@/stores/notifications', () => ({ useNotificationStore: () => notif }))
vi.mock('@/api/directories', () => ({
  listDirectories: vi.fn(() => Promise.resolve({ data: [
    { id: 'dir-a', displayName: 'East', directoryType: 'OPENLDAP', enabled: true },
    { id: 'dir-b', displayName: 'West', directoryType: 'OPENLDAP', enabled: true },
    { id: 'dir-e', displayName: 'Entra', directoryType: 'ENTRA_ID', enabled: true },
  ] })),
}))
const api = vi.hoisted(() => ({
  listDirectoryDiffs: vi.fn(),
  startDirectoryDiff: vi.fn(),
}))
vi.mock('@/api/directoryDiff', () => api)
const auth = vi.hoisted(() => ({ granted: new Set<string>() }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ hasSuperadminPermission: (key: string) => auth.granted.has(key) }),
}))
const sync = vi.hoisted(() => ({
  listSyncLinks: vi.fn(),
  listSyncSets: vi.fn(),
  getSyncSetDefaults: vi.fn(),
}))
vi.mock('@/api/sync', () => sync)
vi.mock('@/components/DnPicker.vue', () => ({
  default: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input class="dn-picker" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
}))

import DirectoryDiffSetup from './DirectoryDiffSetup.vue'
import { diffRun } from './fixtures'
import type { SyncSet } from '@/api/sync'

function syncSet(overrides: Partial<SyncSet>): SyncSet {
  return {
    id: 'set', linkId: 'link-1', name: 'Set', objectScopeBaseDn: 'ou=people,dc=a', objectScope: 'SUB',
    identityKey: null, targetBaseDn: 'ou=Users,dc=b', applicabilityFilter: '(objectClass=inetOrgPerson)',
    referenceAttributes: null, sourceAnchorAttribute: 'employeeNumber', deletePolicy: 'REVIEW',
    transformRules: null, excludedAttributes: null, reconcileCadenceSeconds: null, reconcileLastRunAt: null,
    enabled: true, loggingLevel: 'SUMMARY', createdAt: '', updatedAt: '', version: 0, stateCounts: {},
    ...overrides,
  } as SyncSet
}

beforeEach(() => {
  vi.clearAllMocks()
  auth.granted.clear()
  sync.listSyncLinks.mockResolvedValue({ data: [
    { id: 'link-1', displayName: 'East → West', sourceDirId: 'dir-a', targetDirId: 'dir-b' },
  ] })
  sync.listSyncSets.mockResolvedValue({ data: [
    syncSet({ id: 'set-1', name: 'People' }),
    syncSet({ id: 'set-2', name: 'Admin', objectScope: 'BASE' }),
  ] })
  sync.getSyncSetDefaults.mockResolvedValue({ data: { excludedAttributes: ['userPassword', 'entryUUID'] } })
  api.listDirectoryDiffs.mockResolvedValue({ data: [diffRun(), diffRun({ id: 'run-2', status: 'FAILED', error: 'Boom' })] })
  api.startDirectoryDiff.mockResolvedValue({ data: diffRun({ id: 'run-3', status: 'RUNNING' }) })
})

async function mountSetup(props = {}) {
  const wrapper = mount(DirectoryDiffSetup, { props })
  await flushPromises()
  return wrapper
}

describe('DirectoryDiffSetup', () => {
  it('offers only LDAP directories', async () => {
    const wrapper = await mountSetup()
    const options = wrapper.find('#dd-a-dir').findAll('option').map(o => o.text())
    expect(options).toEqual(['Choose a directory', 'East', 'West'])
  })

  it('starts a comparison by attribute with the chosen attributes', async () => {
    const wrapper = await mountSetup()
    await wrapper.find('#dd-a-dir').setValue('dir-a')
    await wrapper.find('#dd-b-dir').setValue('dir-b')
    await wrapper.findAll('.dn-picker')[1].setValue('ou=Users,dc=b')
    await wrapper.find('#dd-b-filter').setValue('(objectClass=person)')
    await wrapper.find('input[value="ATTRIBUTE"]').setValue()
    await wrapper.find('#dd-pair-attr').setValue(' employeeNumber ')
    await wrapper.find('input[value="ONLY"]').setValue()
    await wrapper.find('#dd-only').setValue('cn, mail member')
    await wrapper.find('#dd-ignore').setValue('description')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(api.startDirectoryDiff).toHaveBeenCalledWith({
      a: { directoryId: 'dir-a', baseDn: null, scope: 'SUB', filter: null },
      b: { directoryId: 'dir-b', baseDn: 'ou=Users,dc=b', scope: 'SUB', filter: '(objectClass=person)' },
      pairBy: 'ATTRIBUTE',
      pairAttribute: 'employeeNumber',
      attributes: ['cn', 'mail', 'member'],
      ignore: ['description'],
    })
    expect(wrapper.emitted('started')?.[0]?.[0]).toMatchObject({ id: 'run-3' })
  })

  it('keeps Compare disabled until both directories are chosen', async () => {
    const wrapper = await mountSetup()
    const submit = wrapper.find('button[type="submit"]')
    expect(submit.attributes('disabled')).toBeDefined()
    await wrapper.find('#dd-a-dir').setValue('dir-a')
    await wrapper.find('#dd-b-dir').setValue('dir-b')
    expect(submit.attributes('disabled')).toBeUndefined()
  })

  it('shows how DNs map when the bases differ', async () => {
    const wrapper = await mountSetup()
    const pickers = wrapper.findAll('.dn-picker')
    await pickers[0].setValue('ou=people,dc=a')
    await pickers[1].setValue('ou=Users,dc=b')
    expect(wrapper.find('[data-testid="rebase-example"]').text())
      .toContain('uid=jo,ou=people,dc=a ↔ uid=jo,ou=Users,dc=b')
  })

  it('reports a start that fails', async () => {
    api.startDirectoryDiff.mockRejectedValue({ response: { data: { detail: 'Side A’s filter isn’t valid' } } })
    const wrapper = await mountSetup()
    await wrapper.find('#dd-a-dir').setValue('dir-a')
    await wrapper.find('#dd-b-dir').setValue('dir-b')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(notif.error).toHaveBeenCalledWith('Side A’s filter isn’t valid')
    expect(wrapper.emitted('started')).toBeUndefined()
  })

  it('pre-fills from an earlier run', async () => {
    const wrapper = await mountSetup({
      prefill: diffRun({ pairBy: 'ATTRIBUTE', pairAttribute: 'mail', attributes: ['cn'], ignore: ['sn'] }),
    })
    expect((wrapper.find('#dd-a-dir').element as HTMLSelectElement).value).toBe('dir-a')
    expect((wrapper.find('#dd-pair-attr').element as HTMLInputElement).value).toBe('mail')
    expect((wrapper.find('#dd-only').element as HTMLInputElement).value).toBe('cn')
    expect((wrapper.find('#dd-ignore').element as HTMLInputElement).value).toBe('sn')
  })

  it('lists recent comparisons and opens one', async () => {
    const wrapper = await mountSetup()
    const rows = wrapper.findAll('[data-testid="recent-table"] tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('East ↔ West')
    expect(rows[1].text()).toContain('Failed')
    expect(rows[1].text()).toContain('Boom')
    await rows[0].find('button').trigger('click')
    expect(wrapper.emitted('open')?.[0]).toEqual(['run-1'])
  })

  it('clears a side’s base DN when its directory changes', async () => {
    const wrapper = await mountSetup()
    await wrapper.find('#dd-a-dir').setValue('dir-a')
    await wrapper.findAll('.dn-picker')[0].setValue('ou=people,dc=a')
    await wrapper.find('#dd-a-dir').setValue('dir-b')
    expect((wrapper.findAll('.dn-picker')[0].element as HTMLInputElement).value).toBe('')
  })

  it('keeps the base DNs an earlier run pre-fills', async () => {
    const wrapper = await mountSetup({ prefill: diffRun() })
    await flushPromises()
    const pickers = wrapper.findAll('.dn-picker')
    expect((pickers[0].element as HTMLInputElement).value).toBe('ou=people,dc=a')
    expect((pickers[1].element as HTMLInputElement).value).toBe('ou=Users,dc=b')
  })

  it('hides the sync set picker without permission to view directory sync', async () => {
    const wrapper = await mountSetup()
    expect(wrapper.find('[data-testid="verify-sync-set"]').exists()).toBe(false)
    expect(sync.listSyncSets).not.toHaveBeenCalled()
  })

  it('fills the form from a sync set and starts that comparison', async () => {
    auth.granted.add('superadmin.view_directory_sync')
    const wrapper = await mountSetup()
    const options = wrapper.find('#dd-sync-set').findAll('option').map(o => o.text())
    expect(options).toContain('East → West › People')
    expect(options.find(o => o.includes('Admin'))).toContain('can’t verify: it syncs a single entry (base scope)')

    await wrapper.find('#dd-sync-set').setValue('set-1')
    await flushPromises()
    expect(wrapper.find('[data-testid="sync-set-notes"]').text()).toContain('only entries the sync stamped with employeeNumber')

    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(api.startDirectoryDiff).toHaveBeenCalledWith({
      a: { directoryId: 'dir-a', baseDn: 'ou=people,dc=a', scope: 'SUB', filter: '(objectClass=inetOrgPerson)' },
      b: { directoryId: 'dir-b', baseDn: 'ou=Users,dc=b', scope: 'SUB', filter: '(employeeNumber=*)' },
      pairBy: 'DN',
      pairAttribute: null,
      attributes: [],
      ignore: ['userPassword', 'entryUUID', 'employeeNumber'],
    })
  })

  it('opens filled in from the sync set it was sent', async () => {
    auth.granted.add('superadmin.view_directory_sync')
    const wrapper = await mountSetup({ verifySetId: 'set-1' })
    expect((wrapper.find('#dd-sync-set').element as HTMLSelectElement).value).toBe('set-1')
    expect((wrapper.find('#dd-a-dir').element as HTMLSelectElement).value).toBe('dir-a')
    expect((wrapper.findAll('.dn-picker')[1].element as HTMLInputElement).value).toBe('ou=Users,dc=b')
  })
})
