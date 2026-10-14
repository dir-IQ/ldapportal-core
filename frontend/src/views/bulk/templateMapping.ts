// SPDX-License-Identifier: Apache-2.0
/**
 * Column-mapping model for the bulk-import template editor.
 *
 * The editor is a plain list: each row is one CSV input column and the LDAP
 * attribute the operator typed for it. A blank attribute means the column is
 * ignored on import. The selected object classes only feed suggestions and
 * checks; they never add rows. Everything here is pure so the component and
 * its specs share one source of truth.
 */

/** An attribute of the selected object classes. */
export interface SchemaAttr {
  name: string
  required: boolean
}

export interface MappingRow {
  /** CSV column; '' while being typed. */
  column: string
  /** LDAP attribute; '' means the column is ignored. */
  attribute: string
  /** Kept from the template although the loaded sample file doesn't have it. */
  notInSample?: boolean
}

/** Persisted template entry shape (matches the backend's CsvColumnMappingDto). */
export interface SavedEntry {
  csvColumn: string
  ldapAttribute: string | null
  ignored: boolean
}

/**
 * mapped: column + attribute. ignored: column, no attribute. incomplete:
 * attribute with no column (can't be saved). dn: the template's DN column,
 * which is never mapped. blank: nothing typed yet.
 */
export type RowStatus = 'mapped' | 'ignored' | 'incomplete' | 'dn' | 'blank'

export interface SampleHeader {
  /** Input column names, in file order. */
  columns: string[]
  /** First-row values when the file has no header row (shown as hints), else null. */
  samples: string[] | null
}

/**
 * Whether two column names refer to the same column. Matches the importer,
 * which ignores case and a leading byte-order mark.
 */
export function sameColumn(a: string | null | undefined, b: string | null | undefined): boolean {
  const k = (s: string) => s.replace(/^\uFEFF/, '').trim().toLowerCase()
  return !!a && !!b && k(a) !== '' && k(a) === k(b)
}

function sameAttr(a: string, b: string): boolean {
  return a.trim().toLowerCase() === b.trim().toLowerCase()
}

/** Attributes the template sets itself, so an operator can't map a column to them. */
const RESERVED_ATTRS = ['objectclass', 'dn']

