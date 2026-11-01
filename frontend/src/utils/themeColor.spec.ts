// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import {
  isLightColor, onThemeTitleClass, onThemeMutedClass, themeTint, themeTintStyle, themeColorAdvice, contrastRatio,
  MIN_TEXT_CONTRAST,
} from './themeColor'

describe('themeColor', () => {
  describe('isLightColor', () => {
    it('treats bright colours as light (dark text)', () => {
      expect(isLightColor('#ffffff')).toBe(true)
      expect(isLightColor('#fde68a')).toBe(true) // amber-200
    })

    it('treats mid/dark brand colours as not light (white text)', () => {
      expect(isLightColor('#2563eb')).toBe(false) // blue-600
      expect(isLightColor('#000000')).toBe(false)
      expect(isLightColor('#166534')).toBe(false) // green-800
    })

    it('accepts hex with or without the leading hash', () => {
      expect(isLightColor('ffffff')).toBe(true)
      expect(isLightColor('#FFFFFF')).toBe(true)
    })

    it('falls back to light (safe dark text) for empty/malformed input', () => {
      expect(isLightColor('')).toBe(true)
      expect(isLightColor(null)).toBe(true)
      expect(isLightColor(undefined)).toBe(true)
      expect(isLightColor('not-a-color')).toBe(true)
      expect(isLightColor('#abc')).toBe(true) // 3-digit not supported → fallback
    })
  })

  it('picks contrasting title/muted classes', () => {
    expect(onThemeTitleClass('#ffffff')).toBe('text-gray-900')
    expect(onThemeTitleClass('#2563eb')).toBe('text-white')
    expect(onThemeMutedClass('#ffffff')).toBe('text-gray-700')
    expect(onThemeMutedClass('#2563eb')).toBe('text-white/80')
  })
})

describe('themeTint', () => {
  // A spread of hues and lightnesses, including the light colours that used to
  // wash out (yellow came to about 2.3:1, light cyan 2.2:1).
  const SAMPLES = [
    '#facc15', '#67e8f9', '#fde68a', '#a7f3d0', '#fbcfe8', '#ffffff', '#f0f0f0',
    '#2563eb', '#16a34a', '#dc2626', '#7c3aed', '#ea580c', '#0f766e', '#000000', '#1f2937',
  ]

  it.each(SAMPLES)('keeps the text of %s readable on both themes', (hex) => {
    for (const dark of [false, true]) {
      const t = themeTint(hex, dark)!
      expect(contrastRatio(t.text, t.background)).toBeGreaterThanOrEqual(MIN_TEXT_CONTRAST)
    }
  })

  it('leaves a colour that already reads close to the old 20%-darker shade', () => {
    // blue-600 reads at its first step, so its text is the old recipe's shade.
    expect(themeTint('#2563eb')!.text).toBe('#1e4fbc')
  })

  it('deepens a light colour only as far as it needs', () => {
    const t = themeTint('#facc15')!
    const ratio = contrastRatio(t.text, t.background)
    expect(ratio).toBeGreaterThanOrEqual(MIN_TEXT_CONTRAST)
    expect(ratio).toBeLessThan(6) // not simply black
  })

  it('tints the dark theme with a dark wash, not a white one', () => {
    const t = themeTint('#2563eb', true)!
    expect(contrastRatio(t.background, '#111827')).toBeLessThan(1.5)
    expect(contrastRatio(t.text, t.background)).toBeGreaterThanOrEqual(MIN_TEXT_CONTRAST)
  })

  it('is null for an unset or malformed colour', () => {
    expect(themeTint('')).toBeNull()
    expect(themeTint(null)).toBeNull()
    expect(themeTint('blue')).toBeNull()
    expect(themeTintStyle('blue')).toBeNull()
  })

  it('gives both themes\' tints as custom properties', () => {
    const style = themeTintStyle('#2563eb') as Record<string, string>
    expect(style['--profile-tint-text']).toBe(themeTint('#2563eb')!.text)
    expect(style['--profile-tint-bg-dark']).toBe(themeTint('#2563eb', true)!.background)
  })
})

describe('themeColorAdvice', () => {
  it('says nothing about a colour that shows on both themes', () => {
    expect(themeColorAdvice('#2563eb')).toBeNull()
    expect(themeColorAdvice('#facc15')).toBeNull()
    expect(themeColorAdvice('')).toBeNull()
  })

  it('warns about a near-white or near-black colour', () => {
    expect(themeColorAdvice('#fafafa')).toContain('Very light')
    expect(themeColorAdvice('#0b0f19')).toContain('Very dark')
  })

  it('asks for a hex colour when the value isn\'t one', () => {
    expect(themeColorAdvice('#abc')).toContain('#2563eb')
  })
})
