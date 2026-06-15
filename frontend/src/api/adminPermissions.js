// SPDX-License-Identifier: Apache-2.0
import client from './client'

const base = (adminId) => `/superadmin/admins/${adminId}/permissions`

export const getPermissions         = (adminId)               => client.get(base(adminId))
export const setProfileRole         = (adminId, data)         => client.put(`${base(adminId)}/profile-roles`, data)
export const removeProfileRole      = (adminId, profileId)    => client.delete(`${base(adminId)}/profile-roles/${profileId}`)

export const setFeaturePermissions  = (adminId, features)     => client.put(`${base(adminId)}/features`, features)
export const clearFeaturePermission = (adminId, featureKey)   => client.delete(`${base(adminId)}/features/${featureKey}`)
