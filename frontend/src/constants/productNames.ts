// SPDX-License-Identifier: Apache-2.0
// Single source of truth for IBM's externally-marketed product name. IBM
// rebrands this periodically (Tivoli Access Manager → IBM Security Access
// Manager → IBM Security Verify Access → IBM Verify Identity Access → …),
// so all UI-visible copy interpolates these constants. The next rename is a
// one-line change here.
//
// IMPORTANT: the INTERNAL identifier is deliberately decoupled from this and
// stays stable as `isva` regardless of marketing churn — the addon package
// (com.ldapportal.addons.isva), the `vendor_integration_isva_config` table,
// the `VENDOR_INTEGRATIONS_ISVA` entitlement (serialized into signed license
// JWTs), the `isva-config` route, and the Maven / Fly identities. Only the
// human-readable strings below track the marketing name.
export const IVIA_NAME = 'IBM Verify Identity Access'
export const IVIA_ABBR = 'IVIA'

// The isva read-enricher (IsvaUserReadEnricher) folds paired secUser
// attributes into user-read responses under an `isva.` key prefix
// (e.g. `isva.seclogin`). That prefix is part of the stable INTERNAL
// identifier and must not be renamed in API payloads — it is only swapped
// for the marketing abbreviation when a key is rendered to a human.
export const ISVA_ATTR_PREFIX = 'isva.'

/** True when an attribute key is an IVIA (isva) read-enrichment attribute. */
export function isIviaAttr(key: string): boolean {
  return key.toLowerCase().startsWith(ISVA_ATTR_PREFIX)
}

/**
 * Human-facing label for an attribute key. IVIA enrichment keys render with
 * the marketing abbreviation as their prefix (`ivia.` derived from IVIA_ABBR)
 * in place of the internal `isva.`; every other key passes through unchanged.
 */
export function iviaAttrLabel(key: string): string {
  return isIviaAttr(key)
    ? `${IVIA_ABBR.toLowerCase()}.${key.slice(ISVA_ATTR_PREFIX.length)}`
    : key
}

// IVIA account operations are audited under a generic AuditAction (a hard
// revoke is USER_DELETE, a grant is USER_UPDATE, etc.) with the real verb in
// the `ivia_op` detail discriminator stamped by the isva addon
// (IsvaAccountService). These map that discriminator to a specific, IVIA-branded
// label so the entry timeline / audit log can show what actually happened
// instead of "Updated" / "Deleted".
const IVIA_OP_LABELS: Record<string, string> = {
  grant:       `${IVIA_ABBR} account granted`,
  revoke_soft: `${IVIA_ABBR} account revoked (soft)`,
  revoke_hard: `${IVIA_ABBR} account revoked (hard)`,
  suspend:     `${IVIA_ABBR} account suspended`,
  restore:     `${IVIA_ABBR} account restored`,
  renew:       `${IVIA_ABBR} account renewed`,
  force_reset: `${IVIA_ABBR} credential reset forced`,
}

/**
 * Specific IVIA-branded label for an audit event whose `detail.ivia_op`
 * identifies an IVIA account operation, or null when the event isn't one — so
 * callers fall back to their normal generic action label.
 */
export function iviaOpLabel(detail: unknown): string | null {
  if (!detail || typeof detail !== 'object') return null
  const op = (detail as Record<string, unknown>).ivia_op
  return typeof op === 'string' ? (IVIA_OP_LABELS[op] ?? null) : null
}
