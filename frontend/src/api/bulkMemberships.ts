// SPDX-License-Identifier: Apache-2.0
import client from './client'
import type { AxiosResponse } from 'axios'

// Bulk group membership from a CSV (user + groups [+ op] columns). The
// preview resolves and classifies every user-group pair without writing;
// apply re-resolves and writes. Both need bulk.import + group.manage_members.

export type MembershipOp = 'ADD' | 'REMOVE'

export type MembershipDisposition =
  | 'WILL_ADD' | 'WILL_REMOVE' | 'ALREADY_MEMBER' | 'NOT_A_MEMBER' | 'DUPLICATE'
  | 'USER_NOT_FOUND' | 'GROUP_NOT_FOUND' | 'AMBIGUOUS' | 'OUT_OF_SCOPE' | 'INVALID'

export interface MembershipPreviewRow {
  rowNumber: number
  user: string | null
  userDn: string | null
  group: string | null
  groupDn: string | null
  op: MembershipOp | null
  disposition: MembershipDisposition
  note: string | null
}

export interface MembershipPreviewResult {
  totalPairs: number
  actionable: number
  rows: MembershipPreviewRow[]
}

export type MembershipRowStatus =
  | 'APPLIED' | 'QUEUED_FOR_APPROVAL' | 'SKIPPED' | 'REFUSED' | 'BLOCKED' | 'ERROR'

export interface MembershipRowResult {
  rowNumber: number
  user: string | null
  userDn: string | null
  group: string | null
  groupDn: string | null
  op: MembershipOp | null
  status: MembershipRowStatus
  approvalId: string | null
  message: string | null
}

export interface MembershipApplyResult {
  totalPairs: number
  applied: number
  queued: number
  skipped: number
  failed: number
  rows: MembershipRowResult[]
}

const base = (dirId: string) => `/directories/${dirId}/memberships/bulk`

function form(file: File): FormData {
  const f = new FormData()
  f.append('file', file)
  return f
}

export const previewBulkMemberships = (
  dirId: string, file: File,
): Promise<AxiosResponse<MembershipPreviewResult>> =>
  client.post(`${base(dirId)}/preview`, form(file), { headers: { 'Content-Type': 'multipart/form-data' } })

export const applyBulkMemberships = (
  dirId: string, file: File,
): Promise<AxiosResponse<MembershipApplyResult>> =>
  client.post(base(dirId), form(file), { headers: { 'Content-Type': 'multipart/form-data' } })
