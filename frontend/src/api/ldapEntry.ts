// SPDX-License-Identifier: Apache-2.0
/**
 * Typed wrapper around the user/group entry-modify endpoints used by
 * the inline-edit results table.
 *
 *   PUT /api/v1/directories/{id}/users/entry?dn=…   (USER_EDIT)
 *   PUT /api/v1/directories/{id}/groups/entry?dn=…  (GROUP_EDIT)
 *
 * Dispatch is by classification (`'user' | 'group'`) so the caller
 * doesn't carry the path string. Phase 1.5 will extend this wrapper
 * with `If-Match` and a `forceOverwrite` flag; the signature is
 * shaped to make those additions opt-in.
 */
import type { AxiosResponse } from 'axios'
import { apiPut, apiGet } from './apiClient'
import type { components } from './openapi'
import type { EntryClassification } from '@/composables/useEntryClassification'

export type AttributeModification = components['schemas']['AttributeModification']
export type LdapEntryResponse = components['schemas']['LdapEntryResponse']

type EditableClassification = Exclude<EntryClassification, 'unknown'>

/**
 * Issues PUT /entry with the given attribute modifications. Caller
 * passes the classification ('user' or 'group'); we route to the
 * matching typed endpoint. Returns the AxiosResponse envelope so
 * consumers can read .data and .status uniformly with the rest of
 * the codebase.
 */
export function updateEntry(
  directoryId: string,
  classification: EditableClassification,
  dn: string,
  modifications: AttributeModification[],
): Promise<AxiosResponse<LdapEntryResponse>> {
  if (classification === 'user') {
    return apiPut(
      (`/api/v1/directories/${directoryId}/users/entry` as '/api/v1/directories/{directoryId}/users/entry'),
      { modifications },
      { params: { dn } },
    )
  }
  return apiPut(
    (`/api/v1/directories/${directoryId}/groups/entry` as '/api/v1/directories/{directoryId}/groups/entry'),
    { modifications },
    { params: { dn } },
  )
}

/**
 * Re-fetches a single entry by DN. Used for the conflict-reload path
 * that lands in Phase 1.5; exposed now because the same call shape
 * is the natural hook for refreshing a row after a successful save.
 */
export function getEntry(
  directoryId: string,
  classification: EditableClassification,
  dn: string,
  attributes = '',
): Promise<AxiosResponse<LdapEntryResponse>> {
  if (classification === 'user') {
    return apiGet(
      (`/api/v1/directories/${directoryId}/users/entry` as '/api/v1/directories/{directoryId}/users/entry'),
      { params: { dn, attributes } },
    )
  }
  return apiGet(
    (`/api/v1/directories/${directoryId}/groups/entry` as '/api/v1/directories/{directoryId}/groups/entry'),
    { params: { dn, attributes } },
  )
}
