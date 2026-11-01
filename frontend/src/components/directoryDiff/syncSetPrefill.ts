// SPDX-License-Identifier: Apache-2.0
// "Verify a sync set": the directory diff settings that check what a sync set
// should have produced on its target, and the reasons behind each choice.
import type { SyncLink, SyncSet } from '@/api/sync'
import type { DirectoryDiffScope } from '@/api/directoryDiff'

export interface SyncSetSide {
  directoryId: string
  baseDn: string
  scope: DirectoryDiffScope
  filter: string
}

export interface SyncSetPrefill {
  a: SyncSetSide
  b: SyncSetSide
  /** Attributes left out because the set doesn't copy them as they are. */
  ignore: string[]
  /** One line per choice, shown under the picker. */
  notes: string[]
}

/** What the form knows about a directory: enough to tell whether the diff can read it. */
export interface DirectoryInfo {
  displayName?: string
  directoryType?: string
  enabled?: boolean
}

/**
 * Why a set can't be verified this way, or null when it can. The diff reads
 * only enabled LDAP directories, and compares a subtree or one level.
 *
 * @param directory looks up a directory by id; undefined when it isn't known
 */
export function unsupportedReason(set: SyncSet, link: SyncLink,
                                  directory: (id: string) => DirectoryInfo | undefined): string | null {
  if (!set.objectScopeBaseDn) return 'it has no source base DN'
  if (set.objectScope === 'BASE') return 'it syncs a single entry (base scope)'
  for (const [side, id] of [['source', link.sourceDirId], ['target', link.targetDirId]] as const) {
    const d = directory(id)
    if (!d) return `its ${side} directory isn’t available`
    if (d.directoryType === 'ENTRA_ID') return `its ${side} directory${d.displayName ? ` (${d.displayName})` : ''} is Entra ID`
    if (d.enabled === false) return `its ${side} directory${d.displayName ? ` (${d.displayName})` : ''} is disabled`
  }
  return null
}

/**
 * The comparison that checks `set`: its source scope on side A, where sync
 * places those entries on side B, paired by DN the way sync places them
 * (A's base swapped for the target base). Attributes the set never copies,
 * renames or rewrites are ignored, and so is the anchor it stamps on targets.
 *
 * @param defaultExcluded the engine's exclusions, used when the set has none of its own
 */
export function prefillFromSyncSet(set: SyncSet, link: SyncLink, defaultExcluded: string[]): SyncSetPrefill {
  const scope: DirectoryDiffScope = set.objectScope === 'ONE' ? 'ONE' : 'SUB'
  const sourceBase = set.objectScopeBaseDn ?? ''
  const targetBase = set.targetBaseDn || sourceBase
  const anchor = set.sourceAnchorAttribute?.trim() || null
  // Sync never copies the identity attribute: the anchor carries the identity
  // instead. The defaults (entryUUID, objectGUID) are operational and left out
  // anyway; a set's own identity key would otherwise differ on every entry.
  const identityKey = set.identityKey?.trim() || null
  const notes: string[] = []

  const excluded = set.excludedAttributes ?? defaultExcluded
  const rewritten: string[] = []
  for (const rule of set.transformRules ?? []) {
    if (rule.sourceAttr) rewritten.push(rule.sourceAttr)
    if (rule.targetAttr) rewritten.push(rule.targetAttr)
  }
  const ignore = unique([...excluded, ...rewritten, ...(anchor ? [anchor] : []),
    ...(identityKey ? [identityKey] : [])])

  if (set.applicabilityFilter) notes.push('Side A: only entries matching the set’s applicability filter.')
  if (anchor) {
    notes.push(`Side B: only entries the sync stamped with ${anchor}, so entries it doesn’t manage stay out of Only in B. `
      + `Entries another set stamps with ${anchor} under the same target base are still included.`)
  } else {
    notes.push('Side B: every entry under the target base. The set writes no source anchor, so entries the sync doesn’t manage show under Only in B.')
  }
  notes.push(targetBase.toLowerCase() === sourceBase.toLowerCase()
    ? 'Paired by DN: the set places entries at the same DN.'
    : 'Paired by DN, with the source base swapped for the target base, as the set places entries.')
  if (excluded.length) {
    notes.push(`Ignoring ${excluded.length} attribute${excluded.length === 1 ? '' : 's'} the set doesn’t copy.`)
  }
  if (identityKey) {
    notes.push(`Ignoring ${identityKey}: the set’s identity key, which sync never copies (${anchor ?? 'the anchor'} carries the identity instead).`)
  }
  if (set.deletePolicy === 'REVIEW') {
    notes.push('Entries that left the set but whose delete is held for review are still on the target, so they show under Only in B.')
  }
  if (rewritten.length) {
    notes.push(`Ignoring ${unique(rewritten).join(', ')}: the set renames or rewrites them, so they’d always differ.`)
  }

  return {
    a: { directoryId: link.sourceDirId, baseDn: sourceBase, scope, filter: set.applicabilityFilter ?? '' },
    b: { directoryId: link.targetDirId, baseDn: targetBase, scope, filter: anchor ? `(${anchor}=*)` : '' },
    ignore,
    notes,
  }
}

function unique(names: string[]): string[] {
  const seen = new Set<string>()
  const out: string[] = []
  for (const name of names) {
    const key = name.trim().toLowerCase()
    if (key && !seen.has(key)) {
      seen.add(key)
      out.push(name.trim())
    }
  }
  return out
}
