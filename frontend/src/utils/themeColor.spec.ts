// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import { isLightColor, onThemeTitleClass, onThemeMutedClass } from './themeColor'

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
