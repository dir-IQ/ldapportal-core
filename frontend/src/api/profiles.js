// SPDX-License-Identifier: Apache-2.0
import client from './client'

const dirBase = (dirId) => `/directories/${dirId}/profiles`

// Profile CRUD (directory-scoped)
export const listAllProfiles   = ()                          => client.get('/profiles')
export const listProfiles      = (dirId)                     => client.get(dirBase(dirId))
export const getProfile        = (dirId, profileId)          => client.get(`${dirBase(dirId)}/${profileId}`)
export const createProfile     = (dirId, data, force = false) =>
  client.post(dirBase(dirId), data, { params: force ? { force: true } : {} })
export const updateProfile     = (dirId, profileId, data, force = false) =>
  client.put(`${dirBase(dirId)}/${profileId}`, data, { params: force ? { force: true } : {} })

// Probe whether the target OU DN actually exists in the directory.
// Used by the profile editor's warning banner so admins see a typo'd
// or missing OU during editing instead of discovering it the first
// time a user creation fails with NO_SUCH_OBJECT.
export const probeTargetOu    = (dirId, dn) =>
  client.post(`${dirBase(dirId)}/probe-target-ou`, null, { params: { dn } })
export const deleteProfile     = (dirId, profileId)          => client.delete(`${dirBase(dirId)}/${profileId}`)
export const cloneProfile      = (dirId, profileId, name)    => client.post(`${dirBase(dirId)}/${profileId}/clone`, { name })

// Seeds a curated set of attribute configs for a known schema
// (currently 'inetOrgPerson'). Refuses 409 if the profile already
// has attribute configs.
export const seedAttributeDefaults = (dirId, profileId, schema = 'inetOrgPerson') =>
  client.post(`${dirBase(dirId)}/${profileId}/seed-attribute-defaults`, null, { params: { schema } })

// Password generation
export const generatePassword = (profileId) => client.post(`/profiles/${profileId}/generate-password`)

// Lifecycle policy
export const getLifecyclePolicy    = (profileId)       => client.get(`/profiles/${profileId}/lifecycle`)
export const setLifecyclePolicy    = (profileId, data) => client.put(`/profiles/${profileId}/lifecycle`, data)
export const deleteLifecyclePolicy = (profileId)       => client.delete(`/profiles/${profileId}/lifecycle`)

// Approval config
export const getApprovalConfig  = (profileId)       => client.get(`/profiles/${profileId}/approval`)
export const setApprovalConfig  = (profileId, data) => client.put(`/profiles/${profileId}/approval`, data)

// Approvers
export const getApprovers  = (profileId)       => client.get(`/profiles/${profileId}/approvers`)
export const setApprovers  = (profileId, data) => client.put(`/profiles/${profileId}/approvers`, data)

// Group change evaluation
export const evaluateGroupChanges = (dirId, profileId) => client.post(`${dirBase(dirId)}/${profileId}/evaluate-group-changes`)
export const applyGroupChanges    = (dirId, profileId) => client.post(`${dirBase(dirId)}/${profileId}/apply-group-changes`)
export const applySelectiveGroupChanges = (dirId, entries) => client.post(`${dirBase(dirId)}/apply-selective-group-changes`, { entries })
