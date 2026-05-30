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
