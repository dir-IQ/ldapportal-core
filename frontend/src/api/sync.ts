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

export interface SyncLink {
  id: string
  displayName: string
  sourceDirId: string
  targetDirId: string
  enabled: boolean
  captureMode: SyncCaptureMode
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
}

export interface SyncTransformRule {
  sourceAttr: string
  targetAttr: string
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
  reconcileCadenceSeconds: number | null
  reconcileLastRunAt: string | null
  enabled: boolean
  createdAt: string
  updatedAt: string
  version: number
}

export type SyncSetPayload = Omit<
  SyncSet,
  'id' | 'reconcileLastRunAt' | 'createdAt' | 'updatedAt' | 'version'
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

// ── Inventory + operator triggers ───────────────────────────────────────────
export const listMemberships = (setId: string, state?: MembershipState) =>
  client.get<Membership[]>(`/superadmin/sync/sets/${setId}/memberships`, {
    params: state ? { state } : {},
  })
export const reconcileSet = (setId: string) =>
  client.post<{ enumerated: number }>(`/superadmin/sync/sets/${setId}/reconcile`)
export const recomputeKey = (setId: string, key: string) =>
  client.post(`/superadmin/sync/sets/${setId}/recompute`, { key })
export const dismissMembership = (setId: string, identity: string) =>
  client.delete(`/superadmin/sync/sets/${setId}/memberships/${encodeURIComponent(identity)}`)
