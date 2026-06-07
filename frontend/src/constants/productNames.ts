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
