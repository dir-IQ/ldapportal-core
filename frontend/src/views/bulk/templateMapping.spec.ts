// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'
import {
  applySampleColumns, canonicalAttr, checkMappings, dnLikeColumns, entriesToRows, GROUP_MEMBERSHIPS, parseSampleHeader,
  readFirstRecord, repeatedColumns, rowStatus, rowsToEntries, sameColumn, suggestAttributes,
  type CheckOptions, type MappingRow, type SchemaAttr,
} from './templateMapping'

const req = (name: string): SchemaAttr => ({ name, required: true })
const opt = (name: string): SchemaAttr => ({ name, required: false })
const row = (column: string, attribute = ''): MappingRow => ({ column, attribute })
const schema = [req('cn'), req('sn'), opt('mail'), opt('uid'), opt('givenName')]
const opts = (o: Partial<CheckOptions> = {}): CheckOptions =>
  ({ schema, dnColumn: null, rdnAttribute: null, ...o })

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

describe('sameColumn', () => {
  it('ignores case, surrounding space and a byte-order mark, like the importer', () => {
    expect(sameColumn('Email', 'email')).toBe(true)
    expect(sameColumn('﻿dn', ' DN ')).toBe(true)
    expect(sameColumn('mail', 'email')).toBe(false)
    expect(sameColumn('', '')).toBe(false)
    expect(sameColumn('dn', null)).toBe(false)
  })
})

describe('rowStatus', () => {
  it('classifies each row', () => {
    expect(rowStatus(row('Email', 'mail'), null)).toBe('mapped')
    expect(rowStatus(row('Notes'), null)).toBe('ignored')
    expect(rowStatus(row('', 'mail'), null)).toBe('incomplete')
    expect(rowStatus(row(''), null)).toBe('blank')
    expect(rowStatus(row('DN', 'cn'), 'dn')).toBe('dn')
  })
})

describe('applySampleColumns', () => {
  it('lists the file columns in order with blank attributes for a new template', () => {
    expect(applySampleColumns([], ['dn', 'uid', 'Email'])).toEqual([row('dn'), row('uid'), row('Email')])
  })

  it("carries each column's attribute across by name, ignoring case and order", () => {
    const before = [row('email', 'mail'), row('Login', 'uid')]
    expect(applySampleColumns(before, ['LOGIN', 'Email', 'Dept'])).toEqual([
      row('LOGIN', 'uid'), row('Email', 'mail'), row('Dept'),
    ])
  })

  it('keeps mapped columns the file lacks at the bottom, flagged; drops unmapped ones', () => {
    const before = [row('email', 'mail'), row('CostCenter', 'departmentNumber'), row('Notes')]
    expect(applySampleColumns(before, ['email', 'uid'])).toEqual([
      row('email', 'mail'), row('uid'),
      { column: 'CostCenter', attribute: 'departmentNumber', notInSample: true },
    ])
  })

  it('gives a repeated file column one row', () => {
    expect(applySampleColumns([row('mail', 'mail')], ['mail', 'MAIL', 'uid'])).toEqual([row('mail', 'mail'), row('uid')])
  })

  it('re-applying the same columns is stable and clears the flag once the column is back', () => {
    const once = applySampleColumns([row('x', 'cn')], ['uid'])
    expect(applySampleColumns(once, ['uid'])).toEqual(once)
    expect(applySampleColumns(once, ['uid', 'x'])).toEqual([row('uid'), row('x', 'cn')])
  })
})

