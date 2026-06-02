// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import UserForm from './UserForm.vue'

// onMounted touches the groups + IVIA-config APIs; stub them so the mount is
// hermetic. With dirId=null the component short-circuits before calling them,
// but the mocks keep the test robust to that detail.
vi.mock('@/api/groups', () => ({
  searchGroups: vi.fn().mockResolvedValue({ data: [] }),
  addGroupMember: vi.fn(),
  removeGroupMember: vi.fn(),
}))
vi.mock('@/api/profiles', () => ({ generatePassword: vi.fn() }))
vi.mock('@/api/isvaConfig', () => ({
  getIsvaConfig: vi.fn().mockResolvedValue({ data: { enabled: false } }),
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
})
