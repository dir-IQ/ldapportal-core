// SPDX-License-Identifier: Apache-2.0
import client from './client'

// ── Auth ──────────────────────────────────────────────────────────────────────
export const selfServiceLogin = (directoryId, username, password) =>
  client.post('/auth/self-service/login', { directoryId, username, password })

// ── Authenticated self-service ────────────────────────────────────────────────
export const getTemplate       = ()           => client.get('/self-service/template')
export const getProfile        = ()           => client.get('/self-service/profile')
export const updateProfile     = (attributes) => client.put('/self-service/profile', attributes)
export const changePassword    = (currentPassword, newPassword) =>
  client.post('/self-service/change-password', { currentPassword, newPassword })
export const getGroups         = ()           => client.get('/self-service/groups')

// ── Public registration ───────────────────────────────────────────────────────
export const listRegistrationDirectories = () =>
  client.get('/self-service/register/directories')

export const listRegistrationProfiles = (directoryId) =>
  client.get(`/self-service/register/profiles/${directoryId}`)

export const getRegistrationForm = (profileId) =>
  client.get(`/self-service/register/form/${profileId}`)

export const submitRegistration = (profileId, email, justification, attributes) =>
  client.post('/self-service/register/submit', { profileId, email, justification, attributes })

export const verifyEmail = (token) =>
  client.post(`/self-service/register/verify/${token}`)

export const getRegistrationStatus = (requestId, email) =>
  client.get(`/self-service/register/status/${requestId}`, { params: { email } })
