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
