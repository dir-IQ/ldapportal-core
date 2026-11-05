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

/** Sends a test email with the saved SMTP settings; resolves to { ok, message }. */
export const testSmtp = (recipient) =>
  client.post('/settings/smtp/test', { recipient })

export const siemBackfill = (from, to) =>
  client.post('/settings/siem/backfill', null, { params: { from, to } })

/** Total pending approval requests across all directories (superadmin). */
export const getPendingApprovalsCount = () =>
  client.get('/settings/pending-approvals-count')
