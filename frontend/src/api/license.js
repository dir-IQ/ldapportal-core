// SPDX-License-Identifier: Apache-2.0
import client from './client'

/**
 * License status — edition, add-ons, limits, expiry, grace state,
 * source. Superadmin-only. Returns a LicenseStatusDto:
 *
 *   {
 *     edition: "BUSINESS",
 *     customerId: "7f2e8a12-...",
 *     signed: true,
 *     addOns: ["GOVERNANCE", "HR_SYNC"],
 *     grantedEntitlements: [...],
 *     withheldEntitlements: [...],
 *     limits: { DIRECTORIES: 25, ADMIN_ACCOUNTS: 100 },
 *     issuedAt: "2026-01-01T00:00:00Z" | null,
 *     expiresAt: "2027-04-22T23:59:59Z" | null,
 *     daysRemaining: 365 | null,
 *     graceState: "VALID" | "APPROACHING_EXPIRY" | "EXPIRED_WITHIN_GRACE" | "PAST_GRACE" | "NO_EXPIRY",
 *     graceDays: 30 | null,
 *     source: "/etc/ldapportal/license.jwt"
 *   }
 */
export const getLicenseStatus = () => client.get('/license/status')
