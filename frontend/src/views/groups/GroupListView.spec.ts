// SPDX-License-Identifier: Apache-2.0
/**
 * Feature-gating on the directory group-management action surface. The
 * backend enforces @RequiresFeature on every GroupController endpoint; the
 * UI should only show the verbs the admin's effective feature set (from
 * /auth/me) grants. These tests pin that: a read-only feature set hides
 * New Group / Export / Edit / Delete and gets a view-only Members drawer,
 * and a full set shows them all.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const state = vi.hoisted(() => ({ features: [] as string[], ivia: false }))

vi.mock('vue-router', () => ({ useRoute: () => ({ params: { dirId: 'd1' } }) }))
vi.mock('@/stores/notifications', () => ({
  useNotificationStore: () => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() }),
}))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    hasFeature: (v: string) => state.features.includes(v),
    get isIsvaIntegrationEnabled() { return state.ivia },
  }),
}))
vi.mock('@/composables/useApi', () => ({
  useApi: () => ({ loading: { value: false }, call: (fn: () => unknown) => fn() }),
  downloadBlob: vi.fn(),
}))
vi.mock('@/api/groups', () => ({
  searchGroups: vi.fn().mockResolvedValue({ data: [{ dn: 'cn=staff,ou=groups,dc=x', attributes: { cn: ['staff'], member: ['uid=a,dc=x'] } }] }),
  getGroup: vi.fn(), createGroup: vi.fn(), updateGroup: vi.fn(), deleteGroup: vi.fn(),
  removeGroupMember: vi.fn(), addGroupMembersBulk: vi.fn(),
  removeGroupMembersBulk: vi.fn(),
}))
vi.mock('@/api/csvTemplates', () => ({ exportGroupCsv: vi.fn() }))
vi.mock('@/api/isvaGroup', () => ({
  getIsvaGroupRegistrations: vi.fn(),
  importIsvaGroup: vi.fn(),
  removeIsvaGroup: vi.fn(),
}))
const confirmApi = vi.hoisted(() => ({ confirm: vi.fn() }))
vi.mock('@/composables/useConfirm', () => ({ useConfirm: () => confirmApi.confirm }))
vi.mock('@/api/profiles', () => ({ listProfiles: vi.fn().mockResolvedValue({ data: [] }) }))

// The view registers an unsaved-changes guard (and reads the sidebar
// picker) through real Pinia stores; give every test a fresh instance.
beforeEach(() => setActivePinia(createPinia()))

import GroupListView from './GroupListView.vue'
import { listProfiles } from '@/api/profiles'
import { createGroup, updateGroup, addGroupMembersBulk, removeGroupMembersBulk, searchGroups } from '@/api/groups'
import { getIsvaGroupRegistrations, importIsvaGroup, removeIsvaGroup } from '@/api/isvaGroup'

const stubs = {
  LdapFilterBuilder: true, RecentSearches: true, AppModal: true, FormField: true,
  ConfirmDialog: true, CopyButton: true,
  ResultsTable: {
    props: ['rows', 'columns', 'rowKey', 'tableKey', 'emptyText'],
    template: `<div><slot name="toolbar" /><template v-for="r in rows" :key="r.dn"><slot name="cell-actions" :row="r" /></template></div>`,
  },
  ActionMenu: {
    props: ['items'],
    template: `<div><span data-slot="primary"><slot name="primary" /></span><button v-for="it in items.filter(i => !i.hidden)"
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

  it('shows only Members as a button, with Edit and Delete in the ... menu', async () => {
    // Real ActionMenu: it keeps up to inlineThreshold buttons inline (3 by
    // default), so a stub would hide extra actions spilling out of the menu.
    state.features = ['group.read', 'group.create_delete', 'group.edit', 'group.manage_members']
    const { ActionMenu: _stubbed, ...withRealMenu } = stubs
    const wrapper = mount(GroupListView, { global: { stubs: withRealMenu }, attachTo: document.body })
    await flushPromises()

    const rowButtons = wrapper.findAll('button').filter(b => b.attributes('aria-label') !== 'Row actions')
    const labels = rowButtons.map(b => b.text())
    expect(labels).toContain('Members')
    expect(labels).not.toContain('Edit')
    expect(labels).not.toContain('Delete')

    await wrapper.find('button[aria-label="Row actions"]').trigger('click')
    await flushPromises()
    const menu = document.body.textContent ?? ''
    expect(menu).toContain('Edit')
    expect(menu).toContain('Delete')
    wrapper.unmount()
  })

  it('hides write actions for a read-only feature set', async () => {
    const t = await mountWith(['group.read'])
    expect(t).not.toContain('+ New Group')
    expect(t.join(' ')).not.toContain('Export CSV')
    expect(t).not.toContain('Edit')
    expect(t).not.toContain('Delete')
    // Members stays reachable — the drawer is view-only (below).
    expect(t).toContain('Members')
  })

  it('hides Members without group.read or group.manage_members', async () => {
    const t = await mountWith([])
    expect(t).not.toContain('Members')
  })

  it('shows export but not create for a read-only admin that can export', async () => {
    const t = await mountWith(['group.read', 'bulk.export'])
    expect(t).not.toContain('+ New Group')
    expect(t.join(' ')).toContain('Export CSV')
  })
})

// The Members drawer lists the row's loaded members for anyone with
// group.read; the add / remove controls need group.manage_members.
describe('GroupListView members drawer', () => {
  beforeEach(() => vi.clearAllMocks())

  const drawerStubs = {
    ...stubs,
    AppModal: { props: ['modelValue'], template: '<div v-if="modelValue" data-testid="modal"><slot /></div>' },
    ActionMenu: {
      props: ['items'],
      template: `<div><slot name="primary" /><button v-for="it in items.filter(i => !i.hidden)" :key="it.label"
                   :data-action="it.label" @click="it.onClick()">{{ it.label }}</button></div>`,
    },
  }

  async function openDrawer(features: string[]) {
    state.features = features
    const wrapper = mount(GroupListView, { global: { stubs: drawerStubs } })
    await flushPromises()
    const members = wrapper.findAll('button').find(b => b.text() === 'Members')
    await members!.trigger('click')
    await flushPromises()
    return wrapper
  }

  it('is view-only for a read-only admin', async () => {
    const wrapper = await openDrawer(['group.read'])
    expect(wrapper.text()).toContain('uid=a,dc=x')
    const t = wrapper.findAll('button').map(b => b.text())
    expect(t).not.toContain('Add Members')
    expect(t).not.toContain('Bulk Remove')
    expect(t).not.toContain('Remove')
  })

  it('offers add and remove with group.manage_members', async () => {
    const wrapper = await openDrawer(['group.read', 'group.manage_members'])
    const t = wrapper.findAll('button').map(b => b.text())
    expect(t).toContain('Add Members')
    expect(t).toContain('Bulk Remove')
    expect(t).toContain('Remove')
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

// The Members dialog: "Add Members" (the bulk textarea) is the only add
// path, and the filter box narrows the shown list client-side.
describe('GroupListView members dialog', () => {
  beforeEach(() => vi.clearAllMocks())

  /* eslint-disable @typescript-eslint/no-explicit-any */
  // Render AppModal's default slot so the dialog's own buttons are visible.
  const dialogStubs = { ...stubs, AppModal: { template: '<div><slot /></div>' } }

  async function mountDialog() {
    state.features = ['group.read', 'group.manage_members']
    const wrapper = mount(GroupListView, { global: { stubs: dialogStubs } })
    await flushPromises()
    const vm = wrapper.vm as any
    vm.openMembers(vm.groups[0])
    await flushPromises()
    return { wrapper, vm }
  }

  it('offers Add Members and Bulk Remove, with no single-entry add', async () => {
    const { wrapper } = await mountDialog()
    const labels = wrapper.findAll('button').map(b => b.text())
    expect(labels).toContain('Add Members')
    expect(labels).toContain('Bulk Remove')
    expect(labels).not.toContain('Bulk Add')
    expect(labels).not.toContain('Add')
    expect(wrapper.find('input[aria-label="Filter members"]').exists()).toBe(true)
  })

  it('filters the shown members client-side without calling the API', async () => {
    const { vm } = await mountDialog()
    vm.members = ['uid=alice,dc=x', 'uid=bob,dc=x', 'uid=carol,dc=x']
    vm.memberFilter = 'AL'
    expect(vm.filteredMembers).toEqual(['uid=alice,dc=x'])
    vm.memberFilter = 'zzz'
    expect(vm.filteredMembers).toEqual([])
    vm.memberFilter = ''
    expect(vm.filteredMembers).toHaveLength(3)
    expect(addGroupMembersBulk).not.toHaveBeenCalled()
  })

  it('resets the filter when the dialog is reopened', async () => {
    const { vm } = await mountDialog()
    vm.memberFilter = 'x'
    vm.openMembers(vm.groups[0])
    expect(vm.memberFilter).toBe('')
  })

  it('Add Members submits the pasted DNs through the bulk endpoint', async () => {
    vi.mocked(addGroupMembersBulk).mockResolvedValue({
      status: 200, data: { added: 1, failed: 0, errors: [] },
    } as any)
    const { vm } = await mountDialog()
    vm.toggleBulk('add')
    vm.bulkMemberDns = 'uid=new,dc=x'
    await vm.doBulkAdd()
    expect(addGroupMembersBulk).toHaveBeenCalledWith('d1', 'cn=staff,ou=groups,dc=x', {
      memberAttribute: 'member', memberValues: ['uid=new,dc=x'],
    })
    expect(vm.members).toContain('uid=new,dc=x')
    expect(vm.groups[0]._memberCount).toBe(2)
  })

  it('an approval-gated add (202) leaves the member list untouched', async () => {
    vi.mocked(addGroupMembersBulk).mockResolvedValue({
      status: 202, data: { message: 'queued', approvalId: 'a1' },
    } as any)
    const { vm } = await mountDialog()
    vm.toggleBulk('add')
    vm.bulkMemberDns = 'uid=new,dc=x'
    await vm.doBulkAdd()
    expect(vm.members).toEqual(['uid=a,dc=x'])
    expect(vm.bulkResult).toBeNull()
    expect(vm.bulkMemberDns).toBe('')
  })
  /* eslint-enable @typescript-eslint/no-explicit-any */
})

