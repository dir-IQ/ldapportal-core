// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import UserAttributesView from './UserAttributesView.vue'

function rowTexts(wrapper: ReturnType<typeof mount>) {
  return wrapper.findAll('[data-testid="user-attr-row"]').map(r => r.text())
}

describe('UserAttributesView', () => {
  it('lists every non-empty attribute, sorted, with each value', () => {
    const wrapper = mount(UserAttributesView, { props: { attributes: {
      uid: ['jdoe'], cn: 'Jane Doe', mail: ['jdoe@x', 'jane@x'], description: [], title: null,
    } } })
    const rows = rowTexts(wrapper)
    expect(rows).toHaveLength(3)
    expect(rows[0]).toContain('cn')
    expect(rows[0]).toContain('Jane Doe')
    expect(rows[1]).toContain('jdoe@x')
    expect(rows[1]).toContain('jane@x')
    expect(rows[2]).toContain('uid')
  })

  it('never shows password values', () => {
    const wrapper = mount(UserAttributesView, { props: { attributes: {
      uid: ['jdoe'], userPassword: ['{SSHA}secret'],
    } } })
    expect(wrapper.text()).toContain('userPassword')
    expect(wrapper.text()).not.toContain('{SSHA}secret')
    expect(wrapper.text()).toContain('(hidden)')
  })

  it('filters by attribute name or value, never matching hidden values', async () => {
    const wrapper = mount(UserAttributesView, { props: { attributes: {
      uid: ['jdoe'], mail: ['jdoe@x'], userPassword: ['jdoe-pw'],
    } } })
    await wrapper.find('input').setValue('jdoe')
    expect(wrapper.findAll('[data-testid="user-attr-row"] dt').map(d => d.text())).toEqual(['mail', 'uid'])
    await wrapper.find('input').setValue('nothing')
    expect(wrapper.text()).toContain('No attributes match the filter')
  })
})
