// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import { driftBreakdown, driftCounts, driftSummary, isDrifting, verifyIncompleteSince } from './syncDrift'

const at = '2026-11-02T10:00:00Z'
const later = '2026-11-02T11:00:00Z'
const snap = (o: object) => ({ at, missing: 0, orphan: 0, mismatch: 0, incompleteAt: null, ...o })

describe('syncDrift', () => {
  it('reads no counts from a never-verified set', () => {
    expect(driftCounts({ verify: null })).toBeNull()
    expect(driftCounts({ verify: snap({ at: null }) })).toBeNull()
    expect(driftSummary({ verify: null }).label).toBe('Not verified')
  })

  it('counts an enabled set with drift as drifting, not a disabled or clean one', () => {
    expect(isDrifting({ enabled: true, verify: snap({ orphan: 3 }) })).toBe(true)
    expect(isDrifting({ enabled: false, verify: snap({ orphan: 3 }) })).toBe(false)
    expect(isDrifting({ enabled: true, verify: snap({}) })).toBe(false)
  })

  it('breaks drift down by kind, leaving out kinds with none', () => {
    expect(driftBreakdown({ missing: 2, orphan: 0, mismatch: 1 })).toBe('2 missing · 1 different')
    expect(driftSummary({ verify: snap({ orphan: 5 }) }).label).toBe('5 orphaned')
    expect(driftSummary({ verify: snap({}) }).label).toBe('In sync')
  })

  it('flags counts a later unfinished verify may have outdated', () => {
    expect(verifyIncompleteSince(snap({ incompleteAt: later }))).toBe(true)
    expect(verifyIncompleteSince(snap({ incompleteAt: '2026-11-02T09:00:00Z' }))).toBe(false)
    const s = driftSummary({ verify: snap({ orphan: 1, incompleteAt: later }) })
    expect(s.label).toBe('1 orphaned (may be out of date)')
    expect(s.title).toContain("couldn't read both directories completely")
  })

  it('shows deletes held for review beside the drift, not as orphans', () => {
    const counts = { APPLIED: 10, REVIEW: 4, REVIEW_DELETE: 3 }
    const s = driftSummary({ verify: snap({}), stateCounts: counts })
    expect(s.label).toBe('In sync · 3 held for delete')
    expect(s.tone).toBe('text-green-700')
    expect(s.title).toContain('held for review; not counted as orphaned')
    expect(driftSummary({ verify: snap({ missing: 1 }), stateCounts: counts }).label).toBe('1 missing · 3 held for delete')
    expect(driftSummary({ verify: null, stateCounts: counts }).label).toBe('Not verified · 3 held for delete')
    expect(driftSummary({ verify: snap({}), stateCounts: { APPLIED: 2 } }).label).toBe('In sync')
  })
})
