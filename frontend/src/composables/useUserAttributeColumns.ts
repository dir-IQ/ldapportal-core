// SPDX-License-Identifier: Apache-2.0
/**
 * One definition of the attribute columns a table of user entries offers, so
 * the Users page and the user reports offer the same set, in the same order,
 * with the same defaults, headed by the real LDAP attribute name.
 *
 *   dn → the default attributes that are present (shown) → the groups column
 *   (when the page has one) → every other attribute, A–Z (hidden until picked)
 *
 * Rules shared by both pages:
 *   - Secret attributes (password hashes and the like) are never offered. The
 *     list mirrors OperationalReportService.SECRET_ATTRIBUTES on the backend.
 *   - memberOf / isMemberOf aren't offered as raw columns: group membership is
 *     shown by the groups column, which both pages build.
 *   - Headings are the attribute name with its schema casing (givenName, not
 *     givenname — the backend lower-cases keys); without a schema, the default
 *     attributes keep their known casing and anything else shows as returned.
 *   - Operational attributes are opt-in per table (see
 *     useOperationalAttributesToggle); when fetched they're ordinary
 *     attribute columns, hidden until picked.
 */
import { ref, watch, type Ref } from 'vue'
import type { ColumnDef } from '@/components/ResultsTable.vue'

/** Shown by default, in this order, when an entry carries them. */
export const DEFAULT_USER_ATTRIBUTES = [
  'cn', 'sn', 'givenName', 'displayName', 'mail', 'uid', 'title',
] as const

const DEFAULT_LC = new Map<string, string>(DEFAULT_USER_ATTRIBUTES.map(a => [a.toLowerCase(), a]))

/** Mirror of the backend's OperationalReportService.SECRET_ATTRIBUTES. */
export const SECRET_ATTRIBUTES = new Set([
  'userpassword', 'authpassword', 'unicodepwd', 'dbcspwd', 'ntpwdhistory', 'lmpwdhistory',
  'supplementalcredentials', 'pwdhistory', 'passwordhistory',
  'sambantpassword', 'sambalmpassword', 'sambapasswordhistory',
  'krbprincipalkey', 'krbextradata', 'userpkcs12', 'ibm-slapdadminpw',
])

/** Reverse group-membership attributes; the groups column stands in for them. */
const MEMBERSHIP_ATTRIBUTES = new Set(['memberof', 'ismemberof'])

/** True for a password or password-derived attribute (options like ;binary ignored). */
export function isSecretAttribute(name: string): boolean {
  const lower = name.toLowerCase()
  const semi = lower.indexOf(';')
  return SECRET_ATTRIBUTES.has(semi < 0 ? lower : lower.substring(0, semi))
}

export interface UserAttributeColumnOptions {
  /** Row key of the page's derived groups column; omitted, no groups column. */
  groupsKey?: string
  /** Start the groups column hidden. */
  groupsDefaultHidden?: boolean
  /** Lower-cased attribute name → its schema spelling (e.g. from listAttributeTypes). */
  schemaNames?: Map<string, string>
  /**
   * Heading override for a non-LDAP column (e.g. the IVIA enrichment keys,
   * which display with the product prefix). Return undefined to fall back to
   * the attribute name.
   */
  labelOverride?: (key: string) => string | undefined
  /** Extra per-column fields (widths and the like) merged into every column. */
  columnDefaults?: Partial<ColumnDef>
  /** Extra fields for the dn column. */
  dnColumn?: Partial<ColumnDef>
}

/** The real LDAP name to head a column with: schema casing, else the known default casing, else as given. */
export function attributeDisplayName(key: string, schemaNames?: Map<string, string>): string {
  const lower = key.toLowerCase()
  return schemaNames?.get(lower) ?? DEFAULT_LC.get(lower) ?? key
}

/**
 * Columns for a table of user entries, from the attribute keys its rows carry
 * (as returned by the backend, any case). Keys are used as-is for the row
 * accessor; only the heading is re-cased.
 */
export function buildUserAttributeColumns(
  attributeKeys: Iterable<string>,
  opts: UserAttributeColumnOptions = {},
): ColumnDef[] {
  const byLower = new Map<string, string>()
  for (const k of attributeKeys) {
    const lower = k.toLowerCase()
    if (lower === 'dn' || isSecretAttribute(k) || MEMBERSHIP_ATTRIBUTES.has(lower)) continue
    if (opts.groupsKey && lower === opts.groupsKey.toLowerCase()) continue
    if (!byLower.has(lower)) byLower.set(lower, k)
  }
  const label = (k: string): string =>
    opts.labelOverride?.(k) ?? attributeDisplayName(k, opts.schemaNames)
  const col = (k: string, extra: Partial<ColumnDef> = {}): ColumnDef =>
    ({ key: k, label: label(k), ...opts.columnDefaults, ...extra })

  const defaults = DEFAULT_USER_ATTRIBUTES
    .map(a => byLower.get(a.toLowerCase()))
    .filter((k): k is string => k !== undefined)
  const defaultSet = new Set(defaults)
  const rest = [...byLower.values()]
    .filter(k => !defaultSet.has(k))
    .sort((a, b) => label(a).localeCompare(label(b), undefined, { sensitivity: 'base' }))

  const out: ColumnDef[] = [
    { key: 'dn', label: 'dn', alwaysVisible: true, lockPosition: true, ...opts.columnDefaults, ...opts.dnColumn },
    ...defaults.map(k => col(k)),
  ]
  if (opts.groupsKey) {
    out.push(col(opts.groupsKey, { label: 'groups', sortable: false, defaultHidden: !!opts.groupsDefaultHidden }))
  }
  out.push(...rest.map(k => col(k, { defaultHidden: true })))
  return out
}

/**
 * The attribute names a CSV export should carry for the visible column keys,
 * in display order: each column's heading (the real LDAP name, schema-cased),
 * skipping keys that aren't attributes (row actions, selection, groups).
 */
export function exportAttributeNames(
  visibleKeys: string[],
  columns: ColumnDef[],
  skip: Iterable<string> = [],
): string[] {
  const skipSet = new Set(skip)
  const byKey = new Map(columns.map(c => [c.key, c]))
  return visibleKeys
    .filter(k => !skipSet.has(k) && byKey.has(k))
    .map(k => byKey.get(k)!.label || k)
}

const OPERATIONAL_STORAGE_PREFIX = 'ldapportal:operational-attributes:'

/**
 * Per-table "include operational attributes" switch, remembered in this
 * browser. Storage can be unavailable (private window, blocked site data), so
 * every access is guarded and the switch simply starts off.
 */
export function useOperationalAttributesToggle(tableKey: string | Ref<string>): Ref<boolean> {
  const keyOf = (): string => OPERATIONAL_STORAGE_PREFIX + (typeof tableKey === 'string' ? tableKey : tableKey.value)
  const read = (): boolean => {
    try { return localStorage.getItem(keyOf()) === 'true' } catch { return false }
  }
  const on = ref(read())
  watch(on, v => {
    try { localStorage.setItem(keyOf(), String(v)) } catch { /* not persisted */ }
  })
  if (typeof tableKey !== 'string') watch(tableKey, () => { on.value = read() })
  return on
}
