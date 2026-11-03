// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const notif = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('@/stores/notifications', () => ({ useNotificationStore: () => notif }))
vi.mock('@/api/profiles', () => ({
  copyProfileForm: vi.fn(() => Promise.resolve({ data: [] })),
}))

import CopyFormDialog, { type CopyFormProfile } from './CopyFormDialog.vue'
import { copyProfileForm } from '@/api/profiles'

const prod: CopyFormProfile = { id: 'p-prod', name: 'Employees', directoryId: 'd-prod', directoryName: 'Prod' }
const profiles: CopyFormProfile[] = [
  prod,
  { id: 'p-test', name: 'Employees', directoryId: 'd-test', directoryName: 'Test' },
  { id: 'p-dev', name: 'Employees', directoryId: 'd-dev', directoryName: 'Dev', enabled: false },
  { id: 'p-contractors', name: 'Contractors', directoryId: 'd-prod', directoryName: 'Prod' },
]

function mountDialog() {
  return mount(CopyFormDialog, {
    props: { modelValue: true, source: prod, profiles },
    global: { stubs: { Teleport: true } },
    attachTo: document.body,
  })
}

const confirmButton = (w: ReturnType<typeof mountDialog>) =>
  w.findAll('button').find(b => b.text().startsWith('Replace form') || b.text() === 'Copying…')!

describe('CopyFormDialog', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('lists every other profile by directory, the source directory first, never the source', () => {
    const w = mountDialog()
    expect(w.findAll('legend').map(l => l.text())).toEqual(['Prod', 'Dev', 'Test'])
    const ids = w.findAll('input[type="checkbox"]').map(i => i.attributes('data-target-id'))
    expect(ids).toEqual(['p-contractors', 'p-dev', 'p-test'])
    expect(w.text()).toContain('(disabled)')
    w.unmount()
  })

  it('copies the form to the ticked profiles, then reports and closes', async () => {
    const w = mountDialog()
    expect(confirmButton(w).attributes('disabled')).toBeDefined()

    await w.find('input[data-target-id="p-test"]').setValue(true)
    await w.find('input[data-target-id="p-dev"]').setValue(true)
    expect(confirmButton(w).text()).toBe('Replace form on 2 profiles')

    await confirmButton(w).trigger('click')
    await flushPromises()

    expect(copyProfileForm).toHaveBeenCalledWith('d-prod', 'p-prod', ['p-test', 'p-dev'])
    expect(notif.success).toHaveBeenCalledWith('Form copied to 2 profiles')
    expect(w.emitted('copied')).toEqual([[['p-test', 'p-dev']]])
    expect(w.emitted('update:modelValue')).toEqual([[false]])
    w.unmount()
  })

  it('keeps the dialog open and shows the server\'s reason when a target rejects the form', async () => {
    vi.mocked(copyProfileForm).mockRejectedValueOnce({
      response: { data: { detail: "Profile [Employees] in [Test]: The RDN attribute 'cn' must be a configured form attribute" } },
    })
    const w = mountDialog()
    await w.find('input[data-target-id="p-test"]').setValue(true)

    await confirmButton(w).trigger('click')
    await flushPromises()

    expect(notif.error).toHaveBeenCalledWith(expect.stringContaining("Profile [Employees] in [Test]"))
    expect(w.emitted('copied')).toBeUndefined()
    expect(w.emitted('update:modelValue')).toBeUndefined()
    w.unmount()
  })

  it('clears the selection each time it opens', async () => {
    const w = mountDialog()
    await w.find('input[data-target-id="p-test"]').setValue(true)
    await w.setProps({ modelValue: false })
    await w.setProps({ modelValue: true })
    expect(confirmButton(w).attributes('disabled')).toBeDefined()
    expect((w.find('input[data-target-id="p-test"]').element as HTMLInputElement).checked).toBe(false)
    w.unmount()
  })
})
