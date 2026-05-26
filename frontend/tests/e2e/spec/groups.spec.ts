// Smoke test for LDAP group creation.
//
// Headline flow: superadmin creates a groupOfNames entry under
// ou=groups, with seedAlice as initial member. Verifies the
// directory write path: cookie auth -> DirectoryConnection lookup
// -> bind -> ADD operation through the connection pool. Idempotent
// on re-run (409 tolerated; OpenLDAP testcontainer state may persist
// across tests within the same JVM).

import { test, expect } from '../fixtures'
import { seededDirectoryId } from '../helpers/directory'

test.describe('LDAP groups @smoke', () => {
  test('superadmin can create a group with one member', async ({ superadmin }) => {
    const directoryId = await seededDirectoryId(superadmin)
    const groupDn = `cn=e2e-smoke-group-${Date.now()},ou=groups,dc=test,dc=local`

    const response = await superadmin.request.post(
      `/api/v1/directories/${directoryId}/groups`,
      {
        data: {
          dn: groupDn,
          attributes: {
            objectClass: ['groupOfNames'],
            cn: [groupDn.split(',')[0].split('=')[1]],
            member: ['cn=seedAlice,ou=seed,ou=test,dc=test,dc=local'],
          },
        },
      },
    )
    // 201 on first create; 409 on idempotent re-run (rare — only if a
    // previous run left state in the same JVM's container).
    expect([201, 409]).toContain(response.status())

    if (response.status() === 201) {
      const body = await response.json()
      expect(body.dn).toBe(groupDn)
      // LDAP attribute names normalize to lowercase; objectClass round-trips
      // as "objectclass" through the controller.
      const objectClass = body.attributes.objectClass ?? body.attributes.objectclass
      expect(objectClass).toContain('groupOfNames')
      expect(body.attributes.member).toContain('cn=seedAlice,ou=seed,ou=test,dc=test,dc=local')
    }
  })
})
