// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ThemeColorPreview from './ThemeColorPreview.vue'
import { themeTint } from '@/utils/themeColor'

describe('ThemeColorPreview', () => {
  it('shows the tag on a light page and a dark one, in the tints the app uses', () => {
    const w = mount(ThemeColorPreview, { props: { color: '#facc15', name: 'Contractors' } })
    expect(w.find('[data-testid="theme-preview"]').text()).toContain('Light mode')
    expect(w.find('[data-testid="theme-preview"]').text()).toContain('Dark mode')
    const light = w.find('[data-testid="theme-preview-tag-light"]')
    const dark = w.find('[data-testid="theme-preview-tag-dark"]')
    expect(light.text()).toBe('Contractors')
    expect((light.element as HTMLElement).style.color).toBe(themeTint('#facc15')!.text)
    expect((dark.element as HTMLElement).style.color).toBe(themeTint('#facc15', true)!.text)
    expect(w.find('[data-testid="theme-advice"]').exists()).toBe(false)
  })

  it('warns about a colour that barely shows', () => {
    const w = mount(ThemeColorPreview, { props: { color: '#fafafa', name: 'X' } })
    expect(w.find('[data-testid="theme-advice"]').text()).toContain('Very light')
  })

  it('shows nothing with no colour, and asks for a hex colour for a partial one', () => {
    expect(mount(ThemeColorPreview, { props: { color: '' } }).html()).not.toContain('theme-preview')
    const partial = mount(ThemeColorPreview, { props: { color: '#2563' } })
    expect(partial.find('[data-testid="theme-preview"]').exists()).toBe(false)
    expect(partial.find('[data-testid="theme-advice"]').text()).toContain('#2563eb')
  })

  it('stands in a placeholder for a profile with no name yet', () => {
    const w = mount(ThemeColorPreview, { props: { color: '#2563eb', name: '' } })
    expect(w.find('[data-testid="theme-preview-tag-light"]').text()).toBe('Profile name')
  })
})
