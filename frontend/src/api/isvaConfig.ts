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
export type IsvaGroupMemberTarget = 'DEMOGRAPHIC_DN' | 'SECUSER_DN';
export type IsvaRdnValueSource = 'GENERATED_UUID' | 'UID';
export type SecUserAttributeValueKind = 'LITERAL' | 'COMPUTED';

// One row of the unified secUser attribute model — name, whether a grant
// writes it, and how its value is produced (a literal, or a computed
// expression: ${user.<attr>} / ${sec.<attr>} references plus uuid() / now() /
// nowPlusYears(n)).
export interface SecUserAttribute {
  name: string;
  enabled: boolean;
  valueKind: SecUserAttributeValueKind;
  value: string;
}

export interface IsvaConfigDto {
  enabled: boolean;
  topologyMode: IsvaTopologyMode;
  secAuthority: string | null;
  secLoginType: string | null;
  defaultValidUntilYears: number;
  requireSecGroup: boolean;

  // Applies to both modes
  secuserObjectClasses: string[];
  secuserOverlayAttributes: string[];

  // The effective per-attribute model — always populated (derived from the
  // legacy fields server-side when no explicit model is stored).
  secuserAttributes: SecUserAttribute[];

  // Linked-mode-only — null in INLINE responses
  managementDitBaseDn: string | null;
  secuserRdnAttribute: string | null;
  secuserRdnValueSource: IsvaRdnValueSource | null;
  groupMemberTarget: IsvaGroupMemberTarget | null;

  createdAt: string;
  updatedAt: string;
  updatedBy: string | null;
}

export interface UpsertIsvaConfigRequest {
  enabled: boolean;
  topologyMode: IsvaTopologyMode;
  secAuthority: string | null;
  secLoginType: string | null;
  defaultValidUntilYears: number;
  requireSecGroup: boolean;

  // Applies to both modes — secUser is normalized in server-side if omitted
  secuserObjectClasses: string[];
  // Applies to both modes — the optional sec* overlay attributes to write.
  // Normalized to the known optional attributes server-side. Legacy: the
  // server prefers secuserAttributes below when that is supplied.
  secuserOverlayAttributes: string[];

  // The unified per-attribute model — authoritative when supplied. Normalized
  // to the canonical full set server-side. null → server derives from the
  // legacy value fields.
  secuserAttributes: SecUserAttribute[] | null;

  // Required when topologyMode = LINKED
  managementDitBaseDn: string | null;
  secuserRdnAttribute: string | null;
  secuserRdnValueSource: IsvaRdnValueSource | null;
  groupMemberTarget: IsvaGroupMemberTarget | null;
}

export interface ProbeResult {
  reachable: boolean;
  sampleSecUserFound: boolean;
  // true = all configured objectClasses exist and (linked) the RDN
  // attribute is permitted by one; false = a check failed; null =
  // server schema couldn't be read to decide.
  schemaValid: boolean | null;
  // Attributes the app would write that the target secUser schema forbids
  // (each → "attribute not allowed"); and MUST attributes it requires that
  // the app wouldn't write (each → "missing required attribute").
  disallowedWriteAttributes: string[];
  missingRequiredAttributes: string[];
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
