// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/**
 * How a new user's password is sourced and handled by a provisioning profile.
 *
 * <ul>
 *   <li>{@link #OPERATOR_ENTERED} — the admin types (or generates into) the
 *       visible password field; today's default behaviour.</li>
 *   <li>{@link #GENERATED_DELIVERED} — the server generates the password at
 *       create time and delivers it to the user (email). The field is hidden
 *       from the operator.</li>
 *   <li>{@link #GENERATED_DISCARDED} — the server generates a high-entropy
 *       throwaway purely to satisfy a schema-required {@code userPassword}
 *       attribute for accounts that authenticate by other means (e.g. client
 *       certificate). The value is written once and surfaced nowhere. The
 *       field is hidden from the operator.</li>
 * </ul>
 */
public enum PasswordDisposition {
    OPERATOR_ENTERED,
    GENERATED_DELIVERED,
    GENERATED_DISCARDED;

    /** True when the server generates the password rather than the operator supplying it. */
    public boolean isGenerated() {
        return this == GENERATED_DELIVERED || this == GENERATED_DISCARDED;
    }
}
