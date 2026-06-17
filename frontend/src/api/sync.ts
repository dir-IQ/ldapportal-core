// SPDX-License-Identifier: Apache-2.0
import client from './client'

/**
 * Directory-sync engine admin API. Mirrors {@code SyncLinkController} /
 * {@code SyncSetController}: CRUD for links and sync sets, the membership
 * inventory, and operator triggers (reconcile / recompute / dismiss a
 * quarantine). All endpoints are superadmin + DIRECTORY_SYNC gated.
 */

export type SyncCaptureMode = 'APP_INTERCEPT' | 'CHANGELOG'
export type SyncScope = 'BASE' | 'ONE' | 'SUB'
export type SyncDeletePolicy = 'DELETE' | 'REVIEW'
export type MembershipState = 'APPLIED' | 'PENDING' | 'FAILED' | 'REVIEW'
export type ChangelogFormat = 'DSEE_CHANGELOG' | 'OPENLDAP_ACCESSLOG' | 'AD_DIRSYNC'
export type SyncChangelogHealth =
  | 'HEALTHY' | 'LAGGING' | 'STALLED' | 'GAP_DETECTED' | 'CURSOR_RESET' | 'DISABLED_CONFIG_ERROR'

export interface SyncLink {
  id: string
  displayName: string
  sourceDirId: string
  targetDirId: string
  enabled: boolean
  captureMode: SyncCaptureMode
  changelogFormat: ChangelogFormat | null
  changelogBaseDn: string | null
  changelogHealth: SyncChangelogHealth | null
  changelogLastChangeNumber: number | null
  changelogSourceLastChangeNumber: number | null
  changelogLastPolledAt: string | null
  changelogLastError: string | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface SyncLinkPayload {
  displayName: string
  sourceDirId: string
  targetDirId: string
  enabled: boolean
  captureMode: SyncCaptureMode
  changelogFormat: ChangelogFormat | null
  changelogBaseDn: string | null
}

export interface SyncTransformRule {
  sourceAttr: string
  // null target ⇒ keep the source attribute name (engine semantics).
  targetAttr: string | null
  valueTemplate: string | null
}

export interface SyncSet {
  id: string
  linkId: string
  name: string
  objectScopeBaseDn: string | null
  objectScope: SyncScope | null
  identityKey: string | null
  targetBaseDn: string | null
  applicabilityFilter: string | null
  referenceAttributes: string | null
  sourceAnchorAttribute: string | null
  deletePolicy: SyncDeletePolicy
  transformRules: SyncTransformRule[] | null
  // Attributes never copied from source nor deleted from target. null ⇒ engine
  // defaults (operational + password values); a list (incl. []) is used verbatim.
  excludedAttributes: string[] | null
  reconcileCadenceSeconds: number | null
  reconcileLastRunAt: string | null
  enabled: boolean
  createdAt: string
  updatedAt: string
  version: number
  // Membership counts keyed by state name (APPLIED/PENDING/FAILED/REVIEW); the
  // list endpoint populates this, single-set responses leave it empty.
  stateCounts: Record<string, number>
}

export type SyncSetPayload = Omit<
  SyncSet,
  'id' | 'reconcileLastRunAt' | 'createdAt' | 'updatedAt' | 'version' | 'stateCounts'
>

export interface Membership {
  syncSetId: string
  identity: string
  sourceDn: string
  targetDn: string
  state: MembershipState
  failReason: string | null
  lastSrcCursor: number | null
  lastScanEpoch: number | null
}

// ── Links ──────────────────────────────────────────────────────────────────
export const listSyncLinks = () => client.get<SyncLink[]>('/superadmin/sync/links')
export const createSyncLink = (data: SyncLinkPayload) =>
  client.post<SyncLink>('/superadmin/sync/links', data)
export const updateSyncLink = (id: string, data: SyncLinkPayload) =>
  client.put<SyncLink>(`/superadmin/sync/links/${id}`, data)
export const deleteSyncLink = (id: string) => client.delete(`/superadmin/sync/links/${id}`)

// ── Sync sets ────────────────────────────────────────────────────────────────
export const listSyncSets = (linkId?: string) =>
  client.get<SyncSet[]>('/superadmin/sync/sets', { params: linkId ? { linkId } : {} })
export const createSyncSet = (data: SyncSetPayload) =>
  client.post<SyncSet>('/superadmin/sync/sets', data)
export const updateSyncSet = (id: string, data: SyncSetPayload) =>
  client.put<SyncSet>(`/superadmin/sync/sets/${id}`, data)
export const deleteSyncSet = (id: string) => client.delete(`/superadmin/sync/sets/${id}`)

/** Default excluded attributes (operational + password values) — seeds the editor. */
export const getExcludedAttributeDefaults = () =>
  client.get<string[]>('/superadmin/sync/sets/excluded-attribute-defaults')

/** Dry-run summary of what a reconcile of a sync set would change (no writes). */
export interface SyncReconcilePreview {
  sourceCount: number
  managedCount: number
  plannedAdds: number
  plannedDeletes: number
  sampleDeleteDns: string[]
  guardTripped: boolean
  guardReason: string | null
  completeScan: boolean
}

export const previewReconcile = (setId: string) =>
  client.get<SyncReconcilePreview>(`/superadmin/sync/sets/${setId}/preview`)

/**
 * Independent content verification: re-reads the live source and target and flags
 * missing / orphaned / drifted entries, without consulting the membership index.
 * A belts-and-suspenders check beyond the reconcile preview (no writes).
 */
export interface SyncVerifyResult {
  sourceMembers: number
  targetEntries: number
  inSync: number
  missingOnTarget: number
  orphanOnTarget: number
  contentMismatches: number
  sampleMissing: string[]
  sampleOrphans: string[]
  sampleMismatches: string[]
  sourceComplete: boolean
  targetComplete: boolean
  note: string | null
}

export const verifyContents = (setId: string) =>
  client.get<SyncVerifyResult>(`/superadmin/sync/sets/${setId}/verify`)

// ── Inventory + operator triggers ───────────────────────────────────────────

/** Spring Data Page envelope (the subset the UI consumes). */
export interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number // current page, 0-based
  size: number
}

export interface MembershipQuery {
  state?: MembershipState
  q?: string
  page?: number
  size?: number
}

export const listMemberships = (setId: string, query: MembershipQuery = {}) =>
  client.get<Page<Membership>>(`/superadmin/sync/sets/${setId}/memberships`, { params: query })
export const reconcileSet = (setId: string) =>
  client.post<{ enumerated: number }>(`/superadmin/sync/sets/${setId}/reconcile`)
export const recomputeKey = (setId: string, key: string) =>
  client.post(`/superadmin/sync/sets/${setId}/recompute`, { key })
export const dismissMembership = (setId: string, identity: string) =>
  client.delete(`/superadmin/sync/sets/${setId}/memberships/${encodeURIComponent(identity)}`)
