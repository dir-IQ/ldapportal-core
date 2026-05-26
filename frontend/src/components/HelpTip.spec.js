// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import HelpTip from './HelpTip.vue'

describe('HelpTip.vue', () => {
  it('renders the help icon button', () => {
    const wrapper = mount(HelpTip, { props: { text: 'helpful info' } })
    expect(wrapper.find('button').exists()).toBe(true)
    expect(wrapper.find('svg').exists()).toBe(true)
  })

  it('does not render the tooltip text initially', () => {
    const wrapper = mount(HelpTip, { props: { text: 'helpful info' } })
    expect(wrapper.text()).not.toContain('helpful info')
  })

  it('renders the tooltip text on mouseenter', async () => {
    const wrapper = mount(HelpTip, { props: { text: 'helpful info' } })
    await wrapper.trigger('mouseenter')
    expect(wrapper.text()).toContain('helpful info')
  })

  it('hides the tooltip text on mouseleave', async () => {
    const wrapper = mount(HelpTip, { props: { text: 'helpful info' } })
    await wrapper.trigger('mouseenter')
    expect(wrapper.text()).toContain('helpful info')
    await wrapper.trigger('mouseleave')
    expect(wrapper.text()).not.toContain('helpful info')
  })

  it('renders different text for different prop values', async () => {
    const w1 = mount(HelpTip, { props: { text: 'first message' } })
    await w1.trigger('mouseenter')
    expect(w1.text()).toContain('first message')

    const w2 = mount(HelpTip, { props: { text: 'second message' } })
    await w2.trigger('mouseenter')
    expect(w2.text()).toContain('second message')
  })

  it('toggles visibility across rapid mouseenter/leave cycles', async () => {
    const wrapper = mount(HelpTip, { props: { text: 'flicker' } })
    for (let i = 0; i < 3; i++) {
      await wrapper.trigger('mouseenter')
      expect(wrapper.text()).toContain('flicker')
      await wrapper.trigger('mouseleave')
      expect(wrapper.text()).not.toContain('flicker')
    }
  })
})
