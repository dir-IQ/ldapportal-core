// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import { mergeSavedEntryAttributes } from './savedEntryMerge'

describe('mergeSavedEntryAttributes', () => {
  const columns = ['cn', 'departmentNumber', 'displayName']

  it('re-keys lower-cased response keys to the on-screen casing', () => {
    // The regression: the entry endpoints lower-case attribute keys while
    // search results keep server casing — a naive spread gave the row BOTH
    // departmentNumber and departmentnumber, duplicating table columns and
    // leaving the stale value under the visible key.
    const merged = mergeSavedEntryAttributes(
      columns,
      { cn: ['Finance'], departmentNumber: ['100'] },
      { cn: ['Finance'], departmentnumber: ['200'] },
    )
    expect(merged).toEqual({ cn: ['Finance'], departmentNumber: ['200'] })
    expect(Object.keys(merged)).not.toContain('departmentnumber')
  })

  it('drops row attributes the server no longer returns (value deletions)', () => {
    const merged = mergeSavedEntryAttributes(
      columns,
      { cn: ['Finance'], departmentNumber: ['100'] },
      { cn: ['Finance'] },
    )
    expect(merged).toEqual({ cn: ['Finance'] })
  })

  it('accepts a response key new to the row when a column exists for it', () => {
    // First value typed into an empty cell: the row had no key, but the
    // column (from other rows / the search projection) does.
    const merged = mergeSavedEntryAttributes(
      columns,
      { cn: ['Finance'] },
      { cn: ['Finance'], displayname: ['Finance Dept'] },
    )
    expect(merged).toEqual({ cn: ['Finance'], displayName: ['Finance Dept'] })
  })

  it('ignores read-back attributes outside the row and column sets', () => {
    // The save read-back is a full "*" entry; adopting everything would
    // grow the table's columns after each save.
    const merged = mergeSavedEntryAttributes(
      columns,
      { cn: ['Finance'] },
      { cn: ['Finance'], userpassword: ['{SSHA}…'], entryuuid: ['abc'] },
    )
    expect(merged).toEqual({ cn: ['Finance'] })
  })

  it('keeps operational keys the row already carries (modifyTimestamp guard)', () => {
    const merged = mergeSavedEntryAttributes(
      columns,
      { cn: ['Finance'], modifyTimestamp: ['20260612200000Z'] },
      { cn: ['Finance'], modifytimestamp: ['20260612210000Z'] },
    )
    expect(merged.modifyTimestamp).toEqual(['20260612210000Z'])
  })
})
