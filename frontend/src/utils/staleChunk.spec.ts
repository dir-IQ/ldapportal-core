// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi } from 'vitest'
import { isChunkLoadError, reloadForStaleChunk, RELOAD_KEY, RELOAD_WINDOW_MS } from './staleChunk'

function memoryStorage(initial: Record<string, string> = {}) {
  const data = { ...initial }
  return {
    getItem: (k: string) => (k in data ? data[k] : null),
    setItem: (k: string, v: string) => { data[k] = v },
    data,
  }
}

describe('isChunkLoadError', () => {
  it.each([
    'Failed to fetch dynamically imported module: http://x/assets/DashboardView-abc.js',
    'error loading dynamically imported module: http://x/assets/AppLayout-abc.js',
    'Importing a module script failed.',
    "'text/html' is not a valid JavaScript MIME type.",
  ])('recognises %s', (message) => {
    expect(isChunkLoadError(new TypeError(message))).toBe(true)
  })

  it('recognises a ChunkLoadError by name', () => {
    expect(isChunkLoadError({ name: 'ChunkLoadError', message: '' })).toBe(true)
  })

  it('ignores other errors', () => {
    expect(isChunkLoadError(new Error('Request failed with status code 500'))).toBe(false)
    expect(isChunkLoadError(null)).toBe(false)
    expect(isChunkLoadError('Failed to fetch dynamically imported module')).toBe(false)
  })
})

describe('reloadForStaleChunk', () => {
  it('hard-navigates to the target and records the attempt', () => {
    const storage = memoryStorage()
    const navigate = vi.fn()
    expect(reloadForStaleChunk('/dashboard', { storage, navigate, now: () => 50_000 })).toBe(true)
    expect(navigate).toHaveBeenCalledWith('/dashboard')
    expect(storage.data[RELOAD_KEY]).toBe('50000')
  })

  it('does not reload again inside the window, so a missing chunk cannot loop', () => {
    const storage = memoryStorage({ [RELOAD_KEY]: '50000' })
    const navigate = vi.fn()
    expect(reloadForStaleChunk('/dashboard', { storage, navigate, now: () => 50_000 + RELOAD_WINDOW_MS - 1 })).toBe(false)
    expect(navigate).not.toHaveBeenCalled()
  })

  it('reloads again once the window has passed', () => {
    const storage = memoryStorage({ [RELOAD_KEY]: '50000' })
    const navigate = vi.fn()
    expect(reloadForStaleChunk('/idm/users', { storage, navigate, now: () => 50_000 + RELOAD_WINDOW_MS })).toBe(true)
    expect(navigate).toHaveBeenCalledWith('/idm/users')
  })

  it('still reloads when storage is unavailable', () => {
    const navigate = vi.fn()
    expect(reloadForStaleChunk('/dashboard', { storage: null, navigate })).toBe(true)
    expect(navigate).toHaveBeenCalledOnce()
  })
})
