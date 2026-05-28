// SPDX-License-Identifier: Apache-2.0
// API client for the IVIA account-management endpoints (P2).
//
// Uses the untyped axios `client` because addons/* endpoints aren't in
// openapi.d.ts yet — same shape as isvaConfig.ts.

import client from './client'
import type { AxiosResponse } from 'axios'

export type IsvaTopology = 'INLINE' | 'LINKED'
export type IsvaRevokeMode = 'SOFT' | 'HARD'

/**
 * Snapshot returned by getStatus and by every verb (so callers see
 * the fresh state without a second round-trip). Mirrors the
 * IsvaAccountStatus record on the backend.
 */
export interface IsvaAccountStatus {
  linked: boolean
  orphaned: boolean
  topology: IsvaTopology
  acctValid: boolean
  validUntil: string | null
  daysRemaining: number | null
  pwdValid: boolean
  pwdLastChanged: string | null
  authority: string | null
  /** Linked-mode only — null in INLINE. */
  secUserDn: string | null
}

/**
 * RFC 7807 body shape augmented with the IVIA refusal {@code code}.
 * Frontends dispatch contextual CTAs on the code symbol.
 */
export interface IsvaRefusalProblemDetail {
  type?: string
  title?: string
  status: number
  detail: string
  code:
    | 'ivia_already_linked'
    | 'ivia_orphan'
    | 'ivia_force_off'
    | 'ivia_directory_disabled'
    | 'ivia_renew_not_forward'
    | 'ivia_state_changed'
}

const base = (dirId: string): string =>
  `/directories/${dirId}/isva-account`

const dnParams = (dn: string): { params: Record<string, string> } => ({
  params: { dn },
})

export const getIsvaAccountStatus = (
  dirId: string,
  dn: string,
): Promise<AxiosResponse<IsvaAccountStatus>> =>
  client.get(base(dirId), dnParams(dn))

export const grantIsvaAccount = (
  dirId: string,
  dn: string,
): Promise<AxiosResponse<IsvaAccountStatus>> =>
  client.post(`${base(dirId)}/grant`, null, dnParams(dn))

export const revokeIsvaAccount = (
  dirId: string,
  dn: string,
  mode: IsvaRevokeMode,
): Promise<AxiosResponse<IsvaAccountStatus>> =>
  client.post(`${base(dirId)}/revoke`, { mode }, dnParams(dn))

export const suspendIsvaAccount = (
  dirId: string,
  dn: string,
): Promise<AxiosResponse<IsvaAccountStatus>> =>
  client.post(`${base(dirId)}/suspend`, null, dnParams(dn))

export const restoreIsvaAccount = (
  dirId: string,
  dn: string,
): Promise<AxiosResponse<IsvaAccountStatus>> =>
  client.post(`${base(dirId)}/restore`, null, dnParams(dn))

export const renewIsvaAccount = (
  dirId: string,
  dn: string,
  validUntil: string,
): Promise<AxiosResponse<IsvaAccountStatus>> =>
  client.post(`${base(dirId)}/renew`, { validUntil }, dnParams(dn))

export const forceCredentialReset = (
  dirId: string,
  dn: string,
): Promise<AxiosResponse<IsvaAccountStatus>> =>
  client.post(`${base(dirId)}/force-credential-reset`, null, dnParams(dn))
