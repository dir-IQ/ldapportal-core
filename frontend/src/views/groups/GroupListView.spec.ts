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
import { createPinia, setActivePinia } from 'pinia'

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
  removeGroupMembersBulk: vi.fn(),
}))
vi.mock('@/api/csvTemplates', () => ({ exportGroupCsv: vi.fn() }))
vi.mock('@/api/profiles', () => ({ listProfiles: vi.fn().mockResolvedValue({ data: [] }) }))

// The view registers an unsaved-changes guard (and reads the sidebar
// picker) through real Pinia stores; give every test a fresh instance.
beforeEach(() => setActivePinia(createPinia()))

import GroupListView from './GroupListView.vue'
import { createGroup, updateGroup, removeGroupMembersBulk } from '@/api/groups'

const stubs = {
  LdapFilterBuilder: true, RecentSearches: true, AppModal: true, FormField: true,
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

// The Members column is a synthetic, numeric row field so the table sorts it
// numerically; its value tracks the drawer as members are added / removed.
describe('GroupListView member count column', () => {
  beforeEach(() => vi.clearAllMocks())

  /* eslint-disable @typescript-eslint/no-explicit-any */
  async function mountVm(features = ['group.read', 'group.manage_members']) {
    state.features = features
    const wrapper = mount(GroupListView, { global: { stubs } })
    await flushPromises()
    return wrapper.vm as any
  }

  it('exposes a numeric _memberCount column after the default attribute columns', async () => {
    const vm = await mountVm()
    const keys = vm.cols.map((c: { key: string }) => c.key)
    expect(keys.indexOf('_memberCount')).toBeGreaterThan(keys.indexOf('cn'))
    expect(keys.indexOf('_memberCount')).toBeLessThan(keys.indexOf('actions'))
    expect(vm.groups[0]._memberCount).toBe(1)
    expect(typeof vm.groups[0]._memberCount).toBe('number')
  })

  it('bulk remove drops only the values the server removed and syncs the row count', async () => {
    vi.mocked(removeGroupMembersBulk).mockResolvedValue({
      data: { removed: 1, failed: 1, errors: [{ memberValue: 'uid=ghost,dc=x', error: 'not a member' }] },
    } as any)
    const vm = await mountVm()
    vm.openMembers(vm.groups[0])
    vm.toggleBulk('remove')
    vm.bulkMemberDns = 'uid=a,dc=x\nuid=ghost,dc=x'
    await vm.doBulkRemove()

    expect(removeGroupMembersBulk).toHaveBeenCalledWith('d1', 'cn=staff,ou=groups,dc=x', {
      memberAttribute: 'member',
      memberValues: ['uid=a,dc=x', 'uid=ghost,dc=x'],
    })
    expect(vm.members).toEqual([])
    expect(vm.groups[0]._memberCount).toBe(0)
    expect(vm.bulkResult).toMatchObject({ verb: 'Removed', succeeded: 1, failed: 1 })
    // Mirrors bulk add: the textarea clears once at least one value went through.
    expect(vm.bulkMemberDns).toBe('')
  })

  it('toggling the same bulk verb closes the panel, the other verb switches it', async () => {
    const vm = await mountVm()
    vm.openMembers(vm.groups[0])
    vm.toggleBulk('add')
    expect(vm.bulkMode).toBe('add')
    vm.toggleBulk('remove')
    expect(vm.bulkMode).toBe('remove')
    vm.toggleBulk('remove')
    expect(vm.bulkMode).toBeNull()
  })
  /* eslint-enable @typescript-eslint/no-explicit-any */
})
