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
  bulkUpdateAttributes: vi.fn(), getPasswordStatus: vi.fn(), applyMemberships: vi.fn(),
}))
vi.mock('@/api/groups', () => ({ addGroupMember: vi.fn(), removeGroupMember: vi.fn(), searchGroups: vi.fn() }))
vi.mock('@/api/csvTemplates', () => ({ exportCsv: vi.fn() }))
vi.mock('@/api/profiles', () => ({ listProfiles: vi.fn().mockResolvedValue({ data: [] }), getProfile: vi.fn() }))
vi.mock('@/api/playbooks', () => ({
  listEnabled: vi.fn().mockResolvedValue({ data: [] }),
  previewPlaybook: vi.fn(), executePlaybook: vi.fn(), rollbackExecution: vi.fn(),
}))

import UserListView from './UserListView.vue'
import * as usersApi from '@/api/users'

const stubs = {
  LdapFilterBuilder: true,
  RecentSearches: true,
  AppModal: { props: ['modelValue'], template: '<div v-if="modelValue"><slot /></div>' },
  FormField: true, ConfirmDialog: true, UserForm: true, CopyButton: true,
  EntryTimeline: true, PasswordPolicyStatus: true, GroupChips: true,
  // Render the toolbar slot and the per-row actions slot so gating is visible.
  ResultsTable: {
    name: 'ResultsTable',
    props: ['rows', 'columns', 'selectedKeys', 'rowKey', 'tableKey', 'selectable', 'emptyText'],
    template: `<div><slot name="toolbar" /><template v-for="r in rows" :key="r.dn"><slot name="cell-actions" :row="r" /></template></div>`,
  },
  // Surface non-hidden items as buttons + the primary slot, so we can assert
  // exactly which row actions the gating leaves visible.
  ActionMenu: {
    name: 'ActionMenu',
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

  it('requests memberOf and isMemberOf so the Groups column populates across directory types', async () => {
    await mountWith(ALL)
    expect(usersApi.searchUsers).toHaveBeenCalledWith(
      'd1',
      expect.objectContaining({ attributes: '*,memberOf,isMemberOf' }),
    )
  })

  // Regression: the backend lower-cases all LDAP attribute keys, so each
  // row is keyed `givenname` / `displayname`. The curated DEFAULT_USER_COLUMNS
  // list carries the pretty mixed case (givenName / displayName) for the
  // header label — but the column *key* must be lower-cased to match the row
  // data, otherwise `row['givenName']` is undefined and the cell renders
  // blank even though the directory populated the attribute.
  it('keys curated camelCase columns by their lower-cased name so cells resolve', async () => {
    vi.mocked(usersApi.searchUsers).mockResolvedValueOnce({ data: [{
      dn: 'uid=jdoe,ou=people,dc=x',
      attributes: { givenname: ['Alice'], displayname: ['Alice Anderson'], cn: ['Alice Anderson'] },
    }] } as never)
    const wrapper = await mountWith(ALL)
    const cols = wrapper.findComponent({ name: 'ResultsTable' }).props('columns') as Array<{ key: string, label: string }>
    const given = cols.find(c => c.label === 'givenName')
    const display = cols.find(c => c.label === 'displayName')
    // Header keeps the pretty label; key is lower-cased to match row data.
    expect(given).toMatchObject({ key: 'givenname', label: 'givenName' })
    expect(display).toMatchObject({ key: 'displayname', label: 'displayName' })
    // And the row actually exposes a value under that key.
    const rows = wrapper.findComponent({ name: 'ResultsTable' }).props('rows') as Array<Record<string, unknown>>
    expect(rows[0][given!.key]).toBe('Alice')
    expect(rows[0][display!.key]).toBe('Alice Anderson')
  })

  // IVIA enrichment columns are keyed `isva.*` by the backend (stable internal
  // id) but must display the marketing `ivia.` prefix in the table header.
  it('labels IVIA enrichment columns with the ivia. prefix while keeping the isva. key', async () => {
    vi.mocked(usersApi.searchUsers).mockResolvedValueOnce({ data: [{
      dn: 'uid=jdoe,ou=people,dc=x',
      attributes: { cn: ['Alice Anderson'], 'isva.seclogin': ['alice.anderson'] },
    }] } as never)
    const wrapper = await mountWith(ALL)
    const cols = wrapper.findComponent({ name: 'ResultsTable' }).props('columns') as Array<{ key: string, label: string }>
    const secLogin = cols.find(c => c.key === 'isva.seclogin')
    expect(secLogin).toBeTruthy()
    expect(secLogin!.label).toBe('ivia.seclogin')
    // No column should expose the internal isva. prefix as its label.
    expect(cols.some(c => c.label.startsWith('isva.'))).toBe(false)
  })

  // Regression: ResultsTable is table-layout:fixed, where a column that falls
  // back to width:auto only gets a share of the *leftover* space. Once the
  // fixed-width columns (Groups, Actions, the select checkbox) fill the
  // container, the auto columns collapse to 0px and render invisibly — so a
  // discovered "extra" the user enables in the picker silently never appears.
  // Every column must therefore carry an explicit defaultWidth.
  it('gives every column an explicit defaultWidth so none collapse in the fixed-layout table', async () => {
    vi.mocked(usersApi.searchUsers).mockResolvedValueOnce({ data: [{
      dn: 'uid=jdoe,ou=people,dc=x',
      attributes: {
        cn: ['Alice'], sn: ['Anderson'],
        objectclass: ['inetOrgPerson'], entryuuid: ['abc-123'], 'isva.seclogin': ['a.a'],
      },
    }] } as never)
    const wrapper = await mountWith(ALL)
    const cols = wrapper.findComponent({ name: 'ResultsTable' })
      .props('columns') as Array<{ key: string, defaultWidth?: number }>
    // Curated defaults, the discovered extras (objectclass / entryuuid),
    // the IVIA column, dn, groups and actions should all be present...
    expect(cols.length).toBeGreaterThan(5)
    // ...and not a single one may rely on the collapsing auto fallback.
    for (const c of cols) {
      expect(typeof c.defaultWidth, `column "${c.key}" needs a defaultWidth`).toBe('number')
    }
  })

  // The actions column is pinned to the right (so it stays reachable on a
  // horizontally-scrolled wide table) and slimmed: only Edit stays inline and
  // everything else — Disable/Enable included — folds into the kebab, so the
  // column can be narrow without clipping.
  it('pins and slims the actions column and folds Disable into the kebab', async () => {
    const wrapper = await mountWith(ALL)
    const cols = wrapper.findComponent({ name: 'ResultsTable' })
      .props('columns') as Array<{ key: string, pinned?: boolean, defaultWidth?: number }>
    const actions = cols.find(c => c.key === 'actions')
    expect(actions).toMatchObject({ pinned: true, defaultWidth: 120 })
    // inlineThreshold 1 = only the Edit primary button stays inline; all menu
    // items (Disable, Reset password, …) move into the kebab.
    expect(wrapper.findComponent({ name: 'ActionMenu' }).props('inlineThreshold')).toBe(1)
  })
})

