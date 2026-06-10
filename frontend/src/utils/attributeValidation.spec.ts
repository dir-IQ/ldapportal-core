// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import {
  validateAttributeValue,
  validateAttributes,
  validateDn,
  validateEmail,
  validateBoolean,
  resolveSyntaxKind,
  type AttributeRules,
  type AttributeSyntaxHints,
} from './attributeValidation'

describe('validateAttributeValue', () => {
  it('flags a missing required value using the label', () => {
    const rules: AttributeRules = { attributeName: 'uid', label: 'User ID', required: true }
    expect(validateAttributeValue(rules, '')).toBe('User ID is required')
    expect(validateAttributeValue(rules, null)).toBe('User ID is required')
  })

  it('falls back to the attribute name when no label is given', () => {
    expect(validateAttributeValue({ attributeName: 'uid', required: true }, ''))
      .toBe('uid is required')
  })

  it('passes an empty value when not required', () => {
    expect(validateAttributeValue({ attributeName: 'note' }, '')).toBeNull()
    expect(validateAttributeValue({ attributeName: 'note' }, null)).toBeNull()
    expect(validateAttributeValue({ attributeName: 'note' }, undefined)).toBeNull()
  })

  it('enforces minLength and maxLength', () => {
    const rules: AttributeRules = { attributeName: 'uid', minLength: 3, maxLength: 6 }
    expect(validateAttributeValue(rules, 'ab')).toBe('Must be at least 3 characters')
    expect(validateAttributeValue(rules, 'abcdefg')).toBe('Must be at most 6 characters')
    expect(validateAttributeValue(rules, 'abcd')).toBeNull()
  })

  it('enforces validationRegex with a custom message', () => {
    const rules: AttributeRules = {
      attributeName: 'mail',
      validationRegex: '^[^@]+@[^@]+$',
      validationMessage: 'Must be an email',
    }
    expect(validateAttributeValue(rules, 'nope')).toBe('Must be an email')
    expect(validateAttributeValue(rules, 'a@b')).toBeNull()
  })

  it('uses a default message when the regex fails and none is provided', () => {
    expect(validateAttributeValue({ attributeName: 'x', validationRegex: '^\\d+$' }, 'abc'))
      .toBe('Invalid format')
  })

  it('does not crash on a malformed stored regex (server stays authoritative)', () => {
    expect(validateAttributeValue({ attributeName: 'x', validationRegex: '[unterminated' }, 'abc'))
      .toBeNull()
  })

  it('coerces booleans to TRUE/FALSE before applying rules', () => {
    const rules: AttributeRules = { attributeName: 'enabled', validationRegex: '^(TRUE|FALSE)$' }
    expect(validateAttributeValue(rules, true)).toBeNull()
    expect(validateAttributeValue(rules, false)).toBeNull()
  })
})

describe('validateAttributes', () => {
  it('collects only the failing fields, keyed by attribute name', () => {
    const rules: AttributeRules[] = [
      { attributeName: 'uid', required: true },
      { attributeName: 'mail', validationRegex: '^[^@]+@[^@]+$', validationMessage: 'bad email' },
      { attributeName: 'note', maxLength: 5 },
    ]
    const values: Record<string, unknown> = { uid: '', mail: 'x@y', note: 'too long' }
    const errors = validateAttributes(rules, (n) => values[n])

    expect(errors).toEqual({
      uid: 'uid is required',
      note: 'Must be at most 5 characters',
    })
    expect(errors.mail).toBeUndefined()
  })

  it('returns an empty object when everything is valid', () => {
    const rules: AttributeRules[] = [{ attributeName: 'uid', required: true, minLength: 2 }]
    expect(validateAttributes(rules, () => 'jsmith')).toEqual({})
  })
})

