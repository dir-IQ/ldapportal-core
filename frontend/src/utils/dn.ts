// SPDX-License-Identifier: Apache-2.0
/**
 * Helpers for the *leading RDN* (the naming component) of a DN.
 *
 * LDAP requires every attribute-value assertion (AVA) in an entry's RDN to be
 * present among the entry's attribute values — including each component of a
 * multi-valued RDN such as `o=0001+cn=Sanjay Mishra,ou=People,…`. These helpers
 * parse the leading RDN (honouring RFC 4514 backslash and hex-pair escapes) so
 * forms can derive naming behaviour from the entry's *actual* DN instead of a
 * single designated RDN attribute.
 *
 * Mirrors the server-side `NamingAttributes` merge — the client copy gives
 * instant feedback; the server re-applies it authoritatively.
 *
 * No DOM dependencies — testable in isolation.
 */

export interface RdnAva {
  /** Attribute type, as written in the DN (original case preserved). */
  name: string
  /** Attribute value with RFC 4514 escapes decoded. */
  value: string
}

/** Split on a separator character, honouring backslash escapes. */
function splitUnescaped(s: string, sep: string): string[] {
  const parts: string[] = []
  let cur = ''
  for (let i = 0; i < s.length; i++) {
    const ch = s[i]
    if (ch === '\\' && i + 1 < s.length) {
      cur += ch + s[i + 1]
      i++
    } else if (ch === sep) {
      parts.push(cur)
      cur = ''
    } else {
      cur += ch
    }
  }
  parts.push(cur)
  return parts
}

/** Decode RFC 4514 escapes: `\,` → `,` and hex pairs like `\2C` → `,`. */
function unescapeDnValue(v: string): string {
  let out = ''
  for (let i = 0; i < v.length; i++) {
    if (v[i] !== '\\') {
      out += v[i]
      continue
    }
    const hex = v.slice(i + 1, i + 3)
    if (/^[0-9a-fA-F]{2}$/.test(hex)) {
      out += String.fromCharCode(parseInt(hex, 16))
      i += 2
    } else if (i + 1 < v.length) {
      out += v[i + 1]
      i++
    }
  }
  return out
}

/**
 * Parse the leading RDN of {@code dn} into its AVAs. A single-valued RDN
 * yields one entry; a multi-valued RDN (`o=0001+cn=Sanjay Mishra,…`) yields
 * one per `+`-separated component. Malformed components (no `=`) are skipped
 * rather than thrown — callers treat the result as advisory.
 */
export function parseLeadingRdn(dn: string): RdnAva[] {
  if (!dn || !dn.trim()) return []
  const leading = splitUnescaped(dn, ',')[0] || ''
  const avas: RdnAva[] = []
  for (const component of splitUnescaped(leading, '+')) {
    const fields = splitUnescaped(component, '=')
    if (fields.length < 2) continue
    const name = fields[0].trim()
    // Re-join in case the value itself contains an (unescaped) '='.
    const value = unescapeDnValue(fields.slice(1).join('=').trim())
    if (name && value) avas.push({ name, value })
  }
  return avas
}

/**
 * Returns a copy of {@code attributes} in which every AVA of the DN's leading
 * RDN is present among the entry's values: appended when the attribute already
 * exists with other values, added when absent. Attribute names and values are
 * matched case-insensitively (LDAP attribute names always are, and the common
 * directory string syntaxes are caseIgnore); existing key case is preserved.
 */
export function ensureNamingValues(
  dn: string,
  attributes: Record<string, string[]>,
): Record<string, string[]> {
  const out: Record<string, string[]> = { ...attributes }
  for (const ava of parseLeadingRdn(dn)) {
    const key = Object.keys(out).find(k => k.toLowerCase() === ava.name.toLowerCase()) ?? ava.name
    const values = out[key] ?? []
    if (!values.some(v => v.toLowerCase() === ava.value.toLowerCase())) {
      out[key] = [...values, ava.value]
    }
  }
  return out
}
