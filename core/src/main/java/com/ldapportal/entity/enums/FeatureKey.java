// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

import com.ldapportal.core.entitlement.Entitlement;

import java.util.Arrays;

/**
 * The feature permission keys an admin override can target.
 * DB values use dot notation (e.g. "user.create"); a custom JPA converter
 * handles the mapping because Java enum constants cannot contain dots.
 *
 * <p>A key may carry a {@link #getRequiredEntitlement() required entitlement}:
 * the capability it gates only exists in editions/licenses that hold that
 * entitlement, so the key is hidden from the assignment catalogue and the
 * effective-permissions viewer when the entitlement is absent. (Execution is
 * gated independently by {@code @Entitled}; this only governs whether the
 * toggle is <em>shown</em>.) Keys with no required entitlement are part of the
 * core, edition-agnostic surface.</p>
 */
public enum FeatureKey {

    USER_CREATE          ("user.create"),
    USER_EDIT            ("user.edit"),
    USER_DELETE          ("user.delete"),
    USER_ENABLE_DISABLE  ("user.enable_disable"),
    USER_MOVE            ("user.move"),
    USER_RESET_PASSWORD  ("user.reset_password"),
    GROUP_EDIT           ("group.edit"),
    GROUP_MANAGE_MEMBERS ("group.manage_members"),
    GROUP_CREATE_DELETE  ("group.create_delete"),
    BULK_IMPORT          ("bulk.import"),
    BULK_EXPORT          ("bulk.export"),
    BULK_ATTRIBUTE_UPDATE("bulk.attribute_update"),
    BULK_DELETE          ("bulk.delete"),
    REPORTS_RUN          ("reports.run"),
    REPORTS_SCHEDULE     ("reports.schedule"),
    ACCESS_REVIEW_MANAGE ("access_review.manage"),
    ACCESS_REVIEW_REVIEW ("access_review.review"),
    PLAYBOOK_MANAGE      ("playbook.manage"),
    PLAYBOOK_EXECUTE     ("playbook.execute"),
    APPROVAL_MANAGE      ("approval.manage"),
    CSV_TEMPLATE_MANAGE  ("csv_template.manage"),
    DIRECTORY_BROWSE     ("directory.browse"),
    SCHEMA_READ          ("schema.read"),
    USER_READ            ("user.read"),
    GROUP_READ           ("group.read"),
    SOD_MANAGE           ("sod.manage",  Entitlement.GOVERNANCE),
    SOD_VIEW             ("sod.view",    Entitlement.GOVERNANCE),
    HR_MANAGE            ("hr.manage",   Entitlement.HR_SYNC),
    HR_VIEW              ("hr.view",     Entitlement.HR_SYNC),
    AUDITOR_MANAGE       ("auditor.manage");

    private final String dbValue;

    /** Entitlement that must be present for this key to be exposed, or null
     *  if the key is part of the core, edition-agnostic surface. */
    private final Entitlement requiredEntitlement;

    FeatureKey(String dbValue) {
        this(dbValue, null);
    }

    FeatureKey(String dbValue, Entitlement requiredEntitlement) {
        this.dbValue = dbValue;
        this.requiredEntitlement = requiredEntitlement;
    }

    public String getDbValue() {
        return dbValue;
    }

    /**
     * The entitlement gating exposure of this key, or {@code null} when the
     * key is always exposed (core surface).
     */
    public Entitlement getRequiredEntitlement() {
        return requiredEntitlement;
    }

    public static FeatureKey fromDbValue(String value) {
        return Arrays.stream(values())
            .filter(k -> k.dbValue.equals(value))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown feature key: " + value));
    }
}
