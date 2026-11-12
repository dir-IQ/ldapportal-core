// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import { formatDateTime, formatLocalDate, formatTimeOfDay, localTimeZone } from './dateTime'

// The output is locale- and zone-dependent; assert it matches what Intl
// produces for the runtime's local zone, and that the zone label is present.
const ISO = '2026-04-24T15:04:05Z'
const zoneLabel = (d: Date) =>
  new Intl.DateTimeFormat(undefined, { timeZoneName: 'short' })
    .formatToParts(d).find(p => p.type === 'timeZoneName')!.value

describe('formatDateTime', () => {
  it('renders in the local zone with the zone label and seconds', () => {
    const d = new Date(ISO)
    const out = formatDateTime(ISO)
    expect(out).toBe(d.toLocaleString(undefined, {
      year: 'numeric', month: 'short', day: 'numeric',
      hour: 'numeric', minute: '2-digit', second: '2-digit', timeZoneName: 'short',
    }))
    expect(out).toContain(zoneLabel(d))
    expect(out).toContain('05')
  })

  it('omits seconds on request', () => {
    const out = formatDateTime(ISO, { seconds: false })
    expect(out).toContain(zoneLabel(new Date(ISO)))
    expect(out).toBe(new Date(ISO).toLocaleString(undefined, {
      year: 'numeric', month: 'short', day: 'numeric',
      hour: 'numeric', minute: '2-digit', timeZoneName: 'short',
    }))
  })

  it('accepts Date and epoch millis', () => {
    const d = new Date(ISO)
    expect(formatDateTime(d)).toBe(formatDateTime(ISO))
    expect(formatDateTime(d.getTime())).toBe(formatDateTime(ISO))
  })

  it('returns the fallback for empty or invalid input', () => {
    expect(formatDateTime(null)).toBe('')
    expect(formatDateTime(undefined, { fallback: '—' })).toBe('—')
    expect(formatDateTime('', { fallback: '—' })).toBe('—')
    expect(formatDateTime('not-a-date', { fallback: 'x' })).toBe('x')
  })
})

describe('formatTimeOfDay', () => {
  it('renders the local time with the zone label', () => {
    const out = formatTimeOfDay(ISO)
    expect(out).toContain(zoneLabel(new Date(ISO)))
    expect(formatTimeOfDay(null, { fallback: '—' })).toBe('—')
  })
})

describe('formatLocalDate', () => {
  it('renders a zone-less local calendar date', () => {
    const out = formatLocalDate(ISO)
    expect(out).toContain('2026')
    expect(out).not.toContain(zoneLabel(new Date(ISO)))
    expect(formatLocalDate('bad', { fallback: '—' })).toBe('—')
  })
})

describe('localTimeZone', () => {
  it('returns the resolved IANA zone', () => {
    expect(localTimeZone()).toBe(Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC')
  })
})
