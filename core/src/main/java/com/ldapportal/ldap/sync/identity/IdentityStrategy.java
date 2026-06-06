// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync.identity;

import com.ldapportal.entity.enums.DirectoryType;

/**
 * Per-{@link DirectoryType} strategy for extracting a <em>stable, server-
 * assigned</em> identity from a source entry. The identity keys the
 * {@link com.ldapportal.entity.Membership} index and is written onto every
 * target entry as the {@code sourceAnchor} for brownfield correlation.
 *
 * <p>A good key is stable across rename/move/reparent and mutable-attribute
 * edits, present on every in-scope entry, and unique in scope. The gold
 * standard is a server-assigned immutable opaque id — {@code entryUUID}
 * (OpenLDAP / 389 / OUD / OpenDJ), AD {@code objectGUID}, Entra {@code id}.
 *
 * <p>Phase-0 SPI skeleton: this defines the seam and each family's identity
 * attribute. Full value normalization — notably AD's binary {@code objectGUID}
 * → canonical-string and config-time present/unique validation — lands with the
 * rich-identity phase (see the implementation plan's risk gates).
 */
public interface IdentityStrategy {

    /** Whether this strategy handles the given directory type. */
    boolean supports(DirectoryType type);

    /**
     * The source attribute carrying the stable identity (e.g. {@code entryUUID},
     * {@code objectGUID}). Operational attributes must be requested explicitly.
     * May be {@code null} for non-LDAP sources (e.g. Entra, read via Graph).
     */
    String identityAttribute();

    /**
     * Normalize a raw identity value to its canonical, comparable form (e.g.
     * lowercase UUID). Phase-0 default is a trim; richer per-type normalization
     * arrives later.
     */
    default String normalize(String rawValue) {
        return rawValue == null ? null : rawValue.trim();
    }
}