describe('GroupListView import into IVIA', () => {
  const STAFF = 'cn=staff,ou=groups,dc=x'
  const OPS = 'cn=ops,ou=groups,dc=x'
  // Renders each row's DN cell (for the badge) and its actions.
  const iviaStubs = {
    ...stubs,
    ActionMenu: {
      props: ['items'],
      template: `<div><slot name="primary" /><button v-for="it in items.filter(i => !i.hidden)"
                   :key="it.label" :data-action="it.label" @click="it.onClick()">{{ it.label }}</button></div>`,
    },
    ResultsTable: {
      props: ['rows', 'columns', 'rowKey', 'tableKey', 'emptyText'],
      template: `<div><div v-for="r in rows" :key="r.dn" :data-row="r.dn">
                   <slot name="cell-dn" :value="r.dn" :row="r" /><slot name="cell-actions" :row="r" />
                 </div></div>`,
    },
  }

  beforeEach(() => {
    vi.clearAllMocks()
    state.ivia = true
    vi.mocked(searchGroups).mockResolvedValue({ data: [
      { dn: STAFF, attributes: { cn: ['staff'], member: ['uid=a,dc=x'] } },
      { dn: OPS, attributes: { cn: ['ops'], member: ['uid=b,dc=x'] } },
    ] } as never)
  })

  async function mountIvia(registered: string[], extra: Partial<{ enabled: boolean, canImport: boolean }> = {}) {
    vi.mocked(getIsvaGroupRegistrations).mockResolvedValue({
      data: { enabled: true, canImport: true, registered, ...extra },
    } as never)
    state.features = ['group.read', 'group.edit']
    const wrapper = mount(GroupListView, { global: { stubs: iviaStubs } })
    await flushPromises()
    return wrapper
  }

  const row = (w: Awaited<ReturnType<typeof mountIvia>>, dn: string) => w.find(`[data-row="${dn}"]`)

  it('badges imported groups and offers the import only on the others', async () => {
    // Matched however the server spells the DN.
    const wrapper = await mountIvia(['CN=staff, OU=groups, DC=x'])

    expect(getIsvaGroupRegistrations).toHaveBeenCalledWith('d1', [STAFF, OPS])
    expect(row(wrapper, STAFF).find('.badge-blue').text()).toBe('IVIA')
    expect(row(wrapper, STAFF).find('[data-action="Import into IVIA"]').exists()).toBe(false)
    expect(row(wrapper, OPS).find('[data-action="Import into IVIA"]').exists()).toBe(true)
  })

  it('offers no import without group.edit, or when the directory has no group container', async () => {
    let wrapper = await mountIvia([], { canImport: false })
    expect(wrapper.find('[data-action="Import into IVIA"]').exists()).toBe(false)

    vi.mocked(getIsvaGroupRegistrations).mockResolvedValue({
      data: { enabled: true, canImport: true, registered: [] },
    } as never)
    state.features = ['group.read']
    wrapper = mount(GroupListView, { global: { stubs: iviaStubs } })
    await flushPromises()
    expect(wrapper.find('[data-action="Import into IVIA"]').exists()).toBe(false)
  })

  it('does not ask about IVIA when the addon is absent', async () => {
    state.ivia = false
    state.features = ['group.read', 'group.edit']
    const wrapper = mount(GroupListView, { global: { stubs: iviaStubs } })
    await flushPromises()

    expect(getIsvaGroupRegistrations).not.toHaveBeenCalled()
    expect(wrapper.find('[data-action="Import into IVIA"]').exists()).toBe(false)
  })

  it('imports under the chosen name, then badges the group', async () => {
    vi.mocked(importIsvaGroup).mockResolvedValue({ data: { registered: true } } as never)
    const wrapper = await mountIvia([])
    await row(wrapper, OPS).find('[data-action="Import into IVIA"]').trigger('click')
    const vm = wrapper.vm as unknown as { iviaImportName: string, doIviaImport: () => Promise<void> }
    expect(vm.iviaImportName).toBe('ops')

    vm.iviaImportName = 'Operations'
    await vm.doIviaImport()
    await flushPromises()

    expect(importIsvaGroup).toHaveBeenCalledWith('d1', OPS, 'Operations')
    expect(row(wrapper, OPS).find('.badge-blue').text()).toBe('IVIA')
    expect(row(wrapper, OPS).find('[data-action="Import into IVIA"]').exists()).toBe(false)
  })

  it('keeps the action when the import is refused', async () => {
    vi.mocked(importIsvaGroup).mockRejectedValue({ response: { data: { detail: 'IVIA already has a group named "ops"' } } })
    const wrapper = await mountIvia([])
    await row(wrapper, OPS).find('[data-action="Import into IVIA"]').trigger('click')
    await (wrapper.vm as unknown as { doIviaImport: () => Promise<void> }).doIviaImport()
    await flushPromises()

    expect(importIsvaGroup).toHaveBeenCalledWith('d1', OPS, 'ops')
    expect(row(wrapper, OPS).find('[data-action="Import into IVIA"]').exists()).toBe(true)
  })

  it('offers Remove from IVIA only on imported groups', async () => {
    const wrapper = await mountIvia([STAFF])

    expect(row(wrapper, STAFF).find('[data-action="Remove from IVIA"]').exists()).toBe(true)
    expect(row(wrapper, OPS).find('[data-action="Remove from IVIA"]').exists()).toBe(false)
  })

  it('removes after confirming, then offers the import again', async () => {
    confirmApi.confirm.mockResolvedValue(true)
    vi.mocked(removeIsvaGroup).mockResolvedValue({ data: { registered: false } } as never)
    const wrapper = await mountIvia([STAFF])

    await row(wrapper, STAFF).find('[data-action="Remove from IVIA"]').trigger('click')
    await flushPromises()

    const opts = confirmApi.confirm.mock.calls[0][0]
    expect(opts).toMatchObject({ confirmLabel: 'Remove from IVIA', danger: true })
    expect(opts.message).toContain('Remove "staff" from IBM Verify Identity Access?')
    expect(opts.message).toContain('The LDAP group and its members are not changed')
    expect(removeIsvaGroup).toHaveBeenCalledWith('d1', STAFF)
    expect(row(wrapper, STAFF).find('.badge-blue').exists()).toBe(false)
    expect(row(wrapper, STAFF).find('[data-action="Import into IVIA"]').exists()).toBe(true)
  })

  it('does nothing when the removal is cancelled', async () => {
    confirmApi.confirm.mockResolvedValue(false)
    const wrapper = await mountIvia([STAFF])

    await row(wrapper, STAFF).find('[data-action="Remove from IVIA"]').trigger('click')
    await flushPromises()

    expect(removeIsvaGroup).not.toHaveBeenCalled()
    expect(row(wrapper, STAFF).find('[data-action="Remove from IVIA"]').exists()).toBe(true)
  })
})

