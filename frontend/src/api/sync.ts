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
  /**
   * How much of each run the history keeps: SUMMARY keeps counts and failure
   * reasons only; DETAILED also keeps every changed or flagged entry's DN
   * (CSV download).
   */
  loggingLevel: SyncLoggingLevel
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
  /** Which decision a REVIEW row waits on; null for other states. */
  reviewKind: SyncReviewKind | null
}

/**
 * Why a row is held for REVIEW: a delete waiting for approval, an ambiguous
 * source-anchor match, an occupied placement DN, or an unrecognized reason.
 */
export type SyncReviewKind = 'PENDING_DELETE' | 'AMBIGUOUS_MATCH' | 'PLACEMENT_CONFLICT' | 'OTHER'

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

/** What deleting a set would discard: its index rows, undecided reviews and run history. */
export interface SyncSetDeleteImpact {
  name: string
  /** Target entries the set manages (any state); they stay on the target, unmanaged. */
  managed: number
  /** Rows held for review (e.g. quarantined deletes), discarded undecided. */
  pendingReview: number
  reconcileRuns: number
  /** The other sync sets, whose DN references are remapped through every set's index. */
  otherSets: string[]
}
export const getSyncSetDeleteImpact = (id: string) =>
  client.get<SyncSetDeleteImpact>(`/superadmin/sync/sets/${id}/delete-impact`)

/** Default excluded attributes (operational + password values) — seeds the editor. */
export type SyncLoggingLevel = 'SUMMARY' | 'DETAILED'

/** What the engine uses for each sync-set field left blank (the editor's info tips list these). */
export interface SyncSetDefaults {
  /** Identity attribute per source directory type (DirectoryType name → attribute). */
  identityKeyByDirectoryType: Record<string, string>
  referenceAttributes: string[]
  /** This server's reconcile interval for sets that don't set one. */
  reconcileIntervalSeconds: number
  excludedAttributes: string[]
}
export const getSyncSetDefaults = () => client.get<SyncSetDefaults>('/superadmin/sync/sets/defaults')

/**
 * One recorded run of a set: a reconcile (SCHEDULED / MANUAL), or an
 * INCREMENTAL pass — the changes the recompute worker applied between
 * reconciles (changelog, in-app edits), kept when it did something. The
 * outcome counters mirror SyncPreviewAction, so a run compares directly with
 * its preview. `status` FAILED covers both an aborted run (`error`) and one
 * whose server stopped mid-run.
 */
export interface SyncReconcileRun {
  id: string
  trigger: 'SCHEDULED' | 'MANUAL' | 'INCREMENTAL'
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
  /** The app instance (host) that ran it. */
  instanceName: string | null
  /** When the source scan and plan finished: scan = plannedAt − startedAt. */
  plannedAt: string | null
  /** The content verification right after the run (drift left behind), when recorded. */
  verifiedAt: string | null
  verifyMissing: number | null
  verifyOrphan: number | null
  verifyMismatch: number | null
  /** Every changed or flagged entry's DN was kept: downloadable with `downloadReconcileRunEntries`. */
  allDnsRecorded: boolean
  /** In-scope source entries the set doesn't manage (the applicability filter or placement excludes them). */
  notInSet: number
  /** Where a RUNNING run is (else null). */
  phase: 'SCANNING' | 'APPLYING' | 'SWEEPING' | 'REFERENCES' | null
  /** The entry a RUNNING run is recomputing (else null). */
  currentDn: string | null
  /** When the run last reported progress or that it's alive. */
  heartbeatAt: string | null
}

/** In-scope source entries a reconcile found that the set doesn't manage, for one reason. */
export interface SyncNotInSetGroup {
  reason: 'FILTER' | 'PLACEMENT' | string
  label: string
  count: number
  samples: string[]
}

/** The not-in-set entries of the set's latest complete reconcile (`GET …/not-in-set`). */
export interface SyncNotInSetPage {
  /** null when the set has no complete reconcile yet. */
  runId: string | null
  finishedAt: string | null
  /** Every entry is listed (Detailed logging); otherwise up to `sampleLimit` per reason. */
  allDnsRecorded: boolean
  total: number
  reasons: SyncNotInSetGroup[]
  entries: { sourceDn: string; reason: string; label: string }[]
  /** Entries matching the search: the listed ones (not `total`) when only samples were kept. */
  matched: number
  page: number
  size: number
  sampleLimit: number
}

/** A set's latest content verification: progress while it runs, and how it ended. */
export interface SyncVerifyProgress {
  runId: string
  status: 'RUNNING' | 'COMPLETE' | 'FAILED'
  phase: 'SOURCE' | 'TARGET' | 'COMPARING' | 'DONE'
  processed: number
  total: number
  currentDn: string | null
  error: string | null
  startedAt: string
  heartbeatAt: string
  finishedAt: string | null
}

/** What a set's inventory actions are doing (`GET …/activity`). */
export interface SyncSetActivity {
  reconcile: SyncReconcileRun | null
  verify: SyncVerifyProgress | null
  /** The server's time when read: ages the runs' timestamps without the browser's clock. */
  asOf: string
}

