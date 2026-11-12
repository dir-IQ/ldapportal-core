// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import type { SyncDriftAttribute } from '@/api/sync'
import { CHAR_DIFF_MAX, charDiff, onlyIndexes, shownValues, visibleWhitespace } from './valueDiff'

const attr = (o: Partial<SyncDriftAttribute>): SyncDriftAttribute =>
  ({ attribute: 'x', expected: [], actual: [], hidden: false, truncated: false, ...o })

const changed = (segs: { text: string; changed: boolean }[] | undefined) =>
  (segs ?? []).filter(s => s.changed).map(s => s.text)

describe('valueDiff', () => {
  it('uses the flags the verify recorded, which compare as the sync does', () => {
    // Same member as DNs, so not flagged even though the text differs.
    const a = attr({ expected: ['uid=b,dc=t', 'uid=a,dc=t'], actual: ['UID=A, DC=T'], expectedOnly: [0], actualOnly: [] })
    expect([...onlyIndexes(a, 'expected')]).toEqual([0])
    expect([...onlyIndexes(a, 'actual')]).toEqual([])
  })

  it('falls back to exact text for items stored without flags', () => {
    const a = attr({ expected: ['a', 'b'], actual: ['b', 'c'] })
    expect([...onlyIndexes(a, 'expected')]).toEqual([0])
    expect([...onlyIndexes(a, 'actual')]).toEqual([1])
  })

  it('diffs characters only when one value replaced another', () => {
    const single = attr({ expected: ['Jon Smith'], actual: ['John Smith'] })
    expect(changed(shownValues(single, 'actual')[0].segments ?? undefined)).toEqual(['h'])
    expect(shownValues(single, 'expected')[0].segments?.every(s => !s.changed)).toBe(true)

    const multi = attr({ expected: ['a', 'b', 'x'], actual: ['a', 'y', 'z'] })
    expect(shownValues(multi, 'expected').map(v => [v.only, v.segments])).toEqual([[false, null], [true, null], [true, null]])
  })

  it('reads a changed value as one change, not scattered letters', () => {
    const d = charDiff('Engineer', 'Lead')!
    expect(changed(d.expected)).toEqual(['Engineer'])
    expect(changed(d.actual)).toEqual(['Lead'])
  })

  it('finds separate changes in a longer value', () => {
    const d = charDiff('+1 555 0100 ext 12', '+1 555 0199 ext 12')!
    expect(changed(d.expected)).toEqual(['00'])
    expect(changed(d.actual)).toEqual(['99'])
  })

  it('skips identical, binary and very long values', () => {
    expect(charDiff('a', 'a')).toBeNull()
    expect(charDiff('(binary, 3 bytes)', '(binary, 4 bytes)')).toBeNull()
    expect(charDiff('a'.repeat(CHAR_DIFF_MAX + 1), 'b')).toBeNull()
  })

  it('makes a whitespace-only change visible', () => {
    const d = charDiff('Jane  Doe', 'Jane Doe')!
    expect(changed(d.expected).map(visibleWhitespace)).toEqual(['·'])
    expect(visibleWhitespace('a b')).toBe('a b')
    expect(visibleWhitespace('\t\n')).toBe('→⏎')
  })
})
