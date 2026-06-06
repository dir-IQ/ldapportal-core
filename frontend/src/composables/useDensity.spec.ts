// SPDX-License-Identifier: Apache-2.0
/**
 * Unit tests for useDensity.
 *
 * Verifies persistence (to the preferences store + the pre-paint hint cookie),
 * application to <html>, validation, and the server-sync path. The composable
 * is a singleton (top-level ref + module-load applyDensity) so each test starts
 * by clearing the hint cookie and resetting the data-density attribute.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'

// The composable persists through the preferences store; mock it so the unit
// test doesn't need an active Pinia or a backend.
const writeMock = vi.fn()
vi.mock('@/stores/preferences', () => ({
  usePreferencesStore: () => ({
    read: (_ns: string, _key: string, fallback: unknown) => fallback,
    write: writeMock,
  }),
}))

import { useDensity } from './useDensity'
import { readPrefsHint } from '@/utils/prefsHint'

beforeEach(() => {
  document.cookie = 'prefs-hint=; Path=/; Max-Age=0'
  document.documentElement.removeAttribute('data-density')
  writeMock.mockClear()
})

describe('useDensity', () => {
  it('defaults to comfortable when nothing persisted', () => {
    const { density } = useDensity()
    expect(density.value).toBe('comfortable')
  })

  it('setDensity("compact") persists to the store + hint cookie and applies the attribute', () => {
    const { density, setDensity } = useDensity()
    setDensity('compact')
    expect(density.value).toBe('compact')
    expect(writeMock).toHaveBeenCalledWith('appearance', 'density', 'compact')
    expect(document.documentElement.getAttribute('data-density')).toBe('compact')
    expect(readPrefsHint().density).toBe('compact')
  })

  it('setDensity("comfortable") clears the attribute', () => {
    const { setDensity } = useDensity()
    setDensity('compact')
    expect(document.documentElement.getAttribute('data-density')).toBe('compact')
    setDensity('comfortable')
    expect(document.documentElement.getAttribute('data-density')).toBe(null)
    expect(writeMock).toHaveBeenLastCalledWith('appearance', 'density', 'comfortable')
  })

  it('setDensity ignores invalid values', () => {
    const { density, setDensity } = useDensity()
    const before = density.value
    // @ts-expect-error -- intentional invalid input
    setDensity('cozy')
    expect(density.value).toBe(before)
    expect(writeMock).not.toHaveBeenCalled()
  })

  it('syncFromAccount applies a valid server value without writing back', () => {
    const { density, syncFromAccount } = useDensity()
    syncFromAccount('compact')
    expect(density.value).toBe('compact')
    expect(document.documentElement.getAttribute('data-density')).toBe('compact')
    expect(readPrefsHint().density).toBe('compact')
    // It came from the server — don't echo it back to the server.
    expect(writeMock).not.toHaveBeenCalled()
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
