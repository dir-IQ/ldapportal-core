// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import { byLayoutOrder } from './layoutOrder'

describe('byLayoutOrder', () => {
  const names = (rows: { attributeName: string }[]) => rows.map(r => r.attributeName)

  it('orders by the layout’s own saved order, not the admin order', () => {
    const rows = [
      { attributeName: 'mail', registrationDisplayOrder: 2 },
      { attributeName: 'cn', registrationDisplayOrder: 0 },
      { attributeName: 'sn', registrationDisplayOrder: 1 },
    ]
    expect(names([...rows].sort(byLayoutOrder('registrationDisplayOrder')))).toEqual(['cn', 'sn', 'mail'])
  })

  it('keeps admin order when the layout was never customised', () => {
    const rows = [
      { attributeName: 'mail', selfServiceDisplayOrder: null },
      { attributeName: 'cn', selfServiceDisplayOrder: null },
    ]
    expect(names([...rows].sort(byLayoutOrder('selfServiceDisplayOrder')))).toEqual(['mail', 'cn'])
  })

  it('puts fields without a saved order after the ordered ones', () => {
    const rows = [
      { attributeName: 'pager', selfServiceDisplayOrder: null },
      { attributeName: 'mail', selfServiceDisplayOrder: 1 },
      { attributeName: 'mobile', selfServiceDisplayOrder: 0 },
    ]
    expect(names([...rows].sort(byLayoutOrder('selfServiceDisplayOrder')))).toEqual(['mobile', 'mail', 'pager'])
  })
})
