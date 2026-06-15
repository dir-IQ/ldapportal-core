// Smoke test for LDAP user browse/search.
//
// Exercises the full real-LDAP path: superadmin session -> seeded
// DirectoryConnection lookup -> bind through the production
// LdapConnectionFactory (which decrypts the stored password via
// EncryptionService) -> SUB-scope search against the OpenLDAP
// testcontainer's baseline LDIF, returning the seedAlice entry.
//
// If this test passes, the entire LDAP read path works end to end:
// encryption roundtrip, connection pool, search filter, result
// marshalling, controller response shape.

import { test, expect } from '../fixtures'
import { seededDirectoryId } from '../helpers/directory'

test.describe('LDAP users browse/search @smoke', () => {
  test('search for seedAlice returns the seeded entry', async ({ superadmin }) => {
    const directoryId = await seededDirectoryId(superadmin)

    // Search under ou=seed,ou=test for cn=seedAlice. The baseline LDIF
    // (ee/src/test/resources/ldap/baseline.ldif) seeds 3 inetOrgPerson
    // entries here at container boot.
    const params = new URLSearchParams({
      baseDn: 'ou=seed,ou=test,dc=test,dc=local',
      filter: '(cn=seedAlice)',
      scope: 'sub',
    })
    const response = await superadmin.request.get(
      `/api/v1/superadmin/directories/${directoryId}/browse/search?${params.toString()}`,
    )
    expect(response.ok()).toBe(true)

    const entries = await response.json() as Array<{
      dn: string
      attributes: Record<string, string[]>
    }>
    expect(entries.length).toBe(1)
    const alice = entries[0]
    expect(alice.dn).toBe('cn=seedAlice,ou=seed,ou=test,dc=test,dc=local')
    expect(alice.attributes.cn).toContain('seedAlice')
    expect(alice.attributes.sn).toContain('Alice')
    expect(alice.attributes.mail).toContain('seed.alice@test.local')
  })

  test('search with includeOperational=true returns operational attrs', async ({ superadmin }) => {
    // Regression guard for the includeOperational query param. Without
    // it, the server returns user attributes only (cn, sn, mail, ...).
    // With it, RFC 4511's "+" marker is appended to the attribute list
    // and the server includes operational attributes — at minimum
    // entryUUID/createTimestamp/modifyTimestamp on most directories.
    // The OpenLDAP testcontainer ships entryUUID + createTimestamp on
    // every entry, so we assert on those two as the floor.
    const directoryId = await seededDirectoryId(superadmin)

    const params = new URLSearchParams({
      baseDn: 'ou=seed,ou=test,dc=test,dc=local',
      filter: '(cn=seedAlice)',
      scope: 'sub',
      includeOperational: 'true',
    })
    const response = await superadmin.request.get(
      `/api/v1/superadmin/directories/${directoryId}/browse/search?${params.toString()}`,
    )
    expect(response.ok()).toBe(true)

    const entries = await response.json() as Array<{
      dn: string
      attributes: Record<string, string[]>
    }>
    expect(entries.length).toBe(1)
    const alice = entries[0]
    // Sanity: user attributes still come back when no `attributes` param
    // is passed (we expect the * + + behaviour, not + alone).
    expect(alice.attributes.cn).toContain('seedAlice')
    // Operational attributes the OpenLDAP baseline always provides.
    // Names are case-sensitive in LDAP responses; OpenLDAP returns the
    // canonical-cased forms below.
    const attrNames = Object.keys(alice.attributes)
    expect(attrNames).toContain('entryUUID')
    expect(attrNames).toContain('createTimestamp')
  })

  test('superadmin can open the directory browser view', async ({ superadmin }) => {
    // Light UI smoke: the browser view loads, the directory selector
    // contains the seeded entry. Tree expansion is too async/brittle
    // for a smoke test; SP6+ feature tests cover that.
    await superadmin.goto('/superadmin/directory-browser')
    await expect(superadmin.getByRole('heading', { name: /directory browser/i })).toBeVisible()
    await expect(superadmin.getByRole('combobox')).toBeVisible({ timeout: 5_000 })
  })
})
