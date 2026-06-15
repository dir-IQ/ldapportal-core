// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

/**
 * A single gated capability. Covers the ee/ modules and the
 * Enterprise-tier feature flags listed in {@code docs/edition-boundary.md}.
 *
 * <p>Additions to this enum must also update:
 * <ul>
 *   <li>{@link Edition#baselineEntitlements()} — if the new entitlement should
 *       be included in one or more baseline editions</li>
 *   <li>{@code docs/edition-boundary.md} — the single source of truth for
 *       which editions include which entitlements</li>
 * </ul>
 *
 * <p>Names are serialised into signed license JWTs; renaming a value after
 * licenses have been issued is a breaking change. Additions are safe.</p>
 */
public enum Entitlement {

    // ── ee modules ──────────────────────────────────────────────────────────
    /** Access reviews, SoD policies, compliance dashboards, evidence packages. */
    GOVERNANCE,
    /** Cross-directory identity resolution, reconciliation, drift detection. */
    HYBRID,
    /** Durable event outbox, Kafka transport, signed webhooks, CloudEvents. */
    EVENTS,
    /** HR connectors (BambooHR, etc.) and HR-driven lifecycle. */
    HR_SYNC,
    /** Alert rules and notification routing. */
    ALERTING,
    /** Service account registry and vault-integrated password rotation. */
    SERVICE_ACCOUNTS,
    /**
     * Asynchronous cross-directory replication of app-initiated LDAP
     * writes. Gates the {@code /replication-links} surface, the
     * Directory Sync page, and the related dashboard surfacing
     * (metric / action item / awareness). See
     * {@code docs/plans/2026-05-30-directory-sync-design.md}.
     */
    DIRECTORY_SYNC,

    // ── Enterprise-tier feature flags ──────────────────────────────────────
    /** SAML 2.0 admin SSO (OIDC is core). */
    SAML_ADMIN_SSO,
    /** Cryptographic signing of audit log entries for tamper evidence. */
    AUDIT_LOG_SIGNING,
    /** Multi-instance active/active or active/passive deployment support. */
    HA_DEPLOYMENT,
    /** Region-pinned data storage for regulated deployments. */
    DATA_RESIDENCY,

    // ── Open-source addon entitlements (addons/*) ──────────────────────────
    // Granted by classpath probe rather than license file or edition tier.
    // Not placed in any Edition.baselineEntitlements() set — see the AddonProbe
    // SPI / AddonProbingLicenseProvider for the grant mechanism. The enum
    // entry exists so frontend gates, @Entitled annotations, and the
    // /me payload have something to reference by name.
    /** IBM Security Verify Access full-mode integration ({@code addons/isva}). */
    VENDOR_INTEGRATIONS_ISVA,
}
