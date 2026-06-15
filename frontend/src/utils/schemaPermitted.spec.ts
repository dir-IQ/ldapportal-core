// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import { permittedAttrSet, unsupportedAttrs } from './schemaPermitted'

const cache = {
  inetOrgPerson: {
    required: ['cn', 'sn', 'objectClass'],
    optional: ['uid', 'mail', 'l', 'st', 'street', 'postalCode', 'o'],
  },
}

describe('permittedAttrSet', () => {
  it('unions required + optional, lower-cased, for cached classes', () => {
    const set = permittedAttrSet(cache, ['inetOrgPerson'])!
    expect(set.has('cn')).toBe(true)
    expect(set.has('objectclass')).toBe(true)
    expect(set.has('mail')).toBe(true)
    expect(set.has('c')).toBe(false)
  })

  it('returns null when no selected class has schema loaded', () => {
    expect(permittedAttrSet(cache, ['customPerson'])).toBeNull()
    expect(permittedAttrSet({}, ['inetOrgPerson'])).toBeNull()
  })

  it('still builds a set when only some classes are cached', () => {
    const set = permittedAttrSet(cache, ['inetOrgPerson', 'notCached'])!
    expect(set.has('sn')).toBe(true)
  })
})

describe('unsupportedAttrs', () => {
  const permitted = permittedAttrSet(cache, ['inetOrgPerson'])

  it('flags attributes outside the permitted set, preserving casing', () => {
    expect(unsupportedAttrs(['cn', 'c', 'employeeColour'], permitted))
      .toEqual(['c', 'employeeColour'])
  })

  it('is case-insensitive on the names checked', () => {
    expect(unsupportedAttrs(['CN', 'Mail'], permitted)).toEqual([])
  })

  it('returns empty when schema is unknown (null permitted)', () => {
    expect(unsupportedAttrs(['c'], null)).toEqual([])
  })
})
