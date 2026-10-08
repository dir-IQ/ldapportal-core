// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ActionMenu from './ActionMenu.vue'

const indicator = { dotClass: 'bg-green-500', label: 'IVIA enabled' }

describe('ActionMenu item indicator', () => {
  it('renders a status dot, tooltip and screen-reader label on an inline button', () => {
    const wrapper = mount(ActionMenu, {
      props: { items: [{ label: 'IVIA integration', onClick: () => {}, indicator }] },
    })
    const btn = wrapper.get('button')
    expect(btn.find('.bg-green-500[aria-hidden="true"]').exists()).toBe(true)
    expect(btn.find('.sr-only').text()).toBe('(IVIA enabled)')
    expect(btn.attributes('title')).toBe('IVIA enabled')
  })

  it('renders the indicator on an overflow menu entry', async () => {
    const items = [
      { label: 'One', onClick: () => {} },
      { label: 'Two', onClick: () => {} },
      { label: 'Three', onClick: () => {} },
      { label: 'IVIA integration', onClick: () => {}, indicator },
    ]
    const wrapper = mount(ActionMenu, { props: { items }, attachTo: document.body })
    await wrapper.get('[aria-haspopup="menu"]').trigger('click')
    const entry = document.querySelector('[role="menuitem"]') as HTMLElement
    expect(entry.textContent).toContain('IVIA integration')
    expect(entry.querySelector('.bg-green-500')).not.toBeNull()
    expect(entry.querySelector('.sr-only')?.textContent).toBe('(IVIA enabled)')
    wrapper.unmount()
  })

  it('renders no dot when an item has no indicator', () => {
    const wrapper = mount(ActionMenu, { props: { items: [{ label: 'Edit', onClick: () => {} }] } })
    expect(wrapper.find('.rounded-full').exists()).toBe(false)
    expect(wrapper.get('button').attributes('title')).toBe('')
  })
})
