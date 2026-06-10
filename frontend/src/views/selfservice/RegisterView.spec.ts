// SPDX-License-Identifier: Apache-2.0
/**
 * Regression for the self-service registration form's field validation. It
 * shares frontend/src/utils/attributeValidation with the admin forms, so it
 * must keep enforcing required/length/regex AND now pick up the input-type
 * syntax checks (DN_LOOKUP → DN, BOOLEAN → TRUE/FALSE) automatically — without
 * the admin-only /attribute-syntax hints, which a self-service user can't read.
 * The server (ProvisioningProfileService) re-validates authoritatively.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('@/api/selfservice', () => ({
  listRegistrationDirectories: vi.fn().mockResolvedValue({ data: [] }),
  listRegistrationProfiles: vi.fn().mockResolvedValue({ data: [] }),
  getRegistrationForm: vi.fn().mockResolvedValue({ data: [] }),
  submitRegistration: vi.fn(),
}))

import RegisterView from './RegisterView.vue'

/* eslint-disable @typescript-eslint/no-explicit-any */
async function mountVm() {
  const wrapper = mount(RegisterView, { global: { stubs: { RouterLink: true } } })
  await flushPromises()
  return wrapper.vm as any
}

describe('RegisterView field validation', () => {
  beforeEach(() => vi.clearAllMocks())

  it('still enforces required + regex rules', async () => {
    const vm = await mountVm()
    vm.formFields = [
      { attributeName: 'uid', label: 'User ID', required: true },
      { attributeName: 'mail', label: 'Email', validationRegex: '^[^@]+@[^@]+$', validationMessage: 'Bad email' },
    ]
    vm.attributeValues.uid = ''
    vm.attributeValues.mail = 'nope'

    expect(vm.validateFields()).toBe(false)
    expect(vm.fieldErrors.uid).toBe('User ID is required')
    expect(vm.fieldErrors.mail).toBe('Bad email')
  })

  it('picks up DN_LOOKUP and BOOLEAN syntax checks via the shared util (no hints)', async () => {
    const vm = await mountVm()
    vm.formFields = [
      { attributeName: 'manager', label: 'Manager', inputType: 'DN_LOOKUP' },
      { attributeName: 'active', label: 'Active', inputType: 'BOOLEAN' },
    ]
    vm.attributeValues.manager = 'not a dn'
    vm.attributeValues.active = true // coerced to TRUE → valid boolean

    expect(vm.validateFields()).toBe(false)
    expect(vm.fieldErrors.manager).toBe('Not a valid DN')
    expect(vm.fieldErrors.active).toBeUndefined()
  })

  it('passes when DN and boolean values are well-formed', async () => {
    const vm = await mountVm()
    vm.formFields = [
      { attributeName: 'manager', label: 'Manager', inputType: 'DN_LOOKUP' },
      { attributeName: 'active', label: 'Active', inputType: 'BOOLEAN' },
    ]
    vm.attributeValues.manager = 'uid=boss,ou=people,dc=example,dc=com'
    vm.attributeValues.active = false

    expect(vm.validateFields()).toBe(true)
  })
})
/* eslint-enable @typescript-eslint/no-explicit-any */
