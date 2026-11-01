// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const notif = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('@/stores/notifications', () => ({ useNotificationStore: () => notif }))
const canManage = vi.hoisted(() => ({ value: true }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ hasSuperadminPermission: () => canManage.value }),
}))
const confirmMock = vi.hoisted(() => vi.fn(() => Promise.resolve(true)))
vi.mock('@/composables/useConfirm', () => ({ useConfirm: () => confirmMock }))
const push = vi.hoisted(() => vi.fn())
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
const downloadBlob = vi.hoisted(() => vi.fn())
vi.mock('@/composables/useApi', () => ({ downloadBlob }))
vi.mock('@/api/directories', () => ({
  listDirectories: vi.fn(() => Promise.resolve({ data: [{ id: 'dir-1', slug: 'corp', displayName: 'Corp', directoryType: 'OPENLDAP' }] })),
}))
const GHOST = 'uid=ghost,dc=example,dc=com'
const GONE = 'uid=gone,dc=example,dc=com'
const STAFF = 'cn=staff,dc=example,dc=com'
const broken = (value: string) => ({
  type: 'BROKEN_MEMBER', dn: STAFF, description: `references non-existent DN: ${value}`,
  attribute: 'member', value,
})
const BUILT_IN = [
  { type: 'BROKEN_MEMBER', attributes: ['member', 'uniqueMember'] },
  { type: 'ORPHANED_ENTRY', attributes: [] },
  { type: 'EMPTY_GROUP', attributes: [] },
]
vi.mock('@/api/browse', () => ({
  listIntegrityChecks: vi.fn(() => Promise.resolve({ data: BUILT_IN })),
  checkIntegrity: vi.fn(() => Promise.resolve({
    data: { issues: [broken(GHOST), broken(GONE), { type: 'EMPTY_GROUP', dn: 'cn=empty,dc=example,dc=com',
      description: 'Group has no members', attribute: null, value: null }] },
  })),
  removeBrokenReferences: vi.fn(() => Promise.resolve({
    data: { removed: 1, outcomes: [
      { groupDn: STAFF, attribute: 'member', value: GHOST, status: 'REMOVED', message: null },
      { groupDn: STAFF, attribute: 'member', value: GONE, status: 'KEPT_ENTRY_EXISTS', message: 'exists' },
    ] },
  })),
}))
vi.mock('@/components/DnPicker.vue', () => ({ default: { template: '<input />' } }))

import IntegrityCheckView from './IntegrityCheckView.vue'
import * as browseApi from '@/api/browse'

function button(label: string): HTMLButtonElement | undefined {
  return Array.from(document.querySelectorAll('button')).find((b) => b.textContent?.trim() === label)
}

async function runCheck() {
  const wrapper = mount(IntegrityCheckView, { attachTo: document.body })
  await flushPromises()
  button('Run Check')!.click()
  await flushPromises()
  return wrapper
}

