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

  it('shows a top-of-form summary listing failing fields after a failed save', async () => {
    // scrollIntoView isn't implemented in the test DOM; stub it so the
    // post-validation scroll is a no-op.
    Element.prototype.scrollIntoView = vi.fn()
    const wrapper = mountForm({ rdnValue: 'jsmith', attributes: { mail: 'nope' } })
    // No summary until a save is attempted.
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)

    expect((wrapper.vm as unknown as ExposedForm).validate()).toBe(false)
    await wrapper.vm.$nextTick()

    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('Please fix')
    expect(alert.text()).toContain('Enter a valid email') // the failing field's message
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

// Mirrors the server's syntax layer (workstream A) in the create form: a
// DN_LOOKUP field must hold a valid DN, and a well-known attribute (mail) is
// shape-checked via the /attribute-syntax hints (workstream B).
describe('UserForm syntax validation', () => {
  beforeEach(() => setActivePinia(createPinia()))

  const syntaxConfig = {
    rdnAttribute: 'uid',
    attributeConfigs: [
      { attributeName: 'uid', requiredOnCreate: true, editableOnCreate: true, inputType: 'TEXT' },
      // DN_LOOKUP → DN, derivable from the input type without the hints.
      { attributeName: 'manager', editableOnCreate: true, inputType: 'DN_LOOKUP' },
      // Plain text, but well-known mail → EMAIL once the hints load.
      { attributeName: 'mail', editableOnCreate: true, inputType: 'TEXT' },
    ],
  }

  function mountSyntax(data: Record<string, unknown>) {
    return mount(UserForm, {
      props: { data, isEdit: false, userTemplateConfig: syntaxConfig, dirId: null, profileId: null },
    })
  }

  it('blocks submit and shows a DN error for a malformed DN_LOOKUP value', async () => {
    const wrapper = mountSyntax({ rdnValue: 'jsmith', attributes: { manager: 'not a dn' } })
    expect((wrapper.vm as unknown as ExposedForm).validate()).toBe(false)
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('Not a valid DN')
  })

  it('mirrors the well-known email check once the syntax hints load', async () => {
    const wrapper = mountSyntax({ rdnValue: 'jsmith', attributes: { mail: 'nope' } })
    await flushPromises() // let the attribute-syntax hints resolve
    expect((wrapper.vm as unknown as ExposedForm).validate()).toBe(false)
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('Not a valid email address')
  })

  it('passes with a valid DN and email', async () => {
    const wrapper = mountSyntax({
      rdnValue: 'jsmith',
      attributes: { manager: 'uid=boss,ou=people,dc=example,dc=com', mail: 'a@b.com' },
    })
    await flushPromises()
    expect((wrapper.vm as unknown as ExposedForm).validate()).toBe(true)
  })
})

// The DN is editable on create: it seeds from the profile's dnTemplate (or the
// default "<rdn>=<value>,<parentDn>" composition), tracks the RDN until the
// admin overrides it, and an override must stay within the profile's target OU.
describe('UserForm editable DN (create mode)', () => {
  beforeEach(() => setActivePinia(createPinia()))

  const BASE = 'ou=people,dc=example,dc=com'

  const dnTemplateConfig = {
    rdnAttribute: 'uid',
    dnTemplate: 'uid=${uid},ou=people,dc=example,dc=com',
    attributeConfigs: [
      { attributeName: 'uid', requiredOnCreate: true, editableOnCreate: true, inputType: 'TEXT' },
    ],
  }
  const plainConfig = {
    rdnAttribute: 'uid',
    attributeConfigs: [
      { attributeName: 'uid', requiredOnCreate: true, editableOnCreate: true, inputType: 'TEXT' },
    ],
  }

  function mountDn(config: object, parentDn = BASE) {
    return mount(UserForm, {
      props: {
        data: { rdnAttribute: 'uid', rdnValue: 'jsmith', parentDn, attributes: {} },
        isEdit: false,
        userTemplateConfig: config,
        dirId: null,
        profileId: null,
      },
    })
  }

  // The DN FormField carries a unique placeholder.
  function dnInput(wrapper: ReturnType<typeof mountDn>) {
    return wrapper.findAll('input')
      .find(i => i.attributes('placeholder') === 'uid=jsmith,ou=people,dc=example,dc=com')!
  }

  it('seeds the DN from the profile dnTemplate', async () => {
    const wrapper = mountDn(dnTemplateConfig)
    await wrapper.vm.$nextTick()
    expect((dnInput(wrapper).element as HTMLInputElement).value)
      .toBe('uid=jsmith,ou=people,dc=example,dc=com')
  })

  it('seeds from the default composition when no template is set', async () => {
    const wrapper = mountDn(plainConfig, 'ou=staff,dc=example,dc=com')
    await wrapper.vm.$nextTick()
    expect((dnInput(wrapper).element as HTMLInputElement).value)
      .toBe('uid=jsmith,ou=staff,dc=example,dc=com')
  })

  it('rejects an overridden DN outside the target OU', async () => {
    const wrapper = mountDn(dnTemplateConfig)
    await wrapper.vm.$nextTick()
    await dnInput(wrapper).setValue('uid=jsmith,ou=evil,dc=example,dc=com')
    expect((wrapper.vm as unknown as ExposedForm).validate()).toBe(false)
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain(`DN must be within ${BASE}`)
  })

  it('accepts an overridden DN within the target OU (deeper sub-OU)', async () => {
    const wrapper = mountDn(dnTemplateConfig)
    await wrapper.vm.$nextTick()
    await dnInput(wrapper).setValue('uid=jsmith,ou=eng,ou=people,dc=example,dc=com')
    expect((wrapper.vm as unknown as ExposedForm).validate()).toBe(true)
  })

  it('rejects an RDN-boundary near-miss (ou=people2)', async () => {
    const wrapper = mountDn(dnTemplateConfig)
    await wrapper.vm.$nextTick()
    await dnInput(wrapper).setValue('uid=jsmith,ou=people2,dc=example,dc=com')
    expect((wrapper.vm as unknown as ExposedForm).validate()).toBe(false)
  })

  // Set a dynamic field by its FormField label.
  async function setField(wrapper: ReturnType<typeof mountDn>, label: string, val: string) {
    const ff = wrapper.findAllComponents({ name: 'FormField' })
      .find(c => String(c.props('label') || '').startsWith(label))!
    await ff.find('input').setValue(val)
  }

  it('re-derives the DN when a plain templated field changes', async () => {
    const wrapper = mountDn({
      rdnAttribute: 'uid',
      dnTemplate: 'uid=${uid},ou=${department},dc=example,dc=com',
      attributeConfigs: [
        { attributeName: 'uid', requiredOnCreate: true, editableOnCreate: true, inputType: 'TEXT' },
        { attributeName: 'department', editableOnCreate: true, inputType: 'TEXT' },
      ],
    })
    await wrapper.vm.$nextTick()
    await setField(wrapper, 'department', 'eng')
    await wrapper.vm.$nextTick()
    expect((dnInput(wrapper).element as HTMLInputElement).value)
      .toBe('uid=jsmith,ou=eng,dc=example,dc=com')
  })

  it('reflects a COMPUTED attribute and reacts to its source field', async () => {
    const wrapper = mountDn({
      rdnAttribute: 'uid',
      dnTemplate: 'cn=${cn},ou=people,dc=example,dc=com',
      attributeConfigs: [
        { attributeName: 'uid', requiredOnCreate: true, editableOnCreate: true, inputType: 'TEXT' },
        { attributeName: 'givenName', editableOnCreate: true, inputType: 'TEXT' },
        { attributeName: 'cn', inputType: 'TEXT', hidden: true, computedExpression: '${givenName}' },
      ],
    })
    await wrapper.vm.$nextTick()
    await setField(wrapper, 'givenName', 'John')
    await wrapper.vm.$nextTick()
    expect((dnInput(wrapper).element as HTMLInputElement).value)
      .toBe('cn=John,ou=people,dc=example,dc=com')
  })

  // A computed RDN (e.g. cn derived from o/givenName/sn) renders no RDN input,
  // so the DN field must render standalone and pull the RDN's *computed* value.
  const computedRdnConfig = {
    rdnAttribute: 'cn',
    attributeConfigs: [
      { attributeName: 'o', editableOnCreate: true, inputType: 'TEXT' },
      { attributeName: 'givenName', editableOnCreate: true, inputType: 'TEXT' },
      { attributeName: 'sn', editableOnCreate: true, inputType: 'TEXT' },
      { attributeName: 'cn', hidden: true, inputType: 'TEXT', computedExpression: '${o}+"+"+${givenName}+" "+${sn}' },
    ],
  }

  function mountComputedRdn(cfg: object) {
    return mount(UserForm, {
      props: {
        data: { rdnAttribute: 'cn', rdnValue: '', parentDn: 'ou=People,dc=oud1,dc=example,dc=com', attributes: {} },
        isEdit: false,
        userTemplateConfig: cfg,
        dirId: null,
        profileId: null,
      },
    })
  }

  it('composes the DN from a computed RDN via the dnTemplate', async () => {
    const wrapper = mountComputedRdn({
      ...computedRdnConfig,
      dnTemplate: 'cn=${cn},ou=People,dc=oud1,dc=example,dc=com',
    })
    await wrapper.vm.$nextTick()
    await setField(wrapper, 'o', '00001')
    await setField(wrapper, 'givenName', 'Sanjay')
    await setField(wrapper, 'sn', 'Mishra')
    await wrapper.vm.$nextTick()
    expect((dnInput(wrapper).element as HTMLInputElement).value)
      .toBe('cn=00001\\+Sanjay Mishra,ou=People,dc=oud1,dc=example,dc=com')
  })

  it('composes the DN from a computed RDN without a template (default rule)', async () => {
    const wrapper = mountComputedRdn(computedRdnConfig)
    await wrapper.vm.$nextTick()
    await setField(wrapper, 'o', '00001')
    await setField(wrapper, 'givenName', 'Sanjay')
    await setField(wrapper, 'sn', 'Mishra')
    await wrapper.vm.$nextTick()
    expect((dnInput(wrapper).element as HTMLInputElement).value)
      .toBe('cn=00001\\+Sanjay Mishra,ou=People,dc=oud1,dc=example,dc=com')
  })

  it('RFC 4514-escapes reserved characters in a substituted value', async () => {
    const wrapper = mountDn({
      rdnAttribute: 'uid',
      dnTemplate: 'cn=${cn},ou=people,dc=example,dc=com',
      attributeConfigs: [
        { attributeName: 'uid', requiredOnCreate: true, editableOnCreate: true, inputType: 'TEXT' },
        { attributeName: 'cn', editableOnCreate: true, inputType: 'TEXT' },
      ],
    })
    await wrapper.vm.$nextTick()
    // A literal '+' (the multi-valued-RDN separator) in the value must be escaped
    // so the DN stays a single valid RDN.
    await setField(wrapper, 'cn', '00001+Sanjay Mishra')
    await wrapper.vm.$nextTick()
    expect((dnInput(wrapper).element as HTMLInputElement).value)
      .toBe('cn=00001\\+Sanjay Mishra,ou=people,dc=example,dc=com')
  })
})

// Naming behaviour derives from the entry's *actual* DN, not the profile's
// designated rdnAttribute: a multi-valued RDN (o=0001+cn=…) locks every
// component in edit mode, and a designated attribute an overridden DN no
// longer uses stays editable.
describe('UserForm DN-derived naming attributes', () => {
  beforeEach(() => setActivePinia(createPinia()))

  const multiRdnTemplate = {
    rdnAttribute: 'cn',
    attributeConfigs: [
      { attributeName: 'cn', inputType: 'TEXT', editableOnUpdate: true },
      { attributeName: 'o', inputType: 'TEXT', editableOnUpdate: true },
      { attributeName: 'mail', inputType: 'TEXT', editableOnUpdate: true },
    ],
  }

  function mountMultiRdnEdit(dn: string) {
    return mount(UserForm, {
      props: {
        data: { dn, attributes: { cn: 'Sanjay Mishra', o: '0001', mail: 'sm@example.com' } },
        isEdit: true,
        userTemplateConfig: multiRdnTemplate,
        dirId: null,
        profileId: null,
      },
    })
  }

  function fieldByLabel(wrapper: ReturnType<typeof mountMultiRdnEdit>, label: string) {
    return wrapper.findAllComponents({ name: 'FormField' })
      .find(c => String(c.props('label') || '').startsWith(label))!
  }

  it('locks every component of a multi-valued RDN in edit mode', () => {
    const wrapper = mountMultiRdnEdit('o=0001+cn=Sanjay Mishra,ou=People,dc=oud1,dc=example,dc=com')
    expect(fieldByLabel(wrapper, 'cn').find('input').attributes('disabled')).toBeDefined()
    expect(fieldByLabel(wrapper, 'o').find('input').attributes('disabled')).toBeDefined()
    expect(fieldByLabel(wrapper, 'mail').find('input').attributes('disabled')).toBeUndefined()
  })

  it('marks both naming fields with the (RDN) label suffix', () => {
    const wrapper = mountMultiRdnEdit('o=0001+cn=Sanjay Mishra,ou=People,dc=oud1,dc=example,dc=com')
    expect(String(fieldByLabel(wrapper, 'cn').props('label'))).toBe('cn (RDN)')
    expect(String(fieldByLabel(wrapper, 'o').props('label'))).toBe('o (RDN)')
    expect(String(fieldByLabel(wrapper, 'mail').props('label'))).toBe('mail')
  })

  it('keeps the designated attribute editable when the entry DN does not use it', () => {
    // Entry created under an operator-overridden DN naming uid, not cn (the
    // designated rdnAttribute) — cn must not be wrongly locked.
    const wrapper = mountMultiRdnEdit('uid=smishra,ou=People,dc=oud1,dc=example,dc=com')
    expect(fieldByLabel(wrapper, 'cn').find('input').attributes('disabled')).toBeUndefined()
  })

  it('skips validation rules for actual naming attributes in edit mode', () => {
    const wrapper = mount(UserForm, {
      props: {
        data: {
          dn: 'o=0001+cn=Sanjay Mishra,ou=People,dc=oud1,dc=example,dc=com',
          attributes: { cn: 'Sanjay Mishra', o: '0001' },
        },
        isEdit: true,
        userTemplateConfig: {
          rdnAttribute: 'cn',
          attributeConfigs: [
            { attributeName: 'cn', inputType: 'TEXT', editableOnUpdate: true },
            // o="0001" violates the rule, but o names the entry (its field is
            // disabled), so the rule must not block saving other edits.
            { attributeName: 'o', inputType: 'TEXT', editableOnUpdate: true, validationRegex: '^[a-z]+$', validationMessage: 'letters only' },
          ],
        },
        dirId: null,
        profileId: null,
      },
    })
    expect((wrapper.vm as unknown as ExposedForm).validate()).toBe(true)
  })

  // Create mode: an overridden DN whose naming value disagrees with the typed
  // field value would be rejected by the directory as a naming violation —
  // the form must surface the conflict instead.
  it('flags a conflict between an overridden DN naming value and the typed field', async () => {
    Element.prototype.scrollIntoView = vi.fn()
    const wrapper = mount(UserForm, {
      props: {
        data: { rdnAttribute: 'uid', rdnValue: 'jsmith', parentDn: 'ou=people,dc=example,dc=com', attributes: {} },
        isEdit: false,
        userTemplateConfig: {
          rdnAttribute: 'uid',
          attributeConfigs: [
            { attributeName: 'uid', requiredOnCreate: true, editableOnCreate: true, inputType: 'TEXT' },
          ],
        },
        dirId: null,
        profileId: null,
      },
    })
    await wrapper.vm.$nextTick()
    const dnInput = wrapper.findAll('input')
      .find(i => i.attributes('placeholder') === 'uid=jsmith,ou=people,dc=example,dc=com')!
    await dnInput.setValue('uid=different,ou=people,dc=example,dc=com')
    expect((wrapper.vm as unknown as ExposedForm).validate()).toBe(false)
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('conflicts with the DN naming value uid=different')
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

describe('UserForm password field hiding (password disposition)', () => {
  beforeEach(() => setActivePinia(createPinia()))

  const pwConfig = (passwordDisposition: string) => ({
    rdnAttribute: 'uid',
    passwordDisposition,
    attributeConfigs: [
      { attributeName: 'uid', requiredOnCreate: true, editableOnCreate: true, inputType: 'TEXT' },
      { attributeName: 'userPassword', requiredOnCreate: true, editableOnCreate: true, inputType: 'PASSWORD' },
    ],
  })

  function mountWith(passwordDisposition: string) {
    return mount(UserForm, {
      props: {
        data: { attributes: {} }, isEdit: false,
        userTemplateConfig: pwConfig(passwordDisposition), dirId: null, profileId: 'p1',
      },
    })
  }

  it('renders the password field for OPERATOR_ENTERED', () => {
    const wrapper = mountWith('OPERATOR_ENTERED')
    expect(wrapper.find('#uf-pw-userPassword').exists()).toBe(true)
  })

  it('hides the password field for GENERATED_DISCARDED (and does not fall back to a text input)', () => {
    const wrapper = mountWith('GENERATED_DISCARDED')
    expect(wrapper.find('#uf-pw-userPassword').exists()).toBe(false)
    // The hidden password must not leak through as a plain field of any kind.
    expect(wrapper.find('[id^="uf-pw-"]').exists()).toBe(false)
    expect(wrapper.findAll('input[type="password"]').length).toBe(0)
  })

  it('hides the password field for GENERATED_DELIVERED', () => {
    const wrapper = mountWith('GENERATED_DELIVERED')
    expect(wrapper.find('#uf-pw-userPassword').exists()).toBe(false)
  })
})

describe('UserForm DN field layout', () => {
  beforeEach(() => setActivePinia(createPinia()))

  function mountWithDn(overrides: Record<string, unknown>) {
    return mount(UserForm, {
      props: {
        data: { attributes: {} },
        isEdit: false,
        userTemplateConfig: {
          rdnAttribute: 'uid',
          showDnField: true,
          attributeConfigs: [
            { attributeName: 'uid', requiredOnCreate: true, editableOnCreate: true, inputType: 'TEXT' },
          ],
          ...overrides,
        },
        dirId: null,
        profileId: null,
      },
    })
  }

  it('defaults the DN field to 2/3 width (span 4)', () => {
    const wrapper = mountWithDn({})
    expect(wrapper.text()).toContain('DN')
    expect(wrapper.html()).toContain('grid-column: span 4')
  })

  it('honors a designer-configured DN width', () => {
    const wrapper = mountWithDn({ dnColumnSpan: 6 })
    expect(wrapper.html()).toContain('grid-column: span 6')
  })
})
