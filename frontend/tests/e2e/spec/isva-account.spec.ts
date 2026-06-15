// Smoke test for the IVIA account-management control plane (P2 endpoints).
//
// Like isva-config.spec.ts, this is API-driven rather than UI-driven:
// the OpenLDAP fixture isn't IVIA-schema-loaded, so the grant /
// suspend / etc verbs can't succeed against it at the LDAP layer.
// What this spec pins is the security-relevant wiring that doesn't
// require the LDAP write to succeed:
//
//   - GET /isva-account on a directory with no active IVIA config
//     returns 409 + ProblemDetail with code: ivia_directory_disabled.
//     This is the gate the IsvaAccountPanel relies on to stay hidden
//     on non-IVIA directories — if this regresses, the panel renders
//     and operators see verbs that immediately 500 against LDAP.
//
//   - Verb endpoints (grant, suspend, etc) gated by the same check —
//     a regression here would let the controller hit the LDAP layer
//     uncontrolled on a non-IVIA directory.
//
//   - The seven endpoints exist + answer at the documented paths.
//     Wrong-shape responses or missing endpoints fail here loud.
//
// The verb-success paths (grant on a real IVIA-ready LDAP → status
// flips to linked, suspend round-trips, etc) need a fixture with the
// secUser schema loaded; tracked as a follow-up to this spec.

import { test, expect } from '../fixtures'
import { seededDirectoryId } from '../helpers/directory'

const SOME_DN = 'uid=e2e-smoke-target,ou=people,dc=example,dc=com'
const dnQuery = (dn: string) => `?dn=${encodeURIComponent(dn)}`

test.describe('IVIA account control plane @smoke', () => {

  test('GET returns 409 ivia_directory_disabled when no IVIA config on the directory', async ({ superadmin }) => {
    const directoryId = await seededDirectoryId(superadmin)

    const response = await superadmin.request.get(
      `/api/v1/directories/${directoryId}/isva-account${dnQuery(SOME_DN)}`,
    )
    expect(response.status()).toBe(409)
    const body = await response.json()
    expect(body.status).toBe(409)
    expect(body.code).toBe('ivia_directory_disabled')
  })

  test('verb endpoints answer at their documented paths', async ({ superadmin }) => {
    // We don't expect the verbs to succeed (no IVIA config, no real
    // IVIA-ready LDAP), but each endpoint should route — i.e. return
    // a 4xx ProblemDetail rather than 404 (route not found) or 500.
    const directoryId = await seededDirectoryId(superadmin)
    const base = `/api/v1/directories/${directoryId}/isva-account`
    const q = dnQuery(SOME_DN)

    const checks = [
      { method: 'post', path: `${base}/grant${q}`, body: undefined },
      { method: 'post', path: `${base}/suspend${q}`, body: undefined },
      { method: 'post', path: `${base}/restore${q}`, body: undefined },
      { method: 'post', path: `${base}/force-credential-reset${q}`, body: undefined },
      { method: 'post', path: `${base}/revoke${q}`, body: { mode: 'SOFT' } },
      { method: 'post', path: `${base}/renew${q}`, body: { validUntil: '2030-01-01T00:00:00Z' } },
    ]

    for (const c of checks) {
      const r = await superadmin.request.post(c.path, c.body ? { data: c.body } : undefined)
      // Without an IVIA config on the directory every verb refuses
      // up-front with ivia_directory_disabled — same gate as GET.
      expect(r.status(), `${c.path} status`).toBeGreaterThanOrEqual(400)
      expect(r.status(), `${c.path} status`).toBeLessThan(500)
      const body = await r.json()
      expect(body.code, `${c.path} code`).toBe('ivia_directory_disabled')
    }
  })

  test('renew with invalid body shape returns 400 (validation)', async ({ superadmin }) => {
    // Even before the ivia_directory_disabled refusal, an empty body
    // should be rejected by @NotNull on the RenewRequest. Pins that
    // the controller's @Valid + @RequestBody is wired.
    const directoryId = await seededDirectoryId(superadmin)
    const r = await superadmin.request.post(
      `/api/v1/directories/${directoryId}/isva-account/renew${dnQuery(SOME_DN)}`,
      { data: {} },
    )
    expect(r.status()).toBe(400)
  })

  test('revoke with invalid mode returns 400 (validation)', async ({ superadmin }) => {
    const directoryId = await seededDirectoryId(superadmin)
    const r = await superadmin.request.post(
      `/api/v1/directories/${directoryId}/isva-account/revoke${dnQuery(SOME_DN)}`,
      { data: { mode: 'BOGUS' } },
    )
    expect(r.status()).toBe(400)
  })
})
