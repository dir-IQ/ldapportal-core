// SPDX-License-Identifier: Apache-2.0
// API client for the ISVA full-mode integration config endpoints.
//
// Uses the untyped axios client (./client) because addons/* endpoints
// aren't in the generated openapi.ts yet — the typed apiGet/apiPost
// helpers constrain their path argument against PathsWith<…> derived
// from the spec, which rejects paths the spec doesn't know about.
//
// A future schema-merge change can promote these to typed schemas;
// when that lands, swap to the apiGet/apiPost helpers like
// directories.ts does and the consumer code stays the same.

import client from './client';
import type { AxiosResponse } from 'axios';

export type IsvaTopologyMode = 'INLINE' | 'LINKED';
export type IsvaDeletePolicy = 'DISABLE' | 'HARD_DELETE';
export type IsvaGroupMemberTarget = 'DEMOGRAPHIC_DN' | 'SECUSER_DN';
export type IsvaDemographicDeleteMode = 'LEAVE' | 'DISABLE_AND_MARK';

export interface IsvaConfigDto {
  enabled: boolean;
  topologyMode: IsvaTopologyMode;
  secAuthority: string | null;
  defaultValidUntilYears: number;
  deletePolicy: IsvaDeletePolicy;
  requireSecGroup: boolean;

  // Linked-mode-only — null in INLINE responses
  managementDitBaseDn: string | null;
  secuserRdnAttribute: string | null;
  groupMemberTarget: IsvaGroupMemberTarget | null;
  onDemographicDelete: IsvaDemographicDeleteMode | null;

  createdAt: string;
  updatedAt: string;
  updatedBy: string | null;
}

export interface UpsertIsvaConfigRequest {
  enabled: boolean;
  topologyMode: IsvaTopologyMode;
  secAuthority: string | null;
  defaultValidUntilYears: number;
  deletePolicy: IsvaDeletePolicy;
  requireSecGroup: boolean;

  // Required when topologyMode = LINKED
  managementDitBaseDn: string | null;
  secuserRdnAttribute: string | null;
  groupMemberTarget: IsvaGroupMemberTarget | null;
  onDemographicDelete: IsvaDemographicDeleteMode | null;
}

export interface ProbeResult {
  reachable: boolean;
  sampleSecUserFound: boolean;
  warnings: string[];
}

// UI-only page options, env-driven (EXPOSED_ISVA_TOPOLOGY_MODES). A
// single-element list tells the view to hide the topology selector and
// pin that mode. Never empty.
export interface IsvaUiOptionsDto {
  exposedTopologyModes: IsvaTopologyMode[];
}

const base = (directoryId: string) => `/directories/${directoryId}/isva-config`;

export const getIsvaConfig = (
  directoryId: string,
): Promise<AxiosResponse<IsvaConfigDto>> => client.get(base(directoryId));

export const upsertIsvaConfig = (
  directoryId: string,
  body: UpsertIsvaConfigRequest,
): Promise<AxiosResponse<IsvaConfigDto>> => client.put(base(directoryId), body);

export const probeIsvaConfig = (
  directoryId: string,
): Promise<AxiosResponse<ProbeResult>> =>
  client.post(`${base(directoryId)}/probe`, {});

// Global, deployment-static UI options (env-driven, can't change without a
// restart) — fetch once and memoise so opening directory config pages doesn't
// re-request. A failed fetch clears the cache so the next open retries.
let uiOptionsPromise: Promise<AxiosResponse<IsvaUiOptionsDto>> | null = null;

export const getIsvaUiOptions = (): Promise<AxiosResponse<IsvaUiOptionsDto>> => {
  if (!uiOptionsPromise) {
    uiOptionsPromise = client.get('/isva/ui-options').catch((e) => {
      uiOptionsPromise = null;
      throw e;
    });
  }
  return uiOptionsPromise;
};

// ── Per-profile override ──────────────────────────────────────────
// Narrowing-only: a profile can be FORCE_OFF (exempt from ISVA) in an
// otherwise ISVA-enabled directory. No row / INHERIT follows the
// directory. Keyed by profileId; directoryId is REST-nesting context.

export type IsvaProfileOverride = 'INHERIT' | 'FORCE_OFF';

export interface IsvaProfileOverrideDto {
  override: IsvaProfileOverride;
}

const overrideBase = (directoryId: string, profileId: string) =>
  `/directories/${directoryId}/profiles/${profileId}/isva-override`;

export const getIsvaProfileOverride = (
  directoryId: string,
  profileId: string,
): Promise<AxiosResponse<IsvaProfileOverrideDto>> =>
  client.get(overrideBase(directoryId, profileId));

export const setIsvaProfileOverride = (
  directoryId: string,
  profileId: string,
  override: IsvaProfileOverride,
): Promise<AxiosResponse<IsvaProfileOverrideDto>> =>
  client.put(overrideBase(directoryId, profileId), { override });
