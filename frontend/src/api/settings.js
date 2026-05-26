// SPDX-License-Identifier: Apache-2.0
import client from './client'

export const getSettings = () =>
  client.get('/settings')

export const updateSettings = (data) =>
  client.put('/settings', data)

/** Public (no auth required) — returns only branding fields. */
export const getBranding = () =>
  client.get('/settings/branding')

export const completeSetup = () =>
  client.post('/settings/complete-setup')

export const testSiem = () =>
  client.post('/settings/siem/test')

export const siemBackfill = (from, to) =>
  client.post('/settings/siem/backfill', null, { params: { from, to } })
