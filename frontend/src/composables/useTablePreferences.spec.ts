// SPDX-License-Identifier: Apache-2.0
/**
 * Unit tests for useTablePreferences.
 *
 * The pure helpers (sortRows, filterRows, paginate) are the meat — they're
 * what every consumer of ResultsTable.vue depends on. The persistence layer
 * is also tested but with a stubbed localStorage (happy-dom provides one).
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { nextTick } from 'vue'
import {
  defaultPrefs,
  sortRows,
  filterRows,
  paginate,
  useTablePreferences,
} from './useTablePreferences'

describe('sortRows', () => {
  it('returns rows unchanged when col is empty', () => {
    const rows = [{ a: 2 }, { a: 1 }]
    expect(sortRows(rows, '', true)).toEqual(rows)
  })

  it('sorts numbers numerically (not lexicographically)', () => {
    const rows = [{ a: 2 }, { a: 10 }, { a: 1 }]
    const out = sortRows(rows, 'a', true)
    expect(out.map(r => r.a)).toEqual([1, 2, 10])
  })

  it('sorts strings with locale-aware compare', () => {
    const rows = [{ name: 'banana' }, { name: 'apple' }, { name: 'Cherry' }]
    const out = sortRows(rows, 'name', true)
    // localeCompare puts 'apple' before 'banana' before 'Cherry' (case-insensitive).
    expect(out.map(r => r.name)).toEqual(['apple', 'banana', 'Cherry'])
  })

  it('reverses on descending', () => {
    const rows = [{ a: 1 }, { a: 3 }, { a: 2 }]
    const out = sortRows(rows, 'a', false)
    expect(out.map(r => r.a)).toEqual([3, 2, 1])
  })

  it('puts null/undefined at the end regardless of direction', () => {
    const rows = [{ a: 2 }, { a: null }, { a: 1 }, { a: undefined }]
    const asc = sortRows(rows, 'a', true)
    expect(asc.map(r => r.a).slice(0, 2)).toEqual([1, 2])
    expect(asc[2].a == null).toBe(true)
    expect(asc[3].a == null).toBe(true)

    const desc = sortRows(rows, 'a', false)
    expect(desc.map(r => r.a).slice(0, 2)).toEqual([2, 1])
    expect(desc[2].a == null).toBe(true)
  })

  it('does not mutate the input array', () => {
    const rows = [{ a: 3 }, { a: 1 }, { a: 2 }]
    const before = [...rows]
    sortRows(rows, 'a', true)
    expect(rows).toEqual(before)
  })
})

describe('filterRows', () => {
  const rows = [
    { name: 'Alice', email: 'alice@acme.com', dept: 'Eng' },
    { name: 'Bob', email: 'bob@acme.com', dept: 'Sales' },
    { name: 'Carol', email: 'carol@globex.com', dept: 'Eng' },
  ]
  const cols = ['name', 'email', 'dept']

  it('returns all rows for empty query', () => {
    expect(filterRows(rows, '', cols)).toEqual(rows)
    expect(filterRows(rows, '   ', cols)).toEqual(rows)
  })

  it('matches substrings case-insensitively', () => {
    const out = filterRows(rows, 'ALICE', cols)
    expect(out).toHaveLength(1)
    expect(out[0].name).toBe('Alice')
  })

  it('matches across any visible column', () => {
    expect(filterRows(rows, 'globex', cols).map(r => r.name)).toEqual(['Carol'])
    expect(filterRows(rows, 'eng', cols).map(r => r.name)).toEqual(['Alice', 'Carol'])
  })

  it('only searches the columns provided', () => {
    // dept omitted from search columns — "Eng" should match nothing.
    expect(filterRows(rows, 'eng', ['name', 'email'])).toEqual([])
  })

  it('handles null/undefined cells without throwing', () => {
    const sparse = [{ name: 'X', email: null, dept: undefined }, { name: 'Y', email: 'y@acme', dept: 'Eng' }]
    expect(filterRows(sparse, 'acme', ['name', 'email', 'dept']).map(r => r.name)).toEqual(['Y'])
  })
})

describe('paginate', () => {
  const rows = Array.from({ length: 137 }, (_, i) => ({ id: i }))

  it('slices the requested page', () => {
    const { rows: page0 } = paginate(rows, 0, 50)
    expect(page0).toHaveLength(50)
    expect(page0[0].id).toBe(0)
    expect(page0[49].id).toBe(49)

    const { rows: page2 } = paginate(rows, 2, 50)
    expect(page2).toHaveLength(37)
    expect(page2[0].id).toBe(100)
  })

  it('reports total page count', () => {
    expect(paginate(rows, 0, 50).totalPages).toBe(3)
    expect(paginate(rows, 0, 25).totalPages).toBe(6)
    expect(paginate(rows.slice(0, 25), 0, 25).totalPages).toBe(1)
  })

  it('treats pageSize=0 as "all on one page"', () => {
    const { rows: all, totalPages } = paginate(rows, 0, 0)
    expect(all).toHaveLength(137)
    expect(totalPages).toBe(1)
  })

  it('clamps out-of-range page to last page', () => {
    const { rows: clamped } = paginate(rows, 99, 50)
    expect(clamped).toHaveLength(37) // last page
    expect(clamped[0].id).toBe(100)
  })

  it('handles empty input', () => {
    const { rows: empty, totalPages } = paginate<{ id: number }>([], 0, 50)
    expect(empty).toEqual([])
    expect(totalPages).toBe(1)
  })
})

describe('defaultPrefs', () => {
  it('returns sane defaults', () => {
    expect(defaultPrefs()).toEqual({
      widths: {},
      hidden: [],
      pageSize: 50,
      sortKey: '',
      sortAsc: true,
      seenColumns: [],
    })
  })
})

describe('useTablePreferences (persistence)', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('returns defaults when nothing is persisted', () => {
    const { prefs } = useTablePreferences('fresh-table')
    expect(prefs.value).toEqual(defaultPrefs())
  })

  it('persists changes to localStorage', async () => {
    const { setWidth, setPageSize, setSort } = useTablePreferences('persist-test')
    setWidth('Name', 220)
    setPageSize(100)
    setSort('Created', false)
    await nextTick()

    const raw = localStorage.getItem('ldapportal.table-prefs.v1:persist-test')
    expect(raw).toBeTruthy()
    const parsed = JSON.parse(raw!)
    expect(parsed.widths.Name).toBe(220)
    expect(parsed.pageSize).toBe(100)
    expect(parsed.sortKey).toBe('Created')
    expect(parsed.sortAsc).toBe(false)
  })

  it('reloads persisted prefs on next call with same key', async () => {
    const a = useTablePreferences('reload-test')
    a.setWidth('Email', 300)
    a.setHidden(['Internal ID'])
    await nextTick()

    const b = useTablePreferences('reload-test')
    expect(b.prefs.value.widths.Email).toBe(300)
    expect(b.prefs.value.hidden).toEqual(['Internal ID'])
  })

  it('does not bleed prefs across different table keys', async () => {
    const a = useTablePreferences('table-a')
    a.setPageSize(25)
    await nextTick()

    const b = useTablePreferences('table-b')
    expect(b.prefs.value.pageSize).toBe(50) // default, not 25
  })

  it('toggleHidden flips membership', async () => {
    const { prefs, toggleHidden } = useTablePreferences('toggle-test')
    toggleHidden('X')
    await nextTick()
    expect(prefs.value.hidden).toContain('X')
    toggleHidden('X')
    await nextTick()
    expect(prefs.value.hidden).not.toContain('X')
  })

  it('clamps width to a sane minimum', () => {
    const { prefs, setWidth } = useTablePreferences('min-width-test')
    setWidth('Tiny', 10)
    expect(prefs.value.widths.Tiny).toBe(40)
  })

  it('reset() restores defaults', async () => {
    const { prefs, setWidth, setPageSize, reset } = useTablePreferences('reset-test')
    setWidth('A', 100)
    setPageSize(25)
    await nextTick()
    reset()
    expect(prefs.value).toEqual(defaultPrefs())
  })

  it('falls back to defaults on corrupt JSON', () => {
    localStorage.setItem('ldapportal.table-prefs.v1:corrupt', '{not-json')
    const { prefs } = useTablePreferences('corrupt')
    expect(prefs.value).toEqual(defaultPrefs())
  })
})

describe('recordSeen — defaultHidden seeding gated on seenColumns', () => {
  beforeEach(() => localStorage.clear())

  it('first call seeds defaultHidden columns into hidden + records all keys', () => {
    const { prefs, recordSeen } = useTablePreferences('seen-1')
    recordSeen([
      { key: 'cn' },
      { key: 'mail' },
      { key: 'objectClass', defaultHidden: true },
      { key: 'employeeNumber', defaultHidden: true },
    ])
    expect(prefs.value.hidden.sort()).toEqual(['employeeNumber', 'objectClass'])
    expect(prefs.value.seenColumns.sort()).toEqual(['cn', 'employeeNumber', 'mail', 'objectClass'])
  })

  it('idempotent: second call with same columns is a no-op', () => {
    const { prefs, recordSeen } = useTablePreferences('seen-2')
    const cols = [
      { key: 'cn' },
      { key: 'objectClass', defaultHidden: true },
    ]
    recordSeen(cols)
    const after1 = JSON.stringify(prefs.value)
    recordSeen(cols)
    expect(JSON.stringify(prefs.value)).toBe(after1)
  })

  it('respects user choice: a previously-hidden defaultHidden col stays hidden if user un-hides', () => {
    // Simulate the lifecycle: first render seeds objectClass into
    // hidden; user opens picker and un-hides it; subsequent render
    // must NOT re-add (key is in seenColumns).
    const { prefs, recordSeen, toggleHidden } = useTablePreferences('seen-3')
    recordSeen([{ key: 'objectClass', defaultHidden: true }])
    expect(prefs.value.hidden).toEqual(['objectClass'])
    toggleHidden('objectClass')
    expect(prefs.value.hidden).toEqual([])
    recordSeen([{ key: 'objectClass', defaultHidden: true }])
    expect(prefs.value.hidden).toEqual([])
  })

  it('seeds defaultHidden cols newly-discovered after stored prefs were written', () => {
    // Returning user with prefs from before a column was added to
    // the schema. seenColumns persisted from previous render
    // contains the old set; the new defaultHidden col gets seeded.
    localStorage.setItem('ldapportal.table-prefs.v1:seen-4', JSON.stringify({
      ...defaultPrefs(),
      seenColumns: ['cn', 'mail'],
    }))
    const { prefs, recordSeen } = useTablePreferences('seen-4')
    expect(prefs.value.seenColumns).toEqual(['cn', 'mail'])
    recordSeen([
      { key: 'cn' },
      { key: 'mail' },
      { key: 'newAttr', defaultHidden: true },
    ])
    expect(prefs.value.hidden).toEqual(['newAttr'])
    expect(prefs.value.seenColumns.sort()).toEqual(['cn', 'mail', 'newAttr'])
  })

  it('returning user with empty seenColumns (pre-feature prefs) gets defaultHidden seeding', () => {
    // The actual fix: a user who used the table before defaultHidden
    // existed has empty seenColumns. recordSeen treats every column
    // as new and applies the defaultHidden flags.
    localStorage.setItem('ldapportal.table-prefs.v1:seen-5', JSON.stringify({
      widths: { cn: 200 },
      hidden: [],
      pageSize: 100,
      sortKey: 'cn',
      sortAsc: true,
      // seenColumns intentionally absent — simulates pre-feature
      // localStorage payload. loadPrefs defaults to [].
    }))
    const { prefs, recordSeen } = useTablePreferences('seen-5')
    expect(prefs.value.seenColumns).toEqual([])
    expect(prefs.value.widths.cn).toBe(200)  // confirm prior prefs preserved
    recordSeen([
      { key: 'cn' },
      { key: 'objectClass', defaultHidden: true },
      { key: 'employeeNumber', defaultHidden: true },
    ])
    expect(prefs.value.hidden.sort()).toEqual(['employeeNumber', 'objectClass'])
  })

  it('alwaysVisible-style cols (no defaultHidden) record without joining hidden', () => {
    const { prefs, recordSeen } = useTablePreferences('seen-6')
    recordSeen([{ key: 'dn' }, { key: 'cn' }])
    expect(prefs.value.hidden).toEqual([])
    expect(prefs.value.seenColumns.sort()).toEqual(['cn', 'dn'])
  })
})
