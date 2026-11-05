// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import { friendlyColumnName } from './useReportFormatting'

describe('friendlyColumnName', () => {
  it('names known LDAP attributes and leaves the rest as they are', () => {
    expect(friendlyColumnName('mail')).toBe('Email')
    expect(friendlyColumnName('Missing Attributes')).toBe('Missing Attributes')
  })

  it('shows IVIA attributes under the product prefix, not the internal isva. key', () => {
    expect(friendlyColumnName('isva.seclogin')).toBe('ivia.seclogin')
    expect(friendlyColumnName('ISVA.secAcctValid')).toBe('ivia.secAcctValid')
    // Only the prefix is rewritten.
    expect(friendlyColumnName('visva.x')).toBe('visva.x')
  })
})
