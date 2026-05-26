// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

import java.util.Arrays;

/**
 * The twelve feature permission keys defined in §3.2.
 * DB values use dot notation (e.g. "user.create"); a custom JPA converter
 * handles the mapping because Java enum constants cannot contain dots.
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
    SOD_MANAGE           ("sod.manage"),
    SOD_VIEW             ("sod.view"),
    HR_MANAGE            ("hr.manage"),
    HR_VIEW              ("hr.view"),
    AUDITOR_MANAGE       ("auditor.manage");

    private final String dbValue;

    FeatureKey(String dbValue) {
        this.dbValue = dbValue;
    }

    public String getDbValue() {
        return dbValue;
    }

    public static FeatureKey fromDbValue(String value) {
        return Arrays.stream(values())
            .filter(k -> k.dbValue.equals(value))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown feature key: " + value));
    }
}
