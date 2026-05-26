// SPDX-License-Identifier: Apache-2.0
/**
 * Spec for the inline-edit classification helpers. Pure logic — no
 * @vue/test-utils mount; just the table-driven cases listed in the
 * Phase 1 plan.
 */
import { describe, it, expect } from 'vitest'
import {
  classify,
  rdnAttribute,
  isAttributeEditable,
  type AttributeTypeInfo,
  type DirectoryEntry,
} from './useEntryClassification'

const schemaWith = (
  attrs: Array<{ name: string; singleValued?: boolean; required?: boolean; syntaxOid?: string }>,
): Map<string, AttributeTypeInfo> => {
  const m = new Map<string, AttributeTypeInfo>()
  for (const a of attrs) {
    m.set(a.name.toLowerCase(), {
      name: a.name,
      oid: '1.2.3',
      syntaxOid: a.syntaxOid ?? '1.3.6.1.4.1.1466.115.121.1.15',
      singleValued: a.singleValued ?? true,
    } as AttributeTypeInfo)
  }
  return m
}

const userEntry = (dn = 'cn=alice,ou=people,dc=example,dc=com'): DirectoryEntry => ({
  dn,
  attributes: { objectClass: ['inetOrgPerson', 'top'], cn: ['alice'], mail: ['a@x'] },
})

const groupEntry = (dn = 'cn=devs,ou=groups,dc=example,dc=com'): DirectoryEntry => ({
  dn,
  attributes: { objectClass: ['groupOfNames', 'top'], cn: ['devs'], member: ['cn=alice,…'] },
})

describe('classify', () => {
  it.each([
    [['inetOrgPerson', 'top'],          'user'],
    [['user', 'top'],                   'user'], // AD
    [['posixAccount'],                  'user'],
    [['organizationalPerson', 'top'],   'user'],
    [['groupOfNames', 'top'],           'group'],
    [['groupOfUniqueNames'],            'group'],
    [['group'],                         'group'], // AD group
    [['posixGroup'],                    'group'],
    [['organizationalUnit'],            'unknown'],
    [[],                                'unknown'],
  ] as const)('returns %s for objectClass=%j', (oc, expected) => {
    const entry: DirectoryEntry = { dn: 'cn=x,dc=y', attributes: { objectClass: [...oc] } }
    expect(classify(entry)).toBe(expected)
  })

  it('returns unknown when objectClass is absent', () => {
    expect(classify({ dn: 'cn=x,dc=y', attributes: {} })).toBe('unknown')
  })

  it('is case-insensitive on objectClass values', () => {
    // AD often returns mixed case ("User") and OpenLDAP returns
    // exact case ("inetOrgPerson"). The classifier normalises.
    const entry: DirectoryEntry = {
      dn: 'cn=x,dc=y',
      attributes: { objectClass: ['INETORGPERSON', 'TOP'] },
    }
    expect(classify(entry)).toBe('user')
  })

  it('reads lowercased "objectclass" key as fallback', () => {
    // Some clients normalise attribute keys to lowercase.
    const entry: DirectoryEntry = {
      dn: 'cn=x,dc=y',
      attributes: { objectclass: ['groupOfNames'] },
    }
    expect(classify(entry)).toBe('group')
  })

  it('group beats user when both class sets match', () => {
    // Hypothetical entry that has both — disambiguates toward
    // group because group-edit semantics are stricter (member list
    // shape) and the user route would mis-edit.
    const entry: DirectoryEntry = {
      dn: 'cn=x,dc=y',
      attributes: { objectClass: ['posixAccount', 'posixGroup'] },
    }
    expect(classify(entry)).toBe('group')
  })
})

describe('rdnAttribute', () => {
  it('extracts the first RDN component attribute name', () => {
    expect(rdnAttribute('cn=alice,ou=people,dc=example,dc=com')).toBe('cn')
  })

  it('lowercases for comparison', () => {
    expect(rdnAttribute('CN=alice,DC=example')).toBe('cn')
  })

  it('handles a single-RDN DN', () => {
    expect(rdnAttribute('uid=root')).toBe('uid')
  })

  it('returns empty string on empty DN', () => {
    expect(rdnAttribute('')).toBe('')
  })
})

describe('isAttributeEditable', () => {
  const schema = schemaWith([
    { name: 'cn' },
    { name: 'mail' },
    { name: 'description', singleValued: false },
    { name: 'givenName' },
    { name: 'modifyTimestamp' },
  ])

  it('locks the dn synthetic column', () => {
    expect(isAttributeEditable(userEntry(), 'dn', schema)).toBe(false)
  })

  it('locks objectClass', () => {
    expect(isAttributeEditable(userEntry(), 'objectClass', schema)).toBe(false)
    expect(isAttributeEditable(userEntry(), 'objectclass', schema)).toBe(false)
  })

  it('locks operational attrs', () => {
    expect(isAttributeEditable(userEntry(), 'modifyTimestamp', schema)).toBe(false)
    expect(isAttributeEditable(userEntry(), 'entryUUID', schema)).toBe(false)
    expect(isAttributeEditable(userEntry(), 'createTimestamp', schema)).toBe(false)
  })

  it('locks the RDN attribute for the row', () => {
    expect(isAttributeEditable(userEntry(), 'cn', schema)).toBe(false)
  })

  it('locks every attribute on unknown classification', () => {
    const ou: DirectoryEntry = {
      dn: 'ou=people,dc=example',
      attributes: { objectClass: ['organizationalUnit'] },
    }
    expect(isAttributeEditable(ou, 'description', schema)).toBe(false)
    expect(isAttributeEditable(ou, 'mail', schema)).toBe(false)
  })

  it('locks attributes the schema does not know about', () => {
    expect(isAttributeEditable(userEntry(), 'someUnknownAttr', schema)).toBe(false)
  })

  it('locks multi-valued attributes (Phase 1 — chip editor lands in Phase 1.5)', () => {
    expect(isAttributeEditable(userEntry(), 'description', schema)).toBe(false)
  })

  it('allows a non-RDN, single-valued, schema-known string attribute on a user', () => {
    expect(isAttributeEditable(userEntry(), 'mail', schema)).toBe(true)
    expect(isAttributeEditable(userEntry(), 'givenName', schema)).toBe(true)
  })

  it('allows an attribute on a group entry too (RDN locks "cn" though)', () => {
    expect(isAttributeEditable(groupEntry(), 'mail', schema)).toBe(true)
    expect(isAttributeEditable(groupEntry(), 'cn', schema)).toBe(false) // RDN
  })
})