export const getSyncSetActivity = (setId: string) =>
  client.get<SyncSetActivity>(`/superadmin/sync/sets/${setId}/activity`)

export const listNotInSet = (setId: string, query: { q?: string; page?: number; size?: number } = {}) =>
  client.get<SyncNotInSetPage>(`/superadmin/sync/sets/${setId}/not-in-set`, { params: query })

/** The set's settings and delete-guard limits as the run saw them. */
export interface SyncRunSettings {
  deletePolicy: string | null
  objectScopeBaseDn: string | null
  objectScope: string | null
  applicabilityFilter: string | null
  targetBaseDn: string | null
  identityKey: string | null
  sourceAnchorAttribute: string | null
  reconcileCadenceSeconds: number | null
  guardMaxDeletes: number
  guardMaxDeletePercent: number
  guardMinPopulation: number
}

/** Failed / faulted entries sharing a reason, with one full example. */
export interface SyncRunFailureReason {
  reason: string
  count: number
  example: string
}

/** Everything recorded about one reconcile run. */
export interface SyncReconcileRunDetail {
  run: SyncReconcileRun
  /** Null for a run recorded before settings were kept. */
  settings: SyncRunSettings | null
  failureReasons: SyncRunFailureReason[]
  /** Outcome (ADD, MODIFY, …) → up to `sampleLimit` target DNs. */
  samples: Partial<Record<SyncPreviewAction, string[]>>
  sampleLimit: number
  /** In-scope source entries the set doesn't manage, per reason (most first), with sample DNs. */
  notInSet: SyncNotInSetGroup[]
}

export const getReconcileRun = (setId: string, runId: string) =>
  client.get<SyncReconcileRunDetail>(`/superadmin/sync/sets/${setId}/reconcile-runs/${runId}`)

/** Which runs `listReconcileRuns` returns; omit for both. */
export type SyncRunKind = 'RECONCILE' | 'INCREMENTAL'

/** A set's most recent runs, newest first: reconciles, incremental passes, or both. */
export const listReconcileRuns = (setId: string, limit = 10, kind?: SyncRunKind) =>
  client.get<SyncReconcileRun[]>(`/superadmin/sync/sets/${setId}/reconcile-runs`,
    { params: kind ? { limit, kind } : { limit } })

/**
 * Every entry a run changed or flagged, as CSV (`outcome,dn,reason`) — for a
 * run with `allDnsRecorded`. A FAULT row carries the source DN or identity.
 */
export const downloadReconcileRunEntries = (setId: string, runId: string) =>
  client.get<Blob>(`/superadmin/sync/sets/${setId}/reconcile-runs/${runId}/entries.csv`,
    { responseType: 'blob' })

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
  /** The entry being dry-run now, while RUNNING (else null). */
  currentDn: string | null
  /** When the run last reported progress or that it's alive. */
  heartbeatAt: string | null
  /** The server's time when this status was read: ages startedAt / heartbeatAt without the browser's clock. */
  asOf: string | null
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
  /**
   * Entries per outcome, counted from the stored entries once the run is
   * complete (empty before), so they match what Show changes lists.
   */
  actionCounts: Partial<Record<SyncPreviewAction, number>>
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

/**
 * Result of a target re-check: how many applied entries the next reconcile will
 * re-read, and whether that reconcile was queued (false while the set or its
 * link is disabled).
 */
export interface SyncRecheckResult {
  entries: number
  reconcileQueued: boolean
}

/**
 * Queue a reconcile for the scheduler's next tick (within about a minute) and return at once.
 * reconcileQueued is false while the set or its link is disabled.
 */
export const requestSyncSetReconcile = (setId: string) =>
  client.post<{ reconcileQueued: boolean }>(`/superadmin/sync/sets/${setId}/reconcile-request`)

/** Re-check the target (e.g. after a restore from backup): the next reconcile re-reads every applied entry. */
export const recheckTarget = (setId: string) =>
  client.post<SyncRecheckResult>(`/superadmin/sync/sets/${setId}/recheck-target`)
export const recomputeKey = (setId: string, key: string) =>
  client.post(`/superadmin/sync/sets/${setId}/recompute`, { key })
export const dismissMembership = (setId: string, identity: string) =>
  client.delete(`/superadmin/sync/sets/${setId}/memberships/${encodeURIComponent(identity)}`)

/**
 * Result of approving a held delete: DELETE (target entry removed), FORGET
 * (another identity owns the target entry, so only the row was dropped) or
 * ERROR (the delete failed; the row is now FAILED with `message`).
 */
export interface SyncDeleteApproval {
  outcome: 'DELETE' | 'FORGET' | 'ERROR'
  targetDirectoryId: string
  targetDn: string
  message: string | null
}

/** Approve a delete held for review: deletes the target entry now, whatever the delete policy. */
export const approveDelete = (setId: string, identity: string) =>
  client.post<SyncDeleteApproval>(
    `/superadmin/sync/sets/${setId}/memberships/${encodeURIComponent(identity)}/approve-delete`)