export function isReservedAttr(name: string): boolean {
  return RESERVED_ATTRS.includes(name.trim().toLowerCase())
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

/** True when the row's column is the template's DN column. */
export function isDnRow(row: MappingRow, dnColumn: string | null | undefined): boolean {
  return sameColumn(row.column, dnColumn)
}

export function rowStatus(row: MappingRow, dnColumn: string | null | undefined): RowStatus {
  const hasCol = row.column.trim() !== ''
  const hasAttr = row.attribute.trim() !== ''
  if (hasCol && isDnRow(row, dnColumn)) return 'dn'
  if (hasCol && hasAttr) return 'mapped'
  if (hasCol) return 'ignored'
  if (hasAttr) return 'incomplete'
  return 'blank'
}

/**
 * Rebuilds the rows from a sample file's columns, in file order. A column
 * already in the template keeps its attribute (names match ignoring case).
 * Mapped columns the file doesn't have stay at the bottom, flagged
 * `notInSample`, so a mapping is never dropped just because a sample export
 * left a column out; unmapped ones are dropped. Repeated column names in the
 * file give one row. The DN column stays in the rows; the editor hides it.
 */
export function applySampleColumns(rows: MappingRow[], columns: string[]): MappingRow[] {
  const used = new Set<MappingRow>()
  const out: MappingRow[] = []
  for (const col of columns) {
    if (col.trim() === '' || out.some(r => sameColumn(r.column, col))) continue
    const prev = rows.find(r => !used.has(r) && sameColumn(r.column, col))
    if (prev) used.add(prev)
    out.push({ column: col, attribute: prev?.attribute ?? '' })
  }
  for (const r of rows) {
    if (used.has(r) || r.column.trim() === '' || r.attribute.trim() === '') continue
    if (out.some(o => sameColumn(o.column, r.column))) continue
    out.push({ column: r.column, attribute: r.attribute, notInSample: true })
  }
  return out
}

/** Problems on one row. Errors block saving; warnings don't. */
export interface RowIssues {
  errors: string[]
  warnings: string[]
}

export interface MappingCheck {
  /** Per-row issues, by row index. */
  rows: RowIssues[]
  /** Attributes that must be mapped but aren't: schema-required, plus the RDN attribute. */
  missingRequired: string[]
  /** Whether anything blocks saving. */
  blocking: boolean
}

export interface CheckOptions {
  /** Attributes of the selected object classes, or null while unknown. */
  schema: SchemaAttr[] | null
  /** The DN column when the DN is read from a column, else null. */
  dnColumn: string | null
  /** The RDN attribute when the DN is built from RDN + parent DN, else null. */
  rdnAttribute: string | null
  /** Columns the sample file has more than once (one row each). */
  repeatedColumns?: string[]
}

/** Validates the rows against the schema and the template's DN settings. */
export function checkMappings(rows: MappingRow[], opts: CheckOptions): MappingCheck {
  const live = rows.map(r => rowStatus(r, opts.dnColumn) === 'mapped')
  const result: RowIssues[] = rows.map(() => ({ errors: [], warnings: [] }))
  rows.forEach((r, i) => {
    const status = rowStatus(r, opts.dnColumn)
    const attr = r.attribute.trim()
    if (status === 'dn') {
      // Not saved, but not an error: the importer reads the DN from it.
      result[i].warnings.push(r.attribute.trim()
        ? `This is the DN column, so its mapping to ${attr} isn't saved`
        : "This is the DN column; it isn't mapped")
      return
    }
    if (status === 'incomplete') result[i].errors.push('Enter the CSV column for this attribute')
    if (!attr) {
      if (opts.repeatedColumns?.some(c => sameColumn(c, r.column))) {
        result[i].warnings.push('The sample file has this column more than once')
      }
      return
    }
    if (isReservedAttr(attr)) {
      result[i].errors.push(`${attr} is set by the template and can't be mapped`)
      return
    }
    if (opts.schema && !opts.schema.some(a => sameAttr(a.name, attr))) {
      result[i].warnings.push('Not in the selected object classes')
    }
    if (live[i] && rows.some((o, j) => j !== i && live[j] && sameAttr(o.attribute, attr))) {
      result[i].warnings.push(`Another column also maps to ${attr}`)
    }
    if (r.notInSample) result[i].warnings.push('Not in the sample file')
    if (opts.repeatedColumns?.some(c => sameColumn(c, r.column))) {
      result[i].warnings.push('The sample file has this column more than once')
    }
  })

  const mapped = rows.filter((_, i) => live[i]).map(r => r.attribute.trim().toLowerCase())
  const needed: string[] = []
  for (const a of opts.schema ?? []) if (a.required && !isReservedAttr(a.name)) needed.push(a.name)
  const rdn = opts.rdnAttribute?.trim()
  if (rdn && !needed.some(n => sameAttr(n, rdn))) needed.push(rdn)
  const missingRequired = needed.filter(n => !mapped.includes(n.toLowerCase()))

  const blocking = missingRequired.length > 0 || result.some(r => r.errors.length > 0)
  return { rows: result, missingRequired, blocking }
}

/** The schema's spelling of an attribute typed in another case, else the input trimmed. */
export function canonicalAttr(name: string, schema: SchemaAttr[] | null): string {
  const t = name.trim()
  return schema?.find(a => sameAttr(a.name, t))?.name ?? t
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
 * Fills blank attributes from the column name: an attribute of the same name
 * (ignoring case and punctuation), else a common alias (Email → mail,
 * FirstName → givenName). Only schema attributes are suggested, never one
 * already mapped, and rows with an attribute are left alone. Returns new rows
 * and how many were filled.
 */
export function suggestAttributes(rows: MappingRow[], schema: SchemaAttr[], dnColumn: string | null):
  { rows: MappingRow[], filled: number } {
  const byNorm = new Map<string, string>()
  for (const a of schema) if (!isReservedAttr(a.name)) byNorm.set(norm(a.name), a.name)
  const taken = new Set(rows.filter(r => r.attribute.trim()).map(r => r.attribute.trim().toLowerCase()))
  let filled = 0
  const out = rows.map(r => {
    if (r.attribute.trim() || !r.column.trim() || isDnRow(r, dnColumn)) return r
    const key = norm(r.column)
    const hit = byNorm.get(key) ?? (ALIASES[key] ? byNorm.get(norm(ALIASES[key])) : undefined)
    if (!hit || taken.has(hit.toLowerCase())) return r
    taken.add(hit.toLowerCase())
    filled++
    return { ...r, attribute: hit }
  })
  return { rows: out, filled }
}

/**
 * Rows → payload entries, in row order. Columns without an attribute are
 * saved as ignored. The DN column, blank rows, rows without a column and
 * repeats of a column already listed are left out.
 */
export function rowsToEntries(rows: MappingRow[], dnColumn: string | null): SavedEntry[] {
  const out: SavedEntry[] = []
  for (const r of rows) {
    const csvColumn = r.column.trim()
    if (!csvColumn || isDnRow(r, dnColumn) || out.some(e => sameColumn(e.csvColumn, csvColumn))) continue
    const attr = r.attribute.trim()
    out.push(attr
      ? { csvColumn, ldapAttribute: attr, ignored: false }
      : { csvColumn, ldapAttribute: null, ignored: true })
  }
  return out
}

/**
 * Saved entries → rows, in saved order. The DN column is left out (older
 * editors saved it as an ignored entry; the importer skips it anyway).
 */
export function entriesToRows(entries: SavedEntry[], dnColumn: string | null): MappingRow[] {
  return entries
    .filter(e => !sameColumn(e.csvColumn, dnColumn))
    .map(e => ({ column: e.csvColumn, attribute: e.ignored ? '' : (e.ldapAttribute ?? '') }))
}

/** Sample columns that look like they hold a DN, for the "pick one" hint. */
export function dnLikeColumns(columns: string[]): string[] {
  return columns.filter(c => /(^|[^a-z])dn([^a-z]|$)|distinguished/i.test(c))
}

/** Column names that appear more than once in `columns` (ignoring case), each listed once. */
export function repeatedColumns(columns: string[]): string[] {
  const out: string[] = []
  columns.forEach((c, i) => {
    if (columns.findIndex(o => sameColumn(o, c)) !== i && !out.some(o => sameColumn(o, c))) out.push(c)
  })
  return out
}
