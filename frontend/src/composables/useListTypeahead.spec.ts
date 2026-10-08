// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, afterEach } from 'vitest'
import { useListTypeahead } from './useListTypeahead'

const items = ['account', 'inetLocalMailRecipient', 'inetOrgPerson', 'organizationalPerson']

function key(k: string, mods: Partial<KeyboardEvent> = {}): KeyboardEvent {
  return new KeyboardEvent('keydown', { key: k, cancelable: true, ...mods })
}

describe('useListTypeahead', () => {
  afterEach(() => vi.useRealTimers())

  it('jumps to the first item starting with the typed letters', () => {
    const t = useListTypeahead()
    expect(t.handleKey(key('i'), items)).toBe('inetLocalMailRecipient')
    expect(t.handleKey(key('n'), items)).toBe('inetLocalMailRecipient')
    expect(t.handleKey(key('E'), items)).toBe('inetLocalMailRecipient')
    expect(t.handleKey(key('t'), items)).toBe('inetLocalMailRecipient')
    expect(t.handleKey(key('o'), items)).toBe('inetOrgPerson')
    expect(t.typed.value).toBe('ineto')
  })

  it('returns null when nothing matches and clears after a pause', () => {
    vi.useFakeTimers()
    const t = useListTypeahead(500)
    expect(t.handleKey(key('z'), items)).toBeNull()
    vi.advanceTimersByTime(600)
    expect(t.typed.value).toBe('')
    expect(t.handleKey(key('o'), items)).toBe('organizationalPerson')
  })

  it('ignores navigation keys and modified keys', () => {
    const t = useListTypeahead()
    expect(t.handleKey(key('ArrowDown'), items)).toBeUndefined()
    expect(t.handleKey(key('a', { ctrlKey: true }), items)).toBeUndefined()
    expect(t.handleKey(key(' '), items)).toBeUndefined()
    expect(t.typed.value).toBe('')
  })

  it('clears the buffer on Escape', () => {
    const t = useListTypeahead()
    t.handleKey(key('i'), items)
    t.handleKey(key('Escape'), items)
    expect(t.typed.value).toBe('')
  })
})
