// SPDX-License-Identifier: Apache-2.0
/**
 * Resolve a group search entry's membership attribute and values.
 *
 * The backend lower-cases every attribute key in `LdapEntryResponse`
 * (`uniquemember`, `memberuid`), so reading `attributes.uniqueMember`
 * never matches — which made every groupOfUniqueNames / posixGroup
 * group render as empty. Look keys up case-insensitively and report
 * the canonical attribute name (the spelling used for membership
 * writes, where LDAP itself is case-insensitive).
 */

export type MemberAttr = 'member' | 'uniqueMember' | 'memberUid'

export interface GroupMembers {
  /** Which membership convention the group uses (defaults to `member`
   *  when the group has no members to reveal it). */
  memberAttr: MemberAttr
  /** The raw member values (DNs, or uid strings for posixGroup). */
  members: string[]
}

const CONVENTIONS: MemberAttr[] = ['member', 'uniqueMember', 'memberUid']

export function resolveGroupMembers(
  attributes?: Record<string, string[] | string | null | undefined>,
): GroupMembers {
  const attrs = attributes ?? {}
  for (const name of CONVENTIONS) {
    const key = Object.keys(attrs).find(k => k.toLowerCase() === name.toLowerCase())
    if (!key) continue
    const raw = attrs[key]
    const values = Array.isArray(raw) ? raw : raw == null ? [] : [raw]
    if (values.length) return { memberAttr: name, members: values }
  }
  return { memberAttr: 'member', members: [] }
}
