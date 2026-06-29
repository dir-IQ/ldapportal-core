// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import { ref } from 'vue'
import { useProfileThemeBand } from './useProfileThemeBand'

describe('useProfileThemeBand', () => {
  it('falls back to default styling when no theme colour is set', () => {
    const band = useProfileThemeBand(ref(null))
    expect(band.themed.value).toBe(false)
    expect(band.bandStyle.value).toEqual({})
    expect(band.bandClass.value).toBe('')
    expect(band.titleClass.value).toBe('text-gray-900')
    // The key behaviour the feature changes: the profile name is blue by default.
    expect(band.profileNameClass.value).toBe('text-blue-600')
  })

  it('renders a band and drops the blue profile name when a colour is set', () => {
    const band = useProfileThemeBand(ref('#2563eb'))
    expect(band.themed.value).toBe(true)
    expect(band.bandStyle.value).toEqual({ backgroundColor: '#2563eb' })
    expect(band.bandClass.value).toContain('rounded-lg')
    // Dark band → white title; profile name inherits (no blue).
    expect(band.titleClass.value).toBe('text-white')
    expect(band.profileNameClass.value).toBe('')
  })

  it('reacts to the colour ref changing', () => {
    const color = ref<string | null>(null)
    const band = useProfileThemeBand(color)
    expect(band.themed.value).toBe(false)
    color.value = '#ffffff'
    expect(band.themed.value).toBe(true)
    // Light band → dark title.
    expect(band.titleClass.value).toBe('text-gray-900')
    expect(band.profileNameClass.value).toBe('')
  })
})