describe('UserListView bulk group membership', () => {
  beforeEach(() => vi.clearAllMocks())

  // ResultsTable variant that selects two rows on mount, so the bulk toolbar
  // (which only appears with ≥2 selected) is exercisable.
  const selectingStubs = {
    ...stubs,
    ResultsTable: {
      name: 'ResultsTable',
      props: ['rows', 'columns', 'selectedKeys', 'rowKey', 'tableKey', 'selectable', 'emptyText'],
      emits: ['update:selectedKeys'],
      mounted(this: { $emit: (e: string, v: unknown) => void }) {
        this.$emit('update:selectedKeys', new Set(['uid=a,dc=x', 'uid=b,dc=x']))
      },
      template: `<div><slot name="toolbar" /></div>`,
    },
  }

  async function mountSelecting(features: string[]) {
    state.features = features
    const wrapper = mount(UserListView, { global: { stubs: selectingStubs } })
    await flushPromises()
    return wrapper
  }

  it('shows Manage Groups when ≥2 users are selected and the admin can manage members', async () => {
    const wrapper = await mountSelecting(['user.read', 'group.manage_members'])
    expect(texts(wrapper).some(t => t.startsWith('Manage Groups'))).toBe(true)
  })

  it('hides Manage Groups without the group.manage_members feature', async () => {
    const wrapper = await mountSelecting(['user.read'])
    expect(texts(wrapper).some(t => t.startsWith('Manage Groups'))).toBe(false)
  })
})

// Edit-save must ship only attributes whose value actually changed. The
// regression: every form attribute round-tripped as a REPLACE — including
// disabled, non-editable fields (editableOnUpdate=false, e.g. uid) — and the
// backend correctly 400s any modification targeting a locked attribute, so
// an untouched edit failed with "Attribute [uid] is not editable on update".
describe('UserListView edit save sends only changed attributes', () => {
  const editStubs = {
    ...stubs,
    // Footer too — that's where the Save button lives.
    AppModal: { props: ['modelValue'], template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>' },
    UserForm: {
      name: 'UserForm',
      props: ['data', 'isEdit', 'userTemplateConfig', 'dirId', 'profileId'],
      emits: ['update'],
      template: '<div />',
      methods: {
        validate() { return true },
        applyMembershipChanges() { return Promise.resolve() },
      },
    },
  }

  beforeEach(() => {
    vi.mocked(usersApi.updateUser).mockClear()
    vi.mocked(usersApi.getUser).mockResolvedValue({
      data: { attributes: { uid: ['jdoe'], mail: ['jdoe@x.com'], employeeNumber: ['e1'] } },
    } as Awaited<ReturnType<typeof usersApi.getUser>>)
  })

  async function openEditModal() {
    state.features = ALL
    const wrapper = mount(UserListView, { global: { stubs: editStubs } })
    await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === 'Edit')!.trigger('click')
    await flushPromises()
    return wrapper
  }

  it('skips the update call entirely when nothing changed', async () => {
    const wrapper = await openEditModal()
    await wrapper.findAll('button').find(b => b.text() === 'Save')!.trigger('click')
    await flushPromises()
    expect(usersApi.updateUser).not.toHaveBeenCalled()
  })

  it('sends modifications only for attributes whose value changed', async () => {
    const wrapper = await openEditModal()
    // The form reports an edit to mail; uid (naming + locked) and
    // employeeNumber are untouched and must not be written back.
    wrapper.findComponent({ name: 'UserForm' }).vm.$emit('update', {
      dn: 'uid=jdoe,ou=people,dc=x',
      attributes: { uid: 'jdoe', mail: 'new@x.com', employeeNumber: 'e1' },
    })
    await wrapper.vm.$nextTick()
    await wrapper.findAll('button').find(b => b.text() === 'Save')!.trigger('click')
    await flushPromises()
    expect(usersApi.updateUser).toHaveBeenCalledTimes(1)
    const payload = vi.mocked(usersApi.updateUser).mock.calls[0][2] as
      { modifications: Array<{ operation: string, attribute: string, values: string[] }> }
    expect(payload.modifications).toEqual([
      { operation: 'REPLACE', attribute: 'mail', values: ['new@x.com'] },
    ])
  })
})
