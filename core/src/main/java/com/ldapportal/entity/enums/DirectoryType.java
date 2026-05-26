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
    ENTRA_ID
}
