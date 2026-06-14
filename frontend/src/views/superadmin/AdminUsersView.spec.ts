// SPDX-License-Identifier: Apache-2.0
/**
 * The "Manage Accounts" view lists both ADMIN and SUPERADMIN rows but the
 * /admins endpoints 404 on superadmin rows by design (the superadmin guards
 * live on the /superadmins endpoints). These tests pin the role-based
 * routing: edit/delete of a SUPERADMIN row must hit the superadmin API, and
 * ADMIN rows the admin API.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

const adminApi = vi.hoisted(() => ({
  listAdmins: vi.fn(),
  createAdminWithPermissions: vi.fn(),
  updateAdmin: vi.fn(),
  deleteAdmin: vi.fn(),
  getPermissions: vi.fn(),
}))
const superApi = vi.hoisted(() => ({
  createSuperadmin: vi.fn(),
  updateSuperadmin: vi.fn(),
  resetSuperadminPassword: vi.fn(),
  deleteSuperadmin: vi.fn(),
}))

vi.mock('@/api/adminManagement', () => adminApi)
vi.mock('@/api/superadmin', () => superApi)
vi.mock('@/api/adminPermissions', () => ({
  setProfileRole: vi.fn(), removeProfileRole: vi.fn(),
  setFeaturePermissions: vi.fn(), clearFeaturePermission: vi.fn(),
}))
vi.mock('@/api/profiles', () => ({ listAllProfiles: vi.fn().mockResolvedValue({ data: [] }) }))
vi.mock('@/stores/notifications', () => ({
  useNotificationStore: () => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() }),
}))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ principal: { id: 'me' } }),
}))
vi.mock('@/stores/settings', () => ({
  useSettingsStore: () => ({ enabledAuthTypes: ['LOCAL'] }),
}))

import AdminUsersView from './AdminUsersView.vue'

const stubs = {
  PageContainer: { template: '<div><slot /></div>' },
  AppModal: { props: ['modelValue'], template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>' },
  ActionMenu: {
    props: ['items'],
    // Render the primary slot (Edit) plus a button per non-hidden item.
    template: `<div><slot name="primary" /><button v-for="it in items.filter(i => !i.hidden)"
                 :key="it.label" :data-action="it.label" @click="it.onClick && it.onClick()">{{ it.label }}</button></div>`,
  },
  ConfirmDialog: {
    props: ['modelValue'],
    emits: ['confirm', 'update:modelValue'],
    template: `<button class="confirm" @click="$emit('confirm')">confirm</button>`,
  },
  FormField: { props: ['modelValue'], template: '<div />' },
  EffectivePermissionsDialog: { template: '<div />' },
}

function rows() {
  return [
    { id: 'a1', username: 'alice', displayName: 'Alice', email: 'a@x', role: 'ADMIN', authType: 'LOCAL', active: true },
    { id: 's1', username: 'root', displayName: 'Root', email: 'r@x', role: 'SUPERADMIN', authType: 'LOCAL', active: true },
  ]
}

/** Click the Edit (primary) button for the row at `index`. */
function editButtons(wrapper: ReturnType<typeof mount>) {
  return wrapper.findAll('button').filter(b => b.text() === 'Edit')
}
function deleteButtons(wrapper: ReturnType<typeof mount>) {
  return wrapper.findAll('[data-action="Delete"]')
}
function saveButton(wrapper: ReturnType<typeof mount>) {
  return wrapper.findAll('button').find(b => b.text() === 'Save changes')!
}

beforeEach(() => {
  vi.clearAllMocks()
  adminApi.listAdmins.mockResolvedValue({ data: rows() })
  adminApi.updateAdmin.mockResolvedValue({ data: {} })
  adminApi.deleteAdmin.mockResolvedValue({ data: undefined })
  superApi.updateSuperadmin.mockResolvedValue({ data: {} })
  superApi.deleteSuperadmin.mockResolvedValue({ data: undefined })
})

describe('AdminUsersView role-based routing', () => {
  it('editing a SUPERADMIN row calls the superadmin update endpoint', async () => {
    const wrapper = mount(AdminUsersView, { global: { stubs } })
    await flushPromises()

    await editButtons(wrapper)[1].trigger('click') // row index 1 = superadmin
    await saveButton(wrapper).trigger('click')
    await flushPromises()

    expect(superApi.updateSuperadmin).toHaveBeenCalledWith('s1', {
      displayName: 'Root', email: 'r@x', active: true,
    })
    expect(adminApi.updateAdmin).not.toHaveBeenCalled()
    // No password entered → no password reset.
    expect(superApi.resetSuperadminPassword).not.toHaveBeenCalled()
  })

  it('editing an ADMIN row calls the admin update endpoint', async () => {
    const wrapper = mount(AdminUsersView, { global: { stubs } })
    await flushPromises()

    await editButtons(wrapper)[0].trigger('click') // row index 0 = admin
    await saveButton(wrapper).trigger('click')
    await flushPromises()

    expect(adminApi.updateAdmin).toHaveBeenCalledWith('a1', expect.objectContaining({ role: 'ADMIN' }))
    expect(superApi.updateSuperadmin).not.toHaveBeenCalled()
  })

  it('deleting a SUPERADMIN row calls the superadmin delete endpoint', async () => {
    const wrapper = mount(AdminUsersView, { global: { stubs } })
    await flushPromises()

    await deleteButtons(wrapper)[1].trigger('click') // superadmin row
    await wrapper.find('.confirm').trigger('click')
    await flushPromises()

    expect(superApi.deleteSuperadmin).toHaveBeenCalledWith('s1')
    expect(adminApi.deleteAdmin).not.toHaveBeenCalled()
  })

  it('deleting an ADMIN row calls the admin delete endpoint', async () => {
    const wrapper = mount(AdminUsersView, { global: { stubs } })
    await flushPromises()

    await deleteButtons(wrapper)[0].trigger('click') // admin row
    await wrapper.find('.confirm').trigger('click')
    await flushPromises()

    expect(adminApi.deleteAdmin).toHaveBeenCalledWith('a1')
    expect(superApi.deleteSuperadmin).not.toHaveBeenCalled()
  })
})

describe('AdminUsersView server validation errors', () => {
  it('surfaces a per-field validation 400 in the summary instead of a generic toast', async () => {
    // Backend bean-validation failure: RFC 7807 ProblemDetail with a per-field
    // `errors` map (this is what `@Email` on AdminAccountRequest produces).
    adminApi.updateAdmin.mockRejectedValueOnce({
      response: {
        data: {
          detail: 'Validation failed',
          errors: { email: 'must be a well-formed email address' },
        },
      },
    })

    const wrapper = mount(AdminUsersView, { global: { stubs } })
    await flushPromises()

    await editButtons(wrapper)[0].trigger('click') // admin row → form prefilled
    await saveButton(wrapper).trigger('click')
    await flushPromises()

    // The friendly, field-specific message is shown (FormValidationSummary is a
    // real component here) — not the bare "Validation failed".
    const summary = wrapper.find('[role="alert"]')
    expect(summary.exists()).toBe(true)
    expect(summary.text()).toContain('Email')
    expect(summary.text()).toContain('must be a well-formed email address')
    expect(summary.text()).not.toContain('Validation failed')
  })
})
