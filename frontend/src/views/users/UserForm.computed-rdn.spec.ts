// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { defineComponent, h, reactive } from 'vue'
import UserForm from './UserForm.vue'

vi.mock('@/api/groups', () => ({ searchGroups: vi.fn().mockResolvedValue({ data: [] }), addGroupMember: vi.fn(), removeGroupMember: vi.fn() }))
vi.mock('@/api/users', () => ({ applyMemberships: vi.fn().mockResolvedValue({ data: { applied: 0, queued: 0, refused: 0, blocked: 0, errored: 0, items: [] } }), getUser: vi.fn() }))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ isIsvaIntegrationEnabled: false }) }))
vi.mock('@/api/profiles', () => ({ generatePassword: vi.fn() }))
vi.mock('@/api/isvaConfig', () => ({ getIsvaConfig: vi.fn().mockResolvedValue({ data: { enabled: false } }) }))
vi.mock('@/api/attributeSyntax', () => ({ getAttributeSyntaxHints: vi.fn().mockResolvedValue({ data: { wellKnownAttributes: {}, inputTypeSyntax: {} } }) }))

// A profile whose RDN attribute (cn) is itself computed from other fields.
const templateConfig = {
  rdnAttribute: 'cn',
  dnTemplate: 'cn=${givenName} ${sn},o=example,c=us',
  showDnField: true,
  attributeConfigs: [
    { attributeName: 'cn', inputType: 'TEXT', requiredOnCreate: true, editableOnCreate: true, computedExpression: '${givenName} ${sn}' },
    { attributeName: 'givenName', inputType: 'TEXT', editableOnCreate: true },
    { attributeName: 'sn', inputType: 'TEXT', editableOnCreate: true },
  ],
}

// Parent harness echoing @update back into :data, matching the real caller.
const Harness = defineComponent({
  setup() {
    const state = reactive<{ data: Record<string, unknown> }>({
      data: { attributes: {}, parentDn: 'o=example,c=us', rdnAttribute: 'cn', rdnValue: '' },
    })
    return () => h(UserForm, {
      data: state.data, isEdit: false, userTemplateConfig: templateConfig, dirId: null, profileId: null,
      onUpdate: (d: Record<string, unknown>) => { state.data = d },
    })
  },
})

describe('UserForm — computed attribute that is also the RDN', () => {
  beforeEach(() => setActivePinia(createPinia()))

  // Regression: the cn RDN field was bound to local.rdnValue, which only
  // received the computed value through a fragile round-trip and froze at the
  // first typed character (cn showed "J" for givenName "John"). It now reads the
  // live computed value directly, like the DN field.
  it('shows the full computed value in the RDN field, not just the first letter', async () => {
    const wrapper = mount(Harness)
    await flushPromises()

    async function type(field: string, value: string) {
      const input = wrapper.find(`[data-field="${field}"]`)
      expect(input.exists(), `input for ${field}`).toBe(true)
      await input.setValue(value)
      await flushPromises()
    }

    await type('givenName', 'John')
    await type('sn', 'Smith')

    const cnField = (wrapper.find('[data-field="cn"]').element as HTMLInputElement).value
    expect(cnField).toBe('John Smith')
    // And the computed RDN field is read-only (its value has no operator input).
    expect(wrapper.find('[data-field="cn"]').attributes('disabled')).toBeDefined()
  })
})
