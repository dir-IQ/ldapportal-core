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
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/api/directories', () => ({
  listDirectories: vi.fn(() => Promise.resolve({ data: [{ id: 'dir-1', displayName: 'Corp', directoryType: 'OPENLDAP' }] })),
}))
const GHOST = 'uid=ghost,dc=example,dc=com'
const GONE = 'uid=gone,dc=example,dc=com'
const STAFF = 'cn=staff,dc=example,dc=com'
const broken = (value: string) => ({
  type: 'BROKEN_MEMBER', dn: STAFF, description: `references non-existent DN: ${value}`,
  attribute: 'member', value,
})
vi.mock('@/api/browse', () => ({
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
})
