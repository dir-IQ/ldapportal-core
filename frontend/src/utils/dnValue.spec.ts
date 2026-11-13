// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import { looksLikeDn, rdnLabel } from './dnValue'

describe('looksLikeDn', () => {
  it.each([
    'uid=alice,ou=people,dc=example,dc=com',
    'cn=Smith\\, John,ou=people,o=corp',
    'secAuthority=Default,o=ibm,c=us',
    ' cn=admins , ou=groups ',
    'cn=a+uid=b,dc=x',
  ])('links %s', (v) => {
    expect(looksLikeDn(v)).toBe(true)
  })

  it.each([
    '',
    'alice',
    'cn=admins',                 // a single RDN is too ambiguous to link
    'cn=Smith\\, John',          // ditto, despite the escaped comma
    'hello, world',
    'a=b,',
    '20240101000000Z',
    'https://example.com/?a=b,c=d x',
  ])('does not link %j', (v) => {
    expect(looksLikeDn(v)).toBe(false)
  })

  it('rejects non-strings', () => {
    expect(looksLikeDn(undefined)).toBe(false)
    expect(looksLikeDn(42)).toBe(false)
  })
})

describe('rdnLabel', () => {
  it('returns the leading RDN value', () => {
    expect(rdnLabel('uid=alice,ou=people,dc=x')).toBe('alice')
    expect(rdnLabel('cn=Smith\\, John,ou=people,dc=x')).toBe('Smith, John')
  })
})
