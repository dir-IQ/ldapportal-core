// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useQuickLookStore } from './quickLook'

const A = 'uid=a,ou=people,dc=x'
const B = 'cn=g,ou=groups,dc=x'
const C = 'uid=c,ou=people,dc=x'

describe('quickLook store', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('opens, pushes a trail, steps back and closes', () => {
    const s = useQuickLookStore()
    expect(s.isOpen).toBe(false)

    s.open('d1', A)
    expect(s.current).toEqual({ directoryId: 'd1', dn: A })
    expect(s.canGoBack).toBe(false)

    s.push('d1', B)
    s.push('d1', C)
    expect(s.stack.map(t => t.dn)).toEqual([A, B, C])

    s.back()
    expect(s.current?.dn).toBe(B)

    s.close()
    expect(s.isOpen).toBe(false)
  })

  it('opening from the page starts a fresh trail', () => {
    const s = useQuickLookStore()
    s.open('d1', A)
    s.push('d1', B)
    s.open('d1', C)
    expect(s.stack.map(t => t.dn)).toEqual([C])
  })

  it('ignores a push of the entry already shown (case-insensitively)', () => {
    const s = useQuickLookStore()
    s.open('d1', A)
    s.push('d1', A.toUpperCase())
    expect(s.stack).toHaveLength(1)
  })

  it('jumps back to a breadcrumb', () => {
    const s = useQuickLookStore()
    s.open('d1', A)
    s.push('d1', B)
    s.push('d1', C)
    s.goTo(0)
    expect(s.stack.map(t => t.dn)).toEqual([A])
  })
})
