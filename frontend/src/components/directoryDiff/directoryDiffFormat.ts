// SPDX-License-Identifier: Apache-2.0
import type {
  DirectoryDiffCategory, DirectoryDiffCounts, DirectoryDiffPhase, DirectoryDiffRun, DirectoryDiffSide,
} from '@/api/directoryDiff'

export const CATEGORIES: DirectoryDiffCategory[] = ['DIFFERENT', 'ONLY_A', 'ONLY_B', 'SAME']

export const CATEGORY_LABELS: Record<DirectoryDiffCategory, string> = {
  SAME: 'Same',
  DIFFERENT: 'Different',
  ONLY_A: 'Only in A',
  ONLY_B: 'Only in B',
}

export const CATEGORY_HINTS: Record<DirectoryDiffCategory, string> = {
  SAME: 'Paired, and every compared attribute holds the same values on both sides.',
  DIFFERENT: 'Paired, but at least one compared attribute differs.',
  ONLY_A: 'No partner on side B.',
  ONLY_B: 'No partner on side A.',
}

export const PHASE_LABELS: Record<DirectoryDiffPhase, string> = {
  READING_A: 'Reading side A',
  READING_B: 'Reading side B',
  COMPARING: 'Comparing entries',
  DONE: 'Done',
}

export function countFor(counts: DirectoryDiffCounts, category: DirectoryDiffCategory): number {
  switch (category) {
    case 'SAME': return counts.same
    case 'DIFFERENT': return counts.different
    case 'ONLY_A': return counts.onlyA
    case 'ONLY_B': return counts.onlyB
  }
}

export function totalEntries(counts: DirectoryDiffCounts): number {
  return counts.same + counts.different + counts.onlyA + counts.onlyB
}

/** "People (prod-east) ↔ People (prod-west)", or the base DNs when both sides are one directory. */
export function pairLabel(run: Pick<DirectoryDiffRun, 'a' | 'b'>): string {
  if (run.a.directoryId === run.b.directoryId) {
    return `${run.a.directoryName}: ${run.a.baseDn} ↔ ${run.b.baseDn}`
  }
  return `${run.a.directoryName} ↔ ${run.b.directoryName}`
}

export function sideSummary(side: DirectoryDiffSide): string {
  const parts = [side.baseDn, side.scope === 'ONE' ? 'one level' : 'subtree']
  if (side.filter && side.filter !== '(objectClass=*)') parts.push(side.filter)
  return parts.join(' · ')
}

export function pairingLabel(run: Pick<DirectoryDiffRun, 'pairBy' | 'pairAttribute'>): string {
  return run.pairBy === 'ATTRIBUTE' ? `paired by ${run.pairAttribute}` : 'paired by DN'
}

export function formatTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  const now = new Date()
  const time = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
  if (d.toDateString() === now.toDateString()) return `Today ${time}`
  const yesterday = new Date(now)
  yesterday.setDate(now.getDate() - 1)
  if (d.toDateString() === yesterday.toDateString()) return `Yesterday ${time}`
  return `${d.toLocaleDateString()} ${time}`
}

export function formatDuration(fromIso: string, toIso?: string | null): string {
  const ms = Math.max(0, (toIso ? new Date(toIso) : new Date()).getTime() - new Date(fromIso).getTime())
  const s = Math.round(ms / 1000)
  if (s < 60) return `${s} s`
  const m = Math.floor(s / 60)
  return `${m} min ${s % 60} s`
}

export function n(value: number): string {
  return value.toLocaleString()
}

/** Attribute names separated by commas or spaces, trimmed, without blanks. */
export function parseNames(text: string): string[] {
  return text.split(/[\s,]+/).map(s => s.trim()).filter(Boolean)
}

export function errMsg(e: unknown, fallback: string): string {
  const err = e as {
    response?: { data?: { detail?: string, message?: string } },
    message?: string,
  }
  return err.response?.data?.detail || err.response?.data?.message || err.message || fallback
}

/**
 * A failed blob download carries its error body as a Blob; read it as JSON so
 * {@link errMsg} can show the server's message.
 */
export async function withBlobBody(e: unknown): Promise<unknown> {
  const err = e as { response?: { data?: unknown } }
  const data = err.response?.data
  if (!(data instanceof Blob)) return e
  try {
    return { ...err, response: { ...err.response, data: JSON.parse(await data.text()) } }
  } catch {
    return e
  }
}
