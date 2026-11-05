// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import { SPAN_OPTIONS, effectiveSpan, spanOptionsFor } from './fieldWidth'

describe('spanOptionsFor', () => {
  it('offers every width for ordinary and multi-line fields', () => {
    for (const t of ['TEXT', 'TEXTAREA', 'MULTI_VALUE', 'SELECT', undefined, null]) {
      expect(spanOptionsFor(t).map(o => o.span)).toEqual(SPAN_OPTIONS.map(o => o.span))
    }
  })

  it('limits password and DN-lookup fields to 1/2 or full', () => {
    expect(spanOptionsFor('PASSWORD').map(o => o.label)).toEqual(['1/2', 'Full'])
    expect(spanOptionsFor('DN_LOOKUP').map(o => o.label)).toEqual(['1/2', 'Full'])
  })
})

describe('effectiveSpan', () => {
  it('honors the saved width for multi-line fields (no forced full width)', () => {
    expect(effectiveSpan('TEXTAREA', 2, 3)).toBe(2)
    expect(effectiveSpan('MULTI_VALUE', 4, 3)).toBe(4)
  })

  it('snaps password and DN-lookup widths short of full to 1/2', () => {
    expect(effectiveSpan('PASSWORD', 2, 3)).toBe(3)
    expect(effectiveSpan('PASSWORD', 4, 3)).toBe(3)
    expect(effectiveSpan('DN_LOOKUP', 3, 3)).toBe(3)
    expect(effectiveSpan('DN_LOOKUP', 6, 3)).toBe(6)
  })

  it('uses the fallback when no width is saved', () => {
    expect(effectiveSpan('TEXT', null, 3)).toBe(3)
    expect(effectiveSpan('TEXT', undefined, 6)).toBe(6)
    expect(effectiveSpan('PASSWORD', null, 6)).toBe(6)
  })
})
