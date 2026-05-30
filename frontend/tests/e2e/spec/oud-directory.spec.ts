// SPDX-License-Identifier: Apache-2.0
//
// Smoke test for Oracle Unified Directory support (P5 of the OUD plan).
//
// Like isva-account.spec.ts, this is API-driven: the e2e harness's
// backend-managed Testcontainer is OpenLDAP, not OpenDJ, so we can't
// exercise the full OUD LDAP path through the harness without standing
// up a second container. What this spec pins is the security- and
// regression-relevant wiring around the new DirectoryType that doesn't
// require a live OUD server:
//
//   - POST /api/v1/superadmin/directories accepts
//     directoryType: ORACLE_UNIFIED_DIRECTORY and returns 201 — verifies
//     the enum is registered (P1) and the column-width fix (V6) holds.
//     If V6 is reverted or the enum dropped, the insert fails with
//     'value too long for type character varying' or a 400 validation
//     error — both surface here loud.
//
//   - The persisted row round-trips through GET — directoryType comes
//     back as ORACLE_UNIFIED_DIRECTORY, capabilities is null because
//     the probe couldn't reach the unreachable host we configured
//     (best-effort behaviour — save still succeeds). If the probe ever
//     stops being best-effort, the create will fail here.
//
// The verb-success paths (browse against a live OUD, isMemberOf
// resolution returning the transitive closure, vendor chip showing
// the OpenDJ version) need the compose-managed OUD fixture and are
// tracked as a follow-up to this spec.

import { test, expect } from '../fixtures'

test.describe('Oracle Unified Directory @smoke', () => {

  test('create + round-trip an OUD-typed directory connection', async ({ superadmin }) => {
    const displayName = `OUD smoke ${Date.now()}`

    // Point at a deliberately unreachable host — the capability probe
    // will fail (the best-effort semantics in
    // LdapCapabilityProbeService.probe just log and continue), the row
    // saves, and capabilities comes back null. Port 1 is reserved and
    // has nothing listening; the connect attempt times out within
    // poolConnectTimeoutSeconds.
    const create = await superadmin.request.post(
      '/api/v1/superadmin/directories',
      {
        data: {
          directoryType:                  'ORACLE_UNIFIED_DIRECTORY',
          displayName,
          host:                           '127.0.0.1',
          port:                           1,
          sslMode:                        'NONE',
          trustAllCerts:                  false,
          bindDn:                         'cn=Directory Manager',
          bindPassword:                   'admin',
          baseDn:                         'dc=oudtest,dc=example,dc=com',
          pagingSize:                     500,
          poolMinSize:                    1,
          poolMaxSize:                    2,
          poolConnectTimeoutSeconds:      1,  // fail-fast on the probe
          poolResponseTimeoutSeconds:     5,
          enabled:                        true,
          selfServiceEnabled:             false,
          selfServiceLoginAttribute:      'uid',
          userBaseDns:                    [],
          groupBaseDns:                   [],
        },
      },
    )
    expect(create.status(), await safeBody(create)).toBe(201)
    const created = await create.json()
    expect(created.directoryType).toBe('ORACLE_UNIFIED_DIRECTORY')
    expect(created.displayName).toBe(displayName)
    expect(created.id).toBeTruthy()

    // Round-trip: GET returns the row with the same type, and
    // capabilities stays null. Two reasons it's null here:
    //   1. The probe runs AFTER_COMMIT on an async listener (see
    //      DirectoryCapabilityRefresher) — the create response was
    //      built before the listener fired, so capabilities is null
    //      in the 201 regardless of probe success.
    //   2. The probe itself couldn't reach port 1, so by the time the
    //      listener runs the targeted UPDATE would write null anyway.
    // The combined effect is a stable null assertion that doesn't
    // race the async listener.
    try {
      const get = await superadmin.request.get(
        `/api/v1/superadmin/directories/${created.id}`,
      )
      expect(get.status()).toBe(200)
      const fetched = await get.json()
      expect(fetched.directoryType).toBe('ORACLE_UNIFIED_DIRECTORY')
      expect(fetched.capabilities).toBeNull()
    } finally {
      // Cleanup — keep the e2e DB tidy across runs. Even on assertion
      // failure above, we want to remove the row so the next run's
      // displayName check is unambiguous.
      await superadmin.request.delete(
        `/api/v1/superadmin/directories/${created.id}`,
      )
    }
  })

  test('list endpoint surfaces the new directoryType', async ({ superadmin }) => {
    // Belt-and-braces: even if the create path somehow accepted the
    // new enum but the list serialiser dropped it, the dropdown in
    // the directory-connections UI would silently misrender. Pin
    // that the enum value survives a list-shaped response too.
    const displayName = `OUD list smoke ${Date.now()}`
    const create = await superadmin.request.post(
      '/api/v1/superadmin/directories',
      {
        data: {
          directoryType:                  'ORACLE_UNIFIED_DIRECTORY',
          displayName,
          host:                           '127.0.0.1',
          port:                           1,
          sslMode:                        'NONE',
          trustAllCerts:                  false,
          bindDn:                         'cn=Directory Manager',
          bindPassword:                   'admin',
          baseDn:                         'dc=oudtest,dc=example,dc=com',
          pagingSize:                     500,
          poolMinSize:                    1,
          poolMaxSize:                    2,
          poolConnectTimeoutSeconds:      1,
          poolResponseTimeoutSeconds:     5,
          enabled:                        true,
          selfServiceEnabled:             false,
          selfServiceLoginAttribute:      'uid',
          userBaseDns:                    [],
          groupBaseDns:                   [],
        },
      },
    )
    expect(create.status()).toBe(201)
    const id = (await create.json()).id

    try {
      const list = await superadmin.request.get(
        '/api/v1/superadmin/directories',
      )
      expect(list.status()).toBe(200)
      const all = await list.json()
      const ours = all.find((d: { id: string }) => d.id === id)
      expect(ours).toBeTruthy()
      expect(ours.directoryType).toBe('ORACLE_UNIFIED_DIRECTORY')
    } finally {
      await superadmin.request.delete(
        `/api/v1/superadmin/directories/${id}`,
      )
    }
  })
})

// Best-effort body reader for assertion messages — Playwright's
// default failure renders a status code without the body, and a 400
// validation error is much easier to diagnose with the ProblemDetail
// in the failure message.
async function safeBody(r: { text: () => Promise<string> }): Promise<string> {
  try { return await r.text() } catch { return '<no body>' }
}
