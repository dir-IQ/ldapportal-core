// SPDX-License-Identifier: Apache-2.0
import client from './client'

const base = (dirId) => `/directories/${dirId}/playbooks`

export const listPlaybooks    = (dirId)                     => client.get(base(dirId))
export const listEnabled      = (dirId)                     => client.get(`${base(dirId)}/enabled`)
export const getPlaybook      = (dirId, id)                 => client.get(`${base(dirId)}/${id}`)
export const createPlaybook   = (dirId, data)               => client.post(base(dirId), data)
export const updatePlaybook   = (dirId, id, data)           => client.put(`${base(dirId)}/${id}`, data)
export const deletePlaybook   = (dirId, id)                 => client.delete(`${base(dirId)}/${id}`)
export const previewPlaybook  = (dirId, id, dn)             => client.post(`${base(dirId)}/${id}/preview?dn=${encodeURIComponent(dn)}`)
export const executePlaybook  = (dirId, id, targetDns)      => client.post(`${base(dirId)}/${id}/execute`, { targetDns })
export const rollbackExecution = (dirId, executionId)       => client.post(`${base(dirId)}/executions/${executionId}/rollback`)
export const listExecutions   = (dirId, id)                 => client.get(`${base(dirId)}/${id}/executions`)
