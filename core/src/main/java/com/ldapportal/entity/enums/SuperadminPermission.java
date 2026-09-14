// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * System-scoped capabilities for SUPERADMIN accounts.
 *
 * <p>These are the counterpart to {@link FeatureKey}, which gates
 * <em>directory-scoped</em> admin actions. Superadmin permissions gate
 * <em>system-wide</em> superadmin operations (managing application accounts,
 * directories, integrations, application settings, …) that have no directory
 * dimension.</p>
 *
 * <p>The {@code hasRole('SUPERADMIN')} check remains the coarse gate on
 * {@code /api/v1/superadmin/**}; these permissions add a finer capability
 * check behind it (enforced by
 * {@link com.ldapportal.auth.RequiresSuperadminPermission} /
 * {@link com.ldapportal.auth.SuperadminPermissionAspect}).</p>
 *
 * <h3>View / manage tiers</h3>
 * <p>Most areas come as a pair: a {@code VIEW_*} key that grants read-only
 * access (list / get endpoints, the page itself) and a {@code MANAGE_*} key
 * that grants the writes. Holding {@code MANAGE_X} implies {@code VIEW_X} —
 * see {@link #implies()} and {@link #expand(Collection)} — so an editor only
 * has to grant one key per area, and older grants that pre-date the view tier
 * keep working unchanged. Controllers put the {@code VIEW_*} key at class
 * level and the {@code MANAGE_*} key on each write method.</p>
 *
 * <p><b>Owner model:</b> a superadmin holding {@link #MANAGE_SUPERADMINS} is a
 * full owner — treated as holding every permission, and the only role allowed
 * to edit other superadmins' permission sets. See
 * {@link com.ldapportal.auth.PermissionService}.</p>
 *
 * <p>DB values use dot notation (e.g. {@code "superadmin.manage_application_accounts"});
 * {@link com.ldapportal.entity.converter.SuperadminPermissionConverter} maps
 * between the enum constant and the stored string.</p>
 */
public enum SuperadminPermission {

    // Each VIEW_* constant is declared before the MANAGE_* constant that
    // implies it: Java forbids forward references between enum constants.

    /** View application (admin) accounts and their permissions. */
    VIEW_APPLICATION_ACCOUNTS   ("superadmin.view_application_accounts"),
    /** Create / edit / delete application (admin) accounts. */
    MANAGE_APPLICATION_ACCOUNTS ("superadmin.manage_application_accounts", VIEW_APPLICATION_ACCOUNTS),
    /** Manage superadmin accounts and assign superadmin permissions (owner). */
    MANAGE_SUPERADMINS          ("superadmin.manage_superadmins"),
    /** View directory connections, their status, and Entra directory contents. */
    VIEW_DIRECTORIES            ("superadmin.view_directories"),
    /** Manage directory connections, discovery, and Entra. */
    MANAGE_DIRECTORIES          ("superadmin.manage_directories", VIEW_DIRECTORIES),
    /** View provisioning profiles and their lifecycle / approval settings. */
    VIEW_PROVISIONING_PROFILES  ("superadmin.view_provisioning_profiles"),
    /** Manage provisioning profiles. */
    MANAGE_PROVISIONING_PROFILES("superadmin.manage_provisioning_profiles", VIEW_PROVISIONING_PROFILES),
    /** View vendor integrations and audit data sources. */
    VIEW_INTEGRATIONS           ("superadmin.view_integrations"),
    /** Manage vendor integrations and audit data sources. */
    MANAGE_INTEGRATIONS         ("superadmin.manage_integrations", VIEW_INTEGRATIONS),
    /** View directory-sync links, sets, and their previews. */
    VIEW_DIRECTORY_SYNC         ("superadmin.view_directory_sync"),
    /** Manage directory-sync links and sets. */
    MANAGE_DIRECTORY_SYNC       ("superadmin.manage_directory_sync", VIEW_DIRECTORY_SYNC),
    /** View API tokens (metadata only — secrets are never re-readable). */
    VIEW_API_TOKENS             ("superadmin.view_api_tokens"),
    /** Manage API tokens. */
    MANAGE_API_TOKENS           ("superadmin.manage_api_tokens", VIEW_API_TOKENS),
    /** View application settings (branding, auth methods, …). */
    VIEW_APPLICATION_SETTINGS   ("superadmin.view_application_settings"),
    /** Manage application settings (branding, auth methods, …). */
    MANAGE_APPLICATION_SETTINGS ("superadmin.manage_application_settings", VIEW_APPLICATION_SETTINGS),
    /** View the event backbone (subscriptions / outbox). */
    VIEW_EVENT_BACKBONE         ("superadmin.view_event_backbone"),
    /** Manage the event backbone (subscriptions / outbox). */
    MANAGE_EVENT_BACKBONE       ("superadmin.manage_event_backbone", VIEW_EVENT_BACKBONE),
    /** Apply directory-schema changes (attributeTypes / objectClasses) via LDIF. */
    MANAGE_SCHEMA               ("superadmin.manage_schema"),
    /** View license status. */
    VIEW_LICENSE                ("superadmin.view_license");

    private final String dbValue;

    /** The view-tier key this manage-tier key implies, or {@code null}. */
    private final SuperadminPermission implied;

    SuperadminPermission(String dbValue) {
        this(dbValue, null);
    }

    SuperadminPermission(String dbValue, SuperadminPermission implied) {
        this.dbValue = dbValue;
        this.implied = implied;
    }

    public String getDbValue() {
        return dbValue;
    }

    /**
     * The permission holding this one implicitly grants (a {@code MANAGE_*}
     * key implies its {@code VIEW_*} counterpart), or {@code null} when this
     * key implies nothing beyond itself.
     */
    public SuperadminPermission implies() {
        return implied;
    }

    /** True for the read-only tier of an area ({@code VIEW_*}). */
    public boolean isViewTier() {
        return name().startsWith("VIEW_");
    }

    /**
     * Expand a set of granted keys to the effective set: an owner
     * ({@link #MANAGE_SUPERADMINS}) holds everything; otherwise each granted
     * key contributes itself plus whatever it {@link #implies()}.
     */
    public static Set<SuperadminPermission> expand(Collection<SuperadminPermission> granted) {
        if (granted.contains(MANAGE_SUPERADMINS)) {
            return EnumSet.allOf(SuperadminPermission.class);
        }
        EnumSet<SuperadminPermission> effective = EnumSet.noneOf(SuperadminPermission.class);
        for (SuperadminPermission p : granted) {
            effective.add(p);
            for (SuperadminPermission i = p.implied; i != null; i = i.implied) {
                effective.add(i);
            }
        }
        return effective;
    }

    public static SuperadminPermission fromDbValue(String value) {
        return Arrays.stream(values())
            .filter(p -> p.dbValue.equals(value))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown superadmin permission: " + value));
    }
}
