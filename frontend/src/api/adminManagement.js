// SPDX-License-Identifier: Apache-2.0
import client from './client'

export const listAdmins    = ()           => client.get('/superadmin/admins')
export const getAdmin      = (id)         => client.get(`/superadmin/admins/${id}`)
export const createAdmin   = (data)       => client.post('/superadmin/admins', data)
export const createAdminWithPermissions = (data) =>
  client.post('/superadmin/admins/with-permissions', data)
export const updateAdmin   = (id, data)   => client.put(`/superadmin/admins/${id}`, data)
export const deleteAdmin   = (id)         => client.delete(`/superadmin/admins/${id}`)
export const getPermissions = (id)        => client.get(`/superadmin/admins/${id}/permissions`)
export const getEffectivePermissions = (id) => client.get(`/superadmin/admins/${id}/effective-permissions`)
