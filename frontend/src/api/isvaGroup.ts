// SPDX-License-Identifier: Apache-2.0
// API client for importing groups into IVIA (the addon's isva-group
// endpoints). Untyped axios `client`, like isvaAccount.ts: addon endpoints
// aren't in openapi.d.ts.

import client from './client'
import type { AxiosResponse } from 'axios'

/** Mirrors the backend IsvaGroupStatus record. */
export interface IsvaGroupStatus {
  registered: boolean
  /** The secGroup registration entry, when registered. */
  registrationDn: string | null
  /** The directory has a group container, so groups can be imported. */
  canImport: boolean
  /** Default IVIA group name for an import (the group's cn). */
  suggestedName: string | null
}

/** Which of the asked-about groups are imported. */
export interface IsvaGroupRegistrations {
  /** The directory has an active IVIA integration. */
  enabled: boolean
  canImport: boolean
  /** The asked-about DNs, as sent, that are imported. */
  registered: string[]
}

const base = (dirId: string) => `/directories/${dirId}/isva-group`

export const getIsvaGroupRegistrations = (
  dirId: string, dns: string[],
): Promise<AxiosResponse<IsvaGroupRegistrations>> =>
  client.post(`${base(dirId)}/registrations`, { dns })

/** Imports the group; a blank name uses the group's cn. */
export const importIsvaGroup = (
  dirId: string, dn: string, name?: string,
): Promise<AxiosResponse<IsvaGroupStatus>> =>
  client.post(`${base(dirId)}/import`, { name: name?.trim() || null }, { params: { dn } })