describe('checkMappings', () => {
  it('passes when every required attribute is mapped', () => {
    const c = checkMappings([row('a', 'cn'), row('b', 'sn'), row('c')], opts())
    expect(c.missingRequired).toEqual([])
    expect(c.blocking).toBe(false)
    expect(c.rows.every(r => r.errors.length === 0 && r.warnings.length === 0)).toBe(true)
  })

  it('lists missing required attributes, case-insensitively, and blocks', () => {
    const c = checkMappings([row('a', 'CN')], opts())
    expect(c.missingRequired).toEqual(['sn'])
    expect(c.blocking).toBe(true)
  })

  it('requires the RDN attribute when the DN is built from it', () => {
    const c = checkMappings([row('a', 'cn'), row('b', 'sn')], opts({ rdnAttribute: 'uid' }))
    expect(c.missingRequired).toEqual(['uid'])
    // Already required by the schema: not listed twice.
    expect(checkMappings([row('b', 'sn')], opts({ rdnAttribute: 'cn' })).missingRequired).toEqual(['cn'])
  })

  it('does not count the DN column, ignored or incomplete rows as mapping an attribute', () => {
    const rows = [row('dn', 'cn'), row('x'), row('', 'sn')]
    const c = checkMappings(rows, opts({ dnColumn: 'DN' }))
    expect(c.missingRequired).toEqual(['cn', 'sn'])
    expect(c.rows[0].warnings[0]).toMatch(/DN column/)
    expect(c.rows[0].errors).toEqual([])
    expect(c.rows[2].errors).toEqual(['Enter the CSV column for this attribute'])
  })

  it('rejects attributes the template sets itself', () => {
    const c = checkMappings([row('a', 'cn'), row('b', 'sn'), row('oc', 'objectClass'), row('d', 'DN')], opts())
    expect(c.rows[2].errors[0]).toMatch(/objectClass is set by the template/)
    expect(c.rows[3].errors[0]).toMatch(/DN is set by the template/)
    expect(c.blocking).toBe(true)
  })

  it('warns, without blocking, on unknown, duplicate and not-in-sample attributes', () => {
    const rows: MappingRow[] = [
      row('a', 'cn'), row('b', 'sn'), row('c', 'emplyeeType'),
      row('d', 'mail'), row('e', 'MAIL'), { column: 'f', attribute: 'uid', notInSample: true },
    ]
    const c = checkMappings(rows, opts())
    expect(c.blocking).toBe(false)
    expect(c.rows[2].warnings).toEqual(['Not in the selected object classes'])
    expect(c.rows[3].warnings).toEqual(['Another column also maps to mail; their values are added together'])
    expect(c.rows[4].warnings).toEqual(['Another column also maps to MAIL; their values are added together'])
    expect(c.rows[5].warnings).toEqual(['Not in the sample file'])
  })

  it('skips schema checks while the schema is unknown', () => {
    const c = checkMappings([row('c', 'anything')], opts({ schema: null }))
    expect(c.missingRequired).toEqual([])
    expect(c.rows[0].warnings).toEqual([])
  })

  it('flags columns the sample file repeats', () => {
    const c = checkMappings([row('a', 'cn'), row('b', 'sn'), row('Mail')], opts({ repeatedColumns: ['mail'] }))
    expect(c.rows[2].warnings).toEqual(['The sample file has this column more than once'])
  })
})

describe('canonicalAttr', () => {
  it("uses the schema's spelling of a known attribute and trims the rest", () => {
    expect(canonicalAttr(' givenname ', schema)).toBe('givenName')
    expect(canonicalAttr(' custom ', schema)).toBe('custom')
    expect(canonicalAttr('mail', null)).toBe('mail')
  })
})

describe('suggestAttributes', () => {
  it('fills blank rows by exact name and alias, never overwriting or reusing an attribute', () => {
    const rows = [row('Email'), row('MAIL'), row('Surname'), row('Login', 'cn'), row('UserName'), row('dn'), row('Notes')]
    const { rows: out, filled } = suggestAttributes(rows, schema, 'dn')
    expect(out.map(r => r.attribute)).toEqual(['mail', '', 'sn', 'cn', 'uid', '', ''])
    expect(filled).toBe(3)
  })

  it('only suggests attributes of the schema', () => {
    expect(suggestAttributes([row('phone')], schema, null).filled).toBe(0)
  })
})

