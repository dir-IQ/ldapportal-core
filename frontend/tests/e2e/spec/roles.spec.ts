// Smoke test for role-based permission gating.
//
// Verifies that the SUPERADMIN/ADMIN boundary actually enforces:
// SUPERADMIN can list/inspect admins; ADMIN cannot. Catches regressions
// where @PreAuthorize annotations get dropped or the JWT role claim
// stops being populated correctly.
//
// Uses both fixtures from globalSetup: the bootstrapped superadmin
// and the e2e-admin ADMIN account that globalSetup provisions via
// /api/v1/superadmin/admins on first run.

import { test, expect } from '../fixtures'

test.describe('Role permissions @smoke', () => {
  test('superadmin can list admins; admin cannot', async ({ superadmin, admin }) => {
    // SUPERADMIN-only endpoint per AdminManagementController's
    // class-level @PreAuthorize("hasRole('SUPERADMIN')").
    const superadminResp = await superadmin.request.get('/api/v1/superadmin/admins')
    expect(superadminResp.ok()).toBe(true)
    const admins = await superadminResp.json() as Array<{ username: string; role: string }>
    // The bootstrapped superadmin should always be in the list.
    expect(admins.some(a => a.username === 'superadmin')).toBe(true)

    // ADMIN role hits the same endpoint and gets denied. SecurityConfig
    // gates `/api/v1/superadmin/**` at the filter-chain level via
    // `.requestMatchers().hasRole("SUPERADMIN")`. In this app's setup
    // that path yields 401 (not 403) when an authenticated ADMIN tries
    // it — the AuthenticationEntryPoint handles the deny. Both statuses
    // prove the boundary is enforced; the smoke test's job is to catch
    // accidental wide-open access (200 from this endpoint by an admin).
    const adminResp = await admin.request.get('/api/v1/superadmin/admins')
    expect([401, 403]).toContain(adminResp.status())
    expect(adminResp.status()).not.toBe(200)
  })
})
