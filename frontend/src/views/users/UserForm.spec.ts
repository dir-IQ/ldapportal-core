// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import UserForm from './UserForm.vue'
import * as groupsApi from '@/api/groups'
import * as usersApi from '@/api/users'

// onMounted touches the groups + IVIA-config APIs; stub them so the mount is
// hermetic. With dirId=null the component short-circuits before calling them,
// but the mocks keep the test robust to that detail.
vi.mock('@/api/groups', () => ({
  searchGroups: vi.fn().mockResolvedValue({ data: [] }),
  addGroupMember: vi.fn(),
  removeGroupMember: vi.fn(),
}))
vi.mock('@/api/users', () => ({
  applyMemberships: vi.fn().mockResolvedValue({
    data: { applied: 1, queued: 0, refused: 0, blocked: 0, errored: 0, items: [] },
  }),
}))
vi.mock('@/api/profiles', () => ({ generatePassword: vi.fn() }))
vi.mock('@/api/isvaConfig', () => ({
  getIsvaConfig: vi.fn().mockResolvedValue({ data: { enabled: false } }),
}))
// onMounted loads the attribute-syntax hints; stub so the mount stays hermetic.
vi.mock('@/api/attributeSyntax', () => ({
  getAttributeSyntaxHints: vi.fn().mockResolvedValue({
    data: { wellKnownAttributes: { manager: 'DN', mail: 'EMAIL' }, inputTypeSyntax: { DN_LOOKUP: 'DN', BOOLEAN: 'BOOLEAN' } },
  }),
}))

const templateConfig = {
  rdnAttribute: 'uid',
  attributeConfigs: [
    { attributeName: 'uid', requiredOnCreate: true, editableOnCreate: true, inputType: 'TEXT' },
    {
      attributeName: 'mail',
      editableOnCreate: true,
      inputType: 'TEXT',
      validationRegex: '^[^@]+@[^@]+$',
      validationMessage: 'Enter a valid email',
    },
  ],
}

interface ExposedForm {
  validate: () => boolean
}

function mountForm(data: Record<string, unknown>) {
  return mount(UserForm, {
    props: { data, isEdit: false, userTemplateConfig: templateConfig, dirId: null, profileId: null },
  })
}

describe('UserForm validation', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('fails when the required RDN value is missing, and shows the message', async () => {
    const wrapper = mountForm({ attributes: {} })
    expect((wrapper.vm as unknown as ExposedForm).validate()).toBe(false)
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('uid is required')
  })

  it('fails and renders the custom message when a regex field is invalid', async () => {
    const wrapper = mountForm({ rdnValue: 'jsmith', attributes: { mail: 'nope' } })
    expect((wrapper.vm as unknown as ExposedForm).validate()).toBe(false)
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('Enter a valid email')
  })

  it('passes when required and regex-constrained fields are satisfied', () => {
    const wrapper = mountForm({ rdnValue: 'jsmith', attributes: { mail: 'a@b.com' } })
    expect((wrapper.vm as unknown as ExposedForm).validate()).toBe(true)
  })

  // The RDN field is disabled in edit mode, so a length/regex rule on the RDN
  // attribute must not block edits to an entry whose RDN predates the rule.
  const editTemplate = {
    rdnAttribute: 'uid',
    attributeConfigs: [
      { attributeName: 'uid', inputType: 'TEXT', validationRegex: '^[0-9]+$', validationMessage: 'digits only' },
      {
        attributeName: 'mail',
        editableOnUpdate: true,
        inputType: 'TEXT',
        validationRegex: '^[^@]+@[^@]+$',
        validationMessage: 'Enter a valid email',
      },
    ],
  }

  function mountEdit(attributes: Record<string, unknown>) {
    return mount(UserForm, {
      props: {
        data: { dn: 'uid=jsmith,ou=people,dc=x', attributes },
        isEdit: true,
        userTemplateConfig: editTemplate,
        dirId: null,
        profileId: null,
      },
    })
  }

  it('does not validate the immutable RDN in edit mode', () => {
    // uid="jsmith" violates the RDN's ^[0-9]+$ rule, but the RDN is immutable
    // on edit; with mail valid the form must still pass.
    const wrapper = mountEdit({ uid: 'jsmith', mail: 'a@b.com' })
    expect((wrapper.vm as unknown as ExposedForm).validate()).toBe(true)
  })

  it('still validates editable non-RDN fields in edit mode', () => {
    const wrapper = mountEdit({ uid: 'jsmith', mail: 'not-an-email' })
    expect((wrapper.vm as unknown as ExposedForm).validate()).toBe(false)
  })

  // IVIA (isva.*) enrichment attributes are merged from the paired secUser on
  // read and managed only via the IVIA Account tab actions. In the edit modal
  // they must render display-only (disabled) and carry the marketing `ivia.`
  // prefix rather than the internal `isva.` key.
  it('renders IVIA enrichment attributes read-only with the ivia. display prefix', async () => {
    const wrapper = mountEdit({ uid: 'jsmith', mail: 'a@b.com', 'isva.seclogin': 'alice.anderson' })
    await wrapper.vm.$nextTick()
    // Display prefix is the marketing abbreviation, not the internal id.
    expect(wrapper.text()).toContain('ivia.seclogin')
    expect(wrapper.text()).not.toContain('isva.seclogin')
    // The value renders inside a disabled control — not directly editable.
    const disabled = wrapper.findAll('textarea').filter(t => t.attributes('disabled') !== undefined)
    expect(disabled.some(t => (t.element as HTMLTextAreaElement).value === 'alice.anderson')).toBe(true)
  })
})

