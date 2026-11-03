// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const notif = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn() }))
vi.mock('@/stores/notifications', () => ({ useNotificationStore: () => notif }))
vi.mock('@/api/profiles', () => ({
  importProfile: vi.fn(() => Promise.resolve({
    data: { profile: { id: 'new', name: 'Employees' }, dnChanges: [], warnings: ['Add approvers'] },
  })),
}))

import ImportProfileDialog from './ImportProfileDialog.vue'
import { importProfile } from '@/api/profiles'

const directories = [{ id: 'd-prod', displayName: 'Prod' }, { id: 'd-test', displayName: 'Test' }]
const profiles = [
  { id: 'p-prod', name: 'Employees', directoryId: 'd-prod' },
  { id: 'p-test', name: 'Employees', directoryId: 'd-test' },
]
const exportFile = {
  formatVersion: 1,
  kind: 'ldapportal.provisioning-profile',
  source: { directoryName: 'Dev', baseDn: 'dc=dev,dc=corp', profileName: 'Employees' },
  profile: { name: 'Employees', attributeConfigs: [{}, {}], groupAssignments: [{}] },
}

function mountDialog() {
  return mount(ImportProfileDialog, {
    props: { modelValue: true, directories, profiles },
    global: { stubs: { Teleport: true } },
    attachTo: document.body,
  })
}

async function chooseFile(w: ReturnType<typeof mountDialog>, content: unknown) {
  const input = w.find('#ip-file')
  const text = typeof content === 'string' ? content : JSON.stringify(content)
  Object.defineProperty(input.element, 'files', {
    value: [new File([text], 'p.profile.json', { type: 'application/json' })],
    configurable: true,
  })
  await input.trigger('change')
  await flushPromises()
}

const button = (w: ReturnType<typeof mountDialog>, label: string) =>
  w.findAll('button').find(b => b.text() === label)!

describe('ImportProfileDialog', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('reads the file and summarises it', async () => {
    const w = mountDialog()
    await chooseFile(w, exportFile)
    expect(w.find('[data-testid="ip-summary"]').text())
      .toBe('Employees from Dev: 2 form fields, 1 group assignments.')
    expect((w.find('#ip-name').element as HTMLInputElement).value).toBe('Employees')
    expect((w.find('#ip-from').element as HTMLInputElement).value).toBe('dc=dev,dc=corp')
    w.unmount()
  })

  it('refuses a file that is not a profile export, or not JSON', async () => {
    const w = mountDialog()
    await chooseFile(w, { kind: 'other' })
    expect(w.find('[role="alert"]').text()).toContain("isn't a provisioning profile export")
    await chooseFile(w, '{not json')
    expect(w.find('[role="alert"]').text()).toContain("isn't valid JSON")
    expect(w.find('#ip-directory').exists()).toBe(false)
    w.unmount()
  })

  it('previews as a dry run, then imports a new profile with the base-DN rewrite', async () => {
    vi.mocked(importProfile).mockResolvedValueOnce({
      data: { profile: null, warnings: ['Add approvers'],
        dnChanges: [{ field: 'Target user OU', from: 'ou=people,dc=dev,dc=corp', to: 'ou=people,dc=prod,dc=corp' }] },
    } as never)
    const w = mountDialog()
    await chooseFile(w, exportFile)
    await w.find('#ip-directory').setValue('d-prod')

    await button(w, 'Preview').trigger('click')
    await flushPromises()
    expect(importProfile).toHaveBeenLastCalledWith('d-prod', expect.objectContaining({
      mode: 'CREATE', name: 'Employees', rewriteBaseDn: true, baseDnFrom: 'dc=dev,dc=corp', dryRun: true,
    }))
    expect(w.find('[data-testid="ip-preview"]').text()).toContain('ou=people,dc=prod,dc=corp')
    expect(w.find('[data-testid="ip-preview"]').text()).toContain('Add approvers')

    await button(w, 'Import').trigger('click')
    await flushPromises()
    expect(importProfile).toHaveBeenLastCalledWith('d-prod', expect.objectContaining({
      mode: 'CREATE', dryRun: false, document: exportFile,
    }))
    expect(notif.success).toHaveBeenCalledWith('Profile "Employees" imported (disabled)')
    expect(notif.warning).toHaveBeenCalledWith('Add approvers')
    expect(w.emitted('imported')).toHaveLength(1)
    expect(w.emitted('update:modelValue')).toEqual([[false]])
    w.unmount()
  })

  it("replaces an existing profile's form, offering only that directory's profiles", async () => {
    const w = mountDialog()
    await chooseFile(w, exportFile)
    await w.find('#ip-directory').setValue('d-test')
    await w.find('input[value="REPLACE_FORM"]').setValue(true)
    expect(w.findAll('#ip-target option').map(o => o.attributes('value')).filter(Boolean)).toEqual(['p-test'])
    expect(button(w, 'Import').attributes('disabled')).toBeDefined()
    await w.find('#ip-target').setValue('p-test')

    await button(w, 'Import').trigger('click')
    await flushPromises()

    expect(importProfile).toHaveBeenCalledWith('d-test', expect.objectContaining({
      mode: 'REPLACE_FORM', targetProfileId: 'p-test', rewriteBaseDn: false, dryRun: false,
    }))
    expect(notif.success).toHaveBeenCalledWith('Form replaced from the file')
    w.unmount()
  })

  it("keeps the dialog open with the server's reason when the import is refused", async () => {
    vi.mocked(importProfile).mockRejectedValueOnce({ response: { data: { detail: 'Profile [Employees] already exists in this directory' } } })
    const w = mountDialog()
    await chooseFile(w, exportFile)
    await w.find('#ip-directory').setValue('d-prod')
    await button(w, 'Import').trigger('click')
    await flushPromises()

    expect(notif.error).toHaveBeenCalledWith('Profile [Employees] already exists in this directory')
    expect(w.emitted('update:modelValue')).toBeUndefined()
    w.unmount()
  })
})
