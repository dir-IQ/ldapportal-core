// SPDX-License-Identifier: Apache-2.0
import client from './client'

// `includeScopeCounts=false` returns the dashboard without the LDAP user/group
// counts (the slow part), so the page can paint immediately. The view then
// re-requests with counts included to fill them in. Defaults to true so other
// callers get the full payload.
export const getUnifiedDashboard = (includeScopeCounts = true) =>
  client.get('/dashboard/summary', { params: { includeScopeCounts } })

export const dismissSuggestion = (key) =>
  client.post(`/dashboard/activity/dismiss/${key}`)

// ── Dashboard layout (per-account customization) ──────────────────────────
export const getDashboardLayout = () =>
  client.get('/dashboard/layout')

export const saveDashboardLayout = (layout) =>
  client.put('/dashboard/layout', layout)

export const resetDashboardLayout = () =>
  client.delete('/dashboard/layout')
