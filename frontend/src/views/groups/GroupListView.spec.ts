// SPDX-License-Identifier: Apache-2.0
/**
 * Feature-gating on the directory group-management action surface. The
 * backend enforces @RequiresFeature on every GroupController endpoint; the
 * UI should only show the verbs the admin's effective feature set (from
 * /auth/me) grants. These tests pin that: a read-only feature set hides
 * New Group / Export / Edit / Members / Delete, and a full set shows them.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

const state = vi.hoisted(() => ({ features: [] as string[] }))

vi.mock('vue-router', () => ({ useRoute: () => ({ params: { dirId: 'd1' } }) }))
vi.mock('@/stores/notifications', () => ({
  useNotificationStore: () => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() }),
}))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ hasFeature: (v: string) => state.features.includes(v) }),
}))
vi.mock('@/composables/useApi', () => ({
  useApi: () => ({ loading: { value: false }, call: (fn: () => unknown) => fn() }),
  downloadBlob: vi.fn(),
}))
vi.mock('@/api/groups', () => ({
  searchGroups: vi.fn().mockResolvedValue({ data: [{ dn: 'cn=staff,ou=groups,dc=x', attributes: { cn: ['staff'], member: ['uid=a,dc=x'] } }] }),
  getGroup: vi.fn(), createGroup: vi.fn(), updateGroup: vi.fn(), deleteGroup: vi.fn(),
  addGroupMember: vi.fn(), removeGroupMember: vi.fn(), addGroupMembersBulk: vi.fn(),
}))
vi.mock('@/api/csvTemplates', () => ({ exportGroupCsv: vi.fn() }))
vi.mock('@/api/profiles', () => ({ listProfiles: vi.fn().mockResolvedValue({ data: [] }) }))

import GroupListView from './GroupListView.vue'
import { createGroup, updateGroup } from '@/api/groups'

const stubs = {
  LdapFilterBuilder: true, AppModal: true, FormField: true,
  ConfirmDialog: true, DnPicker: true, CopyButton: true,
  ResultsTable: {
    props: ['rows', 'columns', 'rowKey', 'tableKey', 'emptyText'],
    template: `<div><slot name="toolbar" /><template v-for="r in rows" :key="r.dn"><slot name="cell-actions" :row="r" /></template></div>`,
  },
  ActionMenu: {
    props: ['items'],
    template: `<div><slot name="primary" /><button v-for="it in items.filter(i => !i.hidden)"
                 :key="it.label" :data-action="it.label">{{ it.label }}</button></div>`,
  },
}

async function mountWith(features: string[]) {
  state.features = features
  const wrapper = mount(GroupListView, { global: { stubs } })
  await flushPromises()
  return wrapper.findAll('button').map(b => b.text())
}

describe('GroupListView feature gating', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows all actions with the full group feature set', async () => {
    const t = await mountWith(['group.read', 'group.create_delete', 'group.edit', 'group.manage_members', 'bulk.export'])
    expect(t).toContain('+ New Group')
    expect(t.join(' ')).toContain('Export CSV')
    expect(t).toContain('Edit')
    expect(t).toContain('Members')
    expect(t).toContain('Delete')
  })

  it('hides write actions for a read-only feature set', async () => {
    const t = await mountWith(['group.read'])
    expect(t).not.toContain('+ New Group')
    expect(t.join(' ')).not.toContain('Export CSV')
    expect(t).not.toContain('Edit')
    expect(t).not.toContain('Members')
    expect(t).not.toContain('Delete')
  })

  it('shows export but not create for a read-only admin that can export', async () => {
    const t = await mountWith(['group.read', 'bulk.export'])
    expect(t).not.toContain('+ New Group')
    expect(t.join(' ')).toContain('Export CSV')
  })
})

// Mirrors the server's DN-syntax check on group create/update — a malformed
// owner DN is blocked client-side before the write, with an inline message.
describe('GroupListView owner DN validation', () => {
  beforeEach(() => vi.clearAllMocks())

  /* eslint-disable @typescript-eslint/no-explicit-any */
  async function mountVm() {
    state.features = ['group.read', 'group.create_delete', 'group.edit']
    const wrapper = mount(GroupListView, { global: { stubs } })
    await flushPromises()
    return wrapper.vm as any
  }

  it('blocks create and sets an inline error for a malformed owner DN', async () => {
    const vm = await mountVm()
    vm.createForm.owner = 'not a dn'
    await vm.doCreate()
    expect(createGroup).not.toHaveBeenCalled()
    expect(vm.createOwnerError).toBe('Not a valid DN')
  })

  it('allows create when the owner is a valid DN (or blank)', async () => {
    const vm = await mountVm()
    vm.createForm.cn = 'devs'
    vm.createForm.owner = 'uid=boss,ou=people,dc=example,dc=com'
    await vm.doCreate()
    expect(createGroup).toHaveBeenCalledTimes(1)
    expect(vm.createOwnerError).toBeNull()
  })

  it('blocks edit for a malformed owner DN', async () => {
    const vm = await mountVm()
    vm.editingDn = 'cn=staff,ou=groups,dc=x'
    vm.editForm.owner = 'still not a dn'
    await vm.doEdit()
    expect(updateGroup).not.toHaveBeenCalled()
    expect(vm.editOwnerError).toBe('Not a valid DN')
  })
  /* eslint-enable @typescript-eslint/no-explicit-any */
})
