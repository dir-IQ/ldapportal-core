// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import HelpTip from './HelpTip.vue'

// The tooltip is teleported to <body>, so assertions read document.body
// rather than the wrapper.
describe('HelpTip.vue', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('renders the help icon button', () => {
    const wrapper = mount(HelpTip, { props: { text: 'helpful info' } })
    expect(wrapper.find('button').exists()).toBe(true)
    expect(wrapper.find('svg').exists()).toBe(true)
  })

  it('does not render the tooltip initially', () => {
    mount(HelpTip, { props: { text: 'helpful info' } })
    expect(document.body.textContent).not.toContain('helpful info')
  })

  it('shows the tooltip on mouseenter and hides on mouseleave', async () => {
    const wrapper = mount(HelpTip, { props: { text: 'helpful info' } })
    await wrapper.trigger('mouseenter')
    expect(document.body.textContent).toContain('helpful info')
    await wrapper.trigger('mouseleave')
    expect(document.body.textContent).not.toContain('helpful info')
  })

  it('renders the tooltip with role=tooltip when shown', async () => {
    const wrapper = mount(HelpTip, { props: { text: 'second message' } })
    await wrapper.trigger('mouseenter')
    const tip = document.body.querySelector('[role="tooltip"]')
    expect(tip?.textContent).toContain('second message')
  })
})
