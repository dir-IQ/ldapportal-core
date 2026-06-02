// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import {
  validateAttributeValue,
  validateAttributes,
  type AttributeRules,
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
