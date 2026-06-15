// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

public enum AccountType {
    LOCAL,
    LDAP,
    OIDC,
    /** Pre-authenticated via IBM Verify Identity Access WebSEAL (iv-user header). */
    WEBSEAL
}
