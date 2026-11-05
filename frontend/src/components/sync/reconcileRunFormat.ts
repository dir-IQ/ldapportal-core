// SPDX-License-Identifier: Apache-2.0
/**
 * Labels and formatting shared by the reconcile-run summary
 * (ReconcileRunHistory) and its detail view (ReconcileRunDetail).
 */
import type { SyncPreviewAction, SyncReconcileRun } from '@/api/sync'

export interface OutcomeCount { label: string; n: number; tone: string }

/** The run's outcome counts in display order, with the tone used when non-zero. */
export function outcomeCounts(r: SyncReconcileRun): OutcomeCount[] {
  return [
    { label: 'Added', n: r.added, tone: 'text-emerald-700' },
    { label: 'Modified', n: r.modified, tone: 'text-blue-700' },
    { label: 'Moved', n: r.moved, tone: 'text-blue-700' },
    { label: 'Deleted', n: r.deleted, tone: 'text-rose-700' },
    { label: 'Held for review', n: r.heldForReview, tone: 'text-amber-700' },
    { label: 'Already matched', n: r.noChange, tone: '' },
    { label: 'Unchanged', n: r.unchanged, tone: '' },
    { label: 'Failed', n: r.failed + r.faulted, tone: 'text-rose-700' },
  ]
}

/** Label of a sampled outcome, as in the counts. */
export const OUTCOME_LABELS: Record<SyncPreviewAction, string> = {
  ADD: 'Added',
  MODIFY: 'Modified',
  MOVE: 'Moved',
  DELETE: 'Deleted',
  REVIEW: 'Held for review',
  NO_CHANGE: 'Already matched',
  FORGET: 'Forgotten',
  UNCHANGED: 'Unchanged',
  ERROR: 'Failed',
}

/** How many entries had an outcome (its sample may hold fewer). */
export function outcomeTotal(r: SyncReconcileRun, action: SyncPreviewAction): number {
  return {
    ADD: r.added, MODIFY: r.modified, MOVE: r.moved, DELETE: r.deleted, REVIEW: r.heldForReview,
    NO_CHANGE: r.noChange, FORGET: r.forgotten, UNCHANGED: r.unchanged, ERROR: r.failed,
  }[action]
}

/** Things worth flagging about a run: a mid-run disable, an incomplete scan, the guard, faults. */
export function runWarnings(r: SyncReconcileRun): string[] {
  const w: string[] = []
  if (r.skipped > 0) {
    w.push(`${r.skipped} entr${r.skipped === 1 ? 'y was' : 'ies were'} skipped: the set or link was `
      + 'disabled while the run was in progress.')
  }
  // An incremental pass applies queued changes; it never scans the whole source.
  if (r.status !== 'RUNNING' && !r.completeScan && !isIncremental(r)) {
    w.push('The source scan did not complete, so no deletions were made.')
  }
  if (r.guardTripped) {
    w.push(`Delete guard tripped${r.guardReason ? `: ${r.guardReason}` : ''}. Deletions were held for review.`)
  }
  if (r.faulted > 0) {
    w.push(`${r.faulted} entr${r.faulted === 1 ? 'y' : 'ies'} hit an error and will be retried on the next run.`)
  }
  return w
}

export function statusLabel(s: SyncReconcileRun['status']): string {
  return { RUNNING: 'Running', COMPLETE: 'Completed', FAILED: 'Failed' }[s]
}

export function statusBadge(s: SyncReconcileRun['status']): string {
  return { RUNNING: 'badge-blue', COMPLETE: 'badge-green', FAILED: 'badge-red' }[s]
}

/** A recompute-worker pass between reconciles, not a reconcile. */
export function isIncremental(r: SyncReconcileRun): boolean {
  return r.trigger === 'INCREMENTAL'
}

export function triggerLabel(r: SyncReconcileRun): string {
  if (r.trigger === 'SCHEDULED') return 'Scheduled'
  if (r.trigger === 'INCREMENTAL') return 'Incremental'
  return r.requestedBy ? `Manual (${r.requestedBy})` : 'Manual'
}

/** "45s", "2m 36s" or "1h 4m" between two ISO timestamps; "—" when either is missing. */
export function elapsed(from: string | null, to: string | null): string {
  if (!from || !to) return '—'
  const secs = Math.max(0, Math.round((Date.parse(to) - Date.parse(from)) / 1000))
  if (secs < 60) return `${secs}s`
  const mins = Math.floor(secs / 60)
  return mins < 60 ? `${mins}m ${secs % 60}s` : `${Math.floor(mins / 60)}h ${mins % 60}m`
}

/** The drift the verification after the run found, or null when none was recorded. */
export function driftSummary(r: SyncReconcileRun): string | null {
  if (!r.verifiedAt) return null
  const total = (r.verifyMissing ?? 0) + (r.verifyOrphan ?? 0) + (r.verifyMismatch ?? 0)
  if (total === 0) return 'Verified after the run: source and target agree.'
  return `Verified after the run: ${r.verifyMissing ?? 0} missing on target · `
    + `${r.verifyOrphan ?? 0} orphaned on target · ${r.verifyMismatch ?? 0} with content drift.`
}

export function errMsg(e: unknown, fallback: string): string {
  const err = e as { response?: { data?: { detail?: string; message?: string } } }
  return err.response?.data?.detail || err.response?.data?.message || fallback
}
