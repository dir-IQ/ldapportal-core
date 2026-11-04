// SPDX-License-Identifier: Apache-2.0
/**
 * Every field of the new/edit user modal has browser autocomplete disabled, so
 * a browser's remembered entries or the admin's own saved login never fill in
 * another person's attributes. Password fields use 'new-password' (browsers
 * ignore 'off' there); everything else uses 'off'.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import UserForm from './UserForm.vue'

vi.mock('@/api/groups', () => ({
  searchGroups: vi.fn().mockResolvedValue({ data: [] }),
  addGroupMember: vi.fn(),
  removeGroupMember: vi.fn(),
}))
vi.mock('@/api/users', () => ({ applyMemberships: vi.fn(), getUser: vi.fn() }))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ isIsvaIntegrationEnabled: false }) }))
vi.mock('@/api/profiles', () => ({ generatePassword: vi.fn() }))
vi.mock('@/api/isvaConfig', () => ({ getIsvaConfig: vi.fn().mockResolvedValue({ data: { enabled: false } }) }))
vi.mock('@/api/attributeSyntax', () => ({
  getAttributeSyntaxHints: vi.fn().mockResolvedValue({ data: { wellKnownAttributes: {}, inputTypeSyntax: {} } }),
}))

/** One attribute of every input type a profile can configure. */
const templateConfig = {
  rdnAttribute: 'uid',
  attributeConfigs: [
    { attributeName: 'uid', requiredOnCreate: true, editableOnCreate: true, editableOnUpdate: true, inputType: 'TEXT' },
    { attributeName: 'description', editableOnCreate: true, editableOnUpdate: true, inputType: 'TEXTAREA' },
    { attributeName: 'userPassword', editableOnCreate: true, editableOnUpdate: true, inputType: 'PASSWORD' },
    { attributeName: 'startDate', editableOnCreate: true, editableOnUpdate: true, inputType: 'DATE' },
    { attributeName: 'lastSeen', editableOnCreate: true, editableOnUpdate: true, inputType: 'DATETIME' },
    { attributeName: 'mobile', editableOnCreate: true, editableOnUpdate: true, inputType: 'MULTI_VALUE' },
    { attributeName: 'manager', editableOnCreate: true, editableOnUpdate: true, inputType: 'DN_LOOKUP' },
    { attributeName: 'seeAlso', editableOnCreate: true, editableOnUpdate: true, inputType: 'DN' },
    { attributeName: 'employeeType', editableOnCreate: true, editableOnUpdate: true, inputType: 'SELECT',
      allowedValues: '["staff","contractor"]' },
  ],
}

/** Controls a browser could autofill: everything but checkboxes, radios, hidden and buttons. */
function fillableControls(wrapper: ReturnType<typeof mount>) {
  return wrapper.findAll('input, select, textarea').filter(el => {
    const type = (el.attributes('type') ?? '').toLowerCase()
    return !['checkbox', 'radio', 'hidden', 'button', 'submit'].includes(type)
  })
}

function offenders(wrapper: ReturnType<typeof mount>) {
  return fillableControls(wrapper)
    .filter(el => {
      const want = el.attributes('type') === 'password' ? 'new-password' : 'off'
      return el.attributes('autocomplete') !== want
    })
    .map(el => el.html().slice(0, 140))
}

describe('UserForm autocomplete', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it.each([
    ['create', false],
    ['edit', true],
  ])('%s: every field has autocomplete disabled', async (_mode, isEdit) => {
    const wrapper = mount(UserForm, {
      props: {
        // title / carLicense aren't in the profile, so the extra-attribute fields render too.
        data: { dn: isEdit ? 'uid=a,dc=x' : undefined, attributes: { uid: 'a', title: 'x', carLicense: 'y' } },
        isEdit, userTemplateConfig: templateConfig, dirId: null, profileId: null,
      },
      global: { stubs: { teleport: true }, directives: { 'dialog-a11y': {} } },
    })
    await flushPromises()
    // Open the collapsed "Other Attributes" section (edit mode) so its fields are checked too.
    const other = wrapper.findAll('button').find(b => b.text().startsWith('Other Attributes'))
    if (isEdit) {
      expect(other).toBeDefined()
      await other!.trigger('click')
    }

    expect(fillableControls(wrapper).length).toBeGreaterThan(5)
    expect(offenders(wrapper)).toEqual([])
  })

  it.each([
    ['create', false],
    ['edit', true],
  ])('%s without a profile: the raw-attribute fields have autocomplete disabled too', async (_mode, isEdit) => {
    const wrapper = mount(UserForm, {
      props: {
        data: { dn: isEdit ? 'cn=A,dc=x' : undefined, attributes: { cn: 'A', title: 'x' } },
        isEdit, userTemplateConfig: null, dirId: null, profileId: null,
      },
      global: { stubs: { teleport: true }, directives: { 'dialog-a11y': {} } },
    })
    await flushPromises()

    expect(fillableControls(wrapper).length).toBeGreaterThan(1)
    expect(offenders(wrapper)).toEqual([])
  })
})
