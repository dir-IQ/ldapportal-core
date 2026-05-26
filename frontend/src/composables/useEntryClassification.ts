// SPDX-License-Identifier: Apache-2.0
/**
 * Pure helpers for inline-edit on directory search results.
 *
 *   - classify(entry): user / group / unknown — drives which typed
 *     update endpoint a row save dispatches to.
 *   - rdnAttribute(dn): the first RDN component's attribute name.
 *     The RDN attribute is read-only inline (changing it requires
 *     a modrdn, not a modify).
 *   - isAttributeEditable(entry, attr, schema): combines the above
 *     plus operational-attribute and multi-valued checks. Phase 1
 *     locks multi-valued cells; Phase 1.5 will route them to the
 *     chip editor.
 *
 * No DOM dependencies — testable in isolation; reusable by any
 * component that needs the same predicate.
 */
import type { components } from '@/api/openapi'

export type AttributeTypeInfo = components['schemas']['AttributeTypeInfo']

export type EntryClassification = 'user' | 'group' | 'unknown'

export interface DirectoryEntry {
  dn: string
  attributes: Record<string, string[]>
}

// Lowercase for case-insensitive lookup. AD reports `user` and
// `top` in the objectClass set; OpenLDAP reports `inetOrgPerson`
// + `organizationalPerson` + `person` + `top`. Either is enough
// to classify as a user.
const USER_OBJECT_CLASSES = new Set([
  'inetorgperson',
  'person',
  'user',
  'posixaccount',
  'organizationalperson',
])

// `groupOfNames` and `groupOfUniqueNames` cover OpenLDAP / 389DS;
// `posixGroup` covers Unix-style; `group` is the AD class.
const GROUP_OBJECT_CLASSES = new Set([
  'groupofnames',
  'groupofuniquenames',
  'posixgroup',
  'group',
])

// Operational attributes that LDAP servers set themselves and reject
// modify attempts on. Locking these in the editor is correct
// regardless of objectClass. Lowercase keys.
const OPERATIONAL_ATTRS = new Set([
  'entryuuid',
  'createtimestamp',
  'modifytimestamp',
  'creatorsname',
  'modifiersname',
  'entrydn',
  'subschemasubentry',
  'hassubordinates',
  'numsubordinates',
  'structuralobjectclass',
  'usnchanged',
  'usncreated',
  'whenchanged',
  'whencreated',
  'objectsid',
  'objectguid',
])

/**
 * Reads {@code objectClass} (case-insensitive) and returns the
 * classification used to dispatch a typed update. {@code 'unknown'}
 * means inline edit is not supported for this entry — Phase 1 locks
 * the row.
 */
export function classify(entry: DirectoryEntry): EntryClassification {
  const oc = entry.attributes.objectClass
    ?? entry.attributes.objectclass
    ?? []
  const lower = oc.map(v => v.toLowerCase())
  if (lower.some(v => GROUP_OBJECT_CLASSES.has(v))) return 'group'
  if (lower.some(v => USER_OBJECT_CLASSES.has(v))) return 'user'
  return 'unknown'
}

/**
 * The attribute name from the first RDN component of the DN.
 * For {@code "cn=alice,ou=people,dc=…"} returns {@code "cn"}.
 * Lowercased for case-insensitive comparison; LDAP attribute names
 * are case-insensitive per spec.
 */
export function rdnAttribute(dn: string): string {
  const firstRdn = dn.split(',')[0] ?? ''
  const attr = firstRdn.split('=')[0] ?? ''
  return attr.trim().toLowerCase()
}

/**
 * Returns true when the cell should render as an editable input
 * in Phase 1. Conservative — defaults to {@code false} when the
 * schema is missing, so an unreachable schema fetch locks editing
 * rather than letting the user write data the server may reject.
 */
export function isAttributeEditable(
  entry: DirectoryEntry,
  attr: string,
  schemaByName: Map<string, AttributeTypeInfo>,
): boolean {
  if (attr === 'dn') return false
  const lower = attr.toLowerCase()
  if (lower === 'objectclass') return false
  if (OPERATIONAL_ATTRS.has(lower)) return false
  if (classify(entry) === 'unknown') return false
  if (lower === rdnAttribute(entry.dn)) return false
  const schema = schemaByName.get(lower)
  if (!schema) return false
  // Phase 1 locks multi-valued. Phase 1.5 will return true here and
  // route through the chip editor instead.
  if (schema.singleValued === false) return false
  return true
}
