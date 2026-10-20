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
export type ChangelogFormat = 'DSEE_CHANGELOG' | 'OPENLDAP_ACCESSLOG' | 'AD_DIRSYNC' | 'OUD_COOKIE_CHANGELOG'
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

/**
 * One recorded reconcile run of a set. The outcome counters mirror
 * SyncPreviewAction, so a run compares directly with its preview.
 * `status` FAILED covers both an aborted run (`error`) and one whose server
 * stopped mid-run.
 */
export interface SyncReconcileRun {
  id: string
  trigger: 'SCHEDULED' | 'MANUAL'
  requestedBy: string | null
  status: 'RUNNING' | 'COMPLETE' | 'FAILED'
  startedAt: string
  finishedAt: string | null
  /** False when source enumeration failed or stopped: no deletes were made. */
  completeScan: boolean
  sourceCount: number
  managedBefore: number
  managedAfter: number | null
  processed: number
  added: number
  modified: number
  moved: number
  deleted: number
  heldForReview: number
  /** Applied without a target write: the target already matched. */
  noChange: number
  forgotten: number
  unchanged: number
  /** Target writes that failed (rows now FAILED). */
  failed: number
  /** Recomputes that threw; retried on the next run. */
  faulted: number
  /** Not processed: the set or link was disabled during the run. */
  skipped: number
  guardTripped: boolean
  guardReason: string | null
  error: string | null
}

/** A set's most recent reconcile runs, newest first. */
export const listReconcileRuns = (setId: string, limit = 10) =>
  client.get<SyncReconcileRun[]>(`/superadmin/sync/sets/${setId}/reconcile-runs`, { params: { limit } })

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

/** What a reconcile would do to one entry (from a dry run of the engine). */
export type SyncPreviewAction =
  | 'ADD' | 'MODIFY' | 'MOVE' | 'DELETE' | 'REVIEW' | 'NO_CHANGE' | 'FORGET' | 'UNCHANGED' | 'ERROR'

/** Values an attribute would lose and gain; lists are capped, the rest counted. */
export interface SyncPreviewAttributeChange {
  attribute: string
  removed: string[]
  added: string[]
  omittedRemoved: number
  omittedAdded: number
}

export interface SyncPreviewEntry {
  identity: string
  sourceDn: string | null
  targetDn: string | null
  previousTargetDn: string | null
  action: SyncPreviewAction
  reason: string | null
  changes: SyncPreviewAttributeChange[]
}

/**
 * The latest per-entry preview run of a set (a background job). `status` is
 * null when the set has never been previewed; summary fields are filled once
 * it is COMPLETE.
 */
export interface SyncPreviewStatus {
  runId: string | null
  status: 'RUNNING' | 'COMPLETE' | 'FAILED' | null
  requestedBy: string | null
  startedAt: string | null
  finishedAt: string | null
  processed: number
  total: number
  /** The set was edited or reconciled, or its link or directories changed, after this run started. */
  outdated: boolean
  error: string | null
  sourceCount: number
  managedCount: number
  actionableCount: number
  unchangedCount: number
  guardTripped: boolean
  guardReason: string | null
  completeScan: boolean
  note: string | null
}

/** One page of a completed preview, ordered by target DN (parents first). */
export interface SyncPreviewPage {
  entries: SyncPreviewEntry[]
  /** The page actually returned: a page past the end returns the last page. */
  page: number
  size: number
  totalEntries: number
  totalPages: number
  changesOnly: boolean
  runId: string
}

export const getPreviewRun = (setId: string) =>
  client.get<SyncPreviewStatus>(`/superadmin/sync/sets/${setId}/preview/run`)

/** Starts a preview in the background, or joins the one already running. */
export const startPreviewRun = (setId: string) =>
  client.post<SyncPreviewStatus>(`/superadmin/sync/sets/${setId}/preview/run`)

export const previewEntries = (setId: string, page: number, size: number, changesOnly: boolean) =>
  client.get<SyncPreviewPage>(`/superadmin/sync/sets/${setId}/preview/entries`,
    { params: { page, size, changesOnly } })

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
