// SPDX-License-Identifier: Apache-2.0
/**
 * Unit tests for useDensity.
 *
 * Verifies persistence, application to <html>, validation, and the
 * server-sync stub. The composable is a singleton (top-level ref + module-
 * load applyDensity) so each test starts by clearing localStorage and
 * resetting the data-density attribute.
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { useDensity } from './useDensity'

const STORAGE_KEY = 'ldapportal-density'

beforeEach(() => {
  localStorage.clear()
  document.documentElement.removeAttribute('data-density')
})

describe('useDensity', () => {
  it('defaults to comfortable when nothing persisted', () => {
    const { density } = useDensity()
    expect(density.value).toBe('comfortable')
  })

  it('setDensity("compact") persists and applies the attribute', () => {
    const { density, setDensity } = useDensity()
    setDensity('compact')
    expect(density.value).toBe('compact')
    expect(localStorage.getItem(STORAGE_KEY)).toBe('compact')
    expect(document.documentElement.getAttribute('data-density')).toBe('compact')
  })

  it('setDensity("comfortable") clears the attribute', () => {
    const { setDensity } = useDensity()
    setDensity('compact')
    expect(document.documentElement.getAttribute('data-density')).toBe('compact')
    setDensity('comfortable')
    expect(document.documentElement.getAttribute('data-density')).toBe(null)
    expect(localStorage.getItem(STORAGE_KEY)).toBe('comfortable')
  })

  it('setDensity ignores invalid values', () => {
    const { density, setDensity } = useDensity()
    const before = density.value
    // @ts-expect-error -- intentional invalid input
    setDensity('cozy')
    expect(density.value).toBe(before)
    expect(localStorage.getItem(STORAGE_KEY)).toBe(null)
  })

  it('syncFromAccount applies a valid server value', () => {
    const { density, syncFromAccount } = useDensity()
    syncFromAccount('compact')
    expect(density.value).toBe('compact')
    expect(document.documentElement.getAttribute('data-density')).toBe('compact')
    expect(localStorage.getItem(STORAGE_KEY)).toBe('compact')
  })

  it('syncFromAccount ignores null/undefined/invalid values', () => {
    const { setDensity, syncFromAccount, density } = useDensity()
    setDensity('compact')
    syncFromAccount(null)
    expect(density.value).toBe('compact')  // unchanged
    syncFromAccount(undefined)
    expect(density.value).toBe('compact')
    syncFromAccount('huge')
    expect(density.value).toBe('compact')
  })

  it('two callers share the same density ref (singleton)', () => {
    const a = useDensity()
    const b = useDensity()
    a.setDensity('compact')
    expect(b.density.value).toBe('compact')
  })
})
