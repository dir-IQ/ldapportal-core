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
    // A less-rounded chip carrying the profile tint: the .profile-tint class
    // applies the light or dark set of these colours for the current theme.
    expect(w.classes()).toContain('rounded-md')
    expect(w.classes()).toContain('profile-tint')
    const style = w.attributes('style') ?? ''
    expect(style).toContain('--profile-tint-text:')
    expect(style).toContain('--profile-tint-text-dark:')
  })

  it('renders a neutral chip with no dot when no colour is set', () => {
    const w = mount(ProfilePill, { props: { name: 'DEV' } })
    expect(w.text()).toContain('DEV')
    // Same chip shape, neutral grey, and no theme dot.
    expect(w.classes()).toContain('rounded-md')
    expect(w.classes()).toContain('border-gray-300')
    expect(w.classes()).not.toContain('profile-tint')
    expect(w.find('span[aria-hidden="true"]').exists()).toBe(false)
  })
})
