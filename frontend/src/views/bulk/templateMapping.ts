// SPDX-License-Identifier: Apache-2.0
/**
 * Column-mapping model for the bulk-import template editor.
 *
 * The editor shows two independent stacks: CSV input columns on the left and
 * LDAP attributes on the right. Row N of one stack maps to row N of the other.
 * Either side can hold an empty slot (`null`) so items can be lined up without
 * disturbing the rest of the other stack. Everything here is pure so the
 * component and its specs share one source of truth.
 */

export interface MappingAttr {
  name: string
  required: boolean
}

export interface MappingStacks {
  /** CSV column names; `null` (or '' while typing) is an empty slot. */
  inputs: (string | null)[]
  /** LDAP attributes; `null` is an empty slot. */
  attrs: (MappingAttr | null)[]
}

/** Persisted template entry shape (matches the backend's CsvColumnMappingDto). */
export interface SavedEntry {
  csvColumn: string
  ldapAttribute: string | null
  ignored: boolean
}

export type RowStatus = 'mapped' | 'ignored' | 'missing' | 'unmapped' | 'blank'

export interface SampleHeader {
  /** Input column names, in file order. */
  columns: string[]
  /** First-row values when the file has no header row (shown as hints), else null. */
  samples: string[] | null
}

function hasInput(v: string | null | undefined): v is string {
  return v != null && v.trim() !== ''
}

/**
 * Reads the first CSV record of `text` with the given single-character
 * delimiter. Handles a UTF-8 BOM, CRLF line endings and RFC 4180 quoting
 * (quoted delimiters, doubled quotes, and newlines inside quotes).
 */
export function readFirstRecord(text: string, delimiter: string): string[] {
  const src = text.charCodeAt(0) === 0xfeff ? text.slice(1) : text
  const cells: string[] = []
  let cell = ''
  let quoted = false
  for (let i = 0; i < src.length; i++) {
    const ch = src[i]
    if (quoted) {
      if (ch === '"') {
        if (src[i + 1] === '"') { cell += '"'; i++ } else quoted = false
      } else cell += ch
    } else if (ch === '"' && cell.trim() === '') {
      quoted = true
      cell = ''
    } else if (ch === delimiter) {
      cells.push(cell.trim()); cell = ''
    } else if (ch === '\n' || ch === '\r') {
      break
    } else cell += ch
  }
  cells.push(cell.trim())
  // A blank first line yields a single empty cell: treat it as no columns.
  return cells.length === 1 && cells[0] === '' ? [] : cells
}

/**
 * Turns a sample file's first record into input column names. With a header
 * row the record is the names; without one the columns are numbered and the
 * record's values come back as hints.
 */
export function parseSampleHeader(text: string, delimiter: string, hasHeader: boolean): SampleHeader {
  const record = readFirstRecord(text, delimiter || ',')
  if (hasHeader) return { columns: record, samples: null }
  return { columns: record.map((_, i) => `Column ${i + 1}`), samples: record }
}

/**
 * Pads the shorter stack with empty slots and drops trailing rows that are
 * empty on both sides. Mutates and returns `stacks`.
 */
export function padStacks(stacks: MappingStacks): MappingStacks {
  const n = Math.max(stacks.inputs.length, stacks.attrs.length)
  while (stacks.inputs.length < n) stacks.inputs.push(null)
  while (stacks.attrs.length < n) stacks.attrs.push(null)
  while (stacks.inputs.length && !hasInput(stacks.inputs[stacks.inputs.length - 1])
         && stacks.attrs[stacks.attrs.length - 1] == null) {
    stacks.inputs.pop(); stacks.attrs.pop()
  }
  return stacks
}

/** Moves one item within a single stack. Out-of-range moves are ignored. */
export function moveItem<T>(arr: T[], from: number, to: number): boolean {
  if (from === to || from < 0 || to < 0 || from >= arr.length || to >= arr.length) return false
  const [item] = arr.splice(from, 1)
  arr.splice(to, 0, item)
  return true
}

export function rowStatus(input: string | null | undefined, attr: MappingAttr | null | undefined): RowStatus {
  const has = hasInput(input)
  if (has && attr) return 'mapped'
  if (has) return 'ignored'
  if (attr?.required) return 'missing'
  if (attr) return 'unmapped'
  return 'blank'
}

/** Common CSV header spellings for standard attributes, keyed by normalised name. */
const ALIASES: Record<string, string> = {
  email: 'mail', emailaddress: 'mail', mailaddress: 'mail',
  firstname: 'givenName', givenname: 'givenName', forename: 'givenName',
  lastname: 'sn', surname: 'sn', familyname: 'sn',
  fullname: 'cn', name: 'cn', commonname: 'cn',
  displayname: 'displayName',
  phone: 'telephoneNumber', phonenumber: 'telephoneNumber', telephone: 'telephoneNumber',
  mobile: 'mobile', cell: 'mobile', mobilephone: 'mobile',
  jobtitle: 'title',
  dept: 'departmentNumber', department: 'departmentNumber',
  employeeid: 'employeeNumber', employeeno: 'employeeNumber',
  username: 'uid', userid: 'uid', login: 'uid', loginname: 'uid',
  manager: 'manager',
}

