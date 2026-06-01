// SPDX-License-Identifier: Apache-2.0
import client from './client'

// ── Links ──────────────────────────────────────────────────────────────────
export const listReplicationLinks   = ()            => client.get('/superadmin/replication-links')
export const getReplicationLink     = (id)          => client.get(`/superadmin/replication-links/${id}`)
export const createReplicationLink  = (data)        => client.post('/superadmin/replication-links', data)
export const updateReplicationLink  = (id, data)    => client.put(`/superadmin/replication-links/${id}`, data)
export const deleteReplicationLink  = (id)          => client.delete(`/superadmin/replication-links/${id}`)

// ── Events ─────────────────────────────────────────────────────────────────
// listEvents returns a Spring Page<ReplicationEventResponse> shape:
//   { content: [...], number, size, totalElements, totalPages, ... }
export const listReplicationEvents = (linkId, params = {}) =>
    client.get(`/superadmin/replication-links/${linkId}/events`, { params })
export const retryReplicationEvent       = (id) => client.post(`/superadmin/replication-events/${id}/retry`)
export const skipReplicationEvent        = (id) => client.post(`/superadmin/replication-events/${id}/skip`)
export const acknowledgeReplicationEvent = (id) => client.post(`/superadmin/replication-events/${id}/acknowledge`)

// ── Reconciliation ───────────────────────────────────────────────────────────
export const reconcileNow = (linkId) =>
    client.post(`/superadmin/replication-links/${linkId}/reconcile`)
export const listReconciliationRuns = (linkId, params = {}) =>
    client.get(`/superadmin/replication-links/${linkId}/reconciliation-runs`, { params })
export const getReconciliationFindings = (runId, params = {}) =>
    client.get(`/superadmin/reconciliation-runs/${runId}/findings`, { params })
export const applyReconciliationFindings = (runId, body) =>
    client.post(`/superadmin/reconciliation-runs/${runId}/findings/apply`, body)
export const dismissReconciliationFindings = (runId, body) =>
    client.post(`/superadmin/reconciliation-runs/${runId}/findings/dismiss`, body)
export const getOpenFindingCount = (linkId) =>
    client.get(`/superadmin/replication-links/${linkId}/reconciliation-findings/open-count`)
