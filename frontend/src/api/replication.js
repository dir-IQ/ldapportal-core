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

// ── Changelog capture: test + operator remediation ───────────────────────────
// Probe an existing link's source changelog (root DSE first/lastChangeNumber +
// changelog base DN readability). Returns ChangelogTestResult.
export const testReplicationChangelog = (linkId) =>
    client.post(`/superadmin/replication-links/${linkId}/test-changelog`)
// Pre-save probe: body = { sourceDirectoryId, changelogBaseDn? }.
export const testReplicationChangelogPreSave = (body) =>
    client.post('/superadmin/replication-links/test-changelog', body)

// Remediation (cursor reseed/rewind, re-enable). Each returns the updated link.
export const reseedChangelogCursor = (linkId) =>
    client.post(`/superadmin/replication-links/${linkId}/changelog/reseed`)
export const rewindChangelogCursor = (linkId, changeNumber) =>
    client.post(`/superadmin/replication-links/${linkId}/changelog/rewind`, { changeNumber })
export const reEnableChangelog = (linkId) =>
    client.post(`/superadmin/replication-links/${linkId}/changelog/re-enable`)
