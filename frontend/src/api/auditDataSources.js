// SPDX-License-Identifier: Apache-2.0
import client from './client'

export const listAuditSources   = ()            => client.get('/superadmin/audit-sources')
export const createAuditSource  = (data)        => client.post('/superadmin/audit-sources', data)
export const getAuditSource     = (id)          => client.get(`/superadmin/audit-sources/${id}`)
export const updateAuditSource  = (id, data)    => client.put(`/superadmin/audit-sources/${id}`, data)
export const deleteAuditSource  = (id)          => client.delete(`/superadmin/audit-sources/${id}`)
export const testAuditSource   = (data)        => client.post('/superadmin/audit-sources/test', data)
