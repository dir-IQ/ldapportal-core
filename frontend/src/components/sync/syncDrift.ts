// SPDX-License-Identifier: Apache-2.0
// A sync set's stored drift snapshot (its last complete content verify), as the
// sync page's Drift column and drifting-sets list show it.
import type { SyncSet, SyncVerifySnapshot } from '@/api/sync'
import { formatDateTime } from '@/utils/dateTime'

export interface DriftCounts {
  missing: number
  orphan: number
  mismatch: number
}

/** The snapshot's counts, or null when the set was never verified. */
export function driftCounts(set: Pick<SyncSet, 'verify'>): DriftCounts | null {
  const v = set.verify
  if (!v?.at) return null
  return { missing: v.missing ?? 0, orphan: v.orphan ?? 0, mismatch: v.mismatch ?? 0 }
}

export function driftTotal(c: DriftCounts | null): number {
  return c ? c.missing + c.orphan + c.mismatch : 0
}

/** An enabled set whose last complete verify found drift (what the dashboard counts). */
export function isDrifting(set: Pick<SyncSet, 'verify' | 'enabled'>): boolean {
  return set.enabled && driftTotal(driftCounts(set)) > 0
}

/** "2 missing · 3 orphaned · 1 different", leaving out the kinds with none. */
export function driftBreakdown(c: DriftCounts): string {
  const parts: string[] = []
  if (c.missing) parts.push(`${c.missing.toLocaleString()} missing`)
  if (c.orphan) parts.push(`${c.orphan.toLocaleString()} orphaned`)
  if (c.mismatch) parts.push(`${c.mismatch.toLocaleString()} different`)
  return parts.join(' · ')
}

/**
 * Whether a verify newer than the stored counts didn't finish reading both
 * directories, so the counts may be out of date.
 */
export function verifyIncompleteSince(v: SyncVerifySnapshot | null | undefined): boolean {
  if (!v?.incompleteAt) return false
  return !v.at || new Date(v.incompleteAt).getTime() > new Date(v.at).getTime()
}

export interface DriftSummary {
  label: string
  tone: string
  /** Hover text: when the counts are from, and whether a later check didn't finish. */
  title: string
}

/**
 * Deletes held for review right now (the live index, not the snapshot). The
 * verify doesn't count them as orphans, so they're shown beside the drift
 * rather than silently disappearing.
 */
export function heldDeletes(set: Partial<Pick<SyncSet, 'stateCounts'>>): number {
  return set.stateCounts?.REVIEW_DELETE ?? 0
}

export function driftSummary(set: Pick<SyncSet, 'verify'> & Partial<Pick<SyncSet, 'stateCounts'>>): DriftSummary {
  const held = heldDeletes(set)
  const heldLabel = held ? ` · ${held.toLocaleString()} held for delete` : ''
  const heldNote = held
    ? ` ${held.toLocaleString()} ${held === 1 ? 'entry has' : 'entries have'} left the source scope with the delete `
      + 'held for review; not counted as orphaned.'
    : ''
  const c = driftCounts(set)
  const stale = verifyIncompleteSince(set.verify)
  const staleNote = stale && set.verify?.incompleteAt
    ? ` A later verify (${formatDateTime(set.verify.incompleteAt)}) couldn't read both directories completely, so this may be out of date.`
    : ''
  if (!c) {
    return {
      label: (stale ? 'Not verified (check didn’t finish)' : 'Not verified') + heldLabel,
      tone: stale ? 'text-amber-700' : 'text-gray-400',
      title: 'No complete content verify yet. It runs after each reconcile, or use Verify contents.' + staleNote + heldNote,
    }
  }
  const when = `Last complete content verify: ${formatDateTime(set.verify!.at!)}.`
  const total = driftTotal(c)
  return {
    label: (total ? driftBreakdown(c) : 'In sync') + (stale ? ' (may be out of date)' : '') + heldLabel,
    tone: total ? 'text-amber-800' : stale ? 'text-amber-700' : 'text-green-700',
    title: when + staleNote + heldNote,
  }
}
