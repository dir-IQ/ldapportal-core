// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import { IVIA_ABBR, ISVA_ATTR_PREFIX, isIviaAttr, iviaAttrLabel } from './productNames'

describe('IVIA attribute helpers', () => {
  it('detects isva.* enrichment keys case-insensitively', () => {
    expect(isIviaAttr('isva.seclogin')).toBe(true)
    expect(isIviaAttr('ISVA.secLogin')).toBe(true)
    expect(isIviaAttr('isva.orphaned')).toBe(true)
    expect(isIviaAttr('mail')).toBe(false)
    expect(isIviaAttr('displayName')).toBe(false)
  })

  it('swaps the internal isva. prefix for the marketing abbreviation in labels', () => {
    expect(iviaAttrLabel('isva.seclogin')).toBe('ivia.seclogin')
    expect(iviaAttrLabel('isva.secuserdn')).toBe('ivia.secuserdn')
    // Label prefix is derived from IVIA_ABBR, not hard-coded.
    expect(iviaAttrLabel('isva.seclogin')).toBe(`${IVIA_ABBR.toLowerCase()}.seclogin`)
  })

  it('passes non-IVIA keys through unchanged', () => {
    expect(iviaAttrLabel('mail')).toBe('mail')
    expect(iviaAttrLabel('givenName')).toBe('givenName')
  })

  it('exposes the stable internal prefix as a constant', () => {
    expect(ISVA_ATTR_PREFIX).toBe('isva.')
  })
})