describe('IntegrityCheckView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    canManage.value = true
  })

  it('removes the selected broken references after confirming, then checks again', async () => {
    const wrapper = await runCheck()
    const remove = button('Remove broken references')!
    expect(remove.disabled).toBe(true)

    const selectAll = document.querySelector('input[aria-label="Select all broken references"]') as HTMLInputElement
    selectAll.checked = true
    selectAll.dispatchEvent(new Event('change'))
    await flushPromises()
    expect(document.body.textContent).toContain('2 of 2 selected')
    remove.click()
    await flushPromises()

    expect(confirmMock).toHaveBeenCalledWith(expect.objectContaining({ danger: true }))
    expect(browseApi.removeBrokenReferences).toHaveBeenCalledWith('dir-1', [
      { groupDn: STAFF, attribute: 'member', value: GHOST },
      { groupDn: STAFF, attribute: 'member', value: GONE },
    ])
    expect(notif.success).toHaveBeenCalledWith('Removed 1 reference. Kept 1: the entry exists now.')
    expect(browseApi.checkIntegrity).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('removes only the references ticked', async () => {
    const wrapper = await runCheck()
    const one = document.querySelector(`input[aria-label="Select ${GONE} in ${STAFF}"]`) as HTMLInputElement
    one.checked = true
    one.dispatchEvent(new Event('change'))
    await flushPromises()

    button('Remove broken references')!.click()
    await flushPromises()

    expect(browseApi.removeBrokenReferences).toHaveBeenCalledWith('dir-1', [
      { groupDn: STAFF, attribute: 'member', value: GONE },
    ])
    wrapper.unmount()
  })

  it('does nothing when the confirm is cancelled', async () => {
    confirmMock.mockResolvedValueOnce(false)
    const wrapper = await runCheck()
    const selectAll = document.querySelector('input[aria-label="Select all broken references"]') as HTMLInputElement
    selectAll.checked = true
    selectAll.dispatchEvent(new Event('change'))
    await flushPromises()

    button('Remove broken references')!.click()
    await flushPromises()

    expect(browseApi.removeBrokenReferences).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('offers no removal without permission to manage directory data', async () => {
    canManage.value = false
    const wrapper = await runCheck()

    expect(button('Remove broken references')).toBeUndefined()
    expect(document.querySelector('input[aria-label="Select all broken references"]')).toBeNull()
    wrapper.unmount()
  })

  it('offers the directory\'s reference check (secDN), ticked, and lists what it finds without a remove box', async () => {
    vi.mocked(browseApi.listIntegrityChecks).mockResolvedValueOnce({
      data: [...BUILT_IN, { type: 'BROKEN_REFERENCE', attributes: ['secDN'] }],
    } as never)
    vi.mocked(browseApi.checkIntegrity).mockResolvedValueOnce({
      data: { issues: [{
        type: 'BROKEN_REFERENCE', dn: 'secUUID=g,cn=Users,secAuthority=Default',
        description: "Attribute 'secDN' references non-existent DN: uid=ghost,ou=people,dc=example,dc=com",
        attribute: 'secDN', value: 'uid=ghost,ou=people,dc=example,dc=com',
      }] },
    } as never)
    const wrapper = mount(IntegrityCheckView, { attachTo: document.body })
    await flushPromises()

    const label = Array.from(document.querySelectorAll('label'))
      .find(l => l.textContent?.trim() === 'Broken references (secDN)')!
    expect(label).toBeTruthy()
    expect((label.querySelector('input') as HTMLInputElement).checked).toBe(true)

    button('Run Check')!.click()
    await flushPromises()

    expect(browseApi.checkIntegrity).toHaveBeenCalledWith('dir-1', '', expect.arrayContaining(['BROKEN_REFERENCE']))
    expect(document.body.textContent).toContain('Broken References')
    expect(document.body.textContent).toContain('uid=ghost,ou=people,dc=example,dc=com')
    // Only broken member references can be removed from here.
    expect(button('Remove broken references')).toBeUndefined()
    wrapper.unmount()
  })

  it('offers only the built-in checks where the directory has no reference check', async () => {
    const wrapper = mount(IntegrityCheckView, { attachTo: document.body })
    await flushPromises()

    expect(browseApi.listIntegrityChecks).toHaveBeenCalledWith('dir-1')
    expect(document.body.textContent).not.toContain('Broken references (')
    wrapper.unmount()
  })

  it('filters the issues by DN or description, and removes only what it shows', async () => {
    const wrapper = await runCheck()
    const filter = document.querySelector('#icv-filter') as HTMLInputElement
    filter.value = 'GHOST'
    filter.dispatchEvent(new Event('input'))
    await flushPromises()

    expect(document.querySelector('[data-testid="issue-count"]')!.textContent).toContain('3 issues found, 1 shown')
    expect(document.body.textContent).not.toContain(GONE)
    expect(document.body.textContent).not.toContain('cn=empty,dc=example,dc=com')

    const selectAll = document.querySelector('input[aria-label="Select all broken references"]') as HTMLInputElement
    selectAll.checked = true
    selectAll.dispatchEvent(new Event('change'))
    await flushPromises()
    button('Remove broken references')!.click()
    await flushPromises()
    expect(browseApi.removeBrokenReferences).toHaveBeenCalledWith('dir-1', [
      { groupDn: STAFF, attribute: 'member', value: GHOST },
    ])
    wrapper.unmount()
  })

  it('says when the filter matches nothing', async () => {
    const wrapper = await runCheck()
    const filter = document.querySelector('#icv-filter') as HTMLInputElement
    filter.value = 'nothing-like-this'
    filter.dispatchEvent(new Event('input'))
    await flushPromises()
    expect(document.body.textContent).toContain('No issues match “nothing-like-this”')
    expect(button('Export CSV')!.disabled).toBe(true)
    wrapper.unmount()
  })

  it('exports the issues it shows as CSV, references included', async () => {
    const wrapper = await runCheck()
    button('Export CSV')!.click()
    expect(downloadBlob).toHaveBeenCalledWith(expect.any(Blob), 'integrity-check-corp.csv')
    const csv = await (downloadBlob.mock.calls[0][0] as Blob).text()
    expect(csv.split('\r\n')).toEqual([
      'type,dn,description,attribute,value',
      `"Broken Member References","${STAFF}","references non-existent DN: ${GHOST}","member","${GHOST}"`,
      `"Broken Member References","${STAFF}","references non-existent DN: ${GONE}","member","${GONE}"`,
      '"Empty Groups","cn=empty,dc=example,dc=com","Group has no members","",""',
      '',
    ])
    wrapper.unmount()
  })

  it('opens an issue\'s entry in the Directory Browser of the directory checked', async () => {
    const wrapper = await runCheck()
    const cell = Array.from(document.querySelectorAll('td')).find(td => td.textContent?.trim() === 'cn=empty,dc=example,dc=com')!
    cell.click()
    expect(push).toHaveBeenCalledWith({ path: '/superadmin/directory-browser',
      query: { dir: 'dir-1', dn: 'cn=empty,dc=example,dc=com' } })
    wrapper.unmount()
  })
})
