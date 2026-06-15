// SPDX-License-Identifier: Apache-2.0
import client from './client'

export const getUnifiedDashboard = () =>
  client.get('/dashboard/summary')

export const dismissSuggestion = (key) =>
  client.post(`/dashboard/activity/dismiss/${key}`)

// ── Dashboard layout (per-account customization) ──────────────────────────
export const getDashboardLayout = () =>
  client.get('/dashboard/layout')

export const saveDashboardLayout = (layout) =>
  client.put('/dashboard/layout', layout)

export const resetDashboardLayout = () =>
  client.delete('/dashboard/layout')
