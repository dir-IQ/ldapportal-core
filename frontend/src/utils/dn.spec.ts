// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import { parseLeadingRdn, ensureNamingValues, normalizeDnForCompare, dnEquals } from './dn'

describe('parseLeadingRdn', () => {
  it('parses a single-valued RDN', () => {
    expect(parseLeadingRdn('uid=jsmith,ou=people,dc=example,dc=com'))
      .toEqual([{ name: 'uid', value: 'jsmith' }])
  })

  it('parses every component of a multi-valued RDN', () => {
    expect(parseLeadingRdn('o=0001+cn=Sanjay Mishra,ou=People,dc=oud1,dc=example,dc=com'))
      .toEqual([
        { name: 'o', value: '0001' },
        { name: 'cn', value: 'Sanjay Mishra' },
      ])
  })

  it('honours an escaped + inside a value (still a single AVA)', () => {
    expect(parseLeadingRdn('cn=00001\\+Sanjay Mishra,ou=People,dc=example,dc=com'))
      .toEqual([{ name: 'cn', value: '00001+Sanjay Mishra' }])
  })

  it('honours an escaped comma inside a value', () => {
    expect(parseLeadingRdn('cn=Mishra\\, Sanjay,ou=people,dc=example,dc=com'))
      .toEqual([{ name: 'cn', value: 'Mishra, Sanjay' }])
  })

  it('decodes hex-pair escapes', () => {
    expect(parseLeadingRdn('cn=a\\2Cb,dc=example,dc=com'))
      .toEqual([{ name: 'cn', value: 'a,b' }])
  })

  it('returns nothing for blank or component-less input', () => {
    expect(parseLeadingRdn('')).toEqual([])
    expect(parseLeadingRdn('   ')).toEqual([])
    expect(parseLeadingRdn('no-equals-here')).toEqual([])
  })
})

describe('ensureNamingValues', () => {
  it('adds a missing naming value', () => {
    expect(ensureNamingValues('uid=jsmith,ou=people,dc=x', { cn: ['John Smith'] }))
      .toEqual({ cn: ['John Smith'], uid: ['jsmith'] })
  })

  it('adds every component of a multi-valued RDN', () => {
    expect(ensureNamingValues(
      'o=0001+cn=Sanjay Mishra,ou=People,dc=oud1,dc=example,dc=com',
      { mail: ['sm@example.com'] },
    )).toEqual({
      mail: ['sm@example.com'],
      o: ['0001'],
      cn: ['Sanjay Mishra'],
    })
  })

  it('leaves a case-insensitive match alone and preserves the existing key', () => {
    expect(ensureNamingValues('cn=sanjay mishra,ou=people,dc=x', { CN: ['Sanjay Mishra'] }))
      .toEqual({ CN: ['Sanjay Mishra'] })
  })

  it('appends when the attribute already has other values', () => {
    expect(ensureNamingValues('cn=Primary,ou=people,dc=x', { cn: ['Other'] }))
      .toEqual({ cn: ['Other', 'Primary'] })
  })

  it('does not mutate its input', () => {
    const attrs = { cn: ['Other'] }
    ensureNamingValues('cn=Primary,ou=people,dc=x', attrs)
    expect(attrs).toEqual({ cn: ['Other'] })
  })
})

describe('dnEquals', () => {
  it('treats AVA order within a multi-valued RDN as insignificant', () => {
    // The as-written member value vs the server-normalized entry DN.
    expect(dnEquals(
      'o=0001+cn=Jim Moffett,ou=People,dc=oud1,dc=example,dc=com',
      'cn=Jim Moffett+o=0001,ou=People,dc=oud1,dc=example,dc=com',
    )).toBe(true)
  })

  it('ignores case and spacing around separators', () => {
    expect(dnEquals(
      'CN=Jim Moffett, OU=People, DC=x',
      'cn=jim moffett,ou=people,dc=x',
    )).toBe(true)
  })

  it('honours escaped commas instead of splitting on them', () => {
    expect(dnEquals(
      'cn=Moffett\\, Jim,ou=People,dc=x',
      'cn=moffett\\, jim,ou=people,dc=x',
    )).toBe(true)
    // The escaped comma is part of the value, not an RDN boundary.
    expect(normalizeDnForCompare('cn=Moffett\\, Jim,ou=People,dc=x').split(',').length)
      .toBeGreaterThan(1)
  })

  it('distinguishes genuinely different DNs', () => {
    expect(dnEquals('cn=jim,ou=people,dc=x', 'cn=jim,ou=staff,dc=x')).toBe(false)
    expect(dnEquals('cn=jim+o=1,ou=people,dc=x', 'cn=jim+o=2,ou=people,dc=x')).toBe(false)
    expect(dnEquals('cn=jim,ou=people,dc=x', 'cn=jim+o=1,ou=people,dc=x')).toBe(false)
  })

  it('handles empty input', () => {
    expect(dnEquals('', '')).toBe(true)
    expect(dnEquals('cn=jim,dc=x', '')).toBe(false)
  })
})
