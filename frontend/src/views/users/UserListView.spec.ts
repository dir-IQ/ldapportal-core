// SPDX-License-Identifier: Apache-2.0
/**
 * Feature-gating on the directory user-management action surface. The
 * backend enforces @RequiresFeature on every UserController endpoint; the
 * UI should only show the verbs the admin's effective feature set (from
 * /auth/me) actually grants. These tests pin that: a read-only feature set
 * hides Create / Export / Edit / Delete / Move / Reset / Disable / Run
 * playbook, and a full set shows them.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

const state = vi.hoisted(() => ({ features: [] as string[] }))

vi.mock('vue-router', () => ({ useRoute: () => ({ params: { dirId: 'd1' } }) }))
vi.mock('@/stores/notifications', () => ({
  useNotificationStore: () => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() }),
}))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    hasFeature: (v: string) => state.features.includes(v),
    isIsvaIntegrationEnabled: false,
  }),
}))
vi.mock('@/composables/useApi', () => ({
  useApi: () => ({ loading: { value: false }, call: (fn: () => unknown) => fn() }),
  downloadBlob: vi.fn(),
}))
vi.mock('@/api/users', () => ({
  searchUsers: vi.fn().mockResolvedValue({ data: [{ dn: 'uid=jdoe,ou=people,dc=x', enabled: true, attributes: {} }] }),
  getUser: vi.fn(), createUser: vi.fn(), updateUser: vi.fn(), deleteUser: vi.fn(),
  enableUser: vi.fn(), disableUser: vi.fn(), moveUser: vi.fn(), resetPassword: vi.fn(),
  bulkUpdateAttributes: vi.fn(), getPasswordStatus: vi.fn(),
}))
vi.mock('@/api/groups', () => ({ addGroupMember: vi.fn(), removeGroupMember: vi.fn(), searchGroups: vi.fn() }))
vi.mock('@/api/csvTemplates', () => ({ exportCsv: vi.fn() }))
vi.mock('@/api/profiles', () => ({ listProfiles: vi.fn().mockResolvedValue({ data: [] }), getProfile: vi.fn() }))
vi.mock('@/api/playbooks', () => ({
  listEnabled: vi.fn().mockResolvedValue({ data: [] }),
  previewPlaybook: vi.fn(), executePlaybook: vi.fn(), rollbackExecution: vi.fn(),
}))

import UserListView from './UserListView.vue'

const stubs = {
  LdapFilterBuilder: true,
  AppModal: { props: ['modelValue'], template: '<div v-if="modelValue"><slot /></div>' },
  FormField: true, ConfirmDialog: true, UserForm: true, CopyButton: true,
  EntryTimeline: true, PasswordPolicyStatus: true,
  // Render the toolbar slot and the per-row actions slot so gating is visible.
  ResultsTable: {
    props: ['rows', 'columns', 'selectedKeys', 'rowKey', 'tableKey', 'selectable', 'emptyText'],
    template: `<div><slot name="toolbar" /><template v-for="r in rows" :key="r.dn"><slot name="cell-actions" :row="r" /></template></div>`,
  },
  // Surface non-hidden items as buttons + the primary slot, so we can assert
  // exactly which row actions the gating leaves visible.
  ActionMenu: {
    props: ['items', 'disabled', 'inlineThreshold'],
    template: `<div><slot name="primary" :disabled="disabled" /><button v-for="it in items.filter(i => !i.hidden)"
                 :key="it.label" :data-action="it.label">{{ it.label }}</button></div>`,
  },
}

async function mountWith(features: string[]) {
  state.features = features
  const wrapper = mount(UserListView, { global: { stubs } })
  await flushPromises()
  return wrapper
}

function texts(wrapper: ReturnType<typeof mount>) {
  return wrapper.findAll('button').map(b => b.text())
}

const ALL = [
  'user.create', 'user.edit', 'user.delete', 'user.move',
  'user.reset_password', 'user.enable_disable', 'bulk.attribute_update',
  'bulk.export', 'playbook.execute', 'user.read',
]

describe('UserListView feature gating', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows all actions when the admin has every feature', async () => {
    const wrapper = await mountWith(ALL)
    const t = texts(wrapper)
    expect(t).toContain('+ New User')
    expect(t.join(' ')).toContain('Export CSV')
    expect(t).toContain('Edit')
    expect(t).toContain('Delete')
    expect(t).toContain('Move')
    expect(t).toContain('Reset password')
    expect(t).toContain('Run playbook')
  })

  it('hides write actions for a read-only feature set', async () => {
    const wrapper = await mountWith(['user.read'])
    const t = texts(wrapper)
    expect(t).not.toContain('+ New User')
    expect(t.join(' ')).not.toContain('Export CSV')
    expect(t).not.toContain('Edit')
    expect(t).not.toContain('Delete')
    expect(t).not.toContain('Move')
    expect(t).not.toContain('Reset password')
    expect(t).not.toContain('Run playbook')
    // Read-only action that isn't feature-gated stays available.
    expect(t).toContain('View history')
  })

  it('shows export but not create for a read-only admin that can export', async () => {
    const wrapper = await mountWith(['user.read', 'bulk.export'])
    const t = texts(wrapper)
    expect(t).not.toContain('+ New User')
    expect(t.join(' ')).toContain('Export CSV')
  })
})
