// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync.identity;

import com.ldapportal.entity.enums.DirectoryType;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.Filter;

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
 * <p>A strategy owns both directions of its identity: {@link #extract(Entry, String)}
 * derives the normalized key from an entry, and {@link #identityFilter} builds
 * the search that finds the entry again from that key. The two must agree —
 * AD's binary {@code objectGUID} is decoded to a canonical GUID string and
 * searched with a binary assertion value (see {@code ObjectGuidIdentityStrategy}).
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

    /**
     * Extract and normalize the stable identity from a source entry, or
     * {@code null} when the entry doesn't carry it. The {@link #identityAttribute()}
     * is operational for most directories, so callers must request it explicitly
     * in their search. Returns {@code null} for non-LDAP sources whose
     * identity attribute is {@code null}.
     */
    default String extract(Entry entry) {
        return extract(entry, identityAttribute());
    }

    /**
     * Extract and normalize the identity carried in {@code attribute} (the
     * strategy default or a per-set override). Strategies whose identity is
     * binary (AD {@code objectGUID}) override this to read the raw bytes.
     */
    default String extract(Entry entry, String attribute) {
        if (entry == null || attribute == null) {
            return null;
        }
        return normalize(entry.getAttributeValue(attribute));
    }

    /**
     * The equality filter that finds the source entry carrying {@code identity}
     * in {@code attribute}. Must match how {@link #extract(Entry, String)}
     * derived the value, so a binary identity searches with a binary assertion.
     */
    default Filter identityFilter(String attribute, String identity) {
        return Filter.createEqualityFilter(attribute, identity);
    }
}
