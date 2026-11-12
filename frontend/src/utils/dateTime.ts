// SPDX-License-Identifier: Apache-2.0

/**
 * Shared timestamp formatting for the UI. The backend sends instants
 * (ISO-8601 with an offset or `Z`); every date+time an operator sees is
 * rendered in the browser's local time zone and carries that zone's short
 * label ("Nov 12, 2026, 3:04:05 PM EST"), so a time is never ambiguous when
 * it's copied into a ticket or compared against a server log.
 *
 * Date-only values (license expiry, "password last changed") are local
 * calendar dates and stay zone-less — see {@link formatLocalDate}.
 */

export type DateInput = string | number | Date | null | undefined

export interface DateTimeOptions {
  /** Include seconds (default true — audit-style precision). */
  seconds?: boolean
  /** Returned for null/empty/unparseable input (default ''). */
  fallback?: string
}

function toDate(value: DateInput): Date | null {
  if (value === null || value === undefined || value === '') return null
  const d = value instanceof Date ? value : new Date(value)
  return isNaN(d.getTime()) ? null : d
}

/** The operator's IANA time zone (e.g. "America/New_York"), or 'UTC' if unknown. */
export function localTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
  } catch {
    return 'UTC'
  }
}

/** Full local date + time + zone label: "Nov 12, 2026, 3:04:05 PM EST". */
export function formatDateTime(value: DateInput, opts: DateTimeOptions = {}): string {
  const d = toDate(value)
  if (!d) return opts.fallback ?? ''
  return d.toLocaleString(undefined, {
    year: 'numeric', month: 'short', day: 'numeric',
    hour: 'numeric', minute: '2-digit',
    ...(opts.seconds === false ? {} : { second: '2-digit' }),
    timeZoneName: 'short',
  })
}

/** Local time of day + zone label: "3:04 PM EST". */
export function formatTimeOfDay(value: DateInput, opts: DateTimeOptions = {}): string {
  const d = toDate(value)
  if (!d) return opts.fallback ?? ''
  return d.toLocaleTimeString(undefined, {
    hour: 'numeric', minute: '2-digit',
    ...(opts.seconds ? { second: '2-digit' } : {}),
    timeZoneName: 'short',
  })
}

/** Local calendar date, no time or zone: "Nov 12, 2026". */
export function formatLocalDate(value: DateInput, opts: Pick<DateTimeOptions, 'fallback'> = {}): string {
  const d = toDate(value)
  if (!d) return opts.fallback ?? ''
  return d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })
}
