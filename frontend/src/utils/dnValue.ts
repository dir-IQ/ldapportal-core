// SPDX-License-Identifier: Apache-2.0
import { validateDn } from '@/utils/attributeValidation'

/**
 * Whether an attribute value should be offered as a link to the entry it
 * names (the DN quick look). Deliberately conservative: the value must parse
 * as a DN and have at least two RDNs, so a lone `cn=foo` in free text isn't
 * linked, while references such as member, manager, owner, seeAlso or a
 * vendor attribute like secDN are. A false positive only costs a "not found"
 * in the drawer; the directory stays authoritative.
 */
export function looksLikeDn(value: unknown): value is string {
  if (typeof value !== 'string') return false
  const s = value.trim()
  if (!s || !validateDn(s)) return false
  // Count RDNs, ignoring escaped commas (\,) inside values.
  return s.replace(/\\./g, '').split(',').length >= 2
}

/** The leading RDN's value, for a compact label (`uid=alice,ou=…` → `alice`). */
export function rdnLabel(dn: string): string {
  const first = dn.replace(/\\,/g, '\u0000').split(',')[0].replace(/\u0000/g, ',')
  const eq = first.indexOf('=')
  return (eq >= 0 ? first.slice(eq + 1) : first).trim() || dn
}