describe('GroupListView New Group', () => {
  const EMPLOYEES = { id: 'p1', name: 'Employees', enabled: true, targetUserDn: 'ou=people,dc=x', targetGroupDn: 'ou=groups,dc=x' }
  const CONTRACTORS = { id: 'p2', name: 'Contractors', enabled: true, targetUserDn: 'ou=contractors,dc=x', targetGroupDn: 'ou=cgroups,dc=x' }
  const modalStubs = {
    ...stubs,
    AppModal: { props: ['modelValue'], template: '<div v-if="modelValue"><slot /></div>' },
    FormField: {
      props: ['modelValue', 'label'],
      emits: ['update:modelValue'],
      template: `<label :data-label="label"><input :value="modelValue"
                   @input="$emit('update:modelValue', $event.target.value)" /></label>`,
    },
  }

  beforeEach(() => vi.clearAllMocks())

  async function mountWithTwoProfiles(sidebarPick: string | null) {
    state.features = ['group.read', 'group.create_delete']
    vi.mocked(listProfiles).mockResolvedValue({ data: [EMPLOYEES, CONTRACTORS] } as never)
    const { useProfilePickerStore } = await import('@/stores/profilePicker')
    if (sidebarPick) useProfilePickerStore().setSelectedId(sidebarPick)
    const wrapper = mount(GroupListView, { global: { stubs: modalStubs } })
    await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === '+ New Group')!.trigger('click')
    await flushPromises()
    return wrapper
  }

  it('creates under the selected profile without asking', async () => {
    const wrapper = await mountWithTwoProfiles('p2')
    expect(wrapper.text()).not.toContain('Select a provisioning profile')
    await wrapper.find('label[data-label="Group Name (cn) (RDN)"] input').setValue('eng')
    expect((wrapper.find('label[data-label="DN"] input').element as HTMLInputElement).value)
      .toBe('cn=eng,ou=cgroups,dc=x')
  })

  it('asks which profile under "All"', async () => {
    const wrapper = await mountWithTwoProfiles(null)
    expect(wrapper.text()).toContain('Select a provisioning profile')
  })
})