describe('validateDn', () => {
  it.each([
    'uid=jsmith,ou=people,dc=example,dc=com',
    'cn=Engineering',                       // single-RDN DN is valid
    'cn=a+sn=b,dc=example,dc=com',          // multi-valued RDN
    'cn=Smith\\, John,ou=people,dc=example,dc=com', // escaped comma in value
    'cn=a\\+b,dc=example,dc=com',            // escaped plus in value
    'cn=\\,,dc=example,dc=com',              // value is a single escaped char
    '2.5.4.3=foo,dc=example,dc=com',         // numeric OID attribute type
  ])('accepts %s', (dn) => {
    expect(validateDn(dn)).toBe(true)
  })

  it.each([
    '',
    'not a dn',
    'uid',                  // bare attribute, no value
    '=jsmith,dc=com',       // missing attribute type
    'uid=jsmith,',          // trailing comma
    ',dc=com',              // leading comma
  ])('rejects %s', (dn) => {
    expect(validateDn(dn)).toBe(false)
  })
})

describe('validateEmail / validateBoolean', () => {
  it('validates email shape', () => {
    expect(validateEmail('jsmith@example.com')).toBe(true)
    expect(validateEmail('no-dot@domain')).toBe(false)
    expect(validateEmail('has space@x.com')).toBe(false)
  })
  it('validates boolean tokens case-insensitively', () => {
    expect(validateBoolean('TRUE')).toBe(true)
    expect(validateBoolean('false')).toBe(true)
    expect(validateBoolean('yes')).toBe(false)
  })
})

describe('resolveSyntaxKind', () => {
  const hints: AttributeSyntaxHints = {
    wellKnownAttributes: { manager: 'DN', mail: 'EMAIL' },
    inputTypeSyntax: { DN_LOOKUP: 'DN', BOOLEAN: 'BOOLEAN' },
  }

  it('derives DN/boolean from the input type (works without hints)', () => {
    expect(resolveSyntaxKind('DN_LOOKUP', 'whatever')).toBe('DN')
    expect(resolveSyntaxKind('BOOLEAN', 'whatever')).toBe('BOOLEAN')
    expect(resolveSyntaxKind('TEXT', 'whatever')).toBeNull()
  })

  it('falls back to the well-known map for bare attributes when hints are present', () => {
    expect(resolveSyntaxKind('TEXT', 'manager', hints)).toBe('DN')
    expect(resolveSyntaxKind('TEXT', 'MAIL', hints)).toBe('EMAIL') // case-insensitive
    expect(resolveSyntaxKind('TEXT', 'cn', hints)).toBeNull()
  })

  it('lets the input type win over the well-known map', () => {
    expect(resolveSyntaxKind('DN_LOOKUP', 'mail', hints)).toBe('DN')
  })
})

describe('validateAttributeValue — syntax', () => {
  it('derives a DN check from the DN_LOOKUP input type', () => {
    const rules: AttributeRules = { attributeName: 'manager', inputType: 'DN_LOOKUP' }
    expect(validateAttributeValue(rules, 'not a dn')).toBe('Not a valid DN')
    expect(validateAttributeValue(rules, 'uid=boss,dc=example,dc=com')).toBeNull()
  })

  it('applies an explicit syntaxKind (e.g. well-known email)', () => {
    const rules: AttributeRules = { attributeName: 'mail', syntaxKind: 'EMAIL' }
    expect(validateAttributeValue(rules, 'nope')).toBe('Not a valid email address')
    expect(validateAttributeValue(rules, 'a@b.com')).toBeNull()
  })

  it('skips the syntax check for an empty, non-required value', () => {
    expect(validateAttributeValue({ attributeName: 'manager', inputType: 'DN_LOOKUP' }, '')).toBeNull()
  })

  it('runs length/regex before syntax', () => {
    const rules: AttributeRules = { attributeName: 'manager', inputType: 'DN_LOOKUP', minLength: 100 }
    expect(validateAttributeValue(rules, 'cn=x')).toBe('Must be at least 100 characters')
  })
})
