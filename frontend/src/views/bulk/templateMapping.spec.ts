// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import {
  applySampleColumns, autoMatch, entriesToStacks, moveItem, padStacks, parseSampleHeader,
  readFirstRecord, rebuildAttrs, rowStatus, stacksToEntries, type MappingAttr, type MappingStacks,
} from './templateMapping'

const req = (name: string): MappingAttr => ({ name, required: true })
const opt = (name: string): MappingAttr => ({ name, required: false })

describe('readFirstRecord', () => {
  it('splits the first line on the delimiter and trims cells', () => {
    expect(readFirstRecord('uid, mail ,cn\nalice,a@x,Alice\n', ',')).toEqual(['uid', 'mail', 'cn'])
  })

  it('handles a BOM, CRLF and other delimiters', () => {
    expect(readFirstRecord('﻿uid;mail\r\na;b', ';')).toEqual(['uid', 'mail'])
    expect(readFirstRecord('uid\tmail\n', '\t')).toEqual(['uid', 'mail'])
  })

  it('honours quotes: embedded delimiters, doubled quotes and newlines', () => {
    expect(readFirstRecord('"Last, First","Say ""hi""","multi\nline",x\nrow2', ','))
      .toEqual(['Last, First', 'Say "hi"', 'multi\nline', 'x'])
  })

  it('returns no columns for an empty file', () => {
    expect(readFirstRecord('', ',')).toEqual([])
    expect(readFirstRecord('\nuid', ',')).toEqual([])
  })
})

describe('parseSampleHeader', () => {
  it('uses the first record as names when the file has a header row', () => {
    expect(parseSampleHeader('uid,mail\na,b', ',', true)).toEqual({ columns: ['uid', 'mail'], samples: null })
  })

  it('numbers the columns and keeps the values as hints without a header row', () => {
    expect(parseSampleHeader('alice,a@x\n', ',', false))
      .toEqual({ columns: ['Column 1', 'Column 2'], samples: ['alice', 'a@x'] })
  })
})

describe('padStacks / moveItem', () => {
  it('pads the shorter stack and trims rows empty on both sides', () => {
    const s: MappingStacks = { inputs: ['a', null, null], attrs: [opt('x')] }
    expect(padStacks(s)).toEqual({ inputs: ['a'], attrs: [opt('x')] })
    const t: MappingStacks = { inputs: ['a', 'b'], attrs: [opt('x')] }
    expect(padStacks(t).attrs).toEqual([opt('x'), null])
  })

  it('moves an item within one array and ignores out-of-range moves', () => {
    const a = ['a', 'b', 'c']
    expect(moveItem(a, 0, 1)).toBe(true)
    expect(a).toEqual(['b', 'a', 'c'])
    expect(moveItem(a, 0, -1)).toBe(false)
    expect(moveItem(a, 2, 3)).toBe(false)
    expect(a).toEqual(['b', 'a', 'c'])
  })
})

describe('rowStatus', () => {
  it('classifies each row', () => {
    expect(rowStatus('a', opt('x'))).toBe('mapped')
    expect(rowStatus('a', null)).toBe('ignored')
    expect(rowStatus('', req('x'))).toBe('missing')
    expect(rowStatus(null, opt('x'))).toBe('unmapped')
    expect(rowStatus('  ', null)).toBe('blank')
  })
})

describe('autoMatch', () => {
  it('lines up exact (case-insensitive) and alias matches, leftovers fill free rows below', () => {
    const s: MappingStacks = {
      inputs: ['CostCenter', 'Email', 'UID', 'FirstName', 'LastName'],
      attrs: [req('uid'), req('sn'), opt('givenName'), opt('mail'), opt('title')],
    }
    expect(autoMatch(s)).toEqual(['UID', 'LastName', 'FirstName', 'Email', null, 'CostCenter'])
  })

  it('puts a second column matching an already-taken attribute into a free row', () => {
    const s: MappingStacks = { inputs: ['mail', 'Email'], attrs: [opt('mail'), null] }
    expect(autoMatch(s)).toEqual(['mail', 'Email'])
  })
})

describe('applySampleColumns', () => {
  it('fills a fresh template in file order', () => {
    const s: MappingStacks = { inputs: [null, null], attrs: [req('uid'), opt('mail')] }
    expect(applySampleColumns(s, ['login', 'email', 'dept']).inputs).toEqual(['login', 'email', 'dept'])
    expect(s.attrs).toEqual([req('uid'), opt('mail'), null])
  })

  it('keeps pairings whose column still exists and puts new columns below as ignored', () => {
    const s: MappingStacks = { inputs: ['old', 'email', null], attrs: [req('uid'), opt('mail'), opt('cn')] }
    expect(applySampleColumns(s, ['login', 'email', 'name']).inputs).toEqual([null, 'email', null, 'login', 'name'])
    expect(s.attrs).toEqual([req('uid'), opt('mail'), opt('cn'), null, null])
  })

  it('fills attribute-less rows before appending when pairings survive', () => {
    const s: MappingStacks = { inputs: ['email', null], attrs: [opt('mail'), null] }
    expect(applySampleColumns(s, ['email', 'dept']).inputs).toEqual(['email', 'dept'])
  })
})

describe('rebuildAttrs', () => {
  it('carries inputs across by attribute name and drops attributes the schema lost', () => {
    const s: MappingStacks = { inputs: ['e', 'u', 'x', 'loose'], attrs: [opt('mail'), req('uid'), opt('gone'), null] }
    const out = rebuildAttrs(s, [req('uid'), opt('mail'), opt('cn')])
    expect(out.attrs).toEqual([req('uid'), opt('mail'), opt('cn'), null, null])
    expect(out.inputs).toEqual(['u', 'e', null, 'loose', 'x'])
  })

  it('keeps attributes missing from the schema when asked (opening a saved template)', () => {
    const s: MappingStacks = { inputs: ['e', 'x'], attrs: [opt('mail'), opt('extra')] }
    const out = rebuildAttrs(s, [req('uid'), opt('mail')], true)
    expect(out.attrs).toEqual([req('uid'), opt('mail'), opt('extra')])
    expect(out.inputs).toEqual([null, 'e', 'x'])
  })
})

describe('stacksToEntries / entriesToStacks', () => {
  it('saves mapped rows, and inputs without an attribute as ignored', () => {
    const s: MappingStacks = { inputs: ['login', '', 'CostCenter', null], attrs: [req('uid'), opt('mail'), null, opt('cn')] }
    expect(stacksToEntries(s)).toEqual([
      { csvColumn: 'login', ldapAttribute: 'uid', ignored: false },
      { csvColumn: 'CostCenter', ldapAttribute: null, ignored: true },
    ])
  })

  it('restores ignored entries below the mapped ones', () => {
    const s = entriesToStacks([
      { csvColumn: 'CostCenter', ldapAttribute: null, ignored: true },
      { csvColumn: 'login', ldapAttribute: 'uid', ignored: false },
    ])
    expect(s).toEqual({ inputs: ['login', 'CostCenter'], attrs: [opt('uid'), null] })
    expect(stacksToEntries(s)).toEqual([
      { csvColumn: 'login', ldapAttribute: 'uid', ignored: false },
      { csvColumn: 'CostCenter', ldapAttribute: null, ignored: true },
    ])
  })
})
