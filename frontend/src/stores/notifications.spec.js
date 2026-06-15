// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useNotificationStore } from './notifications'

describe('useNotificationStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  describe('errors are sticky by default', () => {
    it('error() does NOT auto-dismiss after the default success duration', () => {
      const store = useNotificationStore()
      store.error('Something blew up')
      expect(store.items).toHaveLength(1)

      // Run well past any reasonable auto-dismiss timeout — 30s.
      vi.advanceTimersByTime(30_000)

      expect(store.items).toHaveLength(1)
      expect(store.items[0].message).toBe('Something blew up')
    })

    it('success() still auto-dismisses on its 4s default', () => {
      const store = useNotificationStore()
      store.success('Saved')
      expect(store.items).toHaveLength(1)

      vi.advanceTimersByTime(4_100)
      expect(store.items).toHaveLength(0)
    })

    it('error() with explicit duration still respects the override', () => {
      const store = useNotificationStore()
      store.error('Transient hiccup', { duration: 1_000 })
      expect(store.items).toHaveLength(1)

      vi.advanceTimersByTime(1_100)
      expect(store.items).toHaveLength(0)
    })
  })

  describe('cap-at-5 prefers dropping non-errors over errors', () => {
    it('a flood of success toasts cannot push out an unacknowledged error', () => {
      const store = useNotificationStore()
      store.error('Critical: db down')
      // 10 successes — far past the cap of 5. Every overflow must drop
      // a success, never the error.
      for (let i = 0; i < 10; i++) {
        store.success(`note ${i}`, { duration: 0 }) // duration=0 so they don't auto-clear during the test
      }

      expect(store.items).toHaveLength(5)
      expect(store.items.find(n => n.type === 'error')?.message).toBe('Critical: db down')
    })

    it('falls back to dropping oldest error when all 5 are errors and a 6th arrives', () => {
      const store = useNotificationStore()
      for (let i = 0; i < 5; i++) store.error(`err ${i}`)
      expect(store.items).toHaveLength(5)
      expect(store.items[0].message).toBe('err 0')

      store.error('err 5')

      expect(store.items).toHaveLength(5)
      // Oldest error (err 0) was dropped to make room for err 5.
      expect(store.items.map(n => n.message)).toEqual(['err 1', 'err 2', 'err 3', 'err 4', 'err 5'])
    })
  })

  describe('explicit acknowledgement', () => {
    it('remove(id) clears a sticky error', () => {
      const store = useNotificationStore()
      const id = store.error('Acknowledge me')
      store.remove(id)
      expect(store.items).toHaveLength(0)
    })
  })
})
