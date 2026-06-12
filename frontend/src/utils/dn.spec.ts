// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import { parseLeadingRdn, ensureNamingValues } from './dn'

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