interface ExposedStaged {
  validate: () => boolean
  applyMembershipChanges: () => Promise<unknown>
  hasPendingMembershipChanges: boolean
}

describe('UserForm staged group memberships (edit mode)', () => {
  const USER_DN = 'uid=jsmith,ou=people,dc=x'
  const DEVS = 'cn=devs,ou=groups,dc=x'
  const OPS  = 'cn=ops,ou=groups,dc=x'

  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(usersApi.applyMemberships).mockClear()
    // Two groups: the user is a member of devs, not ops.
    vi.mocked(groupsApi.searchGroups).mockResolvedValue({
      data: [
        { dn: DEVS, attributes: { cn: ['devs'], member: [USER_DN] } },
        { dn: OPS,  attributes: { cn: ['ops'],  member: ['uid=other,ou=people,dc=x'] } },
      ],
    } as never)
  })

  async function mountEditWithGroups() {
    const wrapper = mount(UserForm, {
      props: {
        data: { dn: USER_DN, attributes: { uid: 'jsmith' } },
        isEdit: true,
        userTemplateConfig: { rdnAttribute: 'uid', attributeConfigs: [] },
        dirId: 'dir1',
        profileId: null,
      },
    })
    await flushPromises()
    return wrapper
  }

  it('stages add + remove and flushes them as one batch on apply', async () => {
    const wrapper = await mountEditWithGroups()
    const vm = wrapper.vm as unknown as ExposedStaged
    const clickButton = async (text: string) => {
      const btn = wrapper.findAll('button').find(b => b.text().trim() === text)
      await btn!.trigger('click')
    }

    // The current membership (devs) renders with a Remove action; ops is
    // offered in the Add list. Stage a removal of devs and an add of ops.
    await clickButton('Remove')
    await clickButton('Add')
    expect(vm.hasPendingMembershipChanges).toBe(true)

    // Nothing persisted yet — staging is local until apply.
    expect(usersApi.applyMemberships).not.toHaveBeenCalled()

    await vm.applyMembershipChanges()

    // One batch call: additions first, then removals (remove-before-add is the
    // server's concern; the client just lists them).
    expect(usersApi.applyMemberships).toHaveBeenCalledTimes(1)
    expect(usersApi.applyMemberships).toHaveBeenCalledWith('dir1', USER_DN, {
      changes: [
        { groupDn: OPS,  memberAttribute: 'member', op: 'ADD' },
        { groupDn: DEVS, memberAttribute: 'member', op: 'REMOVE' },
      ],
    })
  })

  it('apply is a no-op when nothing is staged', async () => {
    const wrapper = await mountEditWithGroups()
    const vm = wrapper.vm as unknown as ExposedStaged
    const result = await vm.applyMembershipChanges()
    expect(result).toBeNull()
    expect(usersApi.applyMemberships).not.toHaveBeenCalled()
  })

  it('copies another user’s groups into staged additions, skipping shared ones', async () => {
    const wrapper = await mountEditWithGroups()
    const vm = wrapper.vm as unknown as ExposedStaged

    // Expand the copy panel and choose a source user who is in ops (which the
    // target — a member of devs only — is not).
    const toggle = wrapper.findAll('button').find(b => b.text().trim() === 'Copy groups from another user')
    await toggle!.trigger('click')
    await wrapper.find('input[placeholder="Select or paste a user DN"]').setValue('uid=other,ou=people,dc=x')
    await wrapper.findAll('button').find(b => b.text().trim() === 'Copy')!.trigger('click')

    expect(vm.hasPendingMembershipChanges).toBe(true)
    await vm.applyMembershipChanges()
    // Only ops is new; devs is the target's own membership and untouched.
    expect(usersApi.applyMemberships).toHaveBeenCalledWith('dir1', USER_DN, {
      changes: [{ groupDn: OPS, memberAttribute: 'member', op: 'ADD' }],
    })
  })

  it('loads groups with the raised cap and slimmed attributes', async () => {
    await mountEditWithGroups()
    expect(groupsApi.searchGroups).toHaveBeenCalledWith('dir1', expect.objectContaining({
      limit: '2000',
      attributes: 'cn,member,uniqueMember,memberUid',
    }))
  })

  it('warns that the membership view may be incomplete when the cap is hit', async () => {
    vi.mocked(groupsApi.searchGroups).mockResolvedValue({
      data: Array.from({ length: 2000 }, (_, i) => ({
        dn: `cn=g${i},ou=groups,dc=x`, attributes: { cn: [`g${i}`] },
      })),
    } as never)
    const wrapper = await mountEditWithGroups()
    expect(wrapper.text()).toContain('may be incomplete')
  })
})
