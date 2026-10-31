// SPDX-License-Identifier: Apache-2.0
// Directory diff: compare two directories, or two branches of one, entry by
// entry. Hand-typed (not in the generated OpenAPI client yet); `client`
// already sets baseURL='/api/v1'.
import client from './client'

export type DirectoryDiffStatus = 'RUNNING' | 'COMPLETE' | 'FAILED' | 'CANCELLED'
export type DirectoryDiffPhase = 'READING_A' | 'READING_B' | 'COMPARING' | 'DONE'
export type DirectoryDiffCategory = 'SAME' | 'DIFFERENT' | 'ONLY_A' | 'ONLY_B'
export type DirectoryDiffPairing = 'DN' | 'ATTRIBUTE'
export type DirectoryDiffScope = 'SUB' | 'ONE'

export interface DirectoryDiffSideRequest {
  directoryId: string
  /** Blank for the directory's base DN. */
  baseDn?: string | null
  scope?: DirectoryDiffScope
  /** Blank for every entry. */
  filter?: string | null
}

export interface DirectoryDiffRequest {
  a: DirectoryDiffSideRequest
  b: DirectoryDiffSideRequest
  pairBy: DirectoryDiffPairing
  pairAttribute?: string | null
  /** Compare only these; empty for every user attribute. */
  attributes?: string[]
  /** Also leave these out. */
  ignore?: string[]
}

export interface DirectoryDiffSide {
  directoryId: string
  directoryName: string
  baseDn: string
  scope: DirectoryDiffScope
  filter: string
}

export interface DirectoryDiffCounts {
  same: number
  different: number
  onlyA: number
  onlyB: number
}

export interface DirectoryDiffAttributeCount {
  name: string
  count: number
}

export interface DirectoryDiffRun {
  id: string
  status: DirectoryDiffStatus
  phase: DirectoryDiffPhase
  processed: number
  total: number
  currentDn: string | null
  error: string | null
  requestedBy: string | null
  startedAt: string
  heartbeatAt: string | null
  finishedAt: string | null
  a: DirectoryDiffSide
  b: DirectoryDiffSide
  pairBy: DirectoryDiffPairing
  pairAttribute: string | null
  attributes: string[]
  ignore: string[]
  counts: DirectoryDiffCounts
  /** For the Different group: each attribute and how many entries it differs in, most first. */
  attributeCounts: DirectoryDiffAttributeCount[]
}

export interface DirectoryDiffEntry {
  seq: number
  category: DirectoryDiffCategory
  dnA: string | null
  dnB: string | null
  /** The attributes that differ (Different only). */
  attributes: string[]
  /** Why an entry couldn't pair, when it couldn't. */
  note: string | null
}

export interface DirectoryDiffPage {
  content: DirectoryDiffEntry[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

/** One attribute that differs; lists are capped at 100 values, the counts are complete. */
export interface DirectoryDiffAttributeDiff {
  name: string
  onlyA: string[]
  onlyACount: number
  onlyB: string[]
  onlyBCount: number
  both: string[]
  bothCount: number
}

export interface DirectoryDiffEntryDetail {
  seq: number
  category: DirectoryDiffCategory
  dnA: string | null
  dnB: string | null
  diffs: DirectoryDiffAttributeDiff[]
  /** Names of the attributes equal on both sides. */
  sameAttributes: string[]
  note: string | null
}

export interface DirectoryDiffLiveEntry {
  /** As it stands now; null when neither entry exists any more. */
  category: DirectoryDiffCategory | null
  dnA: string | null
  dnB: string | null
  presentInA: boolean
  presentInB: boolean
  readAt: string
  diffs: DirectoryDiffAttributeDiff[]
  same: { name: string; values: string[] }[]
}

const BASE = '/superadmin/directory-diffs'

export const startDirectoryDiff = (req: DirectoryDiffRequest) =>
  client.post<DirectoryDiffRun>(BASE, req)

export const listDirectoryDiffs = () =>
  client.get<DirectoryDiffRun[]>(BASE)

export const getDirectoryDiff = (id: string) =>
  client.get<DirectoryDiffRun>(`${BASE}/${id}`)

export const cancelDirectoryDiff = (id: string) =>
  client.post<DirectoryDiffRun>(`${BASE}/${id}/cancel`)

export const listDirectoryDiffEntries = (id: string, params: {
  category: DirectoryDiffCategory
  attribute?: string | null
  q?: string | null
  page?: number
  size?: number
}) => client.get<DirectoryDiffPage>(`${BASE}/${id}/entries`, { params })

export const getDirectoryDiffEntry = (id: string, seq: number) =>
  client.get<DirectoryDiffEntryDetail>(`${BASE}/${id}/entries/${seq}`)

export const recheckDirectoryDiffEntry = (id: string, seq: number) =>
  client.get<DirectoryDiffLiveEntry>(`${BASE}/${id}/entries/${seq}/live`)

/** A group (every group when no category), as CSV: `group,dn_a,dn_b,attributes,note`. */
export const downloadDirectoryDiffCsv = (id: string, category?: DirectoryDiffCategory) =>
  client.get<Blob>(`${BASE}/${id}/entries.csv`, {
    params: category ? { category } : {},
    responseType: 'blob',
  })
