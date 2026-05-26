// SPDX-License-Identifier: Apache-2.0
import client from './client'

export const listPendingApprovals = (dirId) =>
    client.get(`/directories/${dirId}/approvals`)

export const getApproval = (dirId, id) =>
    client.get(`/directories/${dirId}/approvals/${id}`)

export const approveRequest = (dirId, id) =>
    client.post(`/directories/${dirId}/approvals/${id}/approve`)

export const rejectRequest = (dirId, id, reason) =>
    client.post(`/directories/${dirId}/approvals/${id}/reject`, { reason })

export const updateApprovalPayload = (dirId, id, payload) =>
    client.put(`/directories/${dirId}/approvals/${id}/payload`, payload, {
      headers: { 'Content-Type': 'application/json' }
    })

export const countPendingApprovals = (dirId) =>
    client.get(`/directories/${dirId}/approvals/count`)
