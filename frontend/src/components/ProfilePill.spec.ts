// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ProfilePill from './ProfilePill.vue'

describe('ProfilePill', () => {
  it('renders the profile name with a theme-coloured dot and tinted chip', () => {
    const w = mount(ProfilePill, { props: { name: 'PROD', color: '#b91c1c' } })
    expect(w.text()).toContain('PROD')
    // A dot swatch in the raw theme colour.
    const dot = w.find('span[aria-hidden="true"]')
    expect(dot.exists()).toBe(true)
    expect(dot.attributes('style')).toContain('#b91c1c')
    // Rendered as a taller, less-rounded chip (the soft theme tint is applied
    // via inline color-mix styles, which jsdom doesn't parse, so we assert the
    // chrome rather than the computed fill).
    expect(w.classes()).toContain('rounded-md')
  })

  it('renders a neutral chip with no dot when no colour is set', () => {
    const w = mount(ProfilePill, { props: { name: 'DEV' } })
    expect(w.text()).toContain('DEV')
    // Same chip shape, neutral grey, and no theme dot.
    expect(w.classes()).toContain('rounded-md')
    expect(w.classes()).toContain('border-gray-300')
    expect(w.find('span[aria-hidden="true"]').exists()).toBe(false)
  })
})
