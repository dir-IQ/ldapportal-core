// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.DirectoryType;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single source of truth for which LDAP {@code objectClass} values count
 * as a <em>user</em> vs a <em>group</em> entry, per directory vendor.
 *
 * <p>Historically these sets were hardcoded — and had drifted —
 * across {@code LdifPreviewService}, the dashboard services, and
 * {@code LdapOperationService}'s group filter. They now live here as
 * vendor defaults, and a directory may override them per-connection
 * ({@link DirectoryConnection#getUserObjectClasses()} /
 * {@link DirectoryConnection#getGroupObjectClasses()}). Every consumer
 * resolves the effective set through {@link #effectiveUserObjectClasses}
 * / {@link #effectiveGroupObjectClasses} so a directory's configured
 * value wins and an unset value falls back to the vendor default.</p>
 *
 * <p>Matching is case-insensitive (LDAP attribute/objectClass names are
 * case-insensitive per spec); the stored/returned values keep their
 * conventional camelCase spelling for display.</p>
 */
public final class DirectoryObjectClassDefaults {

    private DirectoryObjectClassDefaults() {}

    // Vendor defaults. Kept conservative and aligned with the spellings
    // operators expect to see in their directory's schema.

    private static final List<String> AD_USERS = List.of("user");
    private static final List<String> AD_GROUPS = List.of("group");

    private static final List<String> OPENLDAP_USERS =
            List.of("inetOrgPerson", "organizationalPerson", "person", "posixAccount");
    private static final List<String> OPENLDAP_GROUPS =
            List.of("groupOfNames", "groupOfUniqueNames", "posixGroup");

    private static final List<String> IBM_OUD_USERS =
            List.of("inetOrgPerson", "organizationalPerson", "person");
    private static final List<String> IBM_OUD_GROUPS =
            List.of("groupOfNames", "groupOfUniqueNames", "groupOfURLs");

    private static final List<String> ENTRA_USERS = List.of("user");
    private static final List<String> ENTRA_GROUPS = List.of("group");

    // GENERIC is intentionally permissive — a union covering the common
    // vendors, so an unconfigured generic directory still classifies
    // sensibly.
    private static final List<String> GENERIC_USERS =
            List.of("inetOrgPerson", "organizationalPerson", "person", "user", "posixAccount");
    private static final List<String> GENERIC_GROUPS =
            List.of("groupOfNames", "groupOfUniqueNames", "posixGroup", "group", "groupOfURLs");

    /** Default user object classes for a vendor (never null/empty). */
    public static List<String> userObjectClasses(DirectoryType type) {
        if (type == null) return GENERIC_USERS;
        return switch (type) {
            case ACTIVE_DIRECTORY -> AD_USERS;
            case OPENLDAP -> OPENLDAP_USERS;
            case IBM_DIRECTORY_SERVER, ORACLE_UNIFIED_DIRECTORY -> IBM_OUD_USERS;
            case ENTRA_ID -> ENTRA_USERS;
            case GENERIC -> GENERIC_USERS;
        };
    }

    /** Default group object classes for a vendor (never null/empty). */
    public static List<String> groupObjectClasses(DirectoryType type) {
        if (type == null) return GENERIC_GROUPS;
        return switch (type) {
            case ACTIVE_DIRECTORY -> AD_GROUPS;
            case OPENLDAP -> OPENLDAP_GROUPS;
            case IBM_DIRECTORY_SERVER, ORACLE_UNIFIED_DIRECTORY -> IBM_OUD_GROUPS;
            case ENTRA_ID -> ENTRA_GROUPS;
            case GENERIC -> GENERIC_GROUPS;
        };
    }

    /**
     * The user object classes in effect for a directory: its configured
     * override when present, otherwise the vendor default. Never empty.
     */
    public static List<String> effectiveUserObjectClasses(DirectoryConnection dc) {
        List<String> configured = dc == null ? null : dc.getUserObjectClasses();
        return (configured == null || configured.isEmpty())
                ? userObjectClasses(dc == null ? null : dc.getDirectoryType())
                : configured;
    }

    /**
     * The group object classes in effect for a directory: its configured
     * override when present, otherwise the vendor default. Never empty.
     */
    public static List<String> effectiveGroupObjectClasses(DirectoryConnection dc) {
        List<String> configured = dc == null ? null : dc.getGroupObjectClasses();
        return (configured == null || configured.isEmpty())
                ? groupObjectClasses(dc == null ? null : dc.getDirectoryType())
                : configured;
    }

    /** Lowercased set form of the effective user object classes, for matching. */
    public static Set<String> effectiveUserObjectClassSet(DirectoryConnection dc) {
        return lowerSet(effectiveUserObjectClasses(dc));
    }

    /** Lowercased set form of the effective group object classes, for matching. */
    public static Set<String> effectiveGroupObjectClassSet(DirectoryConnection dc) {
        return lowerSet(effectiveGroupObjectClasses(dc));
    }

    /**
     * Build an LDAP OR filter over the given object classes, e.g.
     * {@code (|(objectClass=group)(objectClass=groupOfNames))}. Returns a
     * single-clause filter without the {@code (|...)} wrapper when only one
     * class is supplied. Assumes a non-empty list (the effective resolvers
     * never return empty).
     */
    public static String orFilter(List<String> objectClasses) {
        if (objectClasses.size() == 1) {
            return "(objectClass=" + objectClasses.get(0) + ")";
        }
        String clauses = objectClasses.stream()
                .map(oc -> "(objectClass=" + oc + ")")
                .collect(Collectors.joining());
        return "(|" + clauses + ")";
    }

    /**
     * LDAP filter selecting <em>user</em> entries in a directory: an OR over
     * its effective user object classes. When that set includes AD's
     * {@code user} class — which computer accounts also carry — the filter
     * additionally excludes {@code objectClass=computer} so machine accounts
     * don't count as users.
     */
    public static String userSearchFilter(DirectoryConnection dc) {
        List<String> ocs = effectiveUserObjectClasses(dc);
        String base = orFilter(ocs);
        boolean adUser = ocs.stream().anyMatch(o -> o.equalsIgnoreCase("user"));
        return adUser ? "(&" + base + "(!(objectClass=computer)))" : base;
    }

    /** LDAP filter selecting <em>group</em> entries: an OR over the effective group OCs. */
    public static String groupSearchFilter(DirectoryConnection dc) {
        return orFilter(effectiveGroupObjectClasses(dc));
    }

    private static Set<String> lowerSet(List<String> values) {
        return values.stream()
                .map(v -> v.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
