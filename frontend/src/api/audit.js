// SPDX-License-Identifier: Apache-2.0
import client from './client'

export const getAuditLog = (params) =>
  // `indexes: null` repeats array params as `action=X&action=Y` (no `[]`
  // suffix) so Spring binds them to `@RequestParam List<AuditAction> action`.
  client.get('/audit', { params, paramsSerializer: { indexes: null } })

// Edition-filtered audit-action catalogue (enum names) for the filter picker.
// The set is authoritative from the backend so non-community actions never
// appear; the client only supplies display labels.
export const getAuditActions = () =>
  client.get('/audit/actions')

export const getEntryTimeline = (directoryId, targetDn, params = {}) =>
  client.get('/audit', { params: { directoryId, targetDn, ...params } })
