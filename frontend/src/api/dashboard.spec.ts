// SPDX-License-Identifier: Apache-2.0
/**
 * getUnifiedDashboard() drives the dashboard's two-phase load: the view first
 * requests the fast count-less payload (includeScopeCounts=false) for an
 * immediate first paint, then re-requests the full payload to fill the LDAP
 * user/group counts in. These pin that the param is sent (and defaults to the
 * full payload for other callers).
 */
import { describe, it, expect, vi } from 'vitest'

vi.mock('./client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

import client from './client'
import { getUnifiedDashboard } from './dashboard'

describe('getUnifiedDashboard', () => {
  it('requests the full payload (counts included) by default', () => {
    getUnifiedDashboard()
    expect(vi.mocked(client.get)).toHaveBeenCalledWith(
      '/dashboard/summary', { params: { includeScopeCounts: true } })
  })

  it('requests the fast count-less payload when asked', () => {
    getUnifiedDashboard(false)
    expect(vi.mocked(client.get)).toHaveBeenCalledWith(
      '/dashboard/summary', { params: { includeScopeCounts: false } })
  })
})