function norm(s: string): string {
  return s.toLowerCase().replace(/[^a-z0-9]/g, '')
}

/**
 * Lines inputs up with attributes by name: an exact case-insensitive match
 * wins, then a small alias table (Email → mail, FirstName → givenName, …).
 * Unmatched inputs keep their relative order and fill the first rows that
 * have no attribute, then any rows after the end of the attribute stack.
 * Returns a new inputs array; attrs are untouched.
 */
export function autoMatch(stacks: MappingStacks): (string | null)[] {
  const attrIndex = new Map<string, number>()
  stacks.attrs.forEach((a, i) => { if (a) attrIndex.set(norm(a.name), i) })
  const next: (string | null)[] = stacks.attrs.map(() => null)
  const leftovers: string[] = []
  for (const col of stacks.inputs) {
    if (!hasInput(col)) continue
    const key = norm(col)
    let idx = attrIndex.get(key)
    if (idx === undefined && ALIASES[key]) idx = attrIndex.get(norm(ALIASES[key]))
    if (idx !== undefined && next[idx] == null) next[idx] = col
    else leftovers.push(col)
  }
  for (const col of leftovers) {
    const free = next.findIndex((v, j) => v == null && stacks.attrs[j] == null)
    if (free >= 0) next[free] = col
    else next.push(col)
  }
  return next
}

/**
 * Replaces the input stack with a sample file's columns. Rows whose current
 * input still exists in the file keep it, so existing pairings survive.
 * With no surviving pairing (a fresh template), the columns fill the rows
 * top-down in file order. Otherwise the new columns go below as ignored
 * rows, so they never pair with an attribute by accident.
 * Mutates and returns `stacks`.
 */
export function applySampleColumns(stacks: MappingStacks, columns: string[]): MappingStacks {
  const remaining = [...columns]
  const take = (name: string): boolean => {
    const i = remaining.indexOf(name)
    if (i < 0) return false
    remaining.splice(i, 1)
    return true
  }
  stacks.inputs = stacks.inputs.map(v => (hasInput(v) && take(v) ? v : null))
  const kept = stacks.inputs.some(v => v != null)
  for (let i = 0; i < stacks.inputs.length && remaining.length; i++) {
    if (stacks.inputs[i] == null && (!kept || stacks.attrs[i] == null)) stacks.inputs[i] = remaining.shift()!
  }
  // Below the last row; pad first so appended columns land after every attribute.
  padStacks(stacks)
  while (stacks.inputs.length < stacks.attrs.length) stacks.inputs.push(null)
  stacks.inputs.push(...remaining)
  return padStacks(stacks)
}

/**
 * Rebuilds the attribute stack for a new set of schema attributes (required
 * first, then optional, in schema order), carrying each attribute's current
 * input across by name. Inputs whose attribute is gone, and unpaired inputs,
 * go below as ignored rows. With `keepUnknown`, attributes already in the
 * stack but absent from the schema are kept after the schema ones — used when
 * opening a saved template so no saved mapping silently disappears.
 */
export function rebuildAttrs(stacks: MappingStacks, schema: MappingAttr[], keepUnknown = false): MappingStacks {
  const paired = new Map<string, string | null>()
  const orphans: string[] = []
  stacks.attrs.forEach((a, i) => {
    const input = stacks.inputs[i]
    if (a) paired.set(a.name.toLowerCase(), hasInput(input) ? input : null)
    else if (hasInput(input)) orphans.push(input)
  })
  const attrs: MappingAttr[] = [...schema]
  const known = new Set(schema.map(a => a.name.toLowerCase()))
  if (keepUnknown) {
    for (const a of stacks.attrs) {
      if (a && !known.has(a.name.toLowerCase())) { attrs.push(a); known.add(a.name.toLowerCase()) }
    }
  }
  for (const [name, input] of paired) {
    if (!known.has(name) && input) orphans.push(input)
  }
  const inputs: (string | null)[] = attrs.map(a => paired.get(a.name.toLowerCase()) ?? null)
  return padStacks({ inputs: [...inputs, ...orphans], attrs })
}

/** Stacks → payload entries. Inputs without an attribute are saved as ignored. */
export function stacksToEntries(stacks: MappingStacks): SavedEntry[] {
  const out: SavedEntry[] = []
  stacks.inputs.forEach((input, i) => {
    if (!hasInput(input)) return
    const attr = stacks.attrs[i]
    const csvColumn = input.trim()
    out.push(attr
      ? { csvColumn, ldapAttribute: attr.name, ignored: false }
      : { csvColumn, ldapAttribute: null, ignored: true })
  })
  return out
}

/** Saved entries → stacks. Mapped entries first (saved order), ignored ones below. */
export function entriesToStacks(entries: SavedEntry[]): MappingStacks {
  const mapped = entries.filter(e => !e.ignored && e.ldapAttribute)
  const ignored = entries.filter(e => e.ignored || !e.ldapAttribute)
  return padStacks({
    inputs: [...mapped.map(e => e.csvColumn), ...ignored.map(e => e.csvColumn)],
    attrs: mapped.map(e => ({ name: e.ldapAttribute!, required: false })),
  })
}
