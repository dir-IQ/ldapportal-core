// SPDX-License-Identifier: Apache-2.0
import client from './client'

export const login = (username, password) =>
  client.post('/auth/login', { username, password })

export const logout = () =>
  client.post('/auth/logout')

export const me = () =>
  client.get('/auth/me')

export const myProfiles = () =>
  client.get('/auth/me/profiles')

export const oidcAuthorize = () =>
  client.get('/auth/oidc/authorize')

export const oidcCallback = (code, state) =>
  client.post('/auth/oidc/callback', { code, state })

/**
 * Silent pre-auth probe for requests that came in through WebSEAL. Returns
 * a LoginResponse on success (cookie already set by the server) or 401 on
 * any failure path — callers should treat the rejection as "fall back to
 * the normal login form".
 */
export const websealAuthorize = () =>
  client.get('/auth/webseal/authorize')

export const updatePreferences = (prefs) =>
  client.post('/auth/me/preferences', prefs)

export const changePassword = (currentPassword, newPassword) =>
  client.post('/auth/me/change-password', { currentPassword, newPassword })
