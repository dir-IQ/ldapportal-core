// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import * as api from '@/api/attributeSyntax'
import { useAttributeSyntaxStore } from './attributeSyntax'

vi.mock('@/api/attributeSyntax', () => ({ getAttributeSyntaxHints: vi.fn() }))

const HINTS = {
  wellKnownAttributes: { manager: 'DN', mail: 'EMAIL' },
  inputTypeSyntax: { DN_LOOKUP: 'DN', BOOLEAN: 'BOOLEAN' },
}

describe('useAttributeSyntaxStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(api.getAttributeSyntaxHints).mockReset()
  })

  it('fetches hints once and shares the in-flight request', async () => {
    vi.mocked(api.getAttributeSyntaxHints).mockResolvedValue({ data: HINTS } as never)
    const store = useAttributeSyntaxStore()

    await Promise.all([store.ensureLoaded(), store.ensureLoaded()])
    await store.ensureLoaded()

    expect(api.getAttributeSyntaxHints).toHaveBeenCalledTimes(1)
    expect(store.hints).toEqual(HINTS)
  })

  it('resolves a field kind against the loaded hints', async () => {
    vi.mocked(api.getAttributeSyntaxHints).mockResolvedValue({ data: HINTS } as never)
    const store = useAttributeSyntaxStore()
    await store.ensureLoaded()

    expect(store.kindFor('TEXT', 'mail')).toBe('EMAIL')
    expect(store.kindFor('DN_LOOKUP', 'x')).toBe('DN')
    expect(store.kindFor('TEXT', 'cn')).toBeNull()
  })

  it('degrades gracefully when the fetch fails', async () => {
    vi.mocked(api.getAttributeSyntaxHints).mockRejectedValue(new Error('403'))
    const store = useAttributeSyntaxStore()
    await store.ensureLoaded()

    expect(store.hints).toEqual({ wellKnownAttributes: {}, inputTypeSyntax: {} })
    // Input-type fallback still works without a well-known map.
    expect(store.kindFor('DN_LOOKUP', 'x')).toBe('DN')
    expect(store.kindFor('TEXT', 'mail')).toBeNull()
  })
})
