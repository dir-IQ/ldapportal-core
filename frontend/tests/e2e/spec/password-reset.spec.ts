// Smoke test for admin-initiated password reset on an LDAP user.
//
// Headline flow: superadmin POSTs a new password for a target DN
// against the seeded directory connection. Exercises the modify-write
// path through the production LdapConnectionFactory.

import { test, expect } from '../fixtures'
import { seededDirectoryId } from '../helpers/directory'

test.describe('Password reset @smoke', () => {
  test('superadmin can reset a user password against the seeded directory', async ({ superadmin }) => {
    const directoryId = await seededDirectoryId(superadmin)
    const targetDn = 'cn=seedAlice,ou=seed,ou=test,dc=test,dc=local'

    const params = new URLSearchParams({ dn: targetDn })
    const response = await superadmin.request.post(
      `/api/v1/directories/${directoryId}/users/reset-password?${params.toString()}`,
      {
        data: { newPassword: 'smoke-Reset-Pw-12345' },
      },
    )
    // Controller returns 204 No Content on success per the spec; some
    // success responses come back as 200. Accept either; the negative
    // case (4xx) would mean the modify failed.
    expect([200, 204]).toContain(response.status())
  })
})
