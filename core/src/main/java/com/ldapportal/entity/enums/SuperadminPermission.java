// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

import java.util.Arrays;

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

    /** Create / edit / delete application (admin) accounts. */
    MANAGE_APPLICATION_ACCOUNTS ("superadmin.manage_application_accounts"),
    /** Manage superadmin accounts and assign superadmin permissions (owner). */
    MANAGE_SUPERADMINS          ("superadmin.manage_superadmins"),
    /** Manage directory connections, discovery, and Entra. */
    MANAGE_DIRECTORIES          ("superadmin.manage_directories"),
    /** Manage provisioning profiles. */
    MANAGE_PROVISIONING_PROFILES("superadmin.manage_provisioning_profiles"),
    /** Manage vendor integrations and audit data sources. */
    MANAGE_INTEGRATIONS         ("superadmin.manage_integrations"),
    /** Manage directory-sync links and sets. */
    MANAGE_DIRECTORY_SYNC       ("superadmin.manage_directory_sync"),
    /** Manage API tokens. */
    MANAGE_API_TOKENS           ("superadmin.manage_api_tokens"),
    /** Manage application settings (branding, auth methods, …). */
    MANAGE_APPLICATION_SETTINGS ("superadmin.manage_application_settings"),
    /** Manage the event backbone (subscriptions / outbox). */
    MANAGE_EVENT_BACKBONE       ("superadmin.manage_event_backbone"),
    /** Apply directory-schema changes (attributeTypes / objectClasses) via LDIF. */
    MANAGE_SCHEMA               ("superadmin.manage_schema"),
    /** View license status. */
    VIEW_LICENSE                ("superadmin.view_license");

    private final String dbValue;

    SuperadminPermission(String dbValue) {
        this.dbValue = dbValue;
    }

    public String getDbValue() {
        return dbValue;
    }

    public static SuperadminPermission fromDbValue(String value) {
        return Arrays.stream(values())
            .filter(p -> p.dbValue.equals(value))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown superadmin permission: " + value));
    }
}