describe('rowsToEntries / entriesToRows', () => {
  it('saves rows in order; blank attributes become ignored entries', () => {
    expect(rowsToEntries([row(' Email ', ' mail '), row('Notes'), row(''), row('', 'cn')], null)).toEqual([
      { csvColumn: 'Email', ldapAttribute: 'mail', ignored: false },
      { csvColumn: 'Notes', ldapAttribute: null, ignored: true },
    ])
  })

  it('leaves out the DN column and repeated columns', () => {
    expect(rowsToEntries([row('DN', 'cn'), row('mail', 'mail'), row('Mail', 'cn')], 'dn')).toEqual([
      { csvColumn: 'mail', ldapAttribute: 'mail', ignored: false },
    ])
  })

  it('round-trips saved entries, dropping an old ignored DN entry', () => {
    const entries = [
      { csvColumn: 'dn', ldapAttribute: null, ignored: true },
      { csvColumn: 'email', ldapAttribute: 'mail', ignored: false },
      { csvColumn: 'CostCenter', ldapAttribute: null, ignored: true },
    ]
    const rows = entriesToRows(entries, 'dn')
    expect(rows).toEqual([row('email', 'mail'), row('CostCenter')])
    expect(rowsToEntries(rows, 'dn')).toEqual(entries.slice(1))
  })
})

describe('dnLikeColumns / repeatedColumns', () => {
  it('finds columns that look like they hold a DN', () => {
    expect(dnLikeColumns(['DistinguishedName', 'user_dn', 'DN', 'dnsName', 'mail'])).toEqual(['DistinguishedName', 'user_dn', 'DN'])
  })

  it('lists repeated column names once', () => {
    expect(repeatedColumns(['a', 'B', 'A', 'b', 'a', 'c'])).toEqual(['A', 'b'])
  })
})

describe('group memberships', () => {
  it('maps a new sample column named groups to group memberships, once', () => {
    expect(applySampleColumns([], ['uid', 'Groups'])).toEqual([row('uid'), row('Groups', GROUP_MEMBERSHIPS)])
    // Another column already holds memberships: groups stays an ordinary column.
    const taken = [row('Teams', GROUP_MEMBERSHIPS)]
    expect(applySampleColumns(taken, ['Teams', 'groups'])).toEqual([row('Teams', GROUP_MEMBERSHIPS), row('groups')])
    // A saved mapping for the column wins.
    expect(applySampleColumns([row('groups', 'description')], ['groups'])).toEqual([row('groups', 'description')])
  })

  it('has its own row status and is not an attribute for the checks', () => {
    expect(rowStatus(row('groups', GROUP_MEMBERSHIPS), null)).toBe('groups')
    const c = checkMappings([row('a', 'cn'), row('b', 'sn'), row('groups', GROUP_MEMBERSHIPS)], opts())
    expect(c.rows[2]).toEqual({ errors: [], warnings: [] })
    expect(c.blocking).toBe(false)
  })

  it('allows only one group memberships column', () => {
    const c = checkMappings([row('a', 'cn'), row('b', 'sn'), row('g1', GROUP_MEMBERSHIPS), row('g2', GROUP_MEMBERSHIPS)], opts())
    expect(c.rows[2].errors).toEqual([])
    expect(c.rows[3].errors).toEqual(['Only one column can hold group memberships'])
    expect(c.blocking).toBe(true)
  })

  it('is suggested for a column named groups, and saved as @groups', () => {
    const { rows: out } = suggestAttributes([row('groups'), row('Email')], schema, null)
    expect(out.map(r => r.attribute)).toEqual([GROUP_MEMBERSHIPS, 'mail'])
    expect(rowsToEntries(out, null)[0]).toEqual({ csvColumn: 'groups', ldapAttribute: '@groups', ignored: false })
  })
})
