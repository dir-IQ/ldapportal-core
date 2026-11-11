// SPDX-License-Identifier: Apache-2.0
/**
 * The profile editor's Other tab: the theme colour (moved from General), and
 * Copy form / Export / Import acting on the saved profile being edited.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const hoisted = vi.hoisted(() => ({
  profiles: [] as Array<Record<string, unknown>>,
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    hasSuperadminPermission: () => true,
    isApprovalsEnabled: false,
    isIsvaIntegrationEnabled: false,
  }),
}))
vi.mock('@/api/profiles', () => ({
  listAllProfiles: vi.fn(() => Promise.resolve({ data: hoisted.profiles })),
  createProfile: vi.fn(), updateProfile: vi.fn(), deleteProfile: vi.fn(), cloneProfile: vi.fn(),
  getApprovalConfig: vi.fn(() => Promise.resolve({ data: { requireApproval: false } })),
  setApprovalConfig: vi.fn(),
  getApprovers: vi.fn(() => Promise.resolve({ data: [] })),
  setApprovers: vi.fn(),
  evaluateGroupChanges: vi.fn(), applySelectiveGroupChanges: vi.fn(),
  probeTargetOu: vi.fn(() => Promise.resolve({ data: { exists: true } })),
  exportProfile: vi.fn(() => Promise.resolve({ data: { kind: 'ldapportal.provisioning-profile' } })),
}))
vi.mock('@/api/directories', () => ({
  listDirectories: vi.fn(() => Promise.resolve({ data: [{ id: 'd1', displayName: 'Prod', directoryType: 'OPENLDAP' }] })),
}))
vi.mock('@/api/schema', () => ({
  listObjectClasses: vi.fn(() => Promise.resolve({ data: [] })),
  getObjectClass: vi.fn(() => Promise.resolve({ data: {} })),
}))
vi.mock('@/api/adminManagement', () => ({ listAccountSummaries: vi.fn(() => Promise.resolve({ data: [] })) }))
vi.mock('@/api/isvaConfig', () => ({ setIsvaProfileOverride: vi.fn() }))
vi.mock('@/composables/useApi', () => ({ downloadBlob: vi.fn() }))

import SuperadminProfilesView from './SuperadminProfilesView.vue'
import { exportProfile, listAllProfiles } from '@/api/profiles'
import { downloadBlob } from '@/composables/useApi'

const row = {
  id: 'p1', name: 'Employees', directoryId: 'd1', description: '', themeColor: '#2563eb',
  targetUserDn: 'ou=people,dc=x', targetGroupDn: '', objectClassNames: [], rdnAttribute: 'uid',
  enabled: true, attributeConfigs: [], groupAssignments: [], additionalProfileIds: [],
}

const stubs = {
  AppModal: { props: ['modelValue'], template: '<div v-if="modelValue"><slot name="title" /><slot /><slot name="footer" /></div>' },
  DataTable: {
    props: ['rows'],
    template: '<div><template v-for="r in rows" :key="r.id"><slot name="actions" :row="r" /></template></div>',
  },
  ActionMenu: { template: '<div><slot name="primary" /></div>' },
  CopyFormDialog: { name: 'CopyFormDialog', props: ['modelValue', 'source', 'profiles'], template: '<div />' },
  ImportProfileDialog: { name: 'ImportProfileDialog', props: ['modelValue', 'preset', 'directories', 'profiles'], template: '<div />' },
  ThemeColorPreview: true, FormLayoutDesigner: true, DnPicker: true, ConfirmDialog: true, IsvaProfileOverrideControl: true,
}

async function mountView() {
  const w = mount(SuperadminProfilesView, { global: { stubs, directives: { 'dialog-a11y': {} } } })
  await flushPromises()
  return w
}
const btn = (w: Awaited<ReturnType<typeof mountView>>, text: string) =>
  w.findAll('button').find(b => b.text() === text)
async function openTab(w: Awaited<ReturnType<typeof mountView>>, label: string) {
  await btn(w, label)!.trigger('click')
  await flushPromises()
}

describe('SuperadminProfilesView — Other tab', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    hoisted.profiles = [{ ...row }]
  })

  it('holds the theme colour, which is no longer on General', async () => {
    const w = await mountView()
    await btn(w, 'Edit')!.trigger('click')
    await flushPromises()

    expect(w.find('#sp-theme-color').exists()).toBe(false)
    await openTab(w, 'Other')
    expect(w.find('#sp-theme-color').exists()).toBe(true)
    expect(w.find('#sp-name').exists()).toBe(false)
  })

  it('copies, exports and imports the profile being edited', async () => {
    const w = await mountView()
    await btn(w, 'Edit')!.trigger('click')
    await flushPromises()
    await openTab(w, 'Other')

    await btn(w, 'Copy form to other profiles…')!.trigger('click')
    const copy = w.findComponent({ name: 'CopyFormDialog' })
    expect(copy.props('modelValue')).toBe(true)
    expect(copy.props('source')).toMatchObject({ id: 'p1' })

    await btn(w, 'Export to file')!.trigger('click')
    await flushPromises()
    expect(exportProfile).toHaveBeenCalledWith('d1', 'p1')
    expect(vi.mocked(downloadBlob).mock.calls[0][1]).toBe('Employees.profile.json')

    await btn(w, 'Import form from file…')!.trigger('click')
    const imp = w.findComponent({ name: 'ImportProfileDialog' })
    expect(imp.props('modelValue')).toBe(true)
    expect(imp.props('preset')).toEqual({ directoryId: 'd1', targetProfileId: 'p1' })
  })

  it('reloads the editor from the server after importing into the open profile', async () => {
    const w = await mountView()
    await btn(w, 'Edit')!.trigger('click')
    await flushPromises()
    await openTab(w, 'Other')
    await btn(w, 'Import form from file…')!.trigger('click')

    hoisted.profiles = [{ ...row, themeColor: '#ff0000' }]
    w.findComponent({ name: 'ImportProfileDialog' }).vm.$emit('imported')
    await flushPromises()

    expect(listAllProfiles).toHaveBeenCalledTimes(2)
    // Still on the Other tab, now showing the server's copy.
    expect((w.find('input[aria-label="Theme colour hex"]').element as HTMLInputElement).value).toBe('#ff0000')
    expect(w.findComponent({ name: 'ImportProfileDialog' }).props('preset')).toBeNull()
  })

  it('the list page Import opens without a preset', async () => {
    const w = await mountView()
    await btn(w, 'Import')!.trigger('click')
    const imp = w.findComponent({ name: 'ImportProfileDialog' })
    expect(imp.props('modelValue')).toBe(true)
    expect(imp.props('preset')).toBeNull()
  })

  it('disables the actions while creating a profile, which has nothing saved yet', async () => {
    const w = await mountView()
    await btn(w, '+ Create Profile')!.trigger('click')
    await flushPromises()
    await openTab(w, 'Other')

    for (const label of ['Copy form to other profiles…', 'Export to file', 'Import form from file…']) {
      expect(btn(w, label)!.attributes('disabled')).toBeDefined()
    }
    expect(w.text()).toContain('Save the profile first')
  })

  it('row menu offers only Clone and Delete, folded behind the kebab', async () => {
    // Copy form / Export moved off the row; they live on this Other tab.
    const ActionMenu = {
      name: 'ActionMenu',
      props: ['items', 'inlineThreshold'],
      template: '<div><slot name="primary" /></div>',
    }
    const w = mount(SuperadminProfilesView, {
      global: { stubs: { ...stubs, ActionMenu }, directives: { 'dialog-a11y': {} } },
    })
    await flushPromises()
    const menu = w.findComponent({ name: 'ActionMenu' })
    const labels = (menu.props('items') as Array<{ label: string; hidden?: boolean }>)
      .filter(i => !i.hidden).map(i => i.label)
    expect(labels).toEqual(['Clone', 'Delete'])
    // Only the Edit primary button stays inline.
    expect(menu.props('inlineThreshold')).toBe(1)
  })
})
