// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/** Why an in-scope source entry isn't one of its sync set's managed entries. */
public enum SyncNotInSetReason {
    /** It doesn't match the set's applicability filter. */
    FILTER("Doesn't match the applicability filter"),
    /** It can't be placed under the set's target base DN. */
    PLACEMENT("Can't be placed under the target base");

    private final String label;

    SyncNotInSetReason(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
