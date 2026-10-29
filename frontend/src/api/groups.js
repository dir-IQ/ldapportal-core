// SPDX-License-Identifier: Apache-2.0
import client from './client'

const base = (dirId) => `/directories/${dirId}/groups`

export const searchGroups = (dirId, params) =>
  client.get(base(dirId), { params })

export const getGroup = (dirId, dn) =>
  client.get(`${base(dirId)}/entry`, { params: { dn } })

export const createGroup = (dirId, data) =>
  client.post(base(dirId), data)

export const updateGroup = (dirId, dn, data) =>
  client.put(`${base(dirId)}/entry`, data, { params: { dn } })

export const deleteGroup = (dirId, dn) =>
  client.delete(`${base(dirId)}/entry`, { params: { dn } })

export const addGroupMember = (dirId, dn, data) =>
  client.post(`${base(dirId)}/members`, data, { params: { dn } })

// The member goes in the query, not a DELETE body: reverse proxies such as
// WebSEAL don't reliably forward a DELETE request's body.
export const removeGroupMember = (dirId, dn, data) =>
  client.delete(`${base(dirId)}/members`, {
    params: { dn, memberAttribute: data.memberAttribute, memberValue: data.memberValue },
  })

export const addGroupMembersBulk = (dirId, dn, data) =>
  client.post(`${base(dirId)}/members/bulk`, data, { params: { dn } })

// A POST: a list of DNs is too long for a query, and a DELETE body may be dropped by a proxy.
export const removeGroupMembersBulk = (dirId, dn, data) =>
  client.post(`${base(dirId)}/members/bulk/remove`, data, { params: { dn } })
