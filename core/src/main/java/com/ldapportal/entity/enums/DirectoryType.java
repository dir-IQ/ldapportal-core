// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

public enum DirectoryType {
    GENERIC,
    ACTIVE_DIRECTORY,
    OPENLDAP,
    /**
     * IBM Directory Server — sold over time as Tivoli Directory Server,
     * Security Directory Server, Security Directory Suite, and IBM Verify
     * Directory. Currently treated identically to {@link #GENERIC} by the
     * provider registry; later phases of the ITDS support work add an
     * IbmChangelogStrategy, ibm-allMembers nested-group resolution, and a
     * vendor/version capability probe. See
     * docs/superpowers/specs/2026-05-11-ibm-directory-server-support-design.md.
     */
    IBM_DIRECTORY_SERVER,
    /**
     * Oracle Unified Directory — Oracle's current Java-based LDAP server,
     * the successor to ODSEE / Sun ONE Directory Server. Treated
     * identically to {@link #GENERIC} by the provider registry; the
     * vendor-specific behaviour is layered on:
     * <ul>
     *   <li>Capability probe at connect time (P2) — vendor / version /
     *       supportedControl OIDs land on the connection.</li>
     *   <li>Changelog ingestion via the existing
     *       {@link com.ldapportal.ldap.changelog.DseeChangelogStrategy}
     *       (P3). OUD's {@code cn=changelog} is ODSEE-format, so an
     *       audit source linked to an OUD directory should pick the
     *       {@code DSEE_CHANGELOG} format — no per-type strategy
     *       wiring needed in the reader.</li>
     *   <li>{@code isMemberOf} shortcut for nested-group resolution
     *       (P4 — upcoming).</li>
     * </ul>
     * The local-dev fixture uses OpenDJ — the Apache-2.0 community
     * fork of the same ODSEE codebase Oracle forked to build OUD —
     * so anonymous CI / external contributors don't need OTN registry
     * credentials; see {@code testdata/README-oud.md} for the
     * substitution rationale and known divergences from real Oracle
     * OUD.
     */
    ORACLE_UNIFIED_DIRECTORY,
    ENTRA_ID
}
